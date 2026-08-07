package net.irisshaders.iris.horizon;

import com.google.common.primitives.Ints;
import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.blending.BufferBlendOverride;
import net.irisshaders.iris.gl.program.ProgramImages;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.gl.shader.GlShader;
import net.irisshaders.iris.gl.shader.ShaderType;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.ShaderPrinter;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.samplers.IrisSamplers;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.builtin.BuiltinReplacementUniforms;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.minecraft.client.Minecraft;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shaderpack-provided program for Horizon LOD terrain. This is
 * {@link net.irisshaders.iris.compat.dh.IrisLodRenderProgram} stripped of its
 * Distant Horizons API coupling: Horizon feeds its own meshes through the
 * pack's {@code dh_terrain} program so LODs receive the pack's full gbuffer
 * shading (Complementary etc.) instead of the flat legacy pass.
 */
public class HorizonIrisProgram {
	public final int modelOffsetUniform;
	public final int worldYOffsetUniform;
	public final int mircoOffsetUniform;
	/**
	 * Blocks per unit of the vertex position: 1.0 for the classic engine, 1/16
	 * for the voxel engine, whose positions are in sixteenths so partial shapes
	 * can be expressed. Must always be set — an unset GLSL uniform is 0.0, which
	 * would collapse every vertex onto the model offset.
	 */
	public final int positionScaleUniform;
	/**
	 * Overridden AFTER the uniform system runs, not through it: `far` is a
	 * PER_FRAME uniform computed once per frame, so by the time this program
	 * uploads, the cached vanilla render distance is already in place. Packs fog
	 * their LOD against `far`, so leaving it at a few hundred blocks fogs terrain
	 * ten times further out into a solid wall.
	 */
	public final int farUniform;
	public final int dhFarPlaneUniform;
	public final int dhRenderDistanceUniform;
	private final float positionScale;
	public final int modelViewUniform;
	public final int modelViewInverseUniform;
	public final int projectionUniform;
	public final int projectionInverseUniform;
	public final int normalMatrix3fUniform;
	public final int clipDistanceUniform;
	public final int dhProjectionUniform;
	public final int dhProjectionInverseUniform;
	public final int dhPreviousProjectionUniform;
	private final Matrix4f previousProjection = new Matrix4f();
	private final Matrix4f modelViewInverse = new Matrix4f();
	private final Matrix4f projectionInverse = new Matrix4f();
	private final Matrix3f normalMatrix = new Matrix3f();
	private boolean hasPreviousProjection;
	private final int id;
	private final ProgramUniforms uniforms;
	private final CustomUniforms customUniforms;
	private final ProgramSamplers samplers;
	private final ProgramImages images;
	private final BlendModeOverride blend;
	private final BufferBlendOverride[] bufferBlendOverrides;

