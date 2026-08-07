package net.irisshaders.iris.horizon.voxel;

import net.irisshaders.iris.Iris;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Frees disk when a world's LOD cache reaches its budget (M2b).
 *
 * <p>Eviction is by DISTANCE from the player, not by age. Age is a poor proxy
 * for value here: the region you explored first is often the one you live in,
 * while the far edge of a long trip is the least likely to be looked at again.
 * Distance also matches how the renderer already thinks — it keeps meshes within
 * a per-level radius — so evicting the farthest files removes exactly what was
 * already invisible. Age remains as the tiebreak when two regions are equally
 * far, which is what makes the order total and the result reproducible.
 *
 * <p>Deletion targets a fraction BELOW the budget rather than the budget itself:
 * trimming to exactly the limit would put the cache back over it on the next
 * section written, and the cleanup would run again immediately.
 */
public final class LodCacheCleaner {
	/** Clean down to this fraction of the budget, so it is not re-triggered at once. */
	private static final double TARGET_FRACTION = 0.80;

	private LodCacheCleaner() {
	}

	/** Result of one cleanup, for logging and for telling the player. */
	public record Result(int filesDeleted, long bytesFreed, long bytesRemaining) {
	}

	/**
	 * Deletes the farthest region files under {@code lodRoot} until the cache is
	 * comfortably below {@code budgetBytes}.
	 *
	 * @param lodRoot     the world's LOD directory
	 * @param playerX     player block X, the centre distance is measured from
	 * @param playerZ     player block Z
	 * @param budgetBytes the configured ceiling
	 */
	public static Result cleanFarthest(Path lodRoot, int playerX, int playerZ, long budgetBytes) {
		List<RegionFile> files = collect(lodRoot);
		long total = 0;
		for (RegionFile f : files) {
			total += f.size;
		}
		if (total <= budgetBytes) {
			return new Result(0, 0, total);
		}
		long target = (long) (budgetBytes * TARGET_FRACTION);

		for (RegionFile f : files) {
			f.distanceSq = f.distanceSqFrom(playerX, playerZ);
		}
		// Farthest first; oldest first among equals so the order is total.
		files.sort(Comparator.<RegionFile>comparingDouble(f -> -f.distanceSq)
			.thenComparingLong(f -> f.modified));

		int deleted = 0;
		long freed = 0;
		for (RegionFile f : files) {
			if (total - freed <= target) {
				break;
			}
			try {
				Files.deleteIfExists(f.path);
				freed += f.size;
				deleted++;
			} catch (Throwable t) {
				// A file the OS will not let go of is not worth failing over;
				// the next cleanup will try again.
				Iris.logger.debug("Horizon: could not delete LOD region " + f.path);
			}
		}
		Iris.logger.info("Horizon: LOD cache cleanup freed " + (freed >> 20) + " MB across "
			+ deleted + " region files (" + ((total - freed) >> 20) + " MB left)");
		return new Result(deleted, freed, total - freed);
	}

	/** Total bytes currently held under a world's LOD directory. */
	public static long usedBytes(Path lodRoot) {
		long total = 0;
		for (RegionFile f : collect(lodRoot)) {
			total += f.size;
		}
		return total;
	}

	/** Deletes an entire world's LOD cache. Used by the explicit purge. */
	public static long purge(Path lodRoot) {
		long freed = 0;
		if (lodRoot == null || !Files.isDirectory(lodRoot)) {
			return 0;
		}
		try (var walk = Files.walk(lodRoot)) {
			List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
			for (Path p : paths) {
				try {
					long size = Files.isRegularFile(p) ? Files.size(p) : 0;
					if (Files.deleteIfExists(p)) {
						freed += size;
					}
				} catch (Throwable ignored) {
				}
			}
		} catch (Throwable t) {
			Iris.logger.error("Horizon: LOD cache purge failed for " + lodRoot, t);
		}
		return freed;
	}

	private static final class RegionFile {
		final Path path;
		final int level;
		final int rx;
		final int rz;
		final long size;
		final long modified;
		double distanceSq;

		RegionFile(Path path, int level, int rx, int rz, long size, long modified) {
			this.path = path;
			this.level = level;
			this.rx = rx;
			this.rz = rz;
			this.size = size;
			this.modified = modified;
		}

		/**
		 * Squared distance in blocks from a point to this region's centre. A
		 * storage region spans 8 sections of 32 cells, and a cell is 2^level
		 * blocks, so its footprint grows with the level — which is correct:
		 * a coarse region covers far more ground and should not be judged by the
		 * same yardstick as a fine one.
		 */
		double distanceSqFrom(int px, int pz) {
			long span = (long) (1 << VoxelConstants.STORAGE_REGION_BITS)
				* VoxelConstants.SECTION_SIZE * (1L << level);
			double cx = (rx + 0.5) * span;
			double cz = (rz + 0.5) * span;
			double dx = cx - px;
			double dz = cz - pz;
			return dx * dx + dz * dz;
		}
	}

	private static List<RegionFile> collect(Path lodRoot) {
		List<RegionFile> out = new ArrayList<>();
		if (lodRoot == null || !Files.isDirectory(lodRoot)) {
			return out;
		}
		try (var walk = Files.walk(lodRoot)) {
			for (Path p : walk.toList()) {
				String name = p.getFileName() == null ? "" : p.getFileName().toString();
				if (!name.startsWith("r.") || !name.endsWith(".hlod") || !Files.isRegularFile(p)) {
					continue;
				}
				int[] coords = parse(name);
				if (coords == null) {
					continue;
				}
				int level = levelOf(p);
				out.add(new RegionFile(p, level, coords[0], coords[1],
					Files.size(p), Files.getLastModifiedTime(p).toMillis()));
			}
		} catch (Throwable t) {
			Iris.logger.error("Horizon: could not scan the LOD cache at " + lodRoot, t);
		}
		return out;
	}

	/** {@code r.<x>.<z>.hlod} -> {x, z}. */
	private static int[] parse(String name) {
		String[] parts = name.substring(2, name.length() - 5).split("\\.");
		if (parts.length != 2) {
			return null;
		}
		try {
			return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** Level from the containing {@code L<n>} directory; 0 when it cannot be read. */
	private static int levelOf(Path file) {
		try {
			String dir = file.getParent().getFileName().toString();
			if (dir.startsWith("L")) {
				return Math.max(0, Math.min(VoxelConstants.MAX_LEVEL, Integer.parseInt(dir.substring(1))));
			}
		} catch (Throwable ignored) {
		}
		return 0;
	}
}
