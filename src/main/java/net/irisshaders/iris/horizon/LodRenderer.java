package net.irisshaders.iris.horizon;

import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.Iris;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL33C;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Renders the extended-distance LOD terrain. Owns the GPU meshes, the
 * upload queue fed by the meshing worker, and a minimal flat-shaded fog
 * shader. Draw order: after opaque terrain and entities, before the
 * translucent layer, so vanilla geometry always wins the depth test and
 * water still blends over the horizon correctly.
 */
public final class LodRenderer {
	private final Map<Long, LodRegionMesh> meshes = new ConcurrentHashMap<>();
	private final ConcurrentLinkedQueue<LodMesher.MeshData> uploadQueue = new ConcurrentLinkedQueue<>();
	/** Regions with a mesh build currently queued or running. */
	private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
	/**
	 * Bumped on world unload; results from worker jobs started under an
	 * older epoch are dropped so terrain never leaks across dimensions.
	 */
	private volatile int epoch;

	private int program;
	private int uMvp, uOffset, uFogColor, uFogStart, uFogEnd, uBrightness, uCamXZ, uMaskOrigin, uMaskTexels, uChunkMask, uUseMask;
	private boolean shaderFailed;

	private final Matrix4f mvp = new Matrix4f();
	private final FrustumIntersection frustum = new FrustumIntersection();
	private final float[] mvpArray = new float[16];

	/**
	 * Per-chunk coverage mask: 255 where a real chunk is loaded and inside
	 * the render distance, 0 elsewhere. Sampled with linear filtering in the
	 * fragment shader so the LOD fades out over ~16 blocks against real
	 * terrain (dithered), instead of cutting along a camera-centered circle.
	 */
	private static final int MASK_SIZE = 96;
	private int maskTexture;
	private final java.nio.ByteBuffer maskBuffer = org.lwjgl.system.MemoryUtil.memAlloc(MASK_SIZE * MASK_SIZE);
	private final byte[] maskData = new byte[MASK_SIZE * MASK_SIZE];
	private int maskOriginX;
	private int maskOriginZ;
	private int maskCenterX = Integer.MIN_VALUE;
	private int maskCenterZ = Integer.MIN_VALUE;
	private int maskAge;

	private static final String VERTEX_SHADER = """
		#version 150 core
		in vec3 inPos;
		in vec4 inColor;
		uniform mat4 u_mvp;
		uniform vec3 u_offset;
		out vec4 vColor;
		out vec3 vRelPos;
		void main() {
			vec3 rel = inPos + u_offset;
			vRelPos = rel;
			vColor = inColor;
			gl_Position = u_mvp * vec4(rel, 1.0);
		}
		""";

	private static final String FRAGMENT_SHADER = """
		#version 150 core
		in vec4 vColor;
		in vec3 vRelPos;
		uniform vec4 u_fogColor;
		uniform float u_fogStart;
		uniform float u_fogEnd;
		uniform float u_brightness;
		uniform vec2 u_camXZ;
		uniform sampler2D u_chunkMask;
		uniform vec2 u_maskOrigin;
		uniform float u_maskTexels;
		uniform int u_useMask;
		out vec4 fragColor;
		void main() {
			// Full-resolution collar meshes (u_useMask == 0) render in
			// complete overlap with real terrain: polygon offset makes real
			// blocks win the depth test pixel-for-pixel, so the seam is
			// exact by construction and missing chunks are backfilled.
			// Coarse meshes would poke through real terrain instead, so
			// they get cut by the per-chunk coverage mask, dithered along
			// the linear ramp at its border.
			if (u_useMask == 1) {
				vec2 uv = (((vRelPos.xz + u_camXZ) / 16.0) - u_maskOrigin) / u_maskTexels;
				float covered = 0.0;
				if (uv.x > 0.0 && uv.x < 1.0 && uv.y > 0.0 && uv.y < 1.0) {
					covered = texture(u_chunkMask, uv).r;
				}
				if (covered > 0.999) {
					discard;
				}
				if (covered > 0.001) {
					float n = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
					if (covered > n) {
						discard;
					}
				}
			}
			float dist = length(vRelPos.xz);
			float fog = smoothstep(u_fogStart, u_fogEnd, dist);
			vec3 lit = vColor.rgb * u_brightness;
			fragColor = vec4(mix(lit, u_fogColor.rgb, fog), 1.0);
		}
		""";

	public boolean isMeshScheduled(long regionKey) {
		return inFlight.contains(regionKey);
	}

	public boolean markScheduled(long regionKey) {
		return inFlight.add(regionKey);
	}

	public LodRegionMesh getMesh(long regionKey) {
		return meshes.get(regionKey);
	}

