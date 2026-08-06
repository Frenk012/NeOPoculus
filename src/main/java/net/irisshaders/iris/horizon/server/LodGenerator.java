package net.irisshaders.iris.horizon.server;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.horizon.voxel.VoxelEngine;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Builds the voxel LOD from the server's own chunks instead of waiting for a
 * player to walk over them (M5b phase A, DESIGN.md section 10). Driven by the
 * {@code /horizon lod} commands and pumped from the server tick.
 *
 * <p>Work is strictly budgeted per tick: chunk generation is the single most
 * expensive thing a server can be asked to do, and a pre-generation command must
 * never be the reason a world stops responding. The budget is wall-clock, not a
 * chunk count, because a chunk that has to be generated costs orders of
 * magnitude more than one already on disk.
 *
 * <p>Phase A targets the integrated server: the generated cells go straight into
 * the client's live store in the same process, so there is no palette contract
 * to honour and no network. Phase B reuses this walker with a server-side store.
 */
public final class LodGenerator {
	/** Wall-clock milliseconds of chunk work per server tick (a tick is 50 ms). */
	private static final long DEFAULT_BUDGET_MS = 10L;
	/** Progress is logged at most this often. */
	private static final long LOG_INTERVAL_MS = 5_000L;

	private final ServerLevel level;
	/** Client engine target (integrated server); null when running headless. */
	private final VoxelEngine engine;
	/** Server-side store target (dedicated server); null when targeting the client engine. */
	private final net.irisshaders.iris.horizon.voxel.ServerVoxelStores serverStores;
	private final ChunkPos[] work;
	private final boolean generateMissing;
	private final long budgetMs;

	/** Region-file presence cache, keyed by region coords; see existsOnDisk. */
	private final java.util.Map<Long, Boolean> regionOnDisk = new java.util.HashMap<>();

	private int cursor;
	private int captured;
	private int skipped;
	private int deferred;
	private boolean paused;
	private boolean done;
	private long lastLogAt;

	private LodGenerator(ServerLevel level, VoxelEngine engine, ChunkPos[] work,
						 boolean generateMissing, long budgetMs) {
		this.level = level;
		this.engine = engine;
		this.serverStores = engine == null ? HorizonLodServer.serverStores(level.getServer()) : null;
		this.work = work;
		this.generateMissing = generateMissing;
		this.budgetMs = budgetMs;
	}

