package net.irisshaders.iris.horizon.voxel;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.horizon.HorizonConfig;
import net.irisshaders.iris.mixin.GlStateManagerAccessor;
import net.irisshaders.iris.mixin.statelisteners.BooleanStateAccessor;
import net.minecraft.client.Minecraft;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL33C;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Render-thread owner of the voxel LOD meshes: the upload queue fed by mesher
 * workers, the per-region GPU meshes, and the flat-color draw pass
 * (design-mesh-render.md section 5). M3 draws two-sided with polygon offset so
 * loaded chunks always win the depth test at the seam; the photo-atlas path
 * (M4) and the shaderpack path (M6) extend this class.
 */
public final class VoxelRenderer {
	private final Map<Long, VoxelRegionMesh> meshes = new ConcurrentHashMap<>();
	private final ConcurrentLinkedQueue<VoxelMesher.MeshData> uploadQueue = new ConcurrentLinkedQueue<>();
	private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
	private volatile int epoch;
	private final Object epochLock = new Object();
	/**
	 * Regions whose current mesh used a flat-color fallback (unbaked state),
	 * mapped to the bakery epoch at which they were meshed. The scheduler
	 * re-meshes a region once the epoch advances past its recorded value, so the
	 * self-heal is level-triggered per region rather than a single global epoch
	 * edge — which could be consumed before a still-building region joined the
	 * set, leaving it flat forever in a quiescent scene.
	 */
	private final Map<Long, Integer> fallbackEpoch = new ConcurrentHashMap<>();

	/** Reused across frames: the visible regions that have translucent geometry to draw last. */
	private final java.util.List<VoxelRegionMesh> translucentPending = new java.util.ArrayList<>();

	private final NoPackVoxelShader shader = new NoPackVoxelShader();

	// Shaderpack path (M6). Mirrors the classic engine's renderIris state
	// machine: cache the program and framebuffer per (pipeline, depth texture),
	// and remember a failure so a broken pack degrades to the no-pack pass once
	// instead of throwing every frame. Reset when the pipeline is destroyed.
	private net.irisshaders.iris.horizon.HorizonIrisProgram irisTerrain;
	private net.irisshaders.iris.horizon.HorizonIrisProgram irisWater;
	private Object irisPipeline;
	private int irisDepthTex;
	private boolean irisFailed;
	/** Logged once per pack, so the built-in fallback is never silent. */
	private boolean noDhTerrainReported;
	private net.irisshaders.iris.gl.framebuffer.GlFramebuffer irisFramebuffer;
	private final Matrix4f mvp = new Matrix4f();
	private final FrustumIntersection frustum = new FrustumIntersection();
	private final float[] mvpArray = new float[16];

	// Per-chunk coverage mask: 255 where a real chunk is loaded, 0 elsewhere.
	// The fragment shader discards LOD where covered (dithered), so LOD never
	// draws over loaded terrain even when the loaded radius exceeds the client
	// render distance. Ported from the classic LodRenderer.
	private static final int MASK_SIZE = 160;
	private int maskTexture;
	private final java.nio.ByteBuffer maskBuffer = org.lwjgl.system.MemoryUtil.memAlloc(MASK_SIZE * MASK_SIZE);
	private final byte[] maskData = new byte[MASK_SIZE * MASK_SIZE];
	private int maskOriginX, maskOriginZ;
	private int maskCenterX = Integer.MIN_VALUE, maskCenterZ = Integer.MIN_VALUE;
	private int maskAge;

	// Draw-buffer guard, mirroring the classic engine's legacy pass. A shader
	// pack binds a G-buffer with several colour attachments; this program
	// declares one output, so without restricting the draw the LOD fragments
	// leave the pack's normal/material/specular attachments untouched while
	// still writing depth. The deferred pass then lights those pixels — which
	// depth says are real geometry — from stale attachment data, and they blow
	// out to white. Cached per FBO because packs configure this once per
	// framebuffer, so re-querying every frame would cost eight round-trips.
	private final int[] fboDrawBuffers = new int[8];
	private int cachedDrawBuffersFbo = -1;
	private boolean cachedDrawBuffersMulti;

	// Diagnostics surfaced on the F3 line.
	private volatile int drawnLastFrame;
	private volatile long totalUploaded;

	public int drawnLastFrame() {
		return drawnLastFrame;
	}

	public long totalUploaded() {
		return totalUploaded;
	}

	public int currentEpoch() {
		return epoch;
	}

	public boolean markScheduled(long regionKey) {
		return inFlight.add(regionKey);
	}

	public boolean hasMesh(long regionKey) {
		return meshes.containsKey(regionKey);
	}

