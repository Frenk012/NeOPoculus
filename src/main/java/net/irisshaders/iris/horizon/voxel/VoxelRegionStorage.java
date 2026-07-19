package net.irisshaders.iris.horizon.voxel;

import net.irisshaders.iris.Iris;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Disk persistence for the voxel LOD engine: one gzip {@code .hlod} v4 file per
 * (level, storage-region) triple (design-data-storage.md section 6). A storage
 * region is {@code 8x8} sections in x/z at one level, all y
 * ({@link VoxelConstants#STORAGE_REGION_BITS} = 3), so
 * {@code rx = secX >> 3}, {@code rz = secZ >> 3}. Files live under
 * {@code horizon-lod/<worldId>/<dim>/voxel/L<level>/r.<rx>.<rz>.hlod} — the same
 * base-path resolution as classic {@link net.irisshaders.iris.horizon.LodStorage}
 * (game dir / world id / dimension id), then a {@code voxel/} subtree so the two
 * engines' files never collide.
 *
 * <h2>Why per-dimension despite the "world-shared" palette</h2>
 * The palette (id-&gt;state mapping) is shared across all dimensions of a world,
 * but the actual voxel data is not: an overworld and a nether section can share
 * the same {@code (level, rx, rz)}. Dropping the dimension folder from the path
 * would make them clobber each other on disk. The task's one-line path sketch
 * abbreviated the dimension away; design section 6 (authoritative) keeps it, and
 * correctness requires it, so this class mirrors LodStorage's
 * {@code horizon-lod/<worldId>/<dim>} base and appends {@code voxel/}. The
 * world-shared palette lives one level up at {@code <worldId>/palette.nbt}
 * ({@link #paletteFile}), outside any dimension folder.
 *
 * <h2>Row frame (narrowed from the section-6 sketch)</h2>
 * The section-6 file sketch listed a framed {@code byte encoding} field. The
 * shipped {@link VoxelSectionCodec} instead keeps the encoding id as byte 0 of
 * its payload (its "separation of concerns" decision), so storage treats the
 * payload as an opaque blob and does <em>not</em> frame the encoding separately.
 * Each row is therefore:
 * <pre>
 *   long  sectionKey
 *   int   nonAirCount
 *   byte  maskLen                 (== VoxelSection.populationMaskLongs(level))
 *   maskLen x long populationMask
 *   int   payloadLength           (&gt;= 1; includes the codec's encoding-id byte)
 *   byte[payloadLength] payload    (== VoxelSectionCodec.encodeCells output)
 * </pre>
 * preceded by the file header {@code MAGIC, VERSION, level, regionX, regionZ,
 * sectionCount}. The whole file is gzip-framed (its CRC is the primary
 * corruption detector), written to a sibling {@code .tmp} and atomically moved
 * into place — the LodStorage machinery pattern.
 *
 * <h2>Read-modify-write</h2>
 * {@link #saveRegionRows} reads the existing file into a row map, overlays the
 * changed rows (a row with {@code nonAirCount == 0} or a null payload is a
 * removal — an all-air section is dropped, never stored, per design section
 * 1.4), and rewrites the whole file. A region that ends up with no rows has its
 * file deleted. This is O(region) per save even for a one-row change, exactly as
 * design section 6 specifies ("read the whole region, replace changed rows,
 * atomically rewrite").
 *
 * <h2>Concurrency</h2>
 * Called from worker/IO threads. A per-(level, region) monitor
 * ({@link #regionLocks}) serializes concurrent save/load of the <em>same</em>
 * region; different regions proceed in parallel. This is the only synchronization
 * here — the caller ({@code VoxelStore}) owns the palette-first ordering
 * invariant (design section 2) and the HOT/WARM residency it is faulting through.
 * Note the read stream is fully closed before any quarantine rename, so a
 * corrupt file can be moved aside even on Windows (no lingering open handle).
 *
 * <p>Classic LodStorage's {@code loadedRegions} "already loaded, don't re-read"
 * guard is deliberately <em>not</em> replicated here: read-modify-write reads the
 * current disk state on every save, so a stale in-memory view can never clobber
 * newer on-disk rows. The "load a region once, then serve from memory" guard
 * belongs one layer up in {@code VoxelStore} (design section 6's load-on-demand
 * WARM-install), keyed to residency, not to file IO.
 *
 * <h2>Corruption / version handling</h2>
 * {@link #loadRegion} never throws. Bad magic, a truncated/short gzip stream, an
 * implausible count, a row whose mask length or payload length is out of range —
 * all quarantine the file (rename to {@code *.corrupt[-n]}) and return empty, so
 * the terrain re-captures on approach and the bad file does not re-fail every
 * session. A version mismatch quarantines to {@code *.old} (locked
 * regenerate-don't-migrate rule); the caller's stale-id cells then decode to
 * stone via the palette fallback until re-captured.
 */
final class VoxelRegionStorage {
	private static final int MAGIC = 0x484C4F44; // "HLOD"

	/**
	 * Upper bound on rows in one region file: {@code 8x8} section columns times
	 * the full biased section-y range ({@link VoxelConstants#KEY_Y_BITS} = 256
	 * slots). A larger count in a header means a corrupt length field, so we
	 * refuse to allocate for it rather than OOM on garbage.
	 */
	private static final int MAX_ROWS_PER_REGION =
		(1 << (2 * VoxelConstants.STORAGE_REGION_BITS)) * (1 << VoxelConstants.KEY_Y_BITS);

	/**
	 * Largest legal payload: the RAW encoding is {@code 1 + 32768 * 8} bytes;
	 * nothing the codec emits exceeds it. A larger payloadLength is a corrupt
	 * length field.
	 */
	private static final int MAX_PAYLOAD = 1 + VoxelConstants.SECTION_CELLS * Long.BYTES;

	/** Storage-region coordinate bias/mask for the lock key (secCoord &gt;&gt; 3 fits in 23 signed bits). */
	private static final int REGION_COORD_BIAS = 1 << 22;
	private static final long REGION_COORD_MASK = (1L << 23) - 1;

	/** {@code horizon-lod/<worldId>/<dim>/voxel} — parent of the per-level directories. */
	private final Path voxelDir;

	/**
	 * Per-(level, region) monitors. Grows by one small object per storage region
	 * ever touched this session (bounded by explored area, a few thousand at
	 * most) — the same unbounded-per-region-metadata shape classic LodStorage's
	 * {@code loadedRegions} set already has.
	 */
	private final ConcurrentHashMap<Long, Object> regionLocks = new ConcurrentHashMap<>();

	VoxelRegionStorage(Path gameDir, String worldId, String dimensionId) {
		this.voxelDir = gameDir.resolve("horizon-lod")
			.resolve(sanitize(worldId))
			.resolve(sanitize(dimensionId))
			.resolve("voxel");
		try {
			Files.createDirectories(voxelDir);
		} catch (IOException e) {
			Iris.logger.error("Horizon: cannot create voxel LOD storage directory " + voxelDir, e);
		}
	}

	/**
	 * World-shared palette path ({@code horizon-lod/<worldId>/palette.nbt}),
	 * outside any dimension folder because one palette serves every dimension of
	 * the world (design sections 2 and 6). Exposed as the single source of truth
	 * for the on-disk layout so the save cycle's palette-first flush and the
	 * region writes agree on where things live. WHY here and not in
	 * VoxelPalettes: VoxelPalettes takes a {@link Path} and stays oblivious to
	 * the directory scheme; this class owns the scheme.
	 */
	static Path paletteFile(Path gameDir, String worldId) {
		return gameDir.resolve("horizon-lod").resolve(sanitize(worldId)).resolve("palette.nbt");
	}

	/** The {@code voxel/} directory this store writes under; diagnostics and the M2b cache-clear path. */
	Path directory() {
		return voxelDir;
	}

	private static String sanitize(String s) {
		return s.replaceAll("[^a-zA-Z0-9._-]", "_");
	}

	private Path regionFile(int level, int rx, int rz) {
		return voxelDir.resolve("L" + level).resolve("r." + rx + "." + rz + ".hlod");
	}

	/**
	 * Lock key packing (level, rx, rz) into one long: level in bits 46-49, rx in
	 * bits 23-45 (biased 23-bit), rz in bits 0-22 (biased 23-bit). Region
	 * coordinates are section coordinates {@code >> STORAGE_REGION_BITS}, so they
	 * span {@code +/-2^22} and fit the biased 23-bit fields with no collision
	 * across the engine's coordinate domain.
	 */
	private static long regionLockKey(int level, int rx, int rz) {
		long lx = ((long) rx + REGION_COORD_BIAS) & REGION_COORD_MASK;
		long lz = ((long) rz + REGION_COORD_BIAS) & REGION_COORD_MASK;
		return ((long) (level & 0xF) << 46) | (lx << 23) | lz;
	}

	private Object lockFor(int level, int rx, int rz) {
		return regionLocks.computeIfAbsent(regionLockKey(level, rx, rz), k -> new Object());
	}

	// --- Save ------------------------------------------------------------------

	/**
	 * Read-modify-writes the given rows into the {@code (level, rx, rz)} region
	 * file. Every row must belong to this region (its {@code sectionKey}'s level
	 * and storage-region derive to {@code (level, rx, rz)}); the caller groups
	 * dirty sections by region before calling. A row with {@code nonAirCount == 0}
	 * or a null payload removes that section from the file (omit-on-rewrite).
	 *
	 * <p>The passed {@code populationMask} / {@code payload} arrays are consumed
	 * synchronously during this call (written straight to the tmp file), so the
	 * caller may reuse its scratch buffers once the call returns.
	 *
	 * <p><b>Return contract (deviation from the task's {@code void} sketch, taken
	 * deliberately):</b> returns {@code false} if the region could not be
	 * committed. The "dirty section is never lost until saved" invariant (carried
	 * over from {@code LodWorld.evictOutside} / {@code LodStorage.saveRegion},
	 * which is {@code boolean} for exactly this reason) requires the caller to
	 * re-mark these section keys dirty on failure; a {@code void} return would
	 * silently drop them. Success with an empty result (all rows removed) returns
	 * {@code true}.
	 */
	boolean saveRegionRows(int level, int rx, int rz, Collection<DirtyRow> rows) {
		if (rows == null || rows.isEmpty()) {
			return true;
		}
		synchronized (lockFor(level, rx, rz)) {
			// RMW: start from what is already on disk (empty if absent/corrupt),
			// then overlay this cycle's changes and removals.
			Map<Long, DirtyRow> merged = new HashMap<>(loadRegionLocked(level, rx, rz).rows());
			for (DirtyRow row : rows) {
				if (isRemoval(row)) {
					merged.remove(row.sectionKey());
				} else {
					merged.put(row.sectionKey(), row);
				}
			}
			return writeRegionLocked(level, rx, rz, merged);
		}
	}

	private static boolean isRemoval(DirtyRow row) {
		return row.nonAirCount() <= 0 || row.payload() == null || row.populationMask() == null;
	}

	/** Caller holds the region monitor. Returns false only if the commit failed. */
	private boolean writeRegionLocked(int level, int rx, int rz, Map<Long, DirtyRow> rows) {
		Path file = regionFile(level, rx, rz);
		if (rows.isEmpty()) {
			// Nothing left to persist: drop the file so an emptied region leaves
			// no stale bytes to re-load next session.
			try {
				Files.deleteIfExists(file);
				return true;
			} catch (IOException e) {
				Iris.logger.error("Horizon: failed to delete emptied voxel region " + file, e);
				return false;
			}
		}

		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try {
			Files.createDirectories(file.getParent());
			try (OutputStream raw = Files.newOutputStream(tmp);
				 DataOutputStream out = new DataOutputStream(
					 new GZIPOutputStream(new BufferedOutputStream(raw)))) {
				out.writeInt(MAGIC);
				out.writeInt(VoxelConstants.STORAGE_VERSION);
				out.writeByte(level);
				out.writeInt(rx);
				out.writeInt(rz);
				out.writeInt(rows.size());
				for (DirtyRow row : rows.values()) {
					out.writeLong(row.sectionKey());
					out.writeInt(row.nonAirCount());
					long[] mask = row.populationMask();
					out.writeByte(mask.length);
					for (long word : mask) {
						out.writeLong(word);
					}
					byte[] payload = row.payload();
					out.writeInt(payload.length);
					out.write(payload);
				}
			}
		} catch (IOException | RuntimeException e) {
			Iris.logger.error("Horizon: failed to write voxel region " + file
				+ "; keys stay dirty for retry", e);
			deleteQuietly(tmp);
			return false;
		}

		try {
			moveAtomic(tmp, file);
			return true;
		} catch (IOException e) {
			Iris.logger.error("Horizon: failed to commit voxel region " + file
				+ "; keys stay dirty for retry", e);
			deleteQuietly(tmp);
			return false;
		}
	}

	// --- Load ------------------------------------------------------------------

	/**
	 * Reads a region into packed rows keyed by section key — the WARM-install
	 * path. Payloads are returned <em>unpacked</em> (still codec bytes): the
	 * caller inflates only the sections it needs via {@link VoxelSectionCodec}. A
	 * missing file yields an empty result; any corruption quarantines the file
	 * and yields empty. Never throws.
	 */
	RegionData loadRegion(int level, int rx, int rz) {
		synchronized (lockFor(level, rx, rz)) {
			return loadRegionLocked(level, rx, rz);
		}
	}

	/** Caller holds the region monitor. */
	private RegionData loadRegionLocked(int level, int rx, int rz) {
		Path file = regionFile(level, rx, rz);
		if (!Files.exists(file)) {
			return RegionData.empty(level, rx, rz);
		}

		// Parse with the stream scoped so it is CLOSED before any quarantine
		// rename — Windows cannot move a file that still has an open read handle.
		String quarantineSuffix;
		try {
			Map<Long, DirtyRow> parsed = parseRegion(file, level, rx, rz);
			return new RegionData(level, rx, rz, parsed);
		} catch (VersionMismatch v) {
			Iris.logger.warn("Horizon: voxel region " + file + " is format v" + v.version
				+ " (expected v" + VoxelConstants.STORAGE_VERSION
				+ "); discarding, caches will regenerate");
			quarantineSuffix = ".old";
		} catch (IOException | RuntimeException e) {
			Iris.logger.warn("Horizon: corrupt voxel region " + file + " (" + e
				+ "); quarantining and treating as absent");
			quarantineSuffix = ".corrupt";
		}
		quarantine(file, quarantineSuffix);
		return RegionData.empty(level, rx, rz);
	}

	/**
	 * Reads and validates one region file, returning its rows. Opens, reads and
	 * closes the file entirely within this method (try-with-resources) so a
	 * caller that then quarantines faces no open handle. Throws
	 * {@link VersionMismatch} for a wrong format version and {@link IOException}
	 * for any structural corruption (bad magic, header/region mismatch,
	 * implausible counts, out-of-range mask/payload lengths, truncation).
	 */
	private static Map<Long, DirtyRow> parseRegion(Path file, int level, int rx, int rz) throws IOException {
		try (DataInputStream in = new DataInputStream(
				new GZIPInputStream(new BufferedInputStream(Files.newInputStream(file))))) {
			int magic = in.readInt();
			if (magic != MAGIC) {
				throw new IOException("bad magic 0x" + Integer.toHexString(magic));
			}
			int version = in.readInt();
			if (version != VoxelConstants.STORAGE_VERSION) {
				throw new VersionMismatch(version);
			}
			int fileLevel = in.readUnsignedByte();
			int fileRx = in.readInt();
			int fileRz = in.readInt();
			if (fileLevel != level || fileRx != rx || fileRz != rz) {
				throw new IOException("header (L" + fileLevel + " " + fileRx + "," + fileRz
					+ ") does not match requested (L" + level + " " + rx + "," + rz + ")");
			}
			int count = in.readInt();
			if (count < 0 || count > MAX_ROWS_PER_REGION) {
				throw new IOException("implausible section count " + count);
			}
			int expectedMaskLen = VoxelSection.populationMaskLongs(level);
			Map<Long, DirtyRow> rows = new HashMap<>(Math.max(16, count * 2));
			for (int i = 0; i < count; i++) {
				long key = in.readLong();
				int nonAir = in.readInt();
				int maskLen = in.readUnsignedByte();
				if (maskLen != expectedMaskLen) {
					throw new IOException("row mask length " + maskLen + " != expected " + expectedMaskLen);
				}
				long[] mask = new long[maskLen];
				for (int m = 0; m < maskLen; m++) {
					mask[m] = in.readLong();
				}
				int payloadLen = in.readInt();
				if (payloadLen < 1 || payloadLen > MAX_PAYLOAD) {
					throw new IOException("row payload length " + payloadLen + " out of range");
				}
				byte[] payload = new byte[payloadLen];
				in.readFully(payload);
				rows.put(key, new DirtyRow(key, nonAir, mask, payload));
			}
			return rows;
		}
	}

	// --- File helpers ----------------------------------------------------------

	/** Atomic replace where the filesystem supports it, plain replace otherwise (mirrors VoxelPalettes). */
	private static void moveAtomic(Path tmp, Path dest) throws IOException {
		try {
			Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/**
	 * Renames a bad region file aside so it does not re-fail every session,
	 * picking the first free {@code file+suffix[-n]} to preserve prior forensic
	 * copies. Best-effort: a failure to rename is logged, not propagated (load
	 * still returns empty).
	 */
	private static void quarantine(Path file, String suffix) {
		try {
			Path dest = file.resolveSibling(file.getFileName() + suffix);
			for (int n = 1; Files.exists(dest) && n < 1000; n++) {
				dest = file.resolveSibling(file.getFileName() + suffix + "-" + n);
			}
			Files.move(file, dest, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException | RuntimeException e) {
			Iris.logger.warn("Horizon: could not quarantine voxel region " + file, e);
		}
	}

	private static void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
			// A stale .tmp is overwritten on the next attempt; nothing to do.
		}
	}

	// --- Data carriers ---------------------------------------------------------

	/**
	 * One section's persisted row: its key, census, population mask and codec
	 * payload. Used both as the write unit ({@link #saveRegionRows}) and as the
	 * value type in {@link RegionData} on load. On the write side a row with
	 * {@code nonAirCount == 0} (or a null {@code payload}/{@code populationMask})
	 * is a removal marker — see {@link #removal}. Rows returned from load always
	 * carry a non-null payload and mask.
	 *
	 * <p>{@code populationMask} is {@link VoxelSection#populationMaskLongs} longs;
	 * {@code payload} is a {@link VoxelSectionCodec#encodeCells} blob whose byte 0
	 * is the encoding id.
	 */
	record DirtyRow(long sectionKey, int nonAirCount, long[] populationMask, byte[] payload) {
		/** Marks a section for removal from its region file (it became all-air). */
		static DirtyRow removal(long sectionKey) {
			return new DirtyRow(sectionKey, 0, null, null);
		}
	}

	/**
	 * A region's decoded rows, keyed by section key, plus its coordinates for
	 * context. {@link #rows} is never null (empty for a missing/corrupt region).
	 */
	record RegionData(int level, int regionX, int regionZ, Map<Long, DirtyRow> rows) {
		static RegionData empty(int level, int rx, int rz) {
			return new RegionData(level, rx, rz, Map.of());
		}

		boolean isEmpty() {
			return rows.isEmpty();
		}
	}

	/** Internal signal for a wrong format version, so load can quarantine it as {@code .old} rather than {@code .corrupt}. */
	private static final class VersionMismatch extends IOException {
		final int version;

		VersionMismatch(int version) {
			super("format version " + version);
			this.version = version;
		}
	}
}
