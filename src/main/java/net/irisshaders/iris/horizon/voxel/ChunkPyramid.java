package net.irisshaders.iris.horizon.voxel;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * Worker-side scratch converting one vanilla 16^3 section of a
 * {@link ChunkSnapshotter.ChunkSnapshot} into a five-level mip pyramid of
 * packed {@link VoxelCell} longs (design-data-storage.md section 3.3):
 * P0[4096] down to P4[1], level-l index {@code (y<<2b)|(z<<b)|x} with
 * {@code b = 4 - l} (x fastest, matching both the vanilla container and
 * {@link VoxelSection} cell order, so every downstream copy is stride-1).
 *
 * <p>Because a chunk origin (multiple of 16) is a multiple of every cell
 * size up to 16, each mip cell up to L4 lies wholly inside one vanilla
 * section — the pyramid is exact with no cross-chunk merging, which is what
 * lets {@link VoxelIngest} write all five levels unconditionally.
 *
 * <p>One instance per worker thread via {@link #scratch()} (~37 KB, reused
 * forever); the arrays are only valid until the next {@link #build} on the
 * same thread, so callers must copy out (they do — straight into sections).
 *
 * <p>WHY {@code getAll} instead of 4096 {@code get(x,y,z)} calls: it is the
 * public-API bulk path that walks the packed storage in index order, and the
 * one-entry state-to-id memo in the consumer makes runs of equal states (the
 * overwhelmingly common case) skip the palette map entirely.
 */
final class ChunkPyramid {
	private static final ThreadLocal<ChunkPyramid> SCRATCH = ThreadLocal.withInitial(ChunkPyramid::new);

	/** This worker thread's reusable pyramid. */
	static ChunkPyramid scratch() {
		return SCRATCH.get();
	}

	/** levels[l] holds (16 >> l)^3 cells; see class javadoc for index order. */
	private final long[][] levels = new long[VoxelConstants.LEVEL_COUNT][];
	/** Biome palette ids per 4x4x4 quart, vanilla order (qy<<4)|(qz<<2)|qx. */
	private final int[] biomeIds = new int[VoxelConstants.QUARTS_PER_VANILLA_SECTION];
	/** Reusable 8-child gather buffer for {@link VoxelMipper#selectRepresentative}. */
	private final long[] children = new long[8];
	private int nonAirCount;

	private ChunkPyramid() {
		for (int l = 0; l < VoxelConstants.LEVEL_COUNT; l++) {
			int n = axisCells(l);
			levels[l] = new long[n * n * n];
		}
	}

	/** Cells per axis of the pyramid at a level: 16, 8, 4, 2, 1. */
	static int axisCells(int level) {
		return 16 >> level;
	}

	/** The level-l cell array; valid until the next build on this thread. */
	long[] level(int l) {
		return levels[l];
	}

	/**
	 * Non-air cells in P0 after the last build. Zero means every level is
	 * all-air (a mip parent is non-air iff some child is), which is how
	 * {@link VoxelIngest} decides between create-and-write and
	 * write-only-if-resident without rescanning any array.
	 */
	int nonAirCount() {
		return nonAirCount;
	}

	/**
	 * Fills P0 from one snapshot section and mips P0 through P4. Air-only
	 * sections (null states) still produce a valid pyramid — air cells
	 * carrying light — because re-ingesting a mined-out section must
	 * overwrite previously solid cells in resident sections.
	 */
	void build(ChunkSnapshotter.ChunkSnapshot snapshot, int sectionIndex, VoxelPalettes palettes) {
		DataLayer block = snapshot.blockLight()[sectionIndex];
		DataLayer sky = snapshot.skyLight()[sectionIndex];
		int skyFallback = sectionIndex > snapshot.highestFilledSectionIndex() ? 15 : 0;
		PalettedContainer<BlockState> states = snapshot.states()[sectionIndex];

		if (states == null) {
			fillAir(block, sky, skyFallback);
			nonAirCount = 0;
		} else {
			resolveBiomes(snapshot.biomes()[sectionIndex], palettes);
			nonAirCount = fillFromStates(states, block, sky, skyFallback, palettes);
		}
		mip(palettes);
	}

	/**
	 * Resolves the 64 biome holders to palette ids once per section instead
	 * of once per cell (4096:64 ratio). One-entry memo: sections are usually
	 * a single biome, so most quarts hit the previous holder by identity.
	 */
	private void resolveBiomes(Holder<Biome>[] holders, VoxelPalettes palettes) {
		Holder<Biome> lastHolder = null;
		int lastId = VoxelPalettes.FALLBACK_BIOME_ID;
		for (int i = 0; i < biomeIds.length; i++) {
			Holder<Biome> holder = holders != null && i < holders.length ? holders[i] : null;
			if (holder == null) {
				biomeIds[i] = VoxelPalettes.FALLBACK_BIOME_ID;
			} else if (holder == lastHolder) {
				biomeIds[i] = lastId;
			} else {
				lastHolder = holder;
				lastId = palettes.idFor(holder);
				biomeIds[i] = lastId;
			}
		}
	}

	/**
	 * Fills P0 by reading every one of the 4096 cells from the vanilla
	 * container. WHY not {@code PalettedContainer.getAll}: that enumerates the
	 * palette's unique values (once per distinct block state), NOT the cells —
	 * using it filled only the first few p0 entries with palette samples and
	 * left the section almost entirely air. The {@code state == lastState} memo
	 * keeps runs of identical blocks (the common case) off the palette map.
	 *
	 * @return non-air cell count.
	 */
	private int fillFromStates(PalettedContainer<BlockState> states, DataLayer block, DataLayer sky,
							   int skyFallback, VoxelPalettes palettes) {
		long[] p0 = levels[0];
		int nonAir = 0;
		BlockState lastState = null;
		int lastId = 0;
		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				int base = (y << 8) | (z << 4);
				for (int x = 0; x < 16; x++) {
					BlockState state = states.get(x, y, z);
					int stateId;
					if (state == lastState) {
						stateId = lastId;
					} else {
						stateId = palettes.idFor(state);
						lastState = state;
						lastId = stateId;
					}
					int bl = block == null ? 0 : block.get(x, y, z);
					int sl = sky == null ? skyFallback : sky.get(x, y, z);
					int biome = biomeIds[((y >> 2) << 4) | ((z >> 2) << 2) | (x >> 2)];
					p0[base | x] = VoxelCell.pack(stateId, biome, bl, sl);
					if (stateId != 0) {
						nonAir++;
					}
				}
			}
		}
		return nonAir;
	}

	/** All-air P0: state 0, plains biome, light from the layers/heuristic. */
	private void fillAir(DataLayer block, DataLayer sky, int skyFallback) {
		long[] p0 = levels[0];
		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				int base = (y << 8) | (z << 4);
				for (int x = 0; x < 16; x++) {
					int bl = block == null ? 0 : block.get(x, y, z);
					int sl = sky == null ? skyFallback : sky.get(x, y, z);
					p0[base | x] = VoxelCell.pack(0, 0, bl, sl);
				}
			}
		}
	}

	/**
	 * Mips each level into the next: every parent cell is
	 * {@link VoxelMipper#selectRepresentative} over its 2x2x2 children,
	 * gathered in child order {@code (dy<<2)|(dz<<1)|dx} — the same order
	 * the incremental remip path uses, so the lowest-index tiebreak is
	 * deterministic across both producers.
	 */
	private void mip(VoxelPalettes palettes) {
		for (int l = 0; l < VoxelConstants.MAX_LEVEL; l++) {
			long[] src = levels[l];
			long[] dst = levels[l + 1];
			int cb = 4 - l;
			int parentAxis = axisCells(l + 1);
			int pb = cb - 1;
			for (int py = 0; py < parentAxis; py++) {
				for (int pz = 0; pz < parentAxis; pz++) {
					for (int px = 0; px < parentAxis; px++) {
						for (int dy = 0; dy <= 1; dy++) {
							for (int dz = 0; dz <= 1; dz++) {
								int srcBase = ((py << 1 | dy) << (2 * cb)) | ((pz << 1 | dz) << cb) | (px << 1);
								children[(dy << 2) | (dz << 1)] = src[srcBase];
								children[(dy << 2) | (dz << 1) | 1] = src[srcBase | 1];
							}
						}
						dst[(py << (2 * pb)) | (pz << pb) | px] = VoxelMipper.selectRepresentative(children, palettes);
					}
				}
			}
		}
	}

}
