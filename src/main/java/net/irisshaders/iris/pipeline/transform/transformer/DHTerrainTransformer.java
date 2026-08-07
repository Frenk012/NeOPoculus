package net.irisshaders.iris.pipeline.transform.transformer;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import io.github.douira.glsl_transformer.util.Type;
import net.irisshaders.iris.gl.shader.ShaderType;
import net.irisshaders.iris.pipeline.transform.parameter.Parameters;

import static net.irisshaders.iris.pipeline.transform.transformer.CommonTransformer.addIfNotExists;

public class DHTerrainTransformer {
	public static void transform(
		ASTParser t,
		TranslationUnit tree,
		Root root, Parameters parameters) {
		transform(t, tree, root, parameters, false);
	}

	/**
	 * @param textured true when the source is a pack's own {@code gbuffers_terrain}
	 *                 rather than a dh program. A dh program is vertex-coloured and
	 *                 never samples a texture, so the DH rewrite feeds
	 *                 {@code gl_MultiTexCoord0} a constant; a terrain program does
	 *                 sample {@code gtexture} and needs a real coordinate into
	 *                 Horizon's photo atlas.
	 */
	public static void transform(
		ASTParser t,
		TranslationUnit tree,
		Root root, Parameters parameters, boolean textured) {
		CommonTransformer.transform(t, tree, root, parameters, false);


		root.replaceExpressionMatches(t, CommonTransformer.glTextureMatrix0, "mat4(1.0)");
		root.replaceExpressionMatches(t, CommonTransformer.glTextureMatrix1, "mat4(1.0)");
		root.rename("gl_ProjectionMatrix", "iris_ProjectionMatrix");

		if (parameters.type.glShaderType == ShaderType.VERTEX) {
			// Alias of gl_MultiTexCoord1 on 1.15+ for OptiFine
			// See https://github.com/IrisShaders/Iris/issues/1149
			root.rename("gl_MultiTexCoord2", "gl_MultiTexCoord1");

			root.replaceReferenceExpressions(t, "gl_MultiTexCoord0",
				textured ? "vec4(_horizon_uv, 0.0, 1.0)" : "vec4(0.0, 0.0, 0.0, 1.0)");

			root.replaceReferenceExpressions(t, "gl_MultiTexCoord1",
				"vec4(_vert_tex_light_coord, 0.0, 1.0)");


			// gl_MultiTexCoord0 and gl_MultiTexCoord1 are the only valid inputs (with
			// gl_MultiTexCoord2 and gl_MultiTexCoord3 as aliases), other texture
			// coordinates are not valid inputs.
			CommonTransformer.replaceGlMultiTexCoordBounded(t, root, 4, 7);
		}

		if (textured && parameters.type.glShaderType == ShaderType.FRAGMENT) {
			injectChunkMaskDiscard(t, tree, root);
		}

		if (textured && parameters.type.glShaderType == ShaderType.VERTEX) {
			// A terrain program identifies blocks through mc_Entity.x, matching it
			// against the ids the pack declares in block.properties — Aurora tests
			// for 10000, 10004, 10008 and so on. Handing it a constant meant no
			// branch ever matched and foliage was shaded as plain terrain, which is
			// what left Aurora's tree canopies the wrong colour.
			//
			// The mesher already writes a category per quad (leaves, water, lava,
			// emissive), so the vertex shader looks the pack's own id up from a
			// small uniform table indexed by that category. A vec4 replacement is
			// deliberate: it is legal under every swizzle a pack might use,
			// including the .w that breaks a narrower substitution.
			root.replaceReferenceExpressions(t, "mc_Entity",
				"vec4(_horizon_entity, -1.0, 0.0, 1.0)");
		}

		root.rename("gl_Color", "_vert_color");

		if (parameters.type.glShaderType == ShaderType.VERTEX) {
			root.replaceReferenceExpressions(t, "gl_Normal", "_vert_normal");

		}

		// TODO: Should probably add the normal matrix as a proper uniform that's
		// computed on the CPU-side of things
		root.replaceReferenceExpressions(t, "gl_NormalMatrix",
			"iris_NormalMatrix");
		tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
			"uniform mat3 iris_NormalMatrix;");

		tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
			"uniform mat4 iris_ModelViewMatrixInverse;");

		tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
			"uniform mat4 iris_ProjectionMatrixInverse;");

		// TODO: All of the transformed variants of the input matrices, preferably
		// computed on the CPU side...
		root.rename("gl_ModelViewMatrix", "iris_ModelViewMatrix");
		root.rename("gl_ModelViewMatrixInverse", "iris_ModelViewMatrixInverse");
		root.rename("gl_ProjectionMatrixInverse", "iris_ProjectionMatrixInverse");

		if (parameters.type.glShaderType == ShaderType.VERTEX) {
			// TODO: Vaporwave-Shaderpack expects that vertex positions will be aligned to
			// chunks.
			if (root.identifierIndex.has("ftransform")) {
				tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_FUNCTIONS,
					"vec4 ftransform() { return gl_ModelViewProjectionMatrix * gl_Vertex; }");
			}
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"uniform mat4 iris_ProjectionMatrix;",
				"uniform mat4 iris_ModelViewMatrix;",
				// _draw_translation replaced with Chunks[_draw_id].offset.xyz
				"vec4 getVertexPosition() { return vec4(modelOffset + _vert_position, 1.0); }");
			root.replaceReferenceExpressions(t, "gl_Vertex", "getVertexPosition()");

			// inject here so that _vert_position is available to the above. (injections
			// inject in reverse order if performed piece-wise but in correct order if
			// performed as an array of injections)
			injectVertInit(t, tree, root, parameters, textured);
		} else {
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"uniform mat4 iris_ModelViewMatrix;",
				"uniform mat4 iris_ProjectionMatrix;");
		}

		root.replaceReferenceExpressions(t, "gl_ModelViewProjectionMatrix",
			"(iris_ProjectionMatrix * iris_ModelViewMatrix)");

		CommonTransformer.applyIntelHd4000Workaround(root);
	}

	public static void injectVertInit(
		ASTParser t,
		TranslationUnit tree,
		Root root,
		Parameters parameters) {
		injectVertInit(t, tree, root, parameters, false);
	}

	public static void injectVertInit(
		ASTParser t,
		TranslationUnit tree,
		Root root,
		Parameters parameters,
		boolean textured) {
		if (textured) {
			injectAtlasUv(t, tree, root);
		}
		tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_FUNCTIONS,
			// translated from sodium's chunk_vertex.glsl
			"vec3 _vert_position;",
			"vec2 _vert_tex_light_coord;",
			"int dhMaterialId;",
			"vec4 _vert_color;",
			"vec3 _vert_normal;",
			"uniform float mircoOffset;",
			"uniform vec3 modelOffset;",
			"const vec3 irisNormals[6] = vec3[](vec3(0,-1,0),vec3(0,1,0),vec3(0,0,-1),vec3(0,0,1),vec3(-1,0,0),vec3(1,0,0));",
			"void _vert_init() {" +
				"    uint meta = vPosition.a;\n" +
				"uint mirco = (meta & 0xFF00u) >> 8u; // mirco offset which is a xyz 2bit value\n" +
				"    // 0b00 = no offset\n" +
				"    // 0b01 = positive offset\n" +
				"    // 0b11 = negative offset\n" +
				"    // format is: 0b00zzyyxx\n" +
				"    float mx = (mirco & 1u)!=0u ? mircoOffset : 0.0;\n" +
				"    mx = (mirco & 2u)!=0u ? -mx : mx;\n" +
				"    float my = (mirco & 4u)!=0u ? mircoOffset : 0.0;\n" +
				"    my = (mirco & 8u)!=0u ? -my : my;\n" +
				"    float mz = (mirco & 16u)!=0u ? mircoOffset : 0.0;\n" +
				"    mz = (mirco & 32u)!=0u ? -mz : mz;\n" +
				"        uint lights = meta & 0xFFu;\n" +
				// irisPositionScale converts the vertex position into blocks: 1.0 for
				// the classic engine and the DH mod, 1/16 for the voxel engine, whose
				// positions are in sixteenths so partial shapes (slabs, fences) can be
				// expressed. The micro-offset is applied AFTER the scale so it stays
				// the same fraction of a block on both. Every consumer must set this
				// uniform explicitly: a GLSL uniform defaults to 0.0, and an unset one
				// would collapse every vertex onto the model offset.
				"_vert_position = vec3(vPosition.xyz) * irisPositionScale + vec3(mx, 0, mz);" +
				// Voxel cross-plant quads carry normal index 6/7; clamp so they read as
				// up-facing instead of indexing a six-entry array out of bounds. A no-op
				// for the classic engine and the DH mod, which only ever emit 0-5.
				"_vert_normal = irisNormals[irisExtra.y < 6u ? irisExtra.y : 1u];" +
				"dhMaterialId = int(irisExtra.x);" +
				"_vert_tex_light_coord = vec2((float(lights/16u)+0.5) / 16.0, (mod(float(lights), 16.0)+0.5) / 16.0);" +
				// A pack multiplies its texture by the vertex colour, the way vanilla
				// terrain applies a biome tint to an untinted sprite. Horizon's
				// photos are already baked per (state, biome), so handing over the
				// block's colour as well applies the tint twice — saturated greens,
				// wrong water. The built-in shader has the same rule written the
				// other way round: with a baked slot it does `base = tex.rgb`,
				// replacing the colour rather than multiplying it, and falls back to
				// the flat colour only when no bake has landed (slot 0). White here
				// means "take the photo as it is"; the alpha still carries
				// translucency.
				(textured
					? "_horizon_relPos = modelOffset + _vert_position;"
					+ "_vert_color = irisTexInfo.x != 0u ? vec4(1.0, 1.0, 1.0, iris_color.a) : iris_color; }"
					: "_vert_color = iris_color; }"));
		addIfNotExists(root, t, tree, "irisPositionScale", Type.FLOAT32, StorageQualifier.StorageType.UNIFORM);
		addIfNotExists(root, t, tree, "iris_color", Type.F32VEC4, StorageQualifier.StorageType.IN);
		addIfNotExists(root, t, tree, "vPosition", Type.U32VEC4, StorageQualifier.StorageType.IN);
		addIfNotExists(root, t, tree, "irisExtra", Type.U32VEC4, StorageQualifier.StorageType.IN);
		tree.prependMainFunctionBody(t, "_vert_init();");
		if (textured) {
			// Runs before _vert_init(); the two are independent.
			tree.prependMainFunctionBody(t, "_horizon_uv_init();");
		}
	}

	/**
	 * Gives a terrain program a texture coordinate into Horizon's photo atlas.
	 *
	 * <p>One atlas slot is stretched across one merged quad rather than tiled per
	 * cell. Tiling is what the built-in shader does, with a fragment-side
	 * {@code fract}, but a pack computes its own texcoord varying in its own
	 * vertex shader and we cannot reach inside it — and linear interpolation of a
	 * per-vertex coordinate cannot produce a {@code fract}. Since quads are
	 * greedy-merged up to {@code MERGE_CAP} cells, the worst case is a 16x16 plate
	 * showing one smeared sprite, which lands about where the flat fallback colour
	 * already was. Everything else the pack does — its own lighting, shadows, fog
	 * and normals — is a clear gain over drawing no distant terrain at all.
	 *
	 * <p>The corner index needs no extra vertex data: the shared index buffer
	 * emits {@code 4q + {0,1,2,2,3,0}} and every quad emitter writes its four
	 * vertices in the ring order (0,0) (1,0) (1,1) (0,1), so the low two bits of
	 * {@code gl_VertexID} are the corner.
	 */
	/**
	 * Cuts distant terrain wherever a real chunk is loaded.
	 *
	 * <p>Both other paths already have this and only the terrain path lacked it.
	 * The built-in shader samples the same per-chunk coverage mask; a dh program
	 * does it a different way, discarding anything nearer than {@code far},
	 * because it knows it is drawing distant terrain. A gbuffers_terrain program
	 * knows nothing of the sort — to it this is ordinary geometry — so with no
	 * cutoff the LOD drew straight over the loaded world: smeared ground where
	 * real block textures should be, and slabs of distant terrain standing up
	 * through it.
	 *
	 * <p>Dithered against a hash of the fragment coordinate rather than cut hard,
	 * so the boundary breaks up along chunk edges instead of showing a seam.
	 */
	private static void injectChunkMaskDiscard(ASTParser t, TranslationUnit tree, Root root) {
		tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_FUNCTIONS,
			"in vec3 _horizon_relPos;",
			"uniform sampler2D horizon_chunkMask;",
			"uniform vec2 horizon_maskRel;",
			"uniform float horizon_maskTexels;",
			"void _horizon_mask_cut() {" +
				"    if (horizon_maskTexels <= 0.0) return;\n" +
				"    vec2 muv = (_horizon_relPos.xz / 16.0 + horizon_maskRel) / horizon_maskTexels;\n" +
				"    if (muv.x <= 0.0 || muv.x >= 1.0 || muv.y <= 0.0 || muv.y >= 1.0) return;\n" +
				"    float covered = texture(horizon_chunkMask, muv).r;\n" +
				"    float n = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));\n" +
				"    if (covered > mix(0.3, 0.7, n)) discard; }");
		tree.prependMainFunctionBody(t, "_horizon_mask_cut();");
	}

	private static void injectAtlasUv(ASTParser t, TranslationUnit tree, Root root) {
		tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_FUNCTIONS,
			"vec2 _horizon_uv;",
			"out vec3 _horizon_relPos;",
			"float _horizon_entity;",
			// 16 entries: the material byte the mesher writes is a small enum, and
			// the clamp keeps a malformed one from reading past the array.
			"uniform float horizon_entityIds[16];",
			"void _horizon_uv_init() {" +
				"    _horizon_entity = horizon_entityIds[irisExtra.x < 16u ? irisExtra.x : 0u];\n" +
				"    float spr = max(horizon_atlasParams.x, 1.0);\n" +
				"    float sz = horizon_atlasParams.y;\n" +
				"    float slot = float(irisTexInfo.x);\n" +
				"    vec2 origin = vec2(mod(slot, spr), floor(slot / spr)) * sz;\n" +
				"    uint corner = uint(gl_VertexID) & 3u;\n" +
				"    vec2 c = vec2((corner == 1u || corner == 2u) ? 1.0 : 0.0,\n" +
				"                  (corner == 2u || corner == 3u) ? 1.0 : 0.0);\n" +
				// Half a texel of a 16px slot, so bilinear filtering at the slot
				// edge cannot bleed in the neighbouring block's photo.
				"    float inset = sz * 0.03125;\n" +
				"    _horizon_uv = origin + inset + c * (sz - 2.0 * inset); }");
		addIfNotExists(root, t, tree, "horizon_atlasParams", Type.F32VEC2, StorageQualifier.StorageType.UNIFORM);
		addIfNotExists(root, t, tree, "irisTexInfo", Type.U32VEC2, StorageQualifier.StorageType.IN);
	}
}
