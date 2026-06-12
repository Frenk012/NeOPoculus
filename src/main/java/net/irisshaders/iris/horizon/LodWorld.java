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
		LodChunk previous = chunks.put(LodChunk.key(chunk.chunkX, chunk.chunkZ), chunk);
		boolean changed = previous == null
			|| !java.util.Arrays.equals(previous.height, chunk.height)
			|| !java.util.Arrays.equals(previous.waterHeight, chunk.waterHeight)
			|| !java.util.Arrays.equals(previous.color, chunk.color);
		if (changed) {
			dirtyRegions.add(LodStorage.regionKey(chunk.chunkX >> 5, chunk.chunkZ >> 5));
		}
		return changed;
	}

	/** Insertion from disk: never marks dirty, never overwrites live data. */
	public void putFromDisk(LodChunk chunk) {
		chunks.putIfAbsent(LodChunk.key(chunk.chunkX, chunk.chunkZ), chunk);
	}

	public boolean contains(int chunkX, int chunkZ) {
		return chunks.containsKey(LodChunk.key(chunkX, chunkZ));
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