	/** Fallback regions meshed before {@code currentEpoch} (newer bakes may now texture them). Scheduler re-meshes these. */
	public java.util.List<Long> fallbackRegionsStaleAt(int currentEpoch) {
		java.util.List<Long> stale = new java.util.ArrayList<>();
		for (Map.Entry<Long, Integer> e : fallbackEpoch.entrySet()) {
			if (e.getValue() < currentEpoch) {
				stale.add(e.getKey());
			}
		}
		return stale;
	}

	public int meshCount() {
		return meshes.size();
	}

	/** Mesher worker handoff (mirrors LodRenderer.submit): drops stale-epoch results. */
	public void submit(long regionKey, VoxelMesher.MeshData data, int jobEpoch) {
		synchronized (epochLock) {
			if (jobEpoch != epoch) {
				if (data != null) {
					data.free();
				}
				inFlight.remove(regionKey);
				return;
			}
			if (data != null) {
				uploadQueue.add(data);
			} else {
				inFlight.remove(regionKey);
			}
		}
	}

	/** Render thread: turn pending vertex data into GPU meshes, bounded per frame. */
	public void processUploads(int budget) {
		VoxelMesher.MeshData data;
		while (budget-- > 0 && (data = uploadQueue.poll()) != null) {
			long key = data.regionKey();
			VoxelRegionMesh mesh = new VoxelRegionMesh(data);
			VoxelRegionMesh old = meshes.put(key, mesh);
			if (old != null) {
				old.delete();
			}
			if (mesh.usedFallback) {
				// Stamp with the epoch at BUILD START, not now: a bake that landed
				// while this region was building must still count as newer, or the
				// region is stranded flat by an edge consumed before its upload.
				fallbackEpoch.put(key, mesh.bakeEpoch);
			} else {
				fallbackEpoch.remove(key);
			}
			if (totalUploaded == 0) {
				Iris.logger.info("Horizon: voxel render path live (first mesh L" + data.level()
					+ ", " + data.quads() + " quads)");
			}
			totalUploaded++;
			inFlight.remove(key);
			data.free();
		}
	}

	/**
	 * Second pass: the translucent tails of every visible region, drawn after all
	 * opaque geometry and sorted back to front so overlapping water surfaces
	 * blend in the right order. Depth writing stays ON — distant water is
	 * essentially one surface, and writing depth stops the far side of a lake
	 * from blending through the near side. The sort runs over at most a few
	 * hundred regions on a reused array, so it costs nothing measurable.
	 */
	private void drawTranslucentPass(double camX, double camY, double camZ, float relY) {
		translucentPending.sort((a, b) -> Double.compare(
			regionDistSq(b, camX, camY, camZ), regionDistSq(a, camX, camY, camZ)));

		GL33C.glEnable(GL33C.GL_BLEND);
		GL33C.glBlendFunc(GL33C.GL_SRC_ALPHA, GL33C.GL_ONE_MINUS_SRC_ALPHA);
		for (VoxelRegionMesh mesh : translucentPending) {
			int level = mesh.level;
			int span = VoxelRegionKey.regionSpanBlocks(level);
			float ox = (float) ((double) VoxelRegionKey.rx(mesh.regionKey) * span - camX);
			float oz = (float) ((double) VoxelRegionKey.rz(mesh.regionKey) * span - camZ);
			shader.setOffset(ox, relY - VoxelConstants.Y_BIAS, oz);
			shader.setCellSize(1 << level);
			mesh.drawTranslucent();
		}
		GL33C.glDisable(GL33C.GL_BLEND);
		translucentPending.clear();
	}

	private static double regionDistSq(VoxelRegionMesh mesh, double camX, double camY, double camZ) {
		int span = VoxelRegionKey.regionSpanBlocks(mesh.level);
		double dx = (double) VoxelRegionKey.rx(mesh.regionKey) * span + span * 0.5 - camX;
		double dz = (double) VoxelRegionKey.rz(mesh.regionKey) * span + span * 0.5 - camZ;
		double dy = (mesh.minY + mesh.maxY) * 0.5 - camY;
		return dx * dx + dy * dy + dz * dz;
	}

	/** Drops meshes beyond their level's keep radius. Any thread (map is concurrent). */
	public void evict(double camX, double camZ) {
		for (Iterator<Map.Entry<Long, VoxelRegionMesh>> it = meshes.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<Long, VoxelRegionMesh> e = it.next();
			long key = e.getKey();
			int level = VoxelRegionKey.level(key);
			int span = VoxelRegionKey.regionSpanBlocks(level);
			double cx = (VoxelRegionKey.rx(key) + 0.5) * span - camX;
			double cz = (VoxelRegionKey.rz(key) + 0.5) * span - camZ;
			double keep = VoxelLodSelector.radiusRegions(level) * (double) span + span;
			if (cx * cx + cz * cz > keep * keep) {
				VoxelRegionMesh mesh = e.getValue();
				it.remove();
				fallbackEpoch.remove(key);
				RenderSystem.recordRenderCall(mesh::delete);
			}
		}
	}

