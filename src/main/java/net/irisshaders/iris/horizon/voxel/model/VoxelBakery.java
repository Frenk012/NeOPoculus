package net.irisshaders.iris.horizon.voxel.model;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.horizon.voxel.VoxelConstants;
import net.irisshaders.iris.horizon.voxel.VoxelPalettes;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates the photo atlas (design-bakery-atlas.md, M4). Mesher workers call
 * {@link #requestBake} when they hit an unbaked (state, biome) pair (using the
 * flat MapColor meanwhile); the render thread drains the queue in
 * {@link #process}, baking each pair's six faces with the biome's tint,
 * deduplicating identical photos, uploading to the {@link PhotoAtlas}, and
 * publishing slots into the {@link StateMetadataTable}. Untinted states resolve
 * biome-independently and are baked once regardless of the requesting biome (see
 * {@link StateMetadataTable}). {@link #epoch()} bumps whenever new slots land, so
 * the renderer can re-mesh the regions still showing flat fallback color.
 */
public final class VoxelBakery {
	private static final int BIOME_MASK = VoxelConstants.MAX_BIOME_IDS - 1;

	private final PhotoAtlas atlas = new PhotoAtlas();
	private final StateMetadataTable metadata = new StateMetadataTable();

	// Queue/requested hold packed (stateId << BIOME_BITS | biomeId) keys.
	private final ConcurrentLinkedQueue<Integer> queue = new ConcurrentLinkedQueue<>();
	private final Set<Integer> requested = ConcurrentHashMap.newKeySet();
	/** Photo hash -> atlas slot, so identical faces (e.g. all six of stone) share one slot. Render thread. */
	private final Map<Long, Integer> dedup = new HashMap<>();
	private final AtomicInteger epoch = new AtomicInteger();
	private boolean fullLogged;

	public StateMetadataTable metadata() {
		return metadata;
	}

	public int epoch() {
		return epoch.get();
	}

	public int atlasTexture() {
		return atlas.textureId();
	}

	public int atlasSlotsPerRow() {
		return atlas.slotsPerRow();
	}

	public int atlasSize() {
		return atlas.atlasSize();
	}

	/** Mesher worker: queue a (state, biome) pair for baking if not already baked/queued. */
	public void requestBake(int stateId, int biomeId) {
		if (stateId <= 0 || metadata.isBaked(stateId, biomeId)) {
			return;
		}
		int key = (stateId << VoxelConstants.BIOME_BITS) | (biomeId & BIOME_MASK);
		if (requested.add(key)) {
			queue.add(key);
		}
	}

	/** Render thread: bake up to {@code budget} queued pairs. */
	public void process(int budget, VoxelPalettes palettes) {
		Integer k;
		int done = 0;
		while (done < budget && (k = queue.poll()) != null) {
			int key = k;
			bake(key >>> VoxelConstants.BIOME_BITS, key & BIOME_MASK, palettes);
			done++;
		}
	}

	private void bake(int stateId, int biomeId, VoxelPalettes palettes) {
		BlockState state = null;
		try {
			state = palettes.stateOf(stateId);
		} catch (Throwable ignored) {
		}
		Biome biome = resolveBiome(biomeId, palettes);
		boolean leafLike = palettes.isLeaf(stateId);
		int[] faceSlots = new int[VoxelConstants.FACE_COUNT];
		boolean tinted = false;
		for (int f = 0; f < VoxelConstants.FACE_COUNT; f++) {
			PhotoBaker.Baked baked = state == null ? null : PhotoBaker.bakeFace(state, biome, f);
			if (baked == null || baked.photo() == null) {
				faceSlots[f] = 0;
				continue;
			}
			tinted |= baked.tinted();
			faceSlots[f] = uploadDedup(baked.photo(), leafLike);
		}
		metadata.setBaked(stateId, biomeId, tinted, faceSlots);
		epoch.incrementAndGet();
	}

	/** Uploads a photo, deduplicating identical ones; returns the atlas slot (0 = flat fallback / atlas full). */
	private int uploadDedup(int[] photo, boolean leafLike) {
		// leafLike changes the mip chain, so two identical photos with different
		// leaf flags are genuinely different slots and must not share one.
		long hash = hash(photo) * 31 + (leafLike ? 1 : 0);
		Integer existing = dedup.get(hash);
		if (existing != null) {
			return existing;
		}
		int slot = atlas.upload(photo, leafLike);
		if (slot == 0) {
			if (!fullLogged) {
				fullLogged = true;
				Iris.logger.warn("Horizon: voxel photo atlas full; remaining block faces use flat color");
			}
			return 0;
		}
		dedup.put(hash, slot);
		return slot;
	}

	/** Resolves a LOD biome id to the live {@link Biome}, or null (default tint) if unavailable. Render thread. */
	private Biome resolveBiome(int biomeId, VoxelPalettes palettes) {
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc.level == null) {
				return null;
			}
			ResourceLocation name = palettes.biomeOf(biomeId);
			return name == null ? null : mc.level.registryAccess().registryOrThrow(Registries.BIOME).get(name);
		} catch (Throwable t) {
			return null;
		}
	}

	private static long hash(int[] photo) {
		long h = 1125899906842597L;
		for (int p : photo) {
			h = 31 * h + p;
		}
		return h;
	}

	/** Render thread: drop the atlas + all bakes (resource reload / shutdown). */
	public void clear() {
		queue.clear();
		requested.clear();
		dedup.clear();
		metadata.clear();
		atlas.destroy();
		epoch.incrementAndGet();
		fullLogged = false;
	}
}
