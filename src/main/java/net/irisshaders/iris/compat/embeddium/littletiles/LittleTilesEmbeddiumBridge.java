package net.irisshaders.iris.compat.embeddium.littletiles;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.embeddedt.embeddium.api.render.chunk.BlockRenderContext;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflection bridge: bakes LittleTiles tile geometry into BakedQuads for injection into
 * Embeddium's native chunk render flow (see MixinBlockRendererSpike). NeOPoculus stays
 * build-independent from LittleTiles/CreativeCore — all access is reflective and the bridge
 * self-disables if LittleTiles is absent or its API shifts.
 *
 * Under Embeddium, LittleTiles' own RenderingThread never bakes these blocks (its chunk
 * triggers are dead), so we replicate its bake loop: build the render boxes, then for each
 * box+facing call RenderBox#getBakedQuad against a fake one-block level (exactly what
 * RenderingThread does). Results are cached per block; on a tile edit LittleTiles repopulates
 * its box cache and we rebuild.
 *
 * Lives OUTSIDE the mixin package on purpose: Mixin reserves its packages.
 */
public final class LittleTilesEmbeddiumBridge {

	private static final boolean OK;

	private static Class<?> BLOCK_TILE;
	private static Field BE_RENDER;                 // BETiles.render -> BERenderManager
	private static Method RM_GET_RENDERING_BOXES;   // BERenderManager.getRenderingBoxes(RenderingBlockContext) -> Int2ObjectMap
	private static Constructor<?> CTX_CTOR;         // RenderingBlockContext(BETiles, boolean, long, RenderingLevelHandler)
	private static Object LEVEL_HANDLER_VANILLA;
	private static Method LH_SECTION_POS;
	private static Method CLML_TUPLES;
	private static Method CLML_IS_EMPTY;
	private static Field LRB_STATE;                 // LittleRenderBox.state
	private static Method LRB_GET_OFFSET;           // LittleRenderBox.getOffset() -> BlockPos
	private static Method RB_SHOULD_RENDER_FACE;    // RenderBox.shouldRenderFace(Facing)
	private static Method RB_GET_BAKED_QUAD;        // RenderBox.getBakedQuad(...) -> List<BakedQuad>
	private static Constructor<?> QGC_CTOR;         // QuadGeneratorContext()
	private static Constructor<?> LAF_CTOR;         // LevelAccessorFake()
	private static Method LAF_SET;                  // LevelAccessorFake.set(Level, BlockPos, BlockState)
	private static Object[] FACING_VALUES;          // Facing.VALUES

	static {
		boolean ok = false;
		try {
			ClassLoader cl = LittleTilesEmbeddiumBridge.class.getClassLoader();
			BLOCK_TILE = Class.forName("team.creative.littletiles.common.block.mc.BlockTile", false, cl);
			Class<?> beTiles = Class.forName("team.creative.littletiles.common.block.entity.BETiles", false, cl);
			Class<?> rm = Class.forName("team.creative.littletiles.client.render.block.BERenderManager", false, cl);
			Class<?> ctx = Class.forName("team.creative.littletiles.client.render.cache.build.RenderingBlockContext", false, cl);
			Class<?> lh = Class.forName("team.creative.littletiles.client.render.cache.build.RenderingLevelHandler", false, cl);
			Class<?> clml = Class.forName("team.creative.creativecore.common.util.type.map.ChunkLayerMapList", false, cl);
			Class<?> renderBox = Class.forName("team.creative.creativecore.client.render.box.RenderBox", false, cl);
			Class<?> littleRenderBox = Class.forName("team.creative.littletiles.client.render.tile.LittleRenderBox", false, cl);
			Class<?> facing = Class.forName("team.creative.creativecore.common.util.math.base.Facing", false, cl);
			Class<?> qgc = Class.forName("team.creative.creativecore.client.render.box.QuadGeneratorContext", false, cl);
			Class<?> laf = Class.forName("team.creative.creativecore.common.level.LevelAccessorFake", false, cl);

			BE_RENDER = beTiles.getField("render");
			RM_GET_RENDERING_BOXES = rm.getMethod("getRenderingBoxes", ctx);
			CTX_CTOR = ctx.getConstructor(beTiles, boolean.class, long.class, lh);
			LEVEL_HANDLER_VANILLA = lh.getField("VANILLA").get(null);
			LH_SECTION_POS = lh.getMethod("sectionPos", beTiles);
			CLML_TUPLES = clml.getMethod("tuples");
			CLML_IS_EMPTY = clml.getMethod("isEmpty");
			LRB_STATE = littleRenderBox.getField("state");
			LRB_GET_OFFSET = littleRenderBox.getMethod("getOffset");
			RB_SHOULD_RENDER_FACE = renderBox.getMethod("shouldRenderFace", facing);
			RB_GET_BAKED_QUAD = renderBox.getMethod("getBakedQuad", qgc, LevelAccessor.class, BlockPos.class,
				BlockPos.class, BlockState.class, BakedModel.class, ModelData.class, facing, RenderType.class,
				RandomSource.class, boolean.class, int.class);
			QGC_CTOR = qgc.getConstructor();
			LAF_CTOR = laf.getConstructor();
			LAF_SET = laf.getMethod("set", Level.class, BlockPos.class, BlockState.class);
			FACING_VALUES = (Object[]) facing.getField("VALUES").get(null);

			ok = true;
		} catch (Throwable t) {
			System.out.println("[NeOPoculus] LittleTiles-Embeddium bridge disabled: " + t);
		}
		OK = ok;
	}