	/**
	 * Shaderpack path: draw every region through the pack's own dh_terrain
	 * program (and dh_water for the translucent tail) into the pack's gbuffers,
	 * so it receives real normals and material ids instead of shading our
	 * single-output pixels from stale attachments.
	 *
	 * @return true when this pass handled the frame; false falls back to the
	 *         no-pack program, which is correct for a pack shipping no dh
	 *         programs at all.
	 */
	private boolean renderIris(Matrix4f modelView, Matrix4f projection,
							   double camX, double camY, double camZ) {
		if (irisFailed || meshes.isEmpty()) {
			return false;
		}
		if (!(net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable()
			instanceof net.irisshaders.iris.pipeline.IrisRenderingPipeline pipeline)) {
			return false;
		}
		try {
			// Publish the depth texture before the dh_terrain test, not after:
			// the dhDepthTex samplers are attached to every program the pack
			// compiles, so leaving it at 0 for a pack that ships no dh_terrain
			// hands the whole pack a texture name of zero.
			int depthTex = pipeline.getHorizonDepthTexture();
			net.irisshaders.iris.horizon.HorizonRuntime.setMainDepthTex(depthTex);
			var terrain = pipeline.getDHTerrainShader();
			if (terrain.isEmpty()) {
				// Say so once. This return used to be silent, which made the pack
				// look like it had rendered through the shader path when it had
				// actually dropped to the built-in program.
				if (!noDhTerrainReported) {
					noDhTerrainReported = true;
					Iris.logger.info("Horizon: this shaderpack ships no dh_terrain program; "
						+ "the voxel LOD will draw with the built-in program instead");
				}
				return false; // nothing sensible to shade LOD with
			}
			if (irisPipeline != pipeline || irisDepthTex != depthTex) {
				if (irisPipeline != pipeline) {
					onPipelineDestroyed();
					irisFailed = false;
				}
				if (irisFramebuffer != null) {
					irisFramebuffer.destroy();
				}
				if (irisTerrain == null) {
					// 1/16: our positions are in sixteenths of a block.
					irisTerrain = net.irisshaders.iris.horizon.HorizonIrisProgram.createProgram(
						"horizon_voxel_terrain", terrain.get(), pipeline.getCustomUniforms(), pipeline, 1.0f / 16.0f);
				}
				if (irisWater == null) {
					// Water gets the pack's dh_water when it has one; otherwise the
					// terrain program, which still shades far better than ours.
					var water = pipeline.getDHWaterShader();
					irisWater = net.irisshaders.iris.horizon.HorizonIrisProgram.createProgram(
						"horizon_voxel_water", water.orElse(terrain.get()),
						pipeline.getCustomUniforms(), pipeline, 1.0f / 16.0f);
				}
				irisFramebuffer = pipeline.createHorizonFramebuffer(terrain.get());
				irisPipeline = pipeline;
				irisDepthTex = depthTex;
			}

			mvp.set(projection).mul(modelView);
			frustum.set(mvp);

			int prevProgram = GL33C.glGetInteger(GL33C.GL_CURRENT_PROGRAM);
			int prevVao = GL33C.glGetInteger(GL33C.GL_VERTEX_ARRAY_BINDING);
			int prevFramebuffer = GL33C.glGetInteger(GL33C.GL_DRAW_FRAMEBUFFER_BINDING);

			// Snapshot from GlStateManager's cache, not from the driver.
			//
			// The cache is what every later call is gated on. Changing depth,
			// blend or cull with a raw glEnable/glDisable leaves vanilla and Iris
			// believing the old value, so their next enable/disable is skipped as
			// redundant and the driver keeps OUR value for the rest of the frame
			// — in every program the pack runs afterwards, not only in this pass.
			// That is how a distant-terrain pass ends up blacking out terrain that
			// has nothing to do with LOD, and why the real Distant Horizons never
			// does: Iris drives that pass and owns the state around it.
			GlStateManager.DepthState depthState = GlStateManagerAccessor.getDEPTH();
			boolean prevDepthTest = ((BooleanStateAccessor) depthState.mode).isEnabled();
			int prevDepthFunc = depthState.func;
			boolean prevDepthMask = depthState.mask;
			// Blend is not snapshotted: BlendModeOverride.restore() puts back what
			// the pack had, which is the only value that matters here.
			// GlStateManager.CullState is package-private, so cull is the one flag
			// read from the driver. That is sound now that every write below goes
			// through GlStateManager: cache and driver no longer diverge.
			boolean prevCull = GL33C.glIsEnabled(GL33C.GL_CULL_FACE);

			net.irisshaders.iris.horizon.HorizonIrisProgram lastBound = null;
			try {
				irisFramebuffer.bind();
				GlStateManager._enableDepthTest();
				GlStateManager._depthFunc(GL33C.GL_LEQUAL);
				GlStateManager._depthMask(true);
				// Not GlStateManager._disableBlend(): while a blend override is
				// locked — which it is, for the pack's gbuffer pass — Iris's own
				// mixin cancels that call and merely records the intent, so the
				// driver keeps the pack's blend and opaque LOD blends away to
				// nothing. overrideBlend is the designed way through: it drops the
				// lock, applies for real, and re-arms. A pack that declares its own
				// blend for dh_terrain still wins, because bind() applies it after.
				net.irisshaders.iris.gl.blending.BlendModeOverride.OFF.apply();
				GlStateManager._disableCull(); // winding is not outward-consistent yet
				GlStateManager._enablePolygonOffset();
				// Same trick as the no-pack pass: push LOD fragments back so loaded
				// chunks always win the depth test at the seam. The pack program has
				// no coverage-mask discard, so this is what hides the overlap.
				GlStateManager._polygonOffset(3.0f, 3.0f);

				double maxDist = HorizonConfig.get().getLodDistanceBlocks() + 192.0;
				double maxDistSq = maxDist * maxDist;
				float relY = (float) -camY;
				int drawn = 0;

				lastBound = irisTerrain;
				irisTerrain.bind();
				irisTerrain.fillUniformData(projection, modelView);
				for (VoxelRegionMesh mesh : meshes.values()) {
					if (!visibleNow(mesh, camX, camZ, relY, maxDistSq)) {
						continue;
					}
					int span = VoxelRegionKey.regionSpanBlocks(mesh.level);
					float ox = (float) ((double) VoxelRegionKey.rx(mesh.regionKey) * span - camX);
					float oz = (float) ((double) VoxelRegionKey.rz(mesh.regionKey) * span - camZ);
					irisTerrain.setModelPos(ox, relY - VoxelConstants.Y_BIAS, oz);
					mesh.drawOpaque();
					drawn++;
					if (mesh.hasTranslucent()) {
						translucentPending.add(mesh);
					}
				}

				if (!translucentPending.isEmpty()) {
					translucentPending.sort((a, b) -> Double.compare(
						regionDistSq(b, camX, camY, camZ), regionDistSq(a, camX, camY, camZ)));
					lastBound = irisWater;
					irisWater.bind();
					irisWater.fillUniformData(projection, modelView);
					for (VoxelRegionMesh mesh : translucentPending) {
						int span = VoxelRegionKey.regionSpanBlocks(mesh.level);
						float ox = (float) ((double) VoxelRegionKey.rx(mesh.regionKey) * span - camX);
						float oz = (float) ((double) VoxelRegionKey.rz(mesh.regionKey) * span - camZ);
						irisWater.setModelPos(ox, relY - VoxelConstants.Y_BIAS, oz);
						mesh.drawTranslucent();
					}
					translucentPending.clear();
				}
				drawnLastFrame = drawn;
			} finally {
				// unbind() is the only thing that releases the program's blend
				// override: bind() arms BlendModeStorage, and while it stays armed
				// every blend change the pack makes afterwards is swallowed. This
				// runs even when a draw threw, because a half-finished pass is
				// exactly when leaving the state dirty does the most damage.
				if (lastBound != null) {
					lastBound.unbind();
				}
				GlStateManager._polygonOffset(0.0f, 0.0f);
				GlStateManager._disablePolygonOffset();
				if (prevDepthTest) {
					GlStateManager._enableDepthTest();
				} else {
					GlStateManager._disableDepthTest();
				}
				GlStateManager._depthFunc(prevDepthFunc);
				GlStateManager._depthMask(prevDepthMask);
				// Hands blend back to whatever the pack had before this pass, and
				// drops the lock so its next toggle is not swallowed. Idempotent,
				// so the unbind() above having already done it costs nothing.
				net.irisshaders.iris.gl.blending.BlendModeOverride.restore();
				if (prevCull) {
					GlStateManager._enableCull();
				} else {
					GlStateManager._disableCull();
				}
				GL33C.glBindFramebuffer(GL33C.GL_DRAW_FRAMEBUFFER, prevFramebuffer);
				GL33C.glBindVertexArray(prevVao);
				GL33C.glUseProgram(prevProgram);
			}
			return true;
		} catch (Throwable t) {
			// One failure is enough: fall back to the no-pack pass for the rest
			// of this pipeline rather than throwing every frame.
			irisFailed = true;
			net.irisshaders.iris.Iris.logger.error(
				"Horizon: voxel LOD shaderpack path failed; falling back to the built-in program", t);
			return false;
		}
	}

