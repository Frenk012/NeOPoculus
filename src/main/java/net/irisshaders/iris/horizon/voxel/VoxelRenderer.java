package net.irisshaders.iris.horizon.voxel;

import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.horizon.HorizonConfig;
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

	/** Render thread. Draws every visible region mesh, sampling the photo atlas (flat color where unbaked). */
	public void render(Matrix4f modelView, Matrix4f projection, net.irisshaders.iris.horizon.voxel.model.VoxelBakery bakery) {
		processUploads(HorizonConfig.get().getMaxUploadsPerFrame());
		if (meshes.isEmpty() || !shader.ensure()) {
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
		int prevActiveTex = GL33C.glGetInteger(GL33C.GL_ACTIVE_TEXTURE);
		int prevTex0 = GL33C.glGetInteger(GL33C.GL_TEXTURE_BINDING_2D);
		boolean prevCull = GL33C.glIsEnabled(GL33C.GL_CULL_FACE);
		boolean prevBlend = GL33C.glIsEnabled(GL33C.GL_BLEND);

		GL33C.glEnable(GL33C.GL_DEPTH_TEST);
		GL33C.glDepthFunc(GL33C.GL_LEQUAL);
		GL33C.glDepthMask(true);
		GL33C.glDisable(GL33C.GL_BLEND);
		GL33C.glDisable(GL33C.GL_CULL_FACE); // M3: two-sided until winding is verified
		GL33C.glEnable(GL33C.GL_POLYGON_OFFSET_FILL);
		GL33C.glPolygonOffset(3.0f, 3.0f);

		GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
		GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, maskTexture);
		int prevTex1 = 0;
		if (bakery.atlasTexture() != 0) {
			GL33C.glActiveTexture(GL33C.GL_TEXTURE1);
			prevTex1 = GL33C.glGetInteger(GL33C.GL_TEXTURE_BINDING_2D);
			GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, bakery.atlasTexture());
			GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
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

		GL33C.glPolygonOffset(0.0f, 0.0f);
		GL33C.glDisable(GL33C.GL_POLYGON_OFFSET_FILL);
		if (prevCull) {
			GL33C.glEnable(GL33C.GL_CULL_FACE);
		}
		if (prevBlend) {
			GL33C.glEnable(GL33C.GL_BLEND);
		}
		if (bakery.atlasTexture() != 0) {
			GL33C.glActiveTexture(GL33C.GL_TEXTURE1);
			GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, prevTex1);
		}
		GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
		GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, prevTex0);
		GL33C.glActiveTexture(prevActiveTex);
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
	public void onPipelineDestroyed(Object pipeline) {
		// M6 adds a HorizonIrisProgram/framebuffer here; nothing to free in M3.
	}

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