	/**
	 * A generator covering every chunk within {@code radiusBlocks} of
	 * (centerX, centerZ), ordered nearest-first so the panorama fills in from
	 * the player outward instead of from an arbitrary corner.
	 */
	public static LodGenerator radius(ServerLevel level, VoxelEngine engine,
									  int centerX, int centerZ, int radiusBlocks, boolean generateMissing) {
		int centerChunkX = centerX >> 4;
		int centerChunkZ = centerZ >> 4;
		int radiusChunks = Math.max(1, (radiusBlocks + 15) >> 4);
		java.util.List<ChunkPos> list = new java.util.ArrayList<>();
		long radiusSq = (long) radiusChunks * radiusChunks;
		for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
			for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
				if ((long) dx * dx + (long) dz * dz <= radiusSq) {
					list.add(new ChunkPos(centerChunkX + dx, centerChunkZ + dz));
				}
			}
		}
		list.sort(java.util.Comparator.comparingLong(p -> {
			long dx = p.x - centerChunkX;
			long dz = p.z - centerChunkZ;
			return dx * dx + dz * dz;
		}));
		return new LodGenerator(level, engine, list.toArray(new ChunkPos[0]),
			generateMissing, DEFAULT_BUDGET_MS);
	}

	/**
	 * A generator covering an explicit block rectangle, ordered nearest-first
	 * from its centre.
	 */
	public static LodGenerator region(ServerLevel level, VoxelEngine engine,
									  int x1, int z1, int x2, int z2, boolean generateMissing) {
		int minCx = Math.min(x1, x2) >> 4;
		int maxCx = Math.max(x1, x2) >> 4;
		int minCz = Math.min(z1, z2) >> 4;
		int maxCz = Math.max(z1, z2) >> 4;
		java.util.List<ChunkPos> list = new java.util.ArrayList<>();
		for (int cz = minCz; cz <= maxCz; cz++) {
			for (int cx = minCx; cx <= maxCx; cx++) {
				list.add(new ChunkPos(cx, cz));
			}
		}
		int cx0 = (minCx + maxCx) / 2;
		int cz0 = (minCz + maxCz) / 2;
		list.sort(java.util.Comparator.comparingLong(p -> {
			long dx = p.x - cx0;
			long dz = p.z - cz0;
			return dx * dx + dz * dz;
		}));
		return new LodGenerator(level, engine, list.toArray(new ChunkPos[0]),
			generateMissing, DEFAULT_BUDGET_MS);
	}

	/**
	 * A generator covering everything already saved to disk, read from the
	 * dimension's region files. Never generates terrain: "the whole world" means
	 * the world that exists, not an unbounded walk outward.
	 */
	public static LodGenerator wholeWorld(ServerLevel level, VoxelEngine engine) {
		java.util.List<ChunkPos> list = new java.util.ArrayList<>();
		try {
			java.nio.file.Path dir = net.minecraft.world.level.dimension.DimensionType
				.getStorageFolder(level.dimension(), level.getServer().getWorldPath(
					net.minecraft.world.level.storage.LevelResource.ROOT))
				.resolve("region");
			if (java.nio.file.Files.isDirectory(dir)) {
				try (var stream = java.nio.file.Files.list(dir)) {
					for (java.nio.file.Path file : stream.toList()) {
						int[] rc = parseRegionName(file.getFileName().toString());
						if (rc == null) {
							continue;
						}
						for (int dz = 0; dz < 32; dz++) {
							for (int dx = 0; dx < 32; dx++) {
								list.add(new ChunkPos((rc[0] << 5) + dx, (rc[1] << 5) + dz));
							}
						}
					}
				}
			}
		} catch (Throwable t) {
			Iris.logger.error("Horizon: could not enumerate region files for whole-world LOD generation", t);
		}
		return new LodGenerator(level, engine, list.toArray(new ChunkPos[0]), false, DEFAULT_BUDGET_MS);
	}

	/** {@code r.<x>.<z>.mca} -> {x, z}, or null when the name is not a region file. */
	private static int[] parseRegionName(String name) {
		if (!name.startsWith("r.") || !name.endsWith(".mca")) {
			return null;
		}
		String[] parts = name.substring(2, name.length() - 4).split("\\.");
		if (parts.length != 2) {
			return null;
		}
		try {
			return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** Chunks this generator will visit; the command reports it before starting. */
	public int totalChunks() {
		return work.length;
	}

	/**
	 * Does up to one tick's worth of work. Server thread.
	 *
	 * @return true while there is still work left.
	 */
	public boolean tick() {
		if (done) {
			return false;
		}
		if (paused) {
			return true;
		}
		long deadline = System.nanoTime() + budgetMs * 1_000_000L;
		while (cursor < work.length) {
			if (System.nanoTime() >= deadline) {
				logProgress(false);
				return true;
			}
			ChunkPos pos = work[cursor];
			LevelChunk chunk = fetch(pos);
			if (chunk == null) {
				skipped++;
				cursor++;
				continue;
			}
			if (capture(chunk)) {
				captured++;
				cursor++;
			} else {
				// Light has not settled for this chunk yet. Leave the cursor put
				// and come back next tick rather than baking darkness in.
				deferred++;
				logProgress(false);
				return true;
			}
		}
		done = true;
		logProgress(true);
		return false;
	}

	/**
	 * Routes the capture to whichever LOD residency this run targets: the live
	 * client engine on an integrated server (same process, so the player sees the
	 * result immediately), or the server's own store when headless.
	 */
	private boolean capture(LevelChunk chunk) {
		if (engine != null) {
			return engine.captureForGeneration(chunk);
		}
		return serverStores != null && serverStores.captureChunk(level, chunk);
	}

	/**
	 * Fetches a chunk to capture.
	 *
	 * <p>Both modes load from DISK, which is the whole point: a world
	 * pre-generated by a tool like Chunky lives in region files, not in memory.
	 * Asking only for resident chunks (the first version of this) matched just
	 * the handful loaded around the player and skipped millions.
	 *
	 * <p>The difference between the modes is only what happens where no terrain
	 * exists yet: the default skips those positions, {@code generateMissing}
	 * makes the server generate them, which is orders of magnitude more
	 * expensive.
	 */
	private LevelChunk fetch(ChunkPos pos) {
		try {
			if (!generateMissing && !level.getChunkSource().hasChunk(pos.x, pos.z)
				&& !existsOnDisk(pos)) {
				return null;
			}
			ChunkAccess access = level.getChunk(pos.x, pos.z, ChunkStatus.FULL, true);
			return access instanceof LevelChunk lc ? lc : null;
		} catch (Throwable t) {
			Iris.logger.error("Horizon: LOD generation failed for chunk " + pos, t);
			return null;
		}
	}

	/**
	 * Whether terrain exists on disk for this chunk, decided by the presence of
	 * its region file. Vanilla's per-chunk check ({@code ChunkMap.readChunk}) is
	 * private, but region-file granularity (512×512 blocks) is enough for the job
	 * this serves: telling "never-generated wilderness" apart from "world already
	 * pre-generated by a tool", where whole regions exist or do not. Results are
	 * cached, so a sweep of millions of positions costs one stat per region.
	 *
	 * <p>An unreadable answer counts as present, so the caller falls back to the
	 * normal load path instead of silently skipping real terrain.
	 */
	private boolean existsOnDisk(ChunkPos pos) {
		long regionKey = (((long) (pos.x >> 5)) << 32) ^ (pos.z >> 5) & 0xFFFFFFFFL;
		Boolean cached = regionOnDisk.get(regionKey);
		if (cached != null) {
			return cached;
		}
		boolean present;
		try {
			java.nio.file.Path dir = net.minecraft.world.level.dimension.DimensionType
				.getStorageFolder(level.dimension(), level.getServer().getWorldPath(
					net.minecraft.world.level.storage.LevelResource.ROOT));
			present = java.nio.file.Files.exists(
				dir.resolve("region").resolve("r." + (pos.x >> 5) + "." + (pos.z >> 5) + ".mca"));
		} catch (Throwable t) {
			present = true;
		}
		regionOnDisk.put(regionKey, present);
		return present;
	}

	private void logProgress(boolean finished) {
		long now = System.currentTimeMillis();
		if (!finished && now - lastLogAt < LOG_INTERVAL_MS) {
			return;
		}
		lastLogAt = now;
		Iris.logger.info("Horizon: LOD generation " + status());
	}

	public String status() {
		int pct = work.length == 0 ? 100 : (int) (cursor * 100L / work.length);
		return (done ? "done" : paused ? "paused" : "running")
			+ " " + cursor + "/" + work.length + " chunks (" + pct + "%), "
			+ captured + " captured, " + skipped + " skipped, " + deferred + " deferrals"
			+ (generateMissing ? ", generating missing terrain" : ", existing chunks only");
	}

	public void pause() {
		paused = true;
	}

	public void resume() {
		paused = false;
	}

	public void cancel() {
		done = true;
	}

	public boolean isDone() {
		return done;
	}

	public ServerLevel level() {
		return level;
	}
}