	public int meshCount() {
		return meshes.size();
	}

	public int currentEpoch() {
		return epoch;
	}

	/** Called from the meshing worker with finished vertex data. */
	public void submit(long regionKey, LodMesher.MeshData data, int jobEpoch) {
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

	/** Render-thread: turn pending vertex data into GPU meshes, bounded per frame. */
	public void processUploads(int budget) {
		LodMesher.MeshData data;
		while (budget-- > 0 && (data = uploadQueue.poll()) != null) {
			long key = LodStorage.regionKey(data.regionX(), data.regionZ());
			LodRegionMesh old = meshes.put(key, new LodRegionMesh(data));
			if (old != null) {
				old.delete();
			}
			data.free();
			inFlight.remove(key);
		}
	}

	/** Drops meshes for regions outside the keep radius (in region coords). */
	public void evictOutside(int centerRegionX, int centerRegionZ, int keepRadiusRegions) {
		Iterator<Map.Entry<Long, LodRegionMesh>> it = meshes.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Long, LodRegionMesh> e = it.next();
			LodRegionMesh mesh = e.getValue();
			int dx = mesh.regionX - centerRegionX;
			int dz = mesh.regionZ - centerRegionZ;
			if (Math.max(Math.abs(dx), Math.abs(dz)) > keepRadiusRegions) {
				mesh.delete();
				it.remove();
			}
		}
	}

	public void render(Matrix4f modelView, Matrix4f projection, double camX, double camY, double camZ,
					   float fogStart, float fogEnd, float[] fogColor, float brightness,
					   net.minecraft.client.multiplayer.ClientLevel level, int renderDistanceChunks) {
		if (shaderFailed || meshes.isEmpty()) {
			return;
		}
		if (program == 0 && !initShader()) {
			return;
		}

		updateChunkMask(level, Math.floorDiv((int) Math.floor(camX), 16), Math.floorDiv((int) Math.floor(camZ), 16), renderDistanceChunks);

		mvp.set(projection).mul(modelView);
		frustum.set(mvp);

		int prevProgram = GL33C.glGetInteger(GL33C.GL_CURRENT_PROGRAM);
		int prevVao = GL33C.glGetInteger(GL33C.GL_VERTEX_ARRAY_BINDING);
		int prevActiveTexture = GL33C.glGetInteger(GL33C.GL_ACTIVE_TEXTURE);
		GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
		int prevTexture = GL33C.glGetInteger(GL33C.GL_TEXTURE_BINDING_2D);

		// Shader packs bind G-buffers with several color attachments here.
		// This program only declares one output; fragments would write
		// undefined data into the other attachments (normals, materials...)
		// and corrupt deferred shading. Restrict the draw to attachment 0
		// for the duration of the pass, then restore the full set.
		int[] prevDrawBuffers = null;
		if (GL33C.glGetInteger(GL33C.GL_DRAW_FRAMEBUFFER_BINDING) != 0) {
			prevDrawBuffers = new int[8];
			boolean multi = false;
			for (int i = 0; i < 8; i++) {
				prevDrawBuffers[i] = GL33C.glGetInteger(GL33C.GL_DRAW_BUFFER0 + i);
				if (i > 0 && prevDrawBuffers[i] != GL33C.GL_NONE) {
					multi = true;
				}
			}
			if (multi) {
				GL33C.glDrawBuffers(prevDrawBuffers[0]);
			} else {
				prevDrawBuffers = null;
			}
		}

		RenderSystem.enableDepthTest();
		RenderSystem.depthMask(true);
		RenderSystem.disableBlend();
		// Two-sided: the column mesh is cheap enough that skipping
		// winding-order bookkeeping beats backface culling here.
		RenderSystem.disableCull();
		// The LOD surface is coplanar with real block tops; bias it away so
		// real geometry always wins the depth test instead of shimmering.
		RenderSystem.polygonOffset(3.0f, 3.0f);
		RenderSystem.enablePolygonOffset();

		GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, maskTexture);
		GL33C.glUseProgram(program);
		mvp.get(mvpArray);
		GL33C.glUniformMatrix4fv(uMvp, false, mvpArray);
		GL33C.glUniform4f(uFogColor, fogColor[0], fogColor[1], fogColor[2], 1.0f);
		GL33C.glUniform1f(uFogStart, fogStart);
		GL33C.glUniform1f(uFogEnd, fogEnd);
		GL33C.glUniform1f(uBrightness, brightness);
		GL33C.glUniform2f(uCamXZ, (float) camX, (float) camZ);
		GL33C.glUniform2f(uMaskOrigin, maskOriginX, maskOriginZ);
		GL33C.glUniform1f(uMaskTexels, MASK_SIZE);
		GL33C.glUniform1i(uChunkMask, 0);

