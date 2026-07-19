package net.irisshaders.iris.horizon.voxel;

/**
 * One 32^3 grid of packed voxel cells ({@link VoxelCell}) at a single LOD
 * level — the unit of residency, meshing and (from M2) persistence. 256 KB
 * of cells per instance, which is why the arrays come from
 * {@link SectionPool} and go back there on {@link #recycle()}.
 *
 * <p>Thread model (DESIGN.md section 4): every cell/mask/dirty access runs
 * under {@code synchronized(this)} — one uncontended monitor per section.
 * Ingest and mesh workers touch disjoint sections almost always, and the
 * worst hold is a full snapshot copy (~20 us), so a plain monitor beats any
 * clever lock-free scheme here. {@code dataVersion} and
 * {@code lastTouchedNanos} are additionally volatile so the mesher's
 * staleness check and the (M2) LRU sweep can read them without locking.
 *
 * <p>Writers do not coalesce no-op writes: a batch that stores identical
 * cells still dirties the section. Detecting equality would double the work
 * of every merge for a case (re-ingesting unchanged terrain) that the
 * dirty-region machinery upstream already deduplicates per save cycle.
 */
public final class VoxelSection {
	/** Packed {@link SectionKey} identifying (level, x, y, z); immutable identity. */
	public final long key;

	/**
	 * 32768 cells indexed {@code (y<<10)|(z<<5)|x} — x fastest, matching
	 * vanilla PalettedContainer iteration order so ingest bulk copies are
	 * stride-1. Never null while the section is resident in a VoxelWorld;
	 * {@link #recycle()} nulls it so use-after-drop fails fast with an NPE
	 * instead of silently corrupting a pooled array that another section
	 * may already own.
	 */
	private long[] cells;

	/** Non-air cells; 0 means the section is a drop candidate at save time. */
	private int nonAirCount;

	/**
	 * One bit per 16x16-block vanilla chunk-column footprint inside this
	 * section: {@code bit = (colZ << (level+1)) | colX} with
	 * {@code columnsPerAxis(level)} columns per axis. WHY: the capture
	 * frontier — the mesher must distinguish "air because empty" from "air
	 * because never captured", or it draws false cliff walls at the edge of
	 * explored terrain. M2 persists this mask verbatim in every v4 row.
	 * Sizes: L0-L2 one long (4/16/64 bits used), L3 four, L4 sixteen.
	 */
	private final long[] populationMask;

	/**
	 * Incremented once per write batch (and per mask change, which also
	 * alters mesh output). Volatile: the mesher snapshots cells, meshes off
	 * the copy, then compares versions to detect that the copy went stale.
	 */
	private volatile int dataVersion;

	/** Unsaved changes; guarded by the section monitor. M2's save cycle consumes this. */
	private boolean dirty;

	/** LRU clock for the (M2) HOT-tier eviction; updated by writes and bulk reads. */
	private volatile long lastTouchedNanos;

	/** Creates an all-air section with a pooled, zeroed cell array. */
	public VoxelSection(long key) {
		this(key, SectionPool.acquire(), 0);
	}

	/**
	 * M2 seam: adopts a decoded (pooled) cell array with a census already
	 * computed by the codec, skipping a redundant 32768-cell scan on every
	 * warm/disk inflate.
	 */
	VoxelSection(long key, long[] cells, int nonAirCount) {
		this.key = key;
		this.cells = cells;
		this.nonAirCount = nonAirCount;
		this.populationMask = new long[populationMaskLongs(SectionKey.level(key))];
		this.lastTouchedNanos = System.nanoTime();
	}

	public int level() {
		return SectionKey.level(key);
	}

	/** Cell index for local coordinates, each in [0,31]: {@code (y<<10)|(z<<5)|x}. */
	public static int cellIndex(int x, int y, int z) {
		return (y << (2 * VoxelConstants.SECTION_BITS)) | (z << VoxelConstants.SECTION_BITS) | x;
	}

	/** Chunk columns per axis covered by a section at this level: 2, 4, 8, 16, 32. */
	public static int columnsPerAxis(int level) {
		return 2 << level;
	}

	/** Population-mask length in longs: max(1, (1 << (2*level+2)) / 64) = 1,1,1,4,16. */
	public static int populationMaskLongs(int level) {
		return Math.max(1, (1 << (2 * level + 2)) / 64);
	}

	// --- Writes (worker threads) ---

	/** Writes one cell by index (see {@link #cellIndex}); the block-update path. */
	public synchronized void writeCell(int idx, long cell) {
		boolean wasAir = VoxelCell.isAir(cells[idx]);
		boolean nowAir = VoxelCell.isAir(cell);
		cells[idx] = cell;
		if (wasAir != nowAir) {
			nonAirCount += nowAir ? -1 : 1;
		}
		dirty = true;
		dataVersion++;
		lastTouchedNanos = System.nanoTime();
	}