	private HorizonIrisProgram(String name, BlendModeOverride override, BufferBlendOverride[] bufferBlendOverrides,
							   String vertex, String tessControl, String tessEval, String geometry, String fragment,
							   CustomUniforms customUniforms, IrisRenderingPipeline pipeline,
							   float positionScale) {
		this.positionScale = positionScale;
		this.bufferBlendOverrides = bufferBlendOverrides;
		id = GL43C.glCreateProgram();

		GL32.glBindAttribLocation(this.id, 0, "vPosition");
		GL32.glBindAttribLocation(this.id, 1, "iris_color");
		GL32.glBindAttribLocation(this.id, 2, "irisExtra");

		GlShader vert = new GlShader(ShaderType.VERTEX, name + ".vsh", vertex);
		GL43C.glAttachShader(id, vert.getHandle());

		GlShader tessCont = null;
		if (tessControl != null) {
			tessCont = new GlShader(ShaderType.TESSELATION_CONTROL, name + ".tcs", tessControl);
			GL43C.glAttachShader(id, tessCont.getHandle());
		}

		GlShader tessE = null;
		if (tessEval != null) {
			tessE = new GlShader(ShaderType.TESSELATION_EVAL, name + ".tes", tessEval);
			GL43C.glAttachShader(id, tessE.getHandle());
		}

		GlShader geom = null;
		if (geometry != null) {
			geom = new GlShader(ShaderType.GEOMETRY, name + ".gsh", geometry);
			GL43C.glAttachShader(id, geom.getHandle());
		}

		GlShader frag = new GlShader(ShaderType.FRAGMENT, name + ".fsh", fragment);
		GL43C.glAttachShader(id, frag.getHandle());

		GL32.glLinkProgram(this.id);
		int status = GL32.glGetProgrami(this.id, GL32.GL_LINK_STATUS);
		if (status != 1) {
			String message = "Shader link error in Horizon LOD program! Details: " + GL32.glGetProgramInfoLog(this.id);
			this.free();
			throw new RuntimeException(message);
		} else {
			GL32.glUseProgram(this.id);
		}

		vert.destroy();
		frag.destroy();

		if (tessCont != null) tessCont.destroy();
		if (tessE != null) tessE.destroy();
		if (geom != null) geom.destroy();

		blend = override;
		ProgramUniforms.Builder uniformBuilder = ProgramUniforms.builder(name, id);
		ProgramSamplers.Builder samplerBuilder = ProgramSamplers.builder(id, IrisSamplers.WORLD_RESERVED_TEXTURE_UNITS);
		CommonUniforms.addDynamicUniforms(uniformBuilder, FogMode.PER_VERTEX);
		// The DH distance uniforms (dhFarPlane, dhNearPlane, dhRenderDistance)
		// live in generalCommonUniforms, which only addNonDynamicUniforms calls —
		// so this program never registered them and they stayed 0.0. Packs that
		// compute their LOD fog from dhFarPlane then saturate it everywhere,
		// which is why some rendered the LOD as nothing but fog while packs using
		// a different formula looked correct.
		CommonUniforms.generalCommonUniforms(uniformBuilder, pipeline.getFrameUpdateNotifier(),
			pipeline.getPackDirectives());
		customUniforms.assignTo(uniformBuilder);
		BuiltinReplacementUniforms.addBuiltinReplacementUniforms(uniformBuilder);
		ProgramImages.Builder builder = ProgramImages.builder(id);
		pipeline.addGbufferOrShadowSamplers(samplerBuilder, builder, pipeline::getFlippedAfterPrepare, false, false, true, false);
		customUniforms.mapholderToPass(uniformBuilder, this);
		this.uniforms = uniformBuilder.buildUniforms();
		this.customUniforms = customUniforms;
		samplers = samplerBuilder.build();
		images = builder.build();

		modelOffsetUniform = tryGetUniformLocation2("modelOffset");
		worldYOffsetUniform = tryGetUniformLocation2("worldYOffset");
		mircoOffsetUniform = tryGetUniformLocation2("mircoOffset");
		positionScaleUniform = tryGetUniformLocation2("irisPositionScale");
		farUniform = tryGetUniformLocation2("far");
		dhFarPlaneUniform = tryGetUniformLocation2("dhFarPlane");
		dhRenderDistanceUniform = tryGetUniformLocation2("dhRenderDistance");
		projectionUniform = tryGetUniformLocation2("iris_ProjectionMatrix");
		projectionInverseUniform = tryGetUniformLocation2("iris_ProjectionMatrixInverse");
		modelViewUniform = tryGetUniformLocation2("iris_ModelViewMatrix");
		modelViewInverseUniform = tryGetUniformLocation2("iris_ModelViewMatrixInverse");
		normalMatrix3fUniform = tryGetUniformLocation2("iris_NormalMatrix");
		clipDistanceUniform = tryGetUniformLocation2("clipDistance");
		dhProjectionUniform = tryGetUniformLocation2("dhProjection");
		dhProjectionInverseUniform = tryGetUniformLocation2("dhProjectionInverse");
		dhPreviousProjectionUniform = tryGetUniformLocation2("dhPreviousProjection");
	}

	/** Classic engine: vertex positions are whole blocks. */
	public static HorizonIrisProgram createProgram(String name, ProgramSource source, CustomUniforms uniforms, IrisRenderingPipeline pipeline) {
		return createProgram(name, source, uniforms, pipeline, 1.0f);
	}

	/**
	 * @param positionScale blocks per unit of vertex position — 1.0 for the
	 *                      classic engine, 1/16 for the voxel engine.
	 */
	public static HorizonIrisProgram createProgram(String name, ProgramSource source, CustomUniforms uniforms, IrisRenderingPipeline pipeline, float positionScale) {
		// DISTANT_HORIZONS is defined pipeline-wide via StandardMacros when
		// Horizon is active, so the pack source already resolves its dh
		// #ifdef branches. Do NOT inject a raw #define here: the DH transformer
		// rejects preprocessor directives at this stage.
		Map<PatchShaderType, String> transformed = TransformPatcher.patchDHTerrain(
			name,
			source.getVertexSource().orElseThrow(RuntimeException::new),
			source.getTessControlSource().orElse(null),
			source.getTessEvalSource().orElse(null),
			source.getGeometrySource().orElse(null),
			source.getFragmentSource().orElseThrow(RuntimeException::new),
			pipeline.getTextureMap());
		String vertex = transformed.get(PatchShaderType.VERTEX);
		String tessControl = transformed.get(PatchShaderType.TESS_CONTROL);
		String tessEval = transformed.get(PatchShaderType.TESS_EVAL);
		String geometry = transformed.get(PatchShaderType.GEOMETRY);
		String fragment = transformed.get(PatchShaderType.FRAGMENT);
		ShaderPrinter.printProgram(name)
			.addSources(transformed)
			.setName("horizon_" + name)
			.print();

		List<BufferBlendOverride> bufferOverrides = new ArrayList<>();
		source.getDirectives().getBufferBlendOverrides().forEach(information -> {
			int index = Ints.indexOf(source.getDirectives().getDrawBuffers(), information.index());
			if (index > -1) {
				bufferOverrides.add(new BufferBlendOverride(index, information.blendMode()));
			}
		});

		return new HorizonIrisProgram(name, source.getDirectives().getBlendModeOverride().orElse(null),
			bufferOverrides.toArray(BufferBlendOverride[]::new), vertex, tessControl, tessEval, geometry, fragment,
			uniforms, pipeline, positionScale);
	}