		int camChunkX = Math.floorDiv((int) Math.floor(camX), 16);
		int camChunkZ = Math.floorDiv((int) Math.floor(camZ), 16);
		int lastUseMask = -1;

		for (LodRegionMesh mesh : meshes.values()) {
			// Deep-interior skip: drop draws only for regions whose chunks
			// are all loaded AND that sit well inside the render distance,
			// where terrain meshes are guaranteed built. Regions near the
			// edge are always drawn so the LOD backfills sections that are
			// still meshing — that's what keeps the seam hole-free.
			int regionCenterChunkX = (mesh.regionX << LodMesher.REGION_CHUNK_BITS) + 4;
			int regionCenterChunkZ = (mesh.regionZ << LodMesher.REGION_CHUNK_BITS) + 4;
			int cheb = Math.max(Math.abs(regionCenterChunkX - camChunkX), Math.abs(regionCenterChunkZ - camChunkZ)) + 4;
			if (cheb + 8 <= renderDistanceChunks && isRegionFullyCovered(mesh)) {
				continue;
			}

			float ox = (float) (mesh.regionX * (long) LodMesher.REGION_BLOCKS - camX);
			float oz = (float) (mesh.regionZ * (long) LodMesher.REGION_BLOCKS - camZ);
			float oy = (float) -camY;
			if (!frustum.testAab(ox, mesh.minY + oy, oz, ox + LodMesher.REGION_BLOCKS, mesh.maxY + oy, oz + LodMesher.REGION_BLOCKS)) {
				continue;
			}

			int useMask = mesh.scale > 1 ? 1 : 0;
			if (useMask != lastUseMask) {
				GL33C.glUniform1i(uUseMask, useMask);
				lastUseMask = useMask;
			}
			GL33C.glUniform3f(uOffset, ox, oy, oz);
			mesh.draw();
		}