	/**
	 * Read-modify-write of one cell's block-state id under a single monitor
	 * hold, preserving the cell's existing biome and light bits — the
	 * block-update path. Returns whether the packed cell actually changed (a
	 * palette-collapsed no-op, e.g. a rotation-only visual update, returns
	 * false so the caller skips the remip). The read and write must share one
	 * lock: a split {@link #cellAt}-then-{@link #writeCell} would let a
	 * concurrent full-cell writer (ingest, or the light-refresh path) land in
	 * the gap and be clobbered by the stale-based store.
	 */
	public synchronized boolean setStateId(int idx, int stateId) {
		long old = cells[idx];
		long cell = (old & ~VoxelConstants.STATE_MASK) | ((long) stateId & VoxelConstants.STATE_MASK);
		if (cell == old) {
			return false;
		}
		boolean wasAir = VoxelCell.isAir(old);
		boolean nowAir = VoxelCell.isAir(cell);
		cells[idx] = cell;
		if (wasAir != nowAir) {
			nonAirCount += nowAir ? -1 : 1;
		}
		dirty = true;
		dataVersion++;
		lastTouchedNanos = System.nanoTime();
		return true;
	}

	/**
	 * Read-modify-write of one cell's light nibbles under a single monitor
	 * hold, preserving state and biome — the debounced light-refresh path.
	 * Returns whether the cell changed. Single-lock RMW for the same reason as
	 * {@link #setStateId}: it must never clobber a concurrent block-state write
	 * back to its old state. Light never touches the state field, so
	 * {@code nonAirCount} is unaffected.
	 */
	public synchronized boolean setLight(int idx, int blockLight, int skyLight) {
		long old = cells[idx];
		long cell = VoxelCell.withLight(old, blockLight, skyLight);
		if (cell == old) {
			return false;
		}
		cells[idx] = cell;
		dirty = true;
		dataVersion++;
		lastTouchedNanos = System.nanoTime();
		return true;
	}

	/**
	 * Copies an nx * ny * nz box of cells from {@code src} into this section
	 * at (baseX, baseY, baseZ) — the ingest merge path, one call per vanilla
	 * section per level. The source cell for offset (dx, dy, dz) is
	 * {@code src[srcBase + dy*srcStrideY + dz*srcStrideZ + dx]}: the x
	 * stride is fixed at 1 because both this section and the ChunkPyramid
	 * scratch arrays are x-fastest, keeping the inner loop a linear walk.
	 *
	 * <p>Contract (unchecked — hot path): base &ge; 0 and base + n &le; 32
	 * on every axis; out-of-contract indices surface as
	 * ArrayIndexOutOfBounds, they never write a wrong cell silently.
	 */
	public synchronized void writeBatch(int baseX, int baseY, int baseZ, int nx, int ny, int nz,
			long[] src, int srcBase, int srcStrideZ, int srcStrideY) {
		for (int dy = 0; dy < ny; dy++) {
			int dstY = (baseY + dy) << (2 * VoxelConstants.SECTION_BITS);
			int srcY = srcBase + dy * srcStrideY;
			for (int dz = 0; dz < nz; dz++) {
				int dst = dstY | ((baseZ + dz) << VoxelConstants.SECTION_BITS) | baseX;
				int srcIdx = srcY + dz * srcStrideZ;
				for (int dx = 0; dx < nx; dx++, dst++, srcIdx++) {
					long cell = src[srcIdx];
					boolean wasAir = VoxelCell.isAir(cells[dst]);
					boolean nowAir = VoxelCell.isAir(cell);
					cells[dst] = cell;
					if (wasAir != nowAir) {
						nonAirCount += nowAir ? -1 : 1;
					}
				}
			}
		}
		dirty = true;
		dataVersion++;
		lastTouchedNanos = System.nanoTime();
	}

	// --- Population mask ---

	/**
	 * Marks one chunk column (local column coords, [0, columnsPerAxis))
	 * as ingested. Bumps the version and dirties the section even though no
	 * cell changed: a flipped frontier bit changes mesh output (faces
	 * bordering the column stop being skipped) and must be persisted.
	 */
	public synchronized void markColumnPopulated(int colX, int colZ) {
		int bit = (colZ << (level() + 1)) | colX;
		populationMask[bit >>> 6] |= 1L << (bit & 63);
		dirty = true;
		dataVersion++;
	}

	public synchronized boolean isColumnPopulated(int colX, int colZ) {
		int bit = (colZ << (level() + 1)) | colX;
		return (populationMask[bit >>> 6] & (1L << (bit & 63))) != 0;
	}

	/** Copies the raw mask (length {@link #populationMaskLongs}); M2 codec + mesher seam. */
	public synchronized void copyPopulationMaskInto(long[] dst) {
		System.arraycopy(populationMask, 0, dst, 0, populationMask.length);
	}

	public int populationMaskLength() {
		return populationMask.length;
	}

	/** How many chunk columns have been ingested; debug/F3 counters. */
	public synchronized int populatedColumnCount() {
		int count = 0;
		for (long word : populationMask) {
			count += Long.bitCount(word);
		}
		return count;
	}

	// --- Reads ---

	public synchronized long cellAt(int x, int y, int z) {
		return cells[cellIndex(x, y, z)];
	}

