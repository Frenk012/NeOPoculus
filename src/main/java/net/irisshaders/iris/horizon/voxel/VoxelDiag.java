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

	public static String line() {
		return "voxel diag: sub " + submitted.get()
			+ " build " + buildInvoked.get()
			+ " capt " + sectionsCaptured.get()
			+ " aNull " + acquireNull.get()
			+ " quads " + quadsEmitted.get();
	}
}