		if (prevDrawBuffers != null) {
			GL33C.glDrawBuffers(prevDrawBuffers);
		}
		GL33C.glBindVertexArray(prevVao);
		GL33C.glUseProgram(prevProgram);
		GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, prevTexture);
		GL33C.glActiveTexture(prevActiveTexture);
		RenderSystem.disablePolygonOffset();
		RenderSystem.polygonOffset(0.0f, 0.0f);
		RenderSystem.enableCull();
	}

	/**
	 * Rebuilds the per-chunk coverage mask around the camera and uploads it.
	 * A chunk counts as covered when the client actually has it and it is
	 * within the effective render distance — so the LOD boundary is a
	 * chunk-aligned grid that adapts to what is really loaded.
	 */
	private void updateChunkMask(net.minecraft.client.multiplayer.ClientLevel level, int camChunkX, int camChunkZ, int renderDistanceChunks) {
		// Chunk load state changes a few times per second at most: rebuild
		// when the camera crosses a chunk border, or every 8 frames.
		boolean stale = maskTexture == 0
			|| camChunkX != maskCenterX || camChunkZ != maskCenterZ
			|| ++maskAge >= 8;
		if (!stale) {
			return;
		}
		maskAge = 0;
		maskCenterX = camChunkX;
		maskCenterZ = camChunkZ;

		if (maskTexture == 0) {
			maskTexture = GL33C.glGenTextures();
			int prev = GL33C.glGetInteger(GL33C.GL_TEXTURE_BINDING_2D);
			GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, maskTexture);
			GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_R8, MASK_SIZE, MASK_SIZE, 0, GL33C.GL_RED, GL33C.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
			GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_LINEAR);
			GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_LINEAR);
			GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_S, GL33C.GL_CLAMP_TO_EDGE);
			GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_T, GL33C.GL_CLAMP_TO_EDGE);
			GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, prev);
		}

		maskOriginX = camChunkX - MASK_SIZE / 2;
		maskOriginZ = camChunkZ - MASK_SIZE / 2;

		var chunkSource = level.getChunkSource();
		for (int j = 0; j < MASK_SIZE; j++) {
			int cz = maskOriginZ + j;
			int dz = Math.abs(cz - camChunkZ);
			for (int i = 0; i < MASK_SIZE; i++) {
				int cx = maskOriginX + i;
				boolean covered = dz <= renderDistanceChunks
					&& Math.abs(cx - camChunkX) <= renderDistanceChunks
					&& chunkSource.hasChunk(cx, cz);
				maskData[i + j * MASK_SIZE] = covered ? (byte) 255 : 0;
			}
		}

		maskBuffer.clear();
		maskBuffer.put(maskData).flip();
		int prev = GL33C.glGetInteger(GL33C.GL_TEXTURE_BINDING_2D);
		GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, maskTexture);
		GL33C.glTexSubImage2D(GL33C.GL_TEXTURE_2D, 0, 0, 0, MASK_SIZE, MASK_SIZE, GL33C.GL_RED, GL33C.GL_UNSIGNED_BYTE, maskBuffer);
		GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, prev);
	}

	/** CPU-side draw skip: true when all 8x8 chunks of a region are covered. */
	private boolean isRegionFullyCovered(LodRegionMesh mesh) {
		int minCx = mesh.regionX << LodMesher.REGION_CHUNK_BITS;
		int minCz = mesh.regionZ << LodMesher.REGION_CHUNK_BITS;
		int chunks = 1 << LodMesher.REGION_CHUNK_BITS;
		// The region must sit strictly inside the mask, with a one-chunk
		// margin so the linear fade ramp at its border is never skipped.
		if (minCx - 1 < maskOriginX || minCz - 1 < maskOriginZ
			|| minCx + chunks + 1 > maskOriginX + MASK_SIZE || minCz + chunks + 1 > maskOriginZ + MASK_SIZE) {
			return false;
		}
		for (int cz = minCz - 1; cz < minCz + chunks + 1; cz++) {
			for (int cx = minCx - 1; cx < minCx + chunks + 1; cx++) {
				if (maskData[(cx - maskOriginX) + (cz - maskOriginZ) * MASK_SIZE] == 0) {
					return false;
				}
			}
		}
		return true;
	}

	private boolean initShader() {
		try {
			int vs = compile(GL33C.GL_VERTEX_SHADER, VERTEX_SHADER);
			int fs = compile(GL33C.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
			program = GL33C.glCreateProgram();
			GL33C.glAttachShader(program, vs);
			GL33C.glAttachShader(program, fs);
			GL33C.glBindAttribLocation(program, 0, "inPos");
			GL33C.glBindAttribLocation(program, 1, "inColor");
			GL33C.glLinkProgram(program);
			if (GL33C.glGetProgrami(program, GL33C.GL_LINK_STATUS) == GL33C.GL_FALSE) {
				throw new IllegalStateException("Horizon LOD shader link failed: " + GL33C.glGetProgramInfoLog(program));
			}
			GL33C.glDeleteShader(vs);
			GL33C.glDeleteShader(fs);
			uMvp = GL33C.glGetUniformLocation(program, "u_mvp");
			uOffset = GL33C.glGetUniformLocation(program, "u_offset");
			uFogColor = GL33C.glGetUniformLocation(program, "u_fogColor");
			uFogStart = GL33C.glGetUniformLocation(program, "u_fogStart");
			uFogEnd = GL33C.glGetUniformLocation(program, "u_fogEnd");
			uBrightness = GL33C.glGetUniformLocation(program, "u_brightness");
			uCamXZ = GL33C.glGetUniformLocation(program, "u_camXZ");
			uMaskOrigin = GL33C.glGetUniformLocation(program, "u_maskOrigin");
			uMaskTexels = GL33C.glGetUniformLocation(program, "u_maskTexels");
			uChunkMask = GL33C.glGetUniformLocation(program, "u_chunkMask");
			uUseMask = GL33C.glGetUniformLocation(program, "u_useMask");
			return true;
		} catch (Exception e) {
			shaderFailed = true;
			Iris.logger.error("Horizon: LOD shader failed to compile; extended LOD rendering disabled", e);
			return false;
		}
	}

	private static int compile(int type, String source) {
		int shader = GL33C.glCreateShader(type);
		GL33C.glShaderSource(shader, source);
		GL33C.glCompileShader(shader);
		if (GL33C.glGetShaderi(shader, GL33C.GL_COMPILE_STATUS) == GL33C.GL_FALSE) {
			throw new IllegalStateException("Horizon LOD shader compile failed: " + GL33C.glGetShaderInfoLog(shader));
		}
		return shader;
	}

	/** Render-thread: frees all GPU resources (world unload). */
	public void clear() {
		epoch++;
		if (maskTexture != 0) {
			GL33C.glDeleteTextures(maskTexture);
			maskTexture = 0;
			maskCenterX = Integer.MIN_VALUE;
			maskCenterZ = Integer.MIN_VALUE;
		}
		for (LodRegionMesh mesh : meshes.values()) {
			mesh.delete();
		}
		meshes.clear();
		LodMesher.MeshData data;
		while ((data = uploadQueue.poll()) != null) {
			data.free();
		}
		inFlight.clear();
	}
}
