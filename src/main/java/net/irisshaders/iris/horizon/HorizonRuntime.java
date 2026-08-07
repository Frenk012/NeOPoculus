package net.irisshaders.iris.horizon;

/**
 * Tiny static bridge so the shaderpack pipeline can treat Horizon LODs like
 * Distant Horizons terrain when the DH mod is absent. When active, Iris
 * defines {@code DISTANT_HORIZONS} pipeline-wide and routes the {@code dh*}
 * far/near/render-distance/depth uniforms to Horizon's values, which flips
 * packs (Complementary, Bliss) onto their extended DH fog/lighting path
 * instead of fogging the distant LODs to the sky at the vanilla render
 * distance.
 */
public final class HorizonRuntime {
	private HorizonRuntime() {
	}

	private static volatile int mainDepthTex = 0;

	/** Published each frame by LodRenderer: the main-pass depth texture id. */
	public static void setMainDepthTex(int tex) {
		mainDepthTex = tex;
	}

	public static int mainDepthTex() {
		return mainDepthTex;
	}

	/** True when Horizon should stand in for Distant Horizons. */
	public static boolean isActive() {
		return HorizonConfig.get().isEnabled();
	}

	/**
	 * True only while the Horizon LOD pass is uploading its uniforms. Packs
	 * compute their LOD fog from {@code far}, the vanilla render distance, so
	 * with the real value the LOD — ten times further out — is fogged solid.
	 * While this is set, {@code far} reports the LOD distance instead, so the
	 * pack fogs against the world it is actually being asked to draw. Scoped to
	 * the upload rather than set globally: every other program must keep seeing
	 * the true render distance.
	 */
	private static boolean uploadingLodUniforms;

	public static void beginLodUniformUpload() {
		uploadingLodUniforms = true;
	}

	public static void endLodUniformUpload() {
		uploadingLodUniforms = false;
	}

	public static boolean isUploadingLodUniforms() {
		return uploadingLodUniforms;
	}

	/** Far clip distance (blocks) Horizon draws LODs to. */
	public static float farPlane() {
		return Math.max(64.0f, HorizonConfig.get().getLodDistanceBlocks());
	}

	public static float nearPlane() {
		return 0.05f;
	}

	/** Effective LOD render distance in chunks. */
	public static int renderDistanceChunks() {
		return Math.max(2, (int) (HorizonConfig.get().getLodDistanceBlocks() / 16.0f));
	}
}
