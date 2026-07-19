package net.irisshaders.iris.horizon.voxel;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Temporary M3 diagnostics: process-wide counters tracing the mesh pipeline
 * (scheduler → mesher → snapshot) so the F3 line can show exactly where it
 * stalls. Removed once M3 renders correctly.
 */
public final class VoxelDiag {
	private VoxelDiag() {
	}

	public static final AtomicLong submitted = new AtomicLong();       // regions the scheduler queued
	public static final AtomicLong buildInvoked = new AtomicLong();    // buildRegion calls
	public static final AtomicLong sectionsCaptured = new AtomicLong();// snapshot.capture == true
	public static final AtomicLong acquireNull = new AtomicLong();     // store.acquire returned null
	public static final AtomicLong quadsEmitted = new AtomicLong();    // total quads written
	public static final java.util.concurrent.atomic.AtomicBoolean loggedProbe = new java.util.concurrent.atomic.AtomicBoolean();

	public static final java.util.concurrent.atomic.AtomicBoolean loggedPlayer = new java.util.concurrent.atomic.AtomicBoolean();

	/** One-shot: is the L0 section under the player resident, and does the mesher's region math reach it? */
	public static void checkPlayer(VoxelStore store, int bx, int by, int bz) {
		if (!loggedPlayer.compareAndSet(false, true)) {
			return;
		}
		long ofb = SectionKey.ofBlock(0, bx, by, bz);
		int span = VoxelRegionKey.regionSpanBlocks(0);
		int rx = Math.floorDiv(bx, span);
		int rz = Math.floorDiv(bz, span);
		// The section coords the mesher would probe for the player's region:
		int sxLo = rx * VoxelConstants.MESH_REGION_SECTIONS;
		int szLo = rz * VoxelConstants.MESH_REGION_SECTIONS;
		long sample = store.hot().sampleKey();
		net.irisshaders.iris.Iris.logger.info("VOXDIAG2 player block (" + bx + "," + by + "," + bz + ")"
			+ " ofBlockKey=" + SectionKey.describe(ofb)
			+ " hotHasOfBlock=" + (store.hot().get(ofb) != null)
			+ " region(" + rx + "," + rz + ") mesherSx=[" + sxLo + ".." + (sxLo + 3) + "] mesherSz=[" + szLo + ".." + (szLo + 3) + "]"
			+ " sample=" + (sample == Long.MIN_VALUE ? "none" : SectionKey.describe(sample)));
	}

	public static String line() {
		return "voxel diag: sub " + submitted.get()
			+ " build " + buildInvoked.get()
			+ " capt " + sectionsCaptured.get()
			+ " aNull " + acquireNull.get()
			+ " quads " + quadsEmitted.get();
	}
}
