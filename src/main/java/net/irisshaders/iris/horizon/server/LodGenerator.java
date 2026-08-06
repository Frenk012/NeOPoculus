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
	private final VoxelEngine engine;
	private final ChunkPos[] work;
	private final boolean generateMissing;
	private final long budgetMs;

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
			if (engine.captureForGeneration(chunk)) {
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

	private LevelChunk fetch(ChunkPos pos) {
		try {
			if (generateMissing) {
				ChunkAccess access = level.getChunk(pos.x, pos.z, ChunkStatus.FULL, true);
				return access instanceof LevelChunk lc ? lc : null;
			}
			// Already-resident chunks only: never make the server generate terrain
			// the player has not visited unless explicitly asked.
			return level.getChunkSource().getChunk(pos.x, pos.z, false);
		} catch (Throwable t) {
			Iris.logger.error("Horizon: LOD generation failed for chunk " + pos, t);
			return null;
		}
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