	private LittleTilesEmbeddiumBridge() {}

	public static boolean isLittleTilesBlock(BlockState state) {
		return OK && BLOCK_TILE.isInstance(state.getBlock());
	}

	/** Collect the tile BakedQuads for the given block + render layer, or null if none/unavailable. */
	@SuppressWarnings("unchecked")
	public static List<BakedQuad> collectQuads(BlockRenderContext ctx) {
		if (!OK) return null;
		try {
			BlockPos pos = ctx.pos();

			BlockEntity be = ctx.localSlice().getBlockEntity(pos);
			if (be == null || !BE_RENDER.getDeclaringClass().isInstance(be)) return null;
			Object rm = BE_RENDER.get(be);
			if (rm == null) return null;
			Level level = be.getLevel();
			if (level == null) return null;

			// Build the geometry boxes (LittleTiles' own trigger is dead under Embeddium).
			long sectionPos = (Long) LH_SECTION_POS.invoke(LEVEL_HANDLER_VANILLA, be);
			Object renderCtx = CTX_CTOR.newInstance(be, false, sectionPos, LEVEL_HANDLER_VANILLA);
			Map<Integer, ?> boxes = (Map<Integer, ?>) RM_GET_RENDERING_BOXES.invoke(rm, renderCtx);
			if (boxes == null || boxes.isEmpty()) return null;

			// Bake every box+facing exactly as LittleTiles' RenderingThread does.
			Object qgc = QGC_CTOR.newInstance();
			Object fake = LAF_CTOR.newInstance();
			RandomSource random = RandomSource.create();
			var blockRenderer = Minecraft.getInstance().getBlockRenderer();

			Map<RenderType, List<BakedQuad>> perLayer = new HashMap<>();
			for (Object layerList : boxes.values()) {
				if (layerList == null || (Boolean) CLML_IS_EMPTY.invoke(layerList)) continue;
				for (Object tuple : (Iterable<?>) CLML_TUPLES.invoke(layerList)) {
					Map.Entry<?, ?> entry = (Map.Entry<?, ?>) tuple;
					RenderType layer = (RenderType) entry.getKey();
					List<BakedQuad> dst = perLayer.computeIfAbsent(layer, k -> new ArrayList<>());
					for (Object box : (Collection<?>) entry.getValue()) {
						BlockState bstate = (BlockState) LRB_STATE.get(box);
						BlockPos offset = (BlockPos) LRB_GET_OFFSET.invoke(box);
						LAF_SET.invoke(fake, level, pos, bstate);
						random.setSeed(bstate.getSeed(pos));
						BakedModel model = blockRenderer.getBlockModel(bstate);
						for (Object f : FACING_VALUES) {
							if (!(Boolean) RB_SHOULD_RENDER_FACE.invoke(box, f)) continue;
							Object quads = RB_GET_BAKED_QUAD.invoke(box, qgc, fake, pos, offset, bstate,
								model, ModelData.EMPTY, f, layer, random, true, -1);
							addQuads(dst, quads);
						}
					}
				}
			}

			perLayer.values().removeIf(List::isEmpty);
			if (perLayer.isEmpty()) return null;
			return perLayer.get(ctx.renderLayer());
		} catch (Throwable t) {
			return null;
		}
	}

	private static void addQuads(List<BakedQuad> out, Object q) {
		if (q == null) return;
		if (q instanceof BakedQuad bq) {
			out.add(bq);
		} else if (q instanceof Iterable<?> it) {
			for (Object e : it) if (e instanceof BakedQuad bq) out.add(bq);
		} else if (q instanceof Object[] arr) {
			for (Object e : arr) if (e instanceof BakedQuad bq) out.add(bq);
		}
	}
}