	/** Frustum + distance test shared by both passes. */
	private boolean visibleNow(VoxelRegionMesh mesh, double camX, double camZ, float relY, double maxDistSq) {
		int span = VoxelRegionKey.regionSpanBlocks(mesh.level);
		double originX = (double) VoxelRegionKey.rx(mesh.regionKey) * span;
		double originZ = (double) VoxelRegionKey.rz(mesh.regionKey) * span;
		double dcx = originX + span * 0.5 - camX;
		double dcz = originZ + span * 0.5 - camZ;
		if (dcx * dcx + dcz * dcz > maxDistSq) {
			return false;
		}
		float ox = (float) (originX - camX);
		float oz = (float) (originZ - camZ);
		return frustum.testAab(ox, mesh.minY + relY, oz, ox + span, mesh.maxY + relY, oz + span);
	}

	/** Pipeline teardown: drop the pack-specific GL objects and re-arm the shader path. */
	public void onPipelineDestroyed(Object pipeline) {
		onPipelineDestroyed();
	}

	/** Pipeline teardown: drop the pack-specific GL objects and re-arm the shader path. */
	public void onPipelineDestroyed() {
		if (irisTerrain != null) {
			irisTerrain.free();
			irisTerrain = null;
		}
		if (irisWater != null) {
			irisWater.free();
			irisWater = null;
		}
		if (irisFramebuffer != null) {
			irisFramebuffer.destroy();
			irisFramebuffer = null;
		}
		irisPipeline = null;
		irisDepthTex = 0;
		irisFailed = false;
		noDhTerrainReported = false; // the next pack gets its own verdict
	}

