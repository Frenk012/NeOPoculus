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
	/** True when this was built from a pack's gbuffers_terrain rather than a dh program. */
	private final boolean terrainMode;
	private final String terrainProbe;
	public final int atlasParamsUniform;
	public final int entityIdsUniform;
	public final int alphaTestUniform;
	public final int fogStartUniform;
	public final int fogEndUniform;
	public final int irisFogStartUniform;
	public final int irisFogEndUniform;
	/**
	 * Vanilla terrain attributes Horizon has no data for. Bound past every
	 * attribute the voxel VAO enables (0-3) so the linker cannot place one on top
	 * of real vertex data, then pinned to a constant.
	 */
	private static final int LEGACY_ATTRIBUTE_BASE = 8;
	private static final String[] LEGACY_ATTRIBUTES = {
		"mc_Entity", "mc_midTexCoord", "at_tangent", "at_midBlock"
	};
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
							   float positionScale, boolean terrainMode, java.util.function.IntSupplier atlas) {
		this.positionScale = positionScale;
		this.terrainMode = terrainMode;
		this.bufferBlendOverrides = bufferBlendOverrides;
		id = GL43C.glCreateProgram();

		GL32.glBindAttribLocation(this.id, 0, "vPosition");
		GL32.glBindAttribLocation(this.id, 1, "iris_color");
		GL32.glBindAttribLocation(this.id, 2, "irisExtra");
		if (terrainMode) {
			GL32.glBindAttribLocation(this.id, 3, "irisTexInfo");
			// A terrain program declares vanilla attributes Horizon has no data
			// for. Rather than rewriting them in the AST — where a fixed-arity
			// substitution breaks the moment a pack declares at_midBlock as vec4
			// and reads .w, which PaintBound does — bind them to dead locations
			// and feed a constant generic value. GL hands a vec2 the .xy of it and
			// a vec4 all of it, so the arity takes care of itself.
			for (int i = 0; i < LEGACY_ATTRIBUTES.length; i++) {
				GL32.glBindAttribLocation(this.id, LEGACY_ATTRIBUTE_BASE + i, LEGACY_ATTRIBUTES[i]);
			}
		}

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
		boolean normalsTaken = false;
		boolean specularTaken = false;
		boolean atlasTaken = false;
		if (terrainMode) {
			// Registered AFTER the gbuffer samplers, and the order is the whole
			// point: both registrations queue a uniform assignment for the same
			// sampler location, and the calls are replayed in order, so the last
			// one wins. Going first meant Iris's own hasTexture=false binding — a
			// 1x1 white pixel on tex/texture/gtexture — overwrote the atlas, and
			// every LOD fragment sampled white.
			//
			// A DYNAMIC sampler, deliberately: an external one would pin gtexture
			// to unit 0 and force binding over the block atlas on the shared unit,
			// the move this codebase already documents as permanent black terrain.
			// Dynamic samplers get a free unit of their own and never touch unit 0.
			//
			// The explicit sampler object turns mipmapping off. The built-in shader
			// picks its own level with textureGrad; a pack samples with automatic
			// derivatives plus its own bias — Kappa uses
			// texture(gtexture, uv, MipBias) — and our coordinate, stretched across
			// a merged quad, drives that to coarse levels where neighbouring slots
			// of the 2048px atlas bleed together. A sampler object binds to one
			// unit only, so the built-in path keeps its mips.
			if (atlas != null) {
				atlasTaken = samplerBuilder.addDynamicSampler(TextureType.TEXTURE_2D, atlas,
					net.irisshaders.iris.gl.sampler.GlSampler.LINEAR, "tex", "texture", "gtexture");
			}
			// Same ordering reason. These otherwise point at the BLOCK atlas's PBR
			// maps, and sampling those with a photo-atlas coordinate reads whatever
			// LabPBR data sits at that spot — random normals and shininess. A flat
			// normal and zero specular say "plain surface" instead.
			normalsTaken = samplerBuilder.addDynamicSampler(HorizonIrisProgram::flatNormalTexture, "normals");
			specularTaken = samplerBuilder.addDynamicSampler(HorizonIrisProgram::zeroSpecularTexture, "specular");
		}
		customUniforms.mapholderToPass(uniformBuilder, this);
		this.uniforms = uniformBuilder.buildUniforms();
		this.customUniforms = customUniforms;
		samplers = samplerBuilder.build();
		images = builder.build();

		atlasParamsUniform = tryGetUniformLocation2("horizon_atlasParams");
		entityIdsUniform = tryGetUniformLocation2("horizon_entityIds");
		alphaTestUniform = tryGetUniformLocation2("iris_currentAlphaTest");
		fogStartUniform = tryGetUniformLocation2("fogStart");
		fogEndUniform = tryGetUniformLocation2("fogEnd");
		irisFogStartUniform = tryGetUniformLocation2("iris_FogStart");
		irisFogEndUniform = tryGetUniformLocation2("iris_FogEnd");
		this.terrainProbe = terrainMode
			? "atlas=" + atlasTaken + " normals=" + normalsTaken + " specular=" + specularTaken
			: null;
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

		if (terrainProbe != null) {
			// Which names a pack actually declares decides whether an override here
			// can do anything at all. A -1 means the pack never uses that uniform,
			// so setting it is a no-op no matter how right the value is.
			net.irisshaders.iris.Iris.logger.info("Horizon terrain program '" + name + "': "
				+ "atlasParams=" + atlasParamsUniform + " alphaTest=" + alphaTestUniform
				+ " far=" + farUniform
				+ " fogStart=" + fogStartUniform + " fogEnd=" + fogEndUniform
				+ " iris_FogStart=" + irisFogStartUniform + " iris_FogEnd=" + irisFogEndUniform
				+ " | samplers: " + terrainProbe);
		}
	}

	/** Classic engine: vertex positions are whole blocks. */
	public static HorizonIrisProgram createProgram(String name, ProgramSource source, CustomUniforms uniforms, IrisRenderingPipeline pipeline) {
		return createProgram(name, source, uniforms, pipeline, 1.0f);
	}

	/**
	 * @param positionScale blocks per unit of vertex position — 1.0 for the
	 *                      classic engine, 1/16 for the voxel engine.
	 */
	/**
	 * Builds from a pack's own {@code gbuffers_terrain} instead of a dh program,
	 * for the packs that ship no dh program at all. {@code atlas} supplies the
	 * photo atlas the pack will sample as {@code gtexture}.
	 */
	public static HorizonIrisProgram createTerrainProgram(String name, ProgramSource source, CustomUniforms uniforms,
														  IrisRenderingPipeline pipeline, float positionScale,
														  java.util.function.IntSupplier atlas) {
		return createProgram(name, source, uniforms, pipeline, positionScale, true, atlas);
	}

	public static HorizonIrisProgram createProgram(String name, ProgramSource source, CustomUniforms uniforms, IrisRenderingPipeline pipeline, float positionScale) {
		return createProgram(name, source, uniforms, pipeline, positionScale, false, null);
	}

	private static HorizonIrisProgram createProgram(String name, ProgramSource source, CustomUniforms uniforms,
													IrisRenderingPipeline pipeline, float positionScale,
													boolean terrainMode, java.util.function.IntSupplier atlas) {
		// DISTANT_HORIZONS is defined pipeline-wide via StandardMacros when
		// Horizon is active, so the pack source already resolves its dh
		// #ifdef branches. Do NOT inject a raw #define here: the DH transformer
		// rejects preprocessor directives at this stage.
		Map<PatchShaderType, String> transformed = terrainMode
			? TransformPatcher.patchHorizonTerrain(
			name,
			source.getVertexSource().orElseThrow(RuntimeException::new),
			source.getTessControlSource().orElse(null),
			source.getTessEvalSource().orElse(null),
			source.getGeometrySource().orElse(null),
			source.getFragmentSource().orElseThrow(RuntimeException::new),
			pipeline.getTextureMap())
			: TransformPatcher.patchDHTerrain(
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
		if (terrainMode) {
			// Written unconditionally, not through ShaderPrinter, which stayed
			// silent here. The patched source is the only ground truth for what a
			// pack's terrain program actually became; without it every question
			// about the result is guesswork.
			dumpPatched(name, vertex, fragment);
		}
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
			uniforms, pipeline, positionScale, terrainMode, atlas);
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

	private static void dumpPatched(String name, String vertex, String fragment) {
		try {
			java.nio.file.Path dir = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get()
				.resolve("horizon-patched");
			java.nio.file.Files.createDirectories(dir);
			String pack = net.irisshaders.iris.Iris.getCurrentPackName().replaceAll("[^A-Za-z0-9._-]", "_");
			java.nio.file.Files.writeString(dir.resolve(pack + "-" + name + ".vsh"), vertex);
			java.nio.file.Files.writeString(dir.resolve(pack + "-" + name + ".fsh"), fragment);
		} catch (Throwable t) {
			net.irisshaders.iris.Iris.logger.warn("Horizon: could not dump the patched terrain source", t);
		}
	}

	private static int flatNormal;
	private static int zeroSpecular;

	/** 1x1 flat normal, so a pack's PBR path reads "surface facing straight out". */
	private static int flatNormalTexture() {
		if (flatNormal == 0) {
			flatNormal = solidTexture(new byte[]{(byte) 128, (byte) 128, (byte) 255, (byte) 255});
		}
		return flatNormal;
	}

	/** 1x1 zero specular, so nothing distant reads as wet, metallic or emissive. */
	private static int zeroSpecularTexture() {
		if (zeroSpecular == 0) {
			zeroSpecular = solidTexture(new byte[]{0, 0, 0, 0});
		}
		return zeroSpecular;
	}

	private static int solidTexture(byte[] rgba) {
		int tex = GL43C.glGenTextures();
		int prevActive = net.irisshaders.iris.mixin.GlStateManagerAccessor.getActiveTexture();
		int prev = net.irisshaders.iris.mixin.GlStateManagerAccessor.getTEXTURES()[prevActive].binding;
		GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, tex);
		java.nio.ByteBuffer pixel = org.lwjgl.system.MemoryUtil.memAlloc(4);
		pixel.put(rgba).flip();
		GL43C.glTexImage2D(GL43C.GL_TEXTURE_2D, 0, GL43C.GL_RGBA8, 1, 1, 0,
			GL43C.GL_RGBA, GL43C.GL_UNSIGNED_BYTE, pixel);
		org.lwjgl.system.MemoryUtil.memFree(pixel);
		GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_MIN_FILTER, GL43C.GL_NEAREST);
		GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_MAG_FILTER, GL43C.GL_NEAREST);
		// Put back what the caller had bound, so building a program never changes
		// what the rest of the frame is sampling.
		GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, prev);
		return tex;
	}

	/**
	 * The pack's own block ids for each material category the mesher writes, so a
	 * terrain program can recognise foliage, water and light sources the way it
	 * does for real blocks. Resolved per pack, since block.properties is what
	 * decides the numbers.
	 */
	public void setEntityIds(float[] ids) {
		if (entityIdsUniform == -1) {
			return;
		}
		GL43C.glUniform1fv(entityIdsUniform, ids);
	}

	/** Atlas geometry for the texture coordinate: slots per row, and slot size in UV. */
	public void setAtlasParams(int slotsPerRow, int atlasSize) {
		if (atlasParamsUniform == -1 || atlasSize <= 0) {
			return;
		}
		GL43C.glUniform2f(atlasParamsUniform, slotsPerRow, 16.0f / atlasSize);
	}

	public boolean isTerrainMode() {
		return terrainMode;
	}

	public void bind() {
		GL43C.glUseProgram(id);
		if (terrainMode) {
			// Constant values for the vanilla attributes we bound to dead slots.
			// mc_Entity.x = 0 is "not a waving block", which is what disables every
			// pack's foliage displacement; y = -1 is Iris's BLOCK_RENDER_TYPE.
			GL43C.glVertexAttrib4f(LEGACY_ATTRIBUTE_BASE, 0.0f, -1.0f, 0.0f, 1.0f);
			// mc_midTexCoord: the middle of the slot, so `uv - mc_midTexCoord`
			// stays small and bounded rather than wandering across the atlas.
			GL43C.glVertexAttrib4f(LEGACY_ATTRIBUTE_BASE + 1, 0.5f, 0.5f, 0.0f, 1.0f);
			// at_tangent must not be the zero vector: packs normalize it to build a
			// TBN, and normalize(vec3(0)) is NaN, which comes out as black pixels.
			GL43C.glVertexAttrib4f(LEGACY_ATTRIBUTE_BASE + 2, 1.0f, 0.0f, 0.0f, 1.0f);
			// at_midBlock: centre, and w = 0 so packs reading it as an emissive
			// flag read "not emissive".
			GL43C.glVertexAttrib4f(LEGACY_ATTRIBUTE_BASE + 3, 0.0f, 0.0f, 0.0f, 0.0f);
		}
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
		if (terrainMode) {
			// Inverted from the dh path, and deliberately. A dh program knows it is
			// drawing LOD and fogs against dhFarPlane, so `far` must stay at the
			// vanilla distance for its near-cutoff to land in the right place. A
			// gbuffers_terrain program has no idea it is drawing LOD: it fogs
			// against `far`, so leaving that at the vanilla distance turns every
			// LOD fragment into solid fog. This cannot leak into the pack's real
			// terrain pass — that is a separate program object with its own
			// uniform storage.
			setUniform(farUniform, lodFar);
			// The cutout threshold. Iris feeds this uniform from the vanilla pass's
			// current alpha test, which says nothing about ours, so it is set here
			// after the shared update — same reason as the projection above.
			setUniform(alphaTestUniform, 0.5f);
			// Vanilla fog is handed to the program at the vanilla render distance,
			// so distant terrain arrives already saturated — Aurora drew the LOD
			// and then buried it under solid sky. Push the range out to the LOD
			// distance, fading only the last stretch, which is what the built-in
			// path has always done.
			setUniform(fogStartUniform, lodFar * 0.80f);
			setUniform(fogEndUniform, lodFar);
			setUniform(irisFogStartUniform, lodFar * 0.80f);
			setUniform(irisFogEndUniform, lodFar);
		}
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
