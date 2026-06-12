package net.irisshaders.iris.horizon;

import net.irisshaders.iris.Iris;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Disk persistence for LOD data. One gzip file per 32x32-chunk storage
 * region, per dimension, per world/server, under {@code horizon-lod/} in
 * the game directory. This is what lets explored terrain stay visible
 * across sessions, far beyond what the server is currently sending.
 */
public final class LodStorage {
	private static final int MAGIC = 0x484C4F44; // "HLOD"
	private static final int VERSION = 1;

	private final Path dimensionDir;
	private final Set<Long> loadedRegions = ConcurrentHashMap.newKeySet();

	public LodStorage(Path gameDir, String worldId, String dimensionId) {
		this.dimensionDir = gameDir.resolve("horizon-lod")
			.resolve(sanitize(worldId))
			.resolve(sanitize(dimensionId));
		try {
			Files.createDirectories(dimensionDir);
		} catch (IOException e) {
			Iris.logger.error("Horizon: cannot create LOD storage directory " + dimensionDir, e);
		}
	}

	private static String sanitize(String s) {
		return s.replaceAll("[^a-zA-Z0-9._-]", "_");
	}

	public static long regionKey(int regionX, int regionZ) {
		return ((long) regionX & 0xFFFFFFFFL) | (((long) regionZ & 0xFFFFFFFFL) << 32);
	}

	private Path regionFile(int regionX, int regionZ) {
		return dimensionDir.resolve("r." + regionX + "." + regionZ + ".hlod");
	}

	/**
	 * Forgets that a region was loaded, so a later loadRegionIfNeeded reads
	 * it from disk again. Called after its chunks are evicted from memory.
	 */
	public void markUnloaded(long regionKey) {
		loadedRegions.remove(regionKey);
	}

	/** Loads a storage region into the world if not already attempted. Worker threads only. */
	public synchronized void loadRegionIfNeeded(LodWorld world, int regionX, int regionZ) {
		if (!loadedRegions.add(regionKey(regionX, regionZ))) {
			return;
		}
		Path file = regionFile(regionX, regionZ);
		if (!Files.exists(file)) {
			return;
		}
		try (InputStream raw = Files.newInputStream(file);
			 DataInputStream in = new DataInputStream(new GZIPInputStream(raw))) {
			if (in.readInt() != MAGIC || in.readInt() != VERSION) {
				Iris.logger.warn("Horizon: unrecognized LOD region format, skipping " + file);
				return;
			}
			int count = in.readInt();
			if (count < 0 || count > 32 * 32) {
				Iris.logger.warn("Horizon: corrupt LOD region (chunk count " + count + "), skipping " + file);
				return;
			}
			for (int i = 0; i < count; i++) {
				int cx = in.readInt();
				int cz = in.readInt();
				LodChunk chunk = new LodChunk(cx, cz);
				for (int j = 0; j < 256; j++) chunk.height[j] = in.readShort();
				for (int j = 0; j < 256; j++) chunk.waterHeight[j] = in.readShort();
				for (int j = 0; j < 256; j++) chunk.color[j] = in.readInt();
				world.putFromDisk(chunk);
			}
		} catch (IOException e) {
			Iris.logger.error("Horizon: failed to load LOD region " + file, e);
		}
	}

	/**
	 * Writes one storage region from the world to disk. Worker threads
	 * only; synchronized so concurrent save/load of the same files cannot
	 * interleave.
	 *
	 * @return false if the data could not be committed; the caller must
	 * keep the region flagged dirty so the data is neither lost nor evicted.
	 */
	public synchronized boolean saveRegion(LodWorld world, int regionX, int regionZ) {
		// Saving implies its content is fully in memory; mark as loaded so a
		// later load does not overwrite newer in-memory data.
		loadRegionIfNeeded(world, regionX, regionZ);

		java.util.List<LodChunk> toWrite = new java.util.ArrayList<>();
		world.forEachInStorageRegion(regionX, regionZ, toWrite::add);
		if (toWrite.isEmpty()) {
			return true;
		}

		Path file = regionFile(regionX, regionZ);
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try (OutputStream raw = Files.newOutputStream(tmp);
			 DataOutputStream out = new DataOutputStream(new GZIPOutputStream(raw))) {
			out.writeInt(MAGIC);
			out.writeInt(VERSION);
			out.writeInt(toWrite.size());
			for (LodChunk chunk : toWrite) {
				out.writeInt(chunk.chunkX);
				out.writeInt(chunk.chunkZ);
				for (int j = 0; j < 256; j++) out.writeShort(chunk.height[j]);
				for (int j = 0; j < 256; j++) out.writeShort(chunk.waterHeight[j]);
				for (int j = 0; j < 256; j++) out.writeInt(chunk.color[j]);
			}
		} catch (IOException e) {
			Iris.logger.error("Horizon: failed to write LOD region " + tmp, e);
			return false;
		}
		try {
			Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			return true;
		} catch (IOException e) {
			Iris.logger.error("Horizon: failed to commit LOD region " + file, e);
			return false;
		}
	}
}
