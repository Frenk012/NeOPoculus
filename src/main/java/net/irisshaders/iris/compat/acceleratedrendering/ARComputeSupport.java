package net.irisshaders.iris.compat.acceleratedrendering;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

/**
 * Runtime gate for the bundled AcceleratedRendering mod.
 *
 * AcceleratedRendering does all of its work through GPU compute shaders, which require OpenGL 4.3
 * (or the ARB_compute_shader extension). NeOPoculus bundles AR and relaxes its declared
 * openGLVersion so it loads on machines whose reported GL <em>context</em> version understates the
 * real capabilities (some Windows drivers report 3.2 core yet expose 4.x via ARB extensions). That
 * relaxation is correct for those machines, but it also lets AR load on systems that genuinely lack
 * compute support — most notably macOS, where Apple caps OpenGL at 4.1 with no compute shaders at
 * all. There AR crashes while compiling its compute programs ("core_block_quad_culling").
 *
 * The declared openGLVersion can't distinguish the two cases (any range that admits a 3.2 context
 * also admits a 4.1 one), so we gate on the <em>actual</em> capabilities instead: AR stays fully
 * active wherever compute is really available and is transparently disabled (falling back to vanilla
 * rendering) wherever it isn't, preventing the crash.
 */
public final class ARComputeSupport {

	private static Boolean supported;

	private ARComputeSupport() {}

	/** True when GPU compute shaders are actually available (OpenGL 4.3 or ARB_compute_shader). */
	public static boolean isSupported() {
		Boolean cached = supported;
		if (cached != null) {
			return cached;
		}
		boolean result;
		try {
			GLCapabilities caps = GL.getCapabilities();
			result = caps.OpenGL43 || caps.GL_ARB_compute_shader;
		} catch (Throwable t) {
			result = false;
		}
		supported = result;
		if (!result) {
			System.out.println("[NeOPoculus] No GL compute support (OpenGL 4.3 / ARB_compute_shader) detected — "
				+ "AcceleratedRendering disabled to avoid a crash (e.g. macOS, capped at OpenGL 4.1).");
		}
		return result;
	}

	/** True when AcceleratedRendering must be held back because compute shaders aren't available. */
	public static boolean isUnsupported() {
		return !isSupported();
	}

	private static Object dummyProgram;
	private static boolean dummyProgramTried;

	/**
	 * A non-null placeholder {@code ComputeProgram} for the compute-unavailable path.
	 *
	 * AR's core static holders (e.g. {@code CoreBuffers.<clinit>}) eagerly fetch compute programs
	 * through {@code ComputeShaderProgramLoader.getProgram} while constructing their program
	 * dispatchers — outside both the resource-reload compile and the feature toggles. With no
	 * programs loaded that getter throws ("...too early! Program is not loaded yet!"), poisoning the
	 * static initializer and crashing during terrain load. {@code ComputeProgram(int)} merely stores
	 * a GL program handle and runs no GL itself, so a handle-0 instance lets those constructors
	 * complete (their {@code getUniform} calls resolve to location -1 harmlessly). The dispatchers are
	 * never actually invoked because every feature reports disabled ({@code MixinAcceleratedFeatureGate}).
	 */
	public static Object dummyProgram() {
		if (dummyProgramTried) {
			return dummyProgram;
		}
		dummyProgramTried = true;
		try {
			Class<?> cp = Class.forName(
				"com.github.argon4w.acceleratedrendering.core.backends.programs.ComputeProgram");
			dummyProgram = cp.getConstructor(int.class).newInstance(0);
		} catch (Throwable t) {
			dummyProgram = null;
		}
		return dummyProgram;
	}
}
