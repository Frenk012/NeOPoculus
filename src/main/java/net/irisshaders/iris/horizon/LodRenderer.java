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
	private int uMvp, uOffset, uFogColor, uFogStart, uFogEnd, uBrightness, uMinDist;
	private boolean shaderFailed;

	private final Matrix4f mvp = new Matrix4f();
	private final FrustumIntersection frustum = new FrustumIntersection();
	private final float[] mvpArray = new float[16];

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
		uniform float u_minDist;
		out vec4 fragColor;
		void main() {
			float dist = length(vRelPos.xz);
			// Real chunks cover everything inside the vanilla render
			// distance; LOD only fills the world beyond it.
			if (dist < u_minDist) {
				discard;
			}
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
					   float fogStart, float fogEnd, float[] fogColor, float brightness, int skipRadiusBlocks) {
		if (shaderFailed || meshes.isEmpty()) {
			return;
		}
		if (program == 0 && !initShader()) {
			return;
		}

		mvp.set(projection).mul(modelView);
		frustum.set(mvp);

		int prevProgram = GL33C.glGetInteger(GL33C.GL_CURRENT_PROGRAM);
		int prevVao = GL33C.glGetInteger(GL33C.GL_VERTEX_ARRAY_BINDING);

		RenderSystem.enableDepthTest();
		RenderSystem.depthMask(true);
		RenderSystem.disableBlend();
		// Two-sided: the column mesh is cheap enough that skipping
		// winding-order bookkeeping beats backface culling here.
		RenderSystem.disableCull();

		GL33C.glUseProgram(program);
		mvp.get(mvpArray);
		GL33C.glUniformMatrix4fv(uMvp, false, mvpArray);
		GL33C.glUniform4f(uFogColor, fogColor[0], fogColor[1], fogColor[2], 1.0f);
		GL33C.glUniform1f(uFogStart, fogStart);
		GL33C.glUniform1f(uFogEnd, fogEnd);
		GL33C.glUniform1f(uBrightness, brightness);
		GL33C.glUniform1f(uMinDist, skipRadiusBlocks);

		long skipSq = (long) skipRadiusBlocks * skipRadiusBlocks;

		for (LodRegionMesh mesh : meshes.values()) {
			float ox = (float) (mesh.regionX * (long) LodMesher.REGION_BLOCKS - camX);
			float oz = (float) (mesh.regionZ * (long) LodMesher.REGION_BLOCKS - camZ);

			// Skip regions whose farthest corner is still inside the
			// vanilla render distance; real chunks cover them.
			float fx = Math.max(Math.abs(ox), Math.abs(ox + LodMesher.REGION_BLOCKS));
			float fz = Math.max(Math.abs(oz), Math.abs(oz + LodMesher.REGION_BLOCKS));
			if ((long) fx * (long) fx + (long) fz * (long) fz < skipSq) {
				continue;
			}

			float oy = (float) -camY;
			if (!frustum.testAab(ox, mesh.minY + oy, oz, ox + LodMesher.REGION_BLOCKS, mesh.maxY + oy, oz + LodMesher.REGION_BLOCKS)) {
				continue;
			}

			GL33C.glUniform3f(uOffset, ox, oy, oz);
			mesh.draw();
		}

		GL33C.glBindVertexArray(prevVao);
		GL33C.glUseProgram(prevProgram);
		RenderSystem.enableCull();
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
			uMinDist = GL33C.glGetUniformLocation(program, "u_minDist");
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
