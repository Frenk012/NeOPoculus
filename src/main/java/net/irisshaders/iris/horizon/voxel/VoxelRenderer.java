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

	private final NoPackVoxelShader shader = new NoPackVoxelShader();
	private final Matrix4f mvp = new Matrix4f();
	private final FrustumIntersection frustum = new FrustumIntersection();
	private final float[] mvpArray = new float[16];

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
			VoxelRegionMesh old = meshes.put(key, new VoxelRegionMesh(data));
			if (old != null) {
				old.delete();
			}
			if (totalUploaded == 0) {
				Iris.logger.info("Horizon: first voxel mesh uploaded (L" + data.level()
					+ " " + data.quads() + " quads); voxel render path is live");
			}
			totalUploaded++;
			inFlight.remove(key);
			data.free();
		}
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
				RenderSystem.recordRenderCall(mesh::delete);
			}
		}
	}

	/** Render thread. Draws every visible region mesh in one flat-color pass. */
	public void render(Matrix4f modelView, Matrix4f projection) {
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
		float skyFactor = mc.level.dimensionType().hasSkyLight()
			? Math.max(0.2f, mc.level.getSkyDarken(1.0f) >= 4 ? 0.3f : 1.0f) : 1.0f;

		mvp.set(projection).mul(modelView);
		frustum.set(mvp);
		mvp.get(mvpArray);

		int prevProgram = GL33C.glGetInteger(GL33C.GL_CURRENT_PROGRAM);
		int prevVao = GL33C.glGetInteger(GL33C.GL_VERTEX_ARRAY_BINDING);
		int prevArrayBuffer = GL33C.glGetInteger(GL33C.GL_ARRAY_BUFFER_BINDING);
		boolean prevCull = GL33C.glIsEnabled(GL33C.GL_CULL_FACE);
		boolean prevBlend = GL33C.glIsEnabled(GL33C.GL_BLEND);

		GL33C.glEnable(GL33C.GL_DEPTH_TEST);
		GL33C.glDepthFunc(GL33C.GL_LEQUAL);
		GL33C.glDepthMask(true);
		GL33C.glDisable(GL33C.GL_BLEND);
		GL33C.glDisable(GL33C.GL_CULL_FACE); // M3: two-sided until winding is verified
		GL33C.glEnable(GL33C.GL_POLYGON_OFFSET_FILL);
		GL33C.glPolygonOffset(3.0f, 3.0f);

		shader.bind();
		shader.setFrame(mvpArray, fogColor, fogStart, fogEnd, skyFactor);

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
			// No coverage cull in M3: LOD under loaded chunks is occluded by
			// real terrain via the depth test + polygon offset, and drawing it
			// everywhere keeps the seam at the render-distance edge visible.
			float ox = (float) (originX - camX);
			float oz = (float) (originZ - camZ);
			if (!frustum.testAab(ox, mesh.minY + relY, oz, ox + span, mesh.maxY + relY, oz + span)) {
				continue;
			}
			shader.setOffset(ox, relY - VoxelConstants.Y_BIAS, oz);
			mesh.draw();
			drawn++;
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
		GL33C.glBindVertexArray(prevVao);
		GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, prevArrayBuffer);
		GL33C.glUseProgram(prevProgram);
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
		for (VoxelRegionMesh mesh : meshes.values()) {
			mesh.delete();
		}
		meshes.clear();
	}

	/** Render thread: full teardown at client shutdown. */
	public void destroy() {
		clear();
		shader.destroy();
	}
}
