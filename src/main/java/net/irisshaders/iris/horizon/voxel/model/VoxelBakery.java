package net.irisshaders.iris.horizon.voxel.model;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.horizon.voxel.VoxelConstants;
import net.irisshaders.iris.horizon.voxel.VoxelPalettes;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates the photo atlas (design-bakery-atlas.md, M4 v1). Mesher workers
 * call {@link #requestBake} when they hit an unbaked state (using the flat
 * MapColor meanwhile); the render thread drains the queue in {@link #process},
 * baking each state's six faces, deduplicating identical photos, uploading to
 * the {@link PhotoAtlas}, and publishing slots into the {@link StateMetadataTable}.
 * {@link #epoch()} bumps whenever new slots land, so the renderer can re-mesh
 * the regions still showing flat fallback color.
 */
public final class VoxelBakery {
	private final PhotoAtlas atlas = new PhotoAtlas();
	private final StateMetadataTable metadata = new StateMetadataTable();

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

	/** Mesher worker: queue a state for baking if not already baked/queued. */
	public void requestBake(int stateId) {
		if (stateId <= 0 || metadata.isBaked(stateId)) {
			return;
		}
		if (requested.add(stateId)) {
			queue.add(stateId);
		}
	}

	/** Render thread: bake up to {@code budget} queued states. */
	public void process(int budget, VoxelPalettes palettes) {
		Integer id;
		int done = 0;
		while (done < budget && (id = queue.poll()) != null) {
			bake(id, palettes);
			done++;
		}
	}

	private void bake(int stateId, VoxelPalettes palettes) {
		int[] faceSlots = new int[VoxelConstants.FACE_COUNT];
		BlockState state = null;
		try {
			state = palettes.stateOf(stateId);
		} catch (Throwable ignored) {
		}
		for (int f = 0; f < VoxelConstants.FACE_COUNT; f++) {
			faceSlots[f] = state == null ? 0 : slotForFace(state, f);
		}
		metadata.setBaked(stateId, faceSlots);
		epoch.incrementAndGet();
	}

	/** Bakes one face, deduplicating identical photos; returns the atlas slot (0 = flat fallback). */
	private int slotForFace(BlockState state, int face) {
		int[] photo = PhotoBaker.bakeFace(state, face);
		if (photo == null) {
			return 0;
		}
		long hash = hash(photo);
		Integer existing = dedup.get(hash);
		if (existing != null) {
			return existing;
		}
		int slot = atlas.upload(photo);
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