	private long probeLast;
	private String probeLastWhat = "";

	/**
	 * Says once every few seconds which path the frame took and how much it
	 * actually drew. "Meshes exist but nothing is on screen" and "no meshes were
	 * ever built" look identical from the player's chair, and guessing between
	 * them has cost more than one wrong fix.
	 */
	private void probe(String what, int held) {
		long now = System.currentTimeMillis();
		if (now - probeLast < 5000 && what.equals(probeLastWhat)) {
			return;
		}
		probeLast = now;
		probeLastWhat = what;
		Iris.logger.info("Horizon probe: " + what + " — " + held + " meshes held, "
			+ drawnLastFrame + " drawn last frame, irisFailed=" + irisFailed);
	}

	/** Render thread. Draws every visible region mesh, sampling the photo atlas (flat color where unbaked). */
	public void render(Matrix4f modelView, Matrix4f projection, net.irisshaders.iris.horizon.voxel.model.VoxelBakery bakery) {
		processUploads(HorizonConfig.get().getMaxUploadsPerFrame());
		if (meshes.isEmpty()) {
			probe("no meshes held", 0);
			return;
		}
		var camPos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
		// Shaderpack first: it gives the pack real normals and materials. Falling
		// through means no pack, no dh programs, or a failure already recorded.
		if (renderIris(modelView, projection, camPos.x, camPos.y, camPos.z)) {
			probe("shaderpack path", meshes.size());
			return;
		}
		probe("built-in path", meshes.size());
		if (!shader.ensure()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return;
		}
		var cam = mc.gameRenderer.getMainCamera();
		double camX = cam.getPosition().x;
		double camY = cam.getPosition().y;
		double camZ = cam.getPosition().z;

		int lodDist = HorizonConfig.get().getLodDistanceBlocks();
		float fogStart = lodDist * 0.80f;
		float fogEnd = lodDist;
		float[] fogColor = RenderSystem.getShaderFogColor();
		// Day/night sky factor from the real sun angle (mirrors the classic
		// engine): day ~1.0, night ~0.15. The old getSkyDarken test never fired,
		// so the LOD stayed day-lit around the clock.
		float skyFactor;
		if (mc.level.dimensionType().hasSkyLight()) {
			float f = mc.level.getTimeOfDay(1.0f);
			float d = net.minecraft.util.Mth.clamp(net.minecraft.util.Mth.cos(f * 6.2831855f) * 2.0f + 0.5f, 0.0f, 1.0f);
			skyFactor = 0.15f + d * 0.85f;
		} else {
			skyFactor = 0.75f;
		}

		mvp.set(projection).mul(modelView);
		frustum.set(mvp);
		mvp.get(mvpArray);

		int rdChunks = mc.options.getEffectiveRenderDistance();
		int camChunkX = Math.floorDiv((int) Math.floor(camX), 16);
		int camChunkZ = Math.floorDiv((int) Math.floor(camZ), 16);
		updateChunkMask(mc.level, camChunkX, camChunkZ, rdChunks + 2);

		int prevProgram = GL33C.glGetInteger(GL33C.GL_CURRENT_PROGRAM);
		int prevVao = GL33C.glGetInteger(GL33C.GL_VERTEX_ARRAY_BINDING);
		int prevArrayBuffer = GL33C.glGetInteger(GL33C.GL_ARRAY_BUFFER_BINDING);

		// Read the texture and pipeline state from GlStateManager's cache rather
		// than from the driver. Asking the driver for GL_TEXTURE_BINDING_2D gives
		// the binding of whatever unit happens to be active, which is not unit 0
		// under a shaderpack — restoring that value into unit 0 evicts the block
		// atlas, and because a raw glBindTexture leaves the cache still claiming
		// the atlas, Iris's own bindTextureToUnit then skips the rebind as
		// redundant and the atlas never comes back. That is the permanent black
		// terrain, and it reaches loaded chunks because unit 0 is shared.
		int prevActiveUnit = GlStateManagerAccessor.getActiveTexture();
		int prevTex0 = GlStateManagerAccessor.getTEXTURES()[0].binding;
		GlStateManager.DepthState depthState = GlStateManagerAccessor.getDEPTH();
		boolean prevDepthTest = ((BooleanStateAccessor) depthState.mode).isEnabled();
		int prevDepthFunc = depthState.func;
		boolean prevDepthMask = depthState.mask;
		boolean prevCull = GL33C.glIsEnabled(GL33C.GL_CULL_FACE);
		// Blend is handled by BlendModeOverride below, so it needs no snapshot.

		GlStateManager._enableDepthTest();
		GlStateManager._depthFunc(GL33C.GL_LEQUAL);
		GlStateManager._depthMask(true);
		// Same reason as the shaderpack path: a pack can be active even when we
		// are drawing with the built-in program, and a locked override would
		// swallow a plain _disableBlend.
		net.irisshaders.iris.gl.blending.BlendModeOverride.OFF.apply();
		GlStateManager._disableCull(); // M3: two-sided until winding is verified
		GlStateManager._enablePolygonOffset();
		GlStateManager._polygonOffset(3.0f, 3.0f);

		int[] prevDrawBuffers = null;
		int boundFbo = GL33C.glGetInteger(GL33C.GL_DRAW_FRAMEBUFFER_BINDING);
		if (boundFbo != 0) {
			if (boundFbo != cachedDrawBuffersFbo) {
				boolean multi = false;
				for (int i = 0; i < 8; i++) {
					fboDrawBuffers[i] = GL33C.glGetInteger(GL33C.GL_DRAW_BUFFER0 + i);
					if (i > 0 && fboDrawBuffers[i] != GL33C.GL_NONE) {
						multi = true;
					}
				}
				cachedDrawBuffersFbo = boundFbo;
				cachedDrawBuffersMulti = multi;
			}
			if (cachedDrawBuffersMulti) {
				prevDrawBuffers = fboDrawBuffers;
				GL33C.glDrawBuffers(fboDrawBuffers[0]);
			}
		}

		GlStateManager._activeTexture(GL33C.GL_TEXTURE0);
		GlStateManager._bindTexture(maskTexture);
		int prevTex1 = GlStateManagerAccessor.getTEXTURES()[1].binding;
		if (bakery.atlasTexture() != 0) {
			GlStateManager._activeTexture(GL33C.GL_TEXTURE1);
			GlStateManager._bindTexture(bakery.atlasTexture());
			GlStateManager._activeTexture(GL33C.GL_TEXTURE0);
		}
		shader.bind();
		shader.setFrame(mvpArray, fogColor, fogStart, fogEnd, skyFactor);
		shader.setMask(0, (float) (camX / 16.0 - maskOriginX), (float) (camZ / 16.0 - maskOriginZ), MASK_SIZE);
		shader.setUseMask(true);
		shader.setAtlas(1, bakery.atlasSlotsPerRow(), 16.0f / bakery.atlasSize());

		double maxDist = lodDist + 192.0;
		double maxDistSq = maxDist * maxDist;
		float relY = (float) -camY;
		int drawn = 0;

		for (VoxelRegionMesh mesh : meshes.values()) {
			int level = mesh.level;
			int span = VoxelRegionKey.regionSpanBlocks(level);
			double originX = (double) VoxelRegionKey.rx(mesh.regionKey) * span;
			double originZ = (double) VoxelRegionKey.rz(mesh.regionKey) * span;
			double dcx = originX + span * 0.5 - camX;
			double dcz = originZ + span * 0.5 - camZ;
			double centerDistSq = dcx * dcx + dcz * dcz;
			if (centerDistSq > maxDistSq) {
				continue;
			}
			float ox = (float) (originX - camX);
			float oz = (float) (originZ - camZ);
			if (!frustum.testAab(ox, mesh.minY + relY, oz, ox + span, mesh.maxY + relY, oz + span)) {
				continue;
			}
			shader.setOffset(ox, relY - VoxelConstants.Y_BIAS, oz);
			shader.setCellSize(1 << level);
			mesh.drawOpaque();
			drawn++;
			if (mesh.hasTranslucent()) {
				// Defer: translucent geometry must be drawn after ALL opaque
				// geometry, and back to front among itself.
				translucentPending.add(mesh);
			}
		}

		if (!translucentPending.isEmpty()) {
			drawTranslucentPass(camX, camY, camZ, relY);
		}
		drawnLastFrame = drawn;

		if (prevDrawBuffers != null) {
			GL33C.glDrawBuffers(prevDrawBuffers);
		}
		GlStateManager._polygonOffset(0.0f, 0.0f);
		GlStateManager._disablePolygonOffset();
		if (prevDepthTest) {
			GlStateManager._enableDepthTest();
		} else {
			GlStateManager._disableDepthTest();
		}
		GlStateManager._depthFunc(prevDepthFunc);
		GlStateManager._depthMask(prevDepthMask);
		if (prevCull) {
			GlStateManager._enableCull();
		} else {
			GlStateManager._disableCull();
		}
		net.irisshaders.iris.gl.blending.BlendModeOverride.restore();
		if (bakery.atlasTexture() != 0) {
			GlStateManager._activeTexture(GL33C.GL_TEXTURE1);
			GlStateManager._bindTexture(prevTex1);
		}
		GlStateManager._activeTexture(GL33C.GL_TEXTURE0);
		GlStateManager._bindTexture(prevTex0);
		GlStateManager._activeTexture(GL33C.GL_TEXTURE0 + prevActiveUnit);
		GL33C.glBindVertexArray(prevVao);
		GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, prevArrayBuffer);
		GL33C.glUseProgram(prevProgram);
	}

	/**
	 * Rebuilds the per-chunk coverage mask around the camera and uploads it,
	 * throttled to a few times a second. A chunk is covered when the client
	 * actually has it, so the LOD boundary follows what is really loaded.
	 * Ported from LodRenderer.updateChunkMask.
	 */
	private void updateChunkMask(net.minecraft.client.multiplayer.ClientLevel level, int camChunkX, int camChunkZ, int rdChunks) {
		boolean moved = camChunkX != maskCenterX || camChunkZ != maskCenterZ;
		maskAge++;
		boolean stale = maskTexture == 0 || (moved && maskAge >= 3) || maskAge >= 8;
		if (!stale) {
			return;
		}
		maskAge = 0;
		maskCenterX = camChunkX;
		maskCenterZ = camChunkZ;
		maskOriginX = camChunkX - MASK_SIZE / 2;
		maskOriginZ = camChunkZ - MASK_SIZE / 2;

		var chunkSource = level.getChunkSource();
		java.util.Arrays.fill(maskData, (byte) 0);
		int rd = Math.min(MASK_SIZE / 2 - 1, rdChunks);
		int rdSq = rd * rd;
		int jMin = Math.max(0, MASK_SIZE / 2 - rd);
		int jMax = Math.min(MASK_SIZE - 1, MASK_SIZE / 2 + rd);
		for (int j = jMin; j <= jMax; j++) {
			int cz = maskOriginZ + j;
			int dz = cz - camChunkZ;
			int dxMax = (int) Math.sqrt((double) (rdSq - dz * dz));
			int iMin = Math.max(0, MASK_SIZE / 2 - dxMax);
			int iMax = Math.min(MASK_SIZE - 1, MASK_SIZE / 2 + dxMax);
			for (int i = iMin; i <= iMax; i++) {
				int cx = maskOriginX + i;
				if (chunkSource.hasChunk(cx, cz)) {
					maskData[i + j * MASK_SIZE] = (byte) 255;
				}
			}
		}
		// Erode the covered region by one chunk: a chunk discards LOD only if it
		// and its four orthogonal neighbours are all loaded. This lets the LOD
		// extend one chunk INTO the loaded area (hidden there by the depth test)
		// instead of stopping at the loaded edge, which left a one-chunk hole
		// between real terrain and LOD.
		erodeMask();
		maskBuffer.clear();
		maskBuffer.put(maskData).flip();

		int prevTex = GL33C.glGetInteger(GL33C.GL_TEXTURE_BINDING_2D);
		int prevUnpackBuffer = GL33C.glGetInteger(GL33C.GL_PIXEL_UNPACK_BUFFER_BINDING);
		int prevRowLength = GL33C.glGetInteger(GL33C.GL_UNPACK_ROW_LENGTH);
		int prevSkipRows = GL33C.glGetInteger(GL33C.GL_UNPACK_SKIP_ROWS);
		int prevSkipPixels = GL33C.glGetInteger(GL33C.GL_UNPACK_SKIP_PIXELS);
		int prevAlignment = GL33C.glGetInteger(GL33C.GL_UNPACK_ALIGNMENT);
		GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, 0);
		GL33C.glPixelStorei(GL33C.GL_UNPACK_ROW_LENGTH, 0);
		GL33C.glPixelStorei(GL33C.GL_UNPACK_SKIP_ROWS, 0);
		GL33C.glPixelStorei(GL33C.GL_UNPACK_SKIP_PIXELS, 0);
		GL33C.glPixelStorei(GL33C.GL_UNPACK_ALIGNMENT, 1);

		boolean firstTime = maskTexture == 0;
		if (firstTime) {
			maskTexture = GL33C.glGenTextures();
		}
		GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, maskTexture);
		if (firstTime) {
			GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_R8, MASK_SIZE, MASK_SIZE, 0, GL33C.GL_RED, GL33C.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
			GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_LINEAR);
			GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_LINEAR);
			GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_S, GL33C.GL_CLAMP_TO_EDGE);
			GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_T, GL33C.GL_CLAMP_TO_EDGE);
		}
		GL33C.glTexSubImage2D(GL33C.GL_TEXTURE_2D, 0, 0, 0, MASK_SIZE, MASK_SIZE, GL33C.GL_RED, GL33C.GL_UNSIGNED_BYTE, maskBuffer);
		GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, prevTex);
		GL33C.glPixelStorei(GL33C.GL_UNPACK_ROW_LENGTH, prevRowLength);
		GL33C.glPixelStorei(GL33C.GL_UNPACK_SKIP_ROWS, prevSkipRows);
		GL33C.glPixelStorei(GL33C.GL_UNPACK_SKIP_PIXELS, prevSkipPixels);
		GL33C.glPixelStorei(GL33C.GL_UNPACK_ALIGNMENT, prevAlignment);
		GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, prevUnpackBuffer);
	}

	/** Erodes {@link #maskData} by one chunk (4-neighbourhood): interior stays covered, the boundary ring clears. */
	private void erodeMask() {
		// Scan interior cells; a covered cell with any uncovered orthogonal
		// neighbour is cleared. Uses maskBuffer's backing as scratch would alias
		// maskData, so read from a snapshot of the covered bits first.
		byte[] src = maskData.clone();
		for (int j = 1; j < MASK_SIZE - 1; j++) {
			for (int i = 1; i < MASK_SIZE - 1; i++) {
				int idx = i + j * MASK_SIZE;
				if (src[idx] == 0) {
					continue;
				}
				if (src[idx - 1] == 0 || src[idx + 1] == 0
					|| src[idx - MASK_SIZE] == 0 || src[idx + MASK_SIZE] == 0) {
					maskData[idx] = 0;
				}
			}
		}
	}

	/** Render thread: pipeline destroy hook (no per-pipeline GL held in M3; kept for symmetry). */


	/** Render thread: free all GPU meshes and bump the epoch (world unload / config change). */
	public void clear() {
		synchronized (epochLock) {
			epoch++;
			VoxelMesher.MeshData pending;
			while ((pending = uploadQueue.poll()) != null) {
				pending.free();
			}
			inFlight.clear();
		}
		fallbackEpoch.clear();
		// The pack may rebuild its framebuffers; a cached layout for a recycled
		// FBO name would restore the wrong draw buffers.
		cachedDrawBuffersFbo = -1;
		for (VoxelRegionMesh mesh : meshes.values()) {
			mesh.delete();
		}
		meshes.clear();
		if (maskTexture != 0) {
			GL33C.glDeleteTextures(maskTexture);
			maskTexture = 0;
			maskCenterX = Integer.MIN_VALUE;
			maskCenterZ = Integer.MIN_VALUE;
		}
	}

	/** Render thread: full teardown at client shutdown. */
	public void destroy() {
		clear();
		shader.destroy();
	}
}
