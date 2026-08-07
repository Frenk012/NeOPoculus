package net.irisshaders.iris.horizon.voxel;

import net.irisshaders.iris.Iris;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Installs server-provided LOD into the client's own store (M5b phase B).
 *
 * <p><b>The client never adopts server ids.</b> Cells carry palette ids, and ids
 * are handed out in first-encounter order per process, so the same number almost
 * never names the same block on two machines. Adopting them would silently
 * reinterpret every locally captured cell — wrong blocks, no error, exactly the
 * failure seen when two worlds accidentally shared one cache directory. Instead
 * the server's palette is translated into a pair of remap tables, and every
 * received cell is rewritten through them before it is stored. Light bits pass
 * through untouched; only the state and biome fields are translated.
 *
 * <p>Everything here treats its input as hostile: lengths, ids and section keys
 * are all range-checked, and reserved cell bits are cleared, so the worst a bad
 * server achieves is wrong-looking distant terrain.
 */
public final class ClientLodInstall {
	private final int[] stateRemap;
	private final int[] biomeRemap;
	private final int paletteVersion;
	private int installedSections;
	private long installedBytes;
	private boolean capLogged;

	/**
	 * Ceilings on what one session will accept from a server. A well-behaved
	 * server sends far less: the whole 300-block test world was 25 files and
	 * 5 MB. These exist so a hostile or broken server cannot grow the client's
	 * disk without bound — the worst it can still do is waste this much.
	 */
	private static final int MAX_SECTIONS_PER_SESSION = 200_000;
	private static final long MAX_BYTES_PER_SESSION = 512L * 1024L * 1024L;

	private ClientLodInstall(int[] stateRemap, int[] biomeRemap, int paletteVersion) {
		this.stateRemap = stateRemap;
		this.biomeRemap = biomeRemap;
		this.paletteVersion = paletteVersion;
	}

	public int mappedStates() {
		return stateRemap.length;
	}

	public int mappedBiomes() {
		return biomeRemap.length;
	}

	public int paletteVersion() {
		return paletteVersion;
	}

	public int installedSections() {
		return installedSections;
	}

	/**
	 * Builds the remap tables from a server palette blob. Unresolvable entries
	 * fall back exactly as the persistence path does — an unknown state becomes
	 * stone, an unknown biome plains — so a client missing one of the server's
	 * mods sees plausible terrain instead of nothing.
	 *
	 * @return the installer, or null when the blob is unusable or the LOD engine
	 *         is not running.
	 */
	public static ClientLodInstall fromServerPalette(CompoundTag root, int version,
													 VoxelPalettes local,
													 HolderGetter<Block> blocks) {
		if (root == null || local == null) {
			return null;
		}
		try {
			ListTag states = root.getList("states", Tag.TAG_COMPOUND);
			ListTag biomes = root.getList("biomes", Tag.TAG_COMPOUND);
			int maxStateId = maxId(states);
			int maxBiomeId = maxId(biomes);
			if (maxStateId >= VoxelConstants.MAX_STATE_IDS || maxBiomeId >= VoxelConstants.MAX_BIOME_IDS) {
				Iris.logger.warn("Horizon: rejected a server palette with out-of-range ids");
				return null;
			}
			int[] stateRemap = new int[maxStateId + 1];
			int[] biomeRemap = new int[maxBiomeId + 1];
			// Anything not explicitly mapped resolves to the fallbacks, which is
			// also what an id past the table means.
			java.util.Arrays.fill(stateRemap, VoxelPalettes.FALLBACK_STATE_ID);
			java.util.Arrays.fill(biomeRemap, VoxelPalettes.FALLBACK_BIOME_ID);
			stateRemap[0] = 0; // air stays air

			for (int i = 0; i < states.size(); i++) {
				CompoundTag entry = states.getCompound(i);
				int serverId = entry.getInt("id");
				if (serverId <= 0 || serverId >= stateRemap.length) {
					continue;
				}
				BlockState state = readState(blocks, entry.getCompound("state"));
				stateRemap[serverId] = state == null
					? VoxelPalettes.FALLBACK_STATE_ID
					: local.idFor(state);
			}
			for (int i = 0; i < biomes.size(); i++) {
				CompoundTag entry = biomes.getCompound(i);
				int serverId = entry.getInt("id");
				if (serverId <= 0 || serverId >= biomeRemap.length) {
					continue;
				}
				ResourceLocation name = ResourceLocation.tryParse(entry.getString("name"));
				biomeRemap[serverId] = name == null
					? VoxelPalettes.FALLBACK_BIOME_ID
					: local.idForBiomeName(name);
			}
			return new ClientLodInstall(stateRemap, biomeRemap, version);
		} catch (Throwable t) {
			Iris.logger.warn("Horizon: could not map a server LOD palette", t);
			return null;
		}
	}

