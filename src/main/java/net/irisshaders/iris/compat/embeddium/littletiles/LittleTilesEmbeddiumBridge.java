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
	private static Method RM_ERASE_BOX_CACHE;       // BERenderManager.eraseBoxCache() -> forces a fresh rebuild
	private static Method RM_BEFORE_BUILDING;       // BERenderManager.beforeBuilding(RenderingBlockContext) -> recomputes outside faces
	private static Method RM_CACHED_BOXES;          // BERenderManager.cachedBoxes() -> Int2ObjectMap
	private static Field RM_NEIGHBOUR_CHANGED;      // BERenderManager.neighbourChanged (private boolean)
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
	private static Field RM_BE;                     // BERenderManager.be -> BETiles (private)
	private static Method LR_SECTION_DIRTY_NEIGHBORS; // Embeddium LevelRenderer.setSectionDirtyWithNeighbors(int,int,int)

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
			RM_ERASE_BOX_CACHE = rm.getMethod("eraseBoxCache");
			RM_BEFORE_BUILDING = rm.getMethod("beforeBuilding", ctx);
			RM_CACHED_BOXES = rm.getMethod("cachedBoxes");
			RM_NEIGHBOUR_CHANGED = rm.getDeclaredField("neighbourChanged");
			RM_NEIGHBOUR_CHANGED.setAccessible(true);
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
			RM_BE = rm.getDeclaredField("be");
			RM_BE.setAccessible(true);

			ok = true;
		} catch (Throwable t) {
			System.out.println("[NeOPoculus] LittleTiles-Embeddium bridge disabled: " + t);
		}
		OK = ok;

		// Embeddium adds this to LevelRenderer; marks a section + its 6 neighbours dirty. Optional.
		try {
			LR_SECTION_DIRTY_NEIGHBORS = Class.forName("net.minecraft.client.renderer.LevelRenderer")
				.getMethod("setSectionDirtyWithNeighbors", int.class, int.class, int.class);
		} catch (Throwable t) {
			LR_SECTION_DIRTY_NEIGHBORS = null;
		}
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
			// getRenderingBoxes short-circuits on a cached boxCache, which goes stale when a
			// neighbour changes — erase it first so every rebuild reflects current neighbour state
			// (this is what kills the "neighbour face stays invisible after breaking a bit" bug).
			RM_ERASE_BOX_CACHE.invoke(rm);
			long sectionPos = (Long) LH_SECTION_POS.invoke(LEVEL_HANDLER_VANILLA, be);
			Object renderCtx = CTX_CTOR.newInstance(be, false, sectionPos, LEVEL_HANDLER_VANILLA);
			RM_GET_RENDERING_BOXES.invoke(rm, renderCtx);
			// Full-block faces shared with a neighbour are cached on the LittleBox and NOT recomputed
			// by getRenderingBoxes — only by beforeBuilding when neighbourChanged. Force it so a face
			// hidden by a now-broken neighbour bit becomes visible (this is the deterministic
			// "single complete face never updates" bug).
			RM_NEIGHBOUR_CHANGED.setBoolean(rm, true);
			RM_BEFORE_BUILDING.invoke(rm, renderCtx);
			Map<Integer, ?> boxes = (Map<Integer, ?>) RM_CACHED_BOXES.invoke(rm);
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

	/**
	 * Fired when a LittleTiles block's render data changes (hook on BERenderManager.sectionUpdate).
	 * Marks the 3x3x3 block region around the change dirty so Embeddium rebuilds the neighbouring
	 * sections too — neighbour tile faces culled against a now-broken bit get recomputed immediately
	 * instead of lingering invisible. No caching here: the bake itself stays re-run-every-build.
	 */
	public static void onTileChange(Object renderManager) {
		if (!OK) return;
		try {
			BlockEntity be = (BlockEntity) RM_BE.get(renderManager);
			if (be == null) return;
			BlockPos pos = be.getBlockPos();
			Minecraft mc = Minecraft.getInstance();
			mc.execute(() -> dirtyAround(mc, pos));
		} catch (Throwable ignored) {
		}
	}

	private static void dirtyAround(Minecraft mc, BlockPos pos) {
		if (mc.levelRenderer == null) return;
		int sx = pos.getX() >> 4, sy = pos.getY() >> 4, sz = pos.getZ() >> 4;
		try {
			if (LR_SECTION_DIRTY_NEIGHBORS != null) {
				// Marks this section AND its 6 neighbour sections — covers neighbour tiles whose
				// shared face crosses a section boundary (common in large structures).
				LR_SECTION_DIRTY_NEIGHBORS.invoke(mc.levelRenderer, sx, sy, sz);
				return;
			}
		} catch (Throwable ignored) {
		}
		mc.levelRenderer.setBlocksDirty(
			pos.getX() - 1, pos.getY() - 1, pos.getZ() - 1,
			pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
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
