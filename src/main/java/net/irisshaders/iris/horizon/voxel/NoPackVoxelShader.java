package net.irisshaders.iris.horizon.voxel;

import net.irisshaders.iris.Iris;
import org.lwjgl.opengl.GL33C;

/**
 * The no-shaderpack GLSL 150 program for M3 flat-color voxel LOD: per-face
 * vanilla shading and captured sky/block light over the cell's MapColor, with
 * horizontal-distance fog. M4 extends the fragment stage to sample the photo
 * atlas; M6 routes packs through {@code HorizonIrisProgram} instead.
 */
public final class NoPackVoxelShader {
	private static final String VERTEX = """
		#version 150 core
		in uvec4 aPosLight;   // x, y(+bias), z, lightMeta
		in vec4  aColor;
		in uvec4 aExtra;      // material, face, 0, 0
		uniform mat4 u_mvp;
		uniform vec3 u_offset;
		out vec4 vColor;
		out vec3 vRelPos;
		flat out uint vFace;
		flat out uint vLight;
		void main() {
			vec3 rel = vec3(aPosLight.xyz) + u_offset;
			vRelPos = rel;
			vColor = aColor;
			vFace = aExtra.y;
			vLight = aPosLight.w;
			gl_Position = u_mvp * vec4(rel, 1.0);
		}
		""";

	private static final String FRAGMENT = """
		#version 150 core
		in vec4 vColor;
		in vec3 vRelPos;
		flat in uint vFace;
		flat in uint vLight;
		uniform vec4 u_fogColor;
		uniform float u_fogStart;
		uniform float u_fogEnd;
		uniform float u_skyFactor;
		uniform sampler2D u_chunkMask;
		uniform vec2 u_maskRel;
		uniform float u_maskTexels;
		uniform int u_useMask;
		out vec4 fragColor;
		const float faceShade[6] = float[6](0.5, 1.0, 0.8, 0.8, 0.6, 0.6);
		void main() {
			// Cut the LOD wherever a real chunk is loaded (per-chunk coverage
			// mask, dithered for a soft chunk-aligned boundary). This is what
			// keeps distant LOD from drawing on top of loaded terrain, since
			// the loaded radius can exceed the client render distance.
			if (u_useMask == 1) {
				vec2 uv = (vRelPos.xz / 16.0 + u_maskRel) / u_maskTexels;
				float covered = 0.0;
				if (uv.x > 0.0 && uv.x < 1.0 && uv.y > 0.0 && uv.y < 1.0) {
					covered = texture(u_chunkMask, uv).r;
				}
				float n = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
				if (covered > mix(0.3, 0.7, n)) {
					discard;
				}
			}
			float block = float((vLight >> 4u) & 15u) / 15.0;
			float sky   = float(vLight & 15u) / 15.0;
			// Ambient floor of 0.2 so shadowed sides and cave mouths read as
			// dark grey instead of pure black (the flat M3 color has no ambient
			// occlusion to soften them); day/night contrast is preserved above.
			float l = 0.2 + 0.8 * max(block, sky * u_skyFactor);
			vec3 rgb = vColor.rgb * l * faceShade[vFace];
			float f = clamp((length(vRelPos.xz) - u_fogStart) / (u_fogEnd - u_fogStart), 0.0, 1.0);
			fragColor = vec4(mix(rgb, u_fogColor.rgb, f), 1.0);
		}
		""";

	private int program;
	private boolean failed;
	private int uMvp, uOffset, uFogColor, uFogStart, uFogEnd, uSkyFactor;
	private int uChunkMask, uMaskRel, uMaskTexels, uUseMask;

	/** @return true if the program is ready to bind. Render thread. */
	public boolean ensure() {
		if (program != 0) {
			return true;
		}
		if (failed) {
			return false;
		}
		try {
			int vs = compile(GL33C.GL_VERTEX_SHADER, VERTEX);
			int fs = compile(GL33C.GL_FRAGMENT_SHADER, FRAGMENT);
			program = GL33C.glCreateProgram();
			GL33C.glAttachShader(program, vs);
			GL33C.glAttachShader(program, fs);
			GL33C.glBindAttribLocation(program, 0, "aPosLight");
			GL33C.glBindAttribLocation(program, 1, "aColor");
			GL33C.glBindAttribLocation(program, 2, "aExtra");
			GL33C.glLinkProgram(program);
			if (GL33C.glGetProgrami(program, GL33C.GL_LINK_STATUS) == GL33C.GL_FALSE) {
				throw new IllegalStateException("link: " + GL33C.glGetProgramInfoLog(program));
			}
			GL33C.glDeleteShader(vs);
			GL33C.glDeleteShader(fs);
			uMvp = GL33C.glGetUniformLocation(program, "u_mvp");
			uOffset = GL33C.glGetUniformLocation(program, "u_offset");
			uFogColor = GL33C.glGetUniformLocation(program, "u_fogColor");
			uFogStart = GL33C.glGetUniformLocation(program, "u_fogStart");
			uFogEnd = GL33C.glGetUniformLocation(program, "u_fogEnd");
			uSkyFactor = GL33C.glGetUniformLocation(program, "u_skyFactor");
			uChunkMask = GL33C.glGetUniformLocation(program, "u_chunkMask");
			uMaskRel = GL33C.glGetUniformLocation(program, "u_maskRel");
			uMaskTexels = GL33C.glGetUniformLocation(program, "u_maskTexels");
			uUseMask = GL33C.glGetUniformLocation(program, "u_useMask");
			return true;
		} catch (Exception e) {
			failed = true;
			Iris.logger.error("Horizon: voxel no-pack shader failed to compile; voxel LOD not drawn", e);
			return false;
		}
	}

	public void bind() {
		GL33C.glUseProgram(program);
	}

	public void setFrame(float[] mvp, float[] fogColor, float fogStart, float fogEnd, float skyFactor) {
		GL33C.glUniformMatrix4fv(uMvp, false, mvp);
		GL33C.glUniform4f(uFogColor, fogColor[0], fogColor[1], fogColor[2], fogColor[3]);
		GL33C.glUniform1f(uFogStart, fogStart);
		GL33C.glUniform1f(uFogEnd, fogEnd);
		GL33C.glUniform1f(uSkyFactor, skyFactor);
	}

	public void setOffset(float ox, float oy, float oz) {
		GL33C.glUniform3f(uOffset, ox, oy, oz);
	}

	/** Coverage mask on texture unit {@code maskUnit}; {@code maskRel} = camera chunk offset from mask origin. */
	public void setMask(int maskUnit, float maskRelX, float maskRelZ, float maskTexels) {
		GL33C.glUniform1i(uChunkMask, maskUnit);
		GL33C.glUniform2f(uMaskRel, maskRelX, maskRelZ);
		GL33C.glUniform1f(uMaskTexels, maskTexels);
	}

	public void setUseMask(boolean use) {
		GL33C.glUniform1i(uUseMask, use ? 1 : 0);
	}

	private static int compile(int type, String src) {
		int s = GL33C.glCreateShader(type);
		GL33C.glShaderSource(s, src);
		GL33C.glCompileShader(s);
		if (GL33C.glGetShaderi(s, GL33C.GL_COMPILE_STATUS) == GL33C.GL_FALSE) {
			throw new IllegalStateException("compile: " + GL33C.glGetShaderInfoLog(s));
		}
		return s;
	}

	public void destroy() {
		if (program != 0) {
			GL33C.glDeleteProgram(program);
			program = 0;
		}
		failed = false;
	}
}