	/**
	 * Snapshots all 32768 cells into {@code dst} (a mesher/codec-owned
	 * buffer) and returns the dataVersion the copy represents. Returning
	 * the version from inside the monitor is the only race-free way to
	 * correlate "these bytes" with "that version" — reading the getter
	 * afterwards could observe a concurrent write's bump and discard a
	 * perfectly consistent copy (or worse, trust a torn one).
	 */
	public synchronized int copyCellsInto(long[] dst) {
		System.arraycopy(cells, 0, dst, 0, VoxelConstants.SECTION_CELLS);
		lastTouchedNanos = System.nanoTime();
		return dataVersion;
	}

	/**
	 * Copies one 32x32 boundary plane of this section into {@code dst}
	 * (length {@link VoxelConstants#SECTION_PLANE_CELLS}) and returns the
	 * dataVersion of the copy. DESIGN.md contract addition (c): the mesher
	 * pulls a neighbor's facing plane (8 KB) instead of snapshotting the
	 * whole neighbor (256 KB) for its one-cell sampling border.
	 *
	 * <p>{@code face} names the face of <em>this</em> section in DH normal
	 * order ({@link VoxelConstants#FACE_NEG_Y}...): a mesher working on the
	 * section at +X of this one asks for FACE_POS_X, the x = 31 plane.
	 * Destination layouts (fastest axis last, mirroring the cell order):
	 * Y faces {@code dst[(z<<5)|x]}, Z faces {@code dst[(y<<5)|x]},
	 * X faces {@code dst[(y<<5)|z]}. Y planes are contiguous in the cell
	 * array (one arraycopy), Z planes copy 32 x-runs, X planes are fully
	 * strided — the layout choice keeps the common cheap cases cheap.
	 */
	public synchronized int copyPlaneInto(int face, long[] dst) {
		int last = VoxelConstants.SECTION_MASK;
		switch (face) {
			case VoxelConstants.FACE_NEG_Y ->
				System.arraycopy(cells, 0, dst, 0, VoxelConstants.SECTION_PLANE_CELLS);
			case VoxelConstants.FACE_POS_Y ->
				System.arraycopy(cells, last << (2 * VoxelConstants.SECTION_BITS), dst, 0, VoxelConstants.SECTION_PLANE_CELLS);
			case VoxelConstants.FACE_NEG_Z, VoxelConstants.FACE_POS_Z -> {
				int z = face == VoxelConstants.FACE_NEG_Z ? 0 : last;
				for (int y = 0; y < VoxelConstants.SECTION_SIZE; y++) {
					System.arraycopy(cells, cellIndex(0, y, z), dst, y << VoxelConstants.SECTION_BITS, VoxelConstants.SECTION_SIZE);
				}
			}
			case VoxelConstants.FACE_NEG_X, VoxelConstants.FACE_POS_X -> {
				int x = face == VoxelConstants.FACE_NEG_X ? 0 : last;
				for (int y = 0; y < VoxelConstants.SECTION_SIZE; y++) {
					for (int z = 0; z < VoxelConstants.SECTION_SIZE; z++) {
						dst[(y << VoxelConstants.SECTION_BITS) | z] = cells[cellIndex(x, y, z)];
					}
				}
			}
			default -> throw new IllegalArgumentException("bad face " + face);
		}
		lastTouchedNanos = System.nanoTime();
		return dataVersion;
	}

	// --- State / lifecycle ---

	public synchronized int nonAirCount() {
		return nonAirCount;
	}

	/** True when every cell is air; such a section is dropped, never stored. */
	public synchronized boolean isEmpty() {
		return nonAirCount == 0;
	}

	/** Volatile read; pair with {@link #copyCellsInto}'s return value for staleness checks. */
	public int dataVersion() {
		return dataVersion;
	}

	public synchronized boolean isDirty() {
		return dirty;
	}

	public synchronized void markDirty() {
		dirty = true;
	}

	/**
	 * Clears the dirty flag only if no write landed since the caller's
	 * snapshot ({@code savedVersion} as returned by {@link #copyCellsInto}).
	 * WHY conditional: the M2 save path snapshots, encodes off-monitor,
	 * then must not mark clean data that changed underneath it — the same
	 * "dirty data is unevictable until saved" invariant LodWorld enforces.
	 * Returns whether the flag was cleared.
	 */
	public synchronized boolean clearDirtyIfUnchanged(int savedVersion) {
		if (dataVersion == savedVersion) {
			dirty = false;
			return true;
		}
		return false;
	}

	public long lastTouchedNanos() {
		return lastTouchedNanos;
	}

	/** LRU touch without a write; VoxelStore (M2) calls this on release. */
	public void touch() {
		lastTouchedNanos = System.nanoTime();
	}

	/**
	 * Returns the cell array to {@link SectionPool} and nulls it. Only
	 * legal after the section has been removed from its residency map (no
	 * new readers can find it); the monitor makes the hand-off safe against
	 * a reader that already held the reference. Any later access throws NPE
	 * by design — better a crash-with-stack than two sections silently
	 * sharing one pooled array.
	 */
	public synchronized void recycle() {
		long[] recycled = cells;
		cells = null;
		if (recycled != null) {
			SectionPool.release(recycled);
		}
	}
}