	public int tryGetUniformLocation2(CharSequence name) {
		return GL32.glGetUniformLocation(this.id, name);
	}

	public void setUniform(int index, Matrix4fc matrix) {
		if (index == -1 || matrix == null) return;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			FloatBuffer buffer = stack.callocFloat(16);
			matrix.get(buffer);
			buffer.rewind();
			RenderSystem.glUniformMatrix4(index, false, buffer);
		}
	}

	public void setUniform(int index, Matrix3f matrix) {
		if (index == -1) return;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			FloatBuffer buffer = stack.callocFloat(9);
			matrix.get(buffer);
			buffer.rewind();
			RenderSystem.glUniformMatrix3(index, false, buffer);
		}
	}

	public void bind() {
		GL43C.glUseProgram(id);
		if (blend != null) blend.apply();
		for (BufferBlendOverride override : bufferBlendOverrides) {
			override.apply();
		}
	}

	public void unbind() {
		GL43C.glUseProgram(0);
		ProgramUniforms.clearActiveUniforms();
		ProgramSamplers.clearActiveSamplers();
		BlendModeOverride.restore();
	}

	public void free() {
		GL43C.glDeleteProgram(id);
	}

	public void fillUniformData(Matrix4fc projection, Matrix4fc modelView) {
		GL43C.glUseProgram(id);

		Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
		IrisRenderSystem.bindTextureToUnit(TextureType.TEXTURE_2D.getGlType(), IrisSamplers.LIGHTMAP_TEXTURE_UNIT, RenderSystem.getShaderTexture(2));
		modelView.invert(modelViewInverse);
		projection.invert(projectionInverse);
		setUniform(modelViewUniform, modelView);
		setUniform(modelViewInverseUniform, modelViewInverse);
		setUniform(projectionUniform, projection);
		setUniform(projectionInverseUniform, projectionInverse);
		setUniform(normalMatrix3fUniform, modelViewInverse.transpose3x3(normalMatrix));

		setUniform(mircoOffsetUniform, 0.01f);
		setUniform(positionScaleUniform, positionScale);
		if (worldYOffsetUniform != -1) setUniform(worldYOffsetUniform, 0.0f);
		// ponytail: Horizon draws right up to the loaded-chunk boundary and
		// masks the overlap itself, so packs get no near-clip discard. If the
		// seam flickers under shaders, feed the mask start distance here.
		setUniform(clipDistanceUniform, 0.0f);

		samplers.update();
		uniforms.update();
		customUniforms.push(this);
		// Last word on the projection, for the same reason as the distance below.
		// The shared uniform system fills dhProjection from DHCompat, which
		// without the real Distant Horizons mod hands back the plain gbuffer
		// projection — vanilla far plane and all. Packs that position LOD with
		// `dhProjection * gbufferModelView * position` (Complementary, BSL,
		// iterationT) then clip every LOD vertex past the vanilla view distance,
		// so the pass draws hundreds of regions that never reach a pixel. Packs
		// that use gl_ProjectionMatrix instead (Sildur's) were unaffected, which
		// is exactly why one pack worked and the rest showed nothing.
		setUniform(dhProjectionUniform, projection);
		setUniform(dhProjectionInverseUniform, projectionInverse);
		setUniform(dhPreviousProjectionUniform, hasPreviousProjection ? previousProjection : projection);
		previousProjection.set(projection);
		hasPreviousProjection = true;

		// `far` is deliberately NOT overridden. To a pack it means the vanilla
		// view distance — the boundary where loaded chunks end and LOD takes
		// over — and packs use it to discard the LOD that would cover real
		// terrain. Complementary fades it out with
		//   color.a *= smoothstep(far * 0.4, far * 0.6, dist)
		// and BSL discards outright below `(dither - DH_OVERDRAW - 0.75) * 16 + far`.
		// Reporting the LOD distance here told both packs to throw away
		// everything nearer than the whole LOD range, which is why they drew
		// hundreds of regions and showed nothing. The LOD range belongs in
		// dhFarPlane and dhRenderDistance, which is where packs look for it.
		float lodFar = HorizonRuntime.farPlane();
		setUniform(dhFarPlaneUniform, lodFar);
		if (dhRenderDistanceUniform != -1) {
			GL43C.glUniform1i(dhRenderDistanceUniform, HorizonRuntime.renderDistanceChunks());
		}
		images.update();
	}

	private void setUniform(int index, float value) {
		GL43C.glUniform1f(index, value);
	}

	public void setModelPos(float x, float y, float z) {
		GL43C.glUniform3f(modelOffsetUniform, x, y, z);
	}
}