	private static int maxId(ListTag list) {
		int max = 0;
		for (int i = 0; i < list.size(); i++) {
			max = Math.max(max, list.getCompound(i).getInt("id"));
		}
		return max;
	}

	private static BlockState readState(HolderGetter<Block> blocks, CompoundTag tag) {
		try {
			BlockState state = NbtUtils.readBlockState(blocks, tag);
			return state == null || state.isAir() ? null : state;
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * Decodes one received section, rewrites its ids into the local id space and
	 * installs it. Worker thread.
	 *
	 * @return true when the section was installed.
	 */
	public boolean install(VoxelStore store, long sectionKey, byte[] encoded) {
		if (store == null || encoded == null || encoded.length == 0) {
			return false;
		}
		if (installedSections >= MAX_SECTIONS_PER_SESSION || installedBytes >= MAX_BYTES_PER_SESSION) {
			if (!capLogged) {
				capLogged = true;
				Iris.logger.warn("Horizon: refusing further server LOD this session — "
					+ installedSections + " sections / " + (installedBytes >> 20)
					+ " MB already accepted");
			}
			return false;
		}
		int level = SectionKey.level(sectionKey);
		if (level < 0 || level > VoxelConstants.MAX_LEVEL) {
			return false; // a key we could not have produced ourselves
		}
		long[] cells;
		try {
			cells = VoxelSectionCodec.decodeCells(encoded);
		} catch (Throwable t) {
			return false; // malformed payload: drop it, never trust the length
		}
		if (cells == null || cells.length != VoxelConstants.SECTION_CELLS) {
			return false;
		}
		for (int i = 0; i < cells.length; i++) {
			cells[i] = translate(cells[i]);
		}
		try {
			VoxelSection section = store.acquireForWrite(sectionKey);
			int n = VoxelConstants.SECTION_SIZE;
			section.writeBatch(0, 0, 0, n, n, n, cells, 0, n, n * n);
			store.markDirty(sectionKey);
			installedSections++;
			installedBytes += encoded.length;
			return true;
		} catch (Throwable t) {
			Iris.logger.error("Horizon: failed to install a server LOD section", t);
			return false;
		}
	}

	/** Rewrites one cell's state and biome into local ids, clearing reserved bits. */
	private long translate(long cell) {
		int serverState = VoxelCell.stateId(cell);
		int serverBiome = VoxelCell.biomeId(cell);
		int localState = serverState < stateRemap.length
			? stateRemap[serverState] : VoxelPalettes.FALLBACK_STATE_ID;
		int localBiome = serverBiome < biomeRemap.length
			? biomeRemap[serverBiome] : VoxelPalettes.FALLBACK_BIOME_ID;
		if (serverState == 0) {
			localState = 0; // air is air in every palette
		}
		// Rebuilding from the accessors also drops bits 37-63, which no honest
		// cell uses and a crafted one might.
		return VoxelCell.pack(localState, localBiome,
			VoxelCell.blockLight(cell), VoxelCell.skyLight(cell));
	}
}
