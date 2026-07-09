package net.irisshaders.iris.horizon;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory LOD chunk store for one dimension. Thread-safe: written from
 * the capture path and storage loader, read from the meshing worker.
 */
public final class LodWorld {
	private final ConcurrentHashMap<Long, LodChunk> chunks = new ConcurrentHashMap<>();
	/** Storage region keys (32x32 chunks) with unsaved changes. */
	private final Set<Long> dirtyRegions = ConcurrentHashMap.newKeySet();

	public LodChunk get(int chunkX, int chunkZ) {
		return chunks.get(LodChunk.key(chunkX, chunkZ));
	}

	/**
	 * Stores a captured chunk. Returns true if the data changed in a way
	 * that requires remeshing.
	 */
	public boolean put(LodChunk chunk) {
		return put(chunk, false);
	}

	public boolean put(LodChunk chunk, boolean preserveColors) {
		LodChunk previous = chunks.get(LodChunk.key(chunk.chunkX, chunk.chunkZ));
		if (preserveColors && previous != null) {
			// Unload snapshot: biome context may be degraded. Keep the stored
			// (correctly tinted) colors for every column whose shape is
			// unchanged; only genuinely edited columns take the new color.
			for (int i = 0; i < chunk.color.length; i++) {
				if (previous.height[i] == chunk.height[i]
					&& previous.waterHeight[i] == chunk.waterHeight[i]
					&& previous.featureTop[i] == chunk.featureTop[i]
					&& previous.featureBottom[i] == chunk.featureBottom[i]) {
					chunk.color[i] = previous.color[i];
					chunk.featureColor[i] = previous.featureColor[i];
				}
			}
		}
		boolean changed = previous == null
			|| !java.util.Arrays.equals(previous.height, chunk.height)
			|| !java.util.Arrays.equals(previous.waterHeight, chunk.waterHeight)
			|| !java.util.Arrays.equals(previous.color, chunk.color)
			|| !java.util.Arrays.equals(previous.featureTop, chunk.featureTop)
			|| !java.util.Arrays.equals(previous.featureBottom, chunk.featureBottom)
			|| !java.util.Arrays.equals(previous.featureColor, chunk.featureColor);
		if (changed) {
			// Mark dirty before publishing so concurrent eviction can never
			// drop an unsaved chunk.
			dirtyRegions.add(LodStorage.regionKey(chunk.chunkX >> 5, chunk.chunkZ >> 5));
		}
		chunks.put(LodChunk.key(chunk.chunkX, chunk.chunkZ), chunk);
		return changed;
	}

	/**
	 * Drops chunks farther than radius (in chunks, chebyshev) from the
	 * center, but only those whose storage region has no unsaved changes:
	 * evicted data is reloaded from disk on demand. Returns the storage
	 * region keys that lost chunks, so the storage layer can allow reloads.
	 */
	public Set<Long> evictOutside(int centerChunkX, int centerChunkZ, int radiusChunks) {
		Set<Long> touched = new java.util.HashSet<>();
		for (LodChunk chunk : chunks.values()) {
			if (Math.max(Math.abs(chunk.chunkX - centerChunkX), Math.abs(chunk.chunkZ - centerChunkZ)) <= radiusChunks) {
				continue;
			}
			long storageRegion = LodStorage.regionKey(chunk.chunkX >> 5, chunk.chunkZ >> 5);
			if (dirtyRegions.contains(storageRegion)) {
				continue; // unsaved data, keep until the next save pass
			}
			// Two-arg remove: if a fresh capture replaced this chunk after
			// the iterator read it, the newer (dirty) instance survives.
			if (chunks.remove(LodChunk.key(chunk.chunkX, chunk.chunkZ), chunk)) {
				touched.add(storageRegion);
			}
		}
		return touched;
	}

	/** Insertion from disk: never marks dirty, never overwrites live data. */
	public void putFromDisk(LodChunk chunk) {
		chunks.putIfAbsent(LodChunk.key(chunk.chunkX, chunk.chunkZ), chunk);
	}

	public boolean contains(int chunkX, int chunkZ) {
		return chunks.containsKey(LodChunk.key(chunkX, chunkZ));
	}

	/** Re-flags a storage region, e.g. after a failed save. */
	public void markDirty(long storageRegionKey) {
		dirtyRegions.add(storageRegionKey);
	}

	public Set<Long> drainDirtyRegions() {
		Set<Long> copy = new java.util.HashSet<>(dirtyRegions);
		dirtyRegions.removeAll(copy);
		return copy;
	}

	public void forEachInStorageRegion(int regionX, int regionZ, java.util.function.Consumer<LodChunk> consumer) {
		int minCx = regionX << 5, minCz = regionZ << 5;
		for (int cx = minCx; cx < minCx + 32; cx++) {
			for (int cz = minCz; cz < minCz + 32; cz++) {
				LodChunk chunk = get(cx, cz);
				if (chunk != null) {
					consumer.accept(chunk);
				}
			}
		}
	}

	public int size() {
		return chunks.size();
	}

	public void clear() {
		chunks.clear();
		dirtyRegions.clear();
	}
}
