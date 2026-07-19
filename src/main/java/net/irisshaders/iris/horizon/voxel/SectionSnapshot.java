package net.irisshaders.iris.horizon.voxel;

/**
 * A reusable 34³ cell view of one section plus its six face-neighbor boundary
 * planes (design-mesh-render.md section 1.1), the mesher's input. The core
 * 32³ is copied from the resident section; each neighbor's adjacent boundary
 * plane fills the ±1 slab so cross-section faces are culled correctly. A
 * missing neighbor stays AIR, which emits a boundary wall — the intended
 * frontier / cross-level seam behavior.
 *
 * <p>Reads go through {@link VoxelStore#acquire} (HOT/WARM only, never disk),
 * so a section evicted to disk simply reads as absent. All section copies are
 * guarded: a concurrent save cycle can recycle a section's array mid-copy, so
 * an NPE degrades that section to AIR rather than aborting the region.
 */
public final class SectionSnapshot {
	private static final int N = VoxelConstants.SECTION_SIZE; // 32
	private static final int DIM = N + 2;                     // 34
	private static final int PLANE = DIM * DIM;               // 1156

	private final long[] cells = new long[DIM * DIM * DIM];
	private final long[] plane = new long[VoxelConstants.SECTION_PLANE_CELLS];
	private long coreNonAir;

	private static int idx(int x, int y, int z) {
		return (x + 1) + (z + 1) * DIM + (y + 1) * PLANE;
	}

	/** @return false when the core section is absent or all-air (caller skips it). */
	public boolean capture(VoxelStore store, int level, int sx, int sy, int sz) {
		long coreKey = SectionKey.pack(level, sx, sy, sz);
		VoxelSection core = store.acquire(coreKey);
		if (core == null) {
			VoxelDiag.acquireNull.incrementAndGet();
			return false;
		}
		if (core.nonAirCount() == 0) {
			return false;
		}
		VoxelDiag.sectionsCaptured.incrementAndGet();
		java.util.Arrays.fill(cells, VoxelConstants.AIR_CELL);
		coreNonAir = core.nonAirCount();
		if (!fillCore(core)) {
			return false;
		}
		for (int face = 0; face < VoxelConstants.FACE_COUNT; face++) {
			long nKey = SectionKey.faceNeighbor(coreKey, face);
			VoxelSection neighbor = store.acquire(nKey);
			if (neighbor != null) {
				fillNeighbor(neighbor, face);
			}
		}
		return true;
	}

	/** Copies the 32³ core, row by row (each x-run is contiguous in both layouts). */
	private boolean fillCore(VoxelSection core) {
		try {
			for (int y = 0; y < N; y++) {
				for (int z = 0; z < N; z++) {
					int base = idx(0, y, z);
					for (int x = 0; x < N; x++) {
						cells[base + x] = core.cellAt(x, y, z);
					}
				}
			}
			return true;
		} catch (NullPointerException recycled) {
			return false;
		}
	}

	/**
	 * Scatters the neighbor's boundary plane (the one facing this section,
	 * i.e. its {@code face^1} plane) into this section's out-of-range slab.
	 * The plane's in-plane layout depends on the axis (see
	 * {@link VoxelSection#copyPlaneInto}).
	 */
	private void fillNeighbor(VoxelSection neighbor, int face) {
		try {
			neighbor.copyPlaneInto(face ^ 1, plane);
		} catch (NullPointerException recycled) {
			return;
		}
		switch (face) {
			case VoxelConstants.FACE_NEG_Y -> { // slab y = -1, plane[z*32+x] = cell(x, *, z)
				for (int z = 0; z < N; z++) {
					for (int x = 0; x < N; x++) {
						cells[idx(x, -1, z)] = plane[z * N + x];
					}
				}
			}
			case VoxelConstants.FACE_POS_Y -> {
				for (int z = 0; z < N; z++) {
					for (int x = 0; x < N; x++) {
						cells[idx(x, N, z)] = plane[z * N + x];
					}
				}
			}
			case VoxelConstants.FACE_NEG_Z -> { // slab z = -1, plane[y*32+x]
				for (int y = 0; y < N; y++) {
					for (int x = 0; x < N; x++) {
						cells[idx(x, y, -1)] = plane[y * N + x];
					}
				}
			}
			case VoxelConstants.FACE_POS_Z -> {
				for (int y = 0; y < N; y++) {
					for (int x = 0; x < N; x++) {
						cells[idx(x, y, N)] = plane[y * N + x];
					}
				}
			}
			case VoxelConstants.FACE_NEG_X -> { // slab x = -1, plane[y*32+z]
				for (int y = 0; y < N; y++) {
					for (int z = 0; z < N; z++) {
						cells[idx(-1, y, z)] = plane[y * N + z];
					}
				}
			}
			case VoxelConstants.FACE_POS_X -> {
				for (int y = 0; y < N; y++) {
					for (int z = 0; z < N; z++) {
						cells[idx(N, y, z)] = plane[y * N + z];
					}
				}
			}
			default -> {
			}
		}
	}

	/** Cell at core-local coords x,y,z ∈ [-1, 32]; out of that range is AIR. */
	public long cell(int x, int y, int z) {
		if (x < -1 || x > N || y < -1 || y > N || z < -1 || z > N) {
			return VoxelConstants.AIR_CELL;
		}
		return cells[idx(x, y, z)];
	}

	public long coreNonAir() {
		return coreNonAir;
	}
}
