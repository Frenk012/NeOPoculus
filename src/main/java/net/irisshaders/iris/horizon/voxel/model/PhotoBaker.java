package net.irisshaders.iris.horizon.voxel.model;

import com.mojang.blaze3d.platform.NativeImage;
import net.irisshaders.iris.mixin.texture.SpriteContentsAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;

/**
 * Bakes one block face into a 16x16 RGBA "photo" for the atlas (M4). Rather than
 * software-rasterizing the 3D model, it reads the face quad's sprite pixels
 * directly (via {@link SpriteContentsAccessor}), downsamples to 16x16, and
 * multiplies in the biome tint for tinted quads so grass, foliage and water are
 * coloured for the actual biome being baked (a swamp's murky grass differs from
 * a plains' bright grass). The tint is resolved through a {@link BiomeTintGetter}
 * so vanilla's own per-block classification (grass / foliage / water) is reused.
 * Exact for full cubes — the vast majority of terrain — and a reasonable
 * approximation for non-cube blocks; a true 3D bake is a later refinement.
 *
 * <p>Runs on the render/client thread (the vanilla model + sprite APIs are not
 * thread-safe). Output ints are {@code 0xRRGGBBAA}, row-major {@code y*16 + x}.
 */
public final class PhotoBaker {
	private static final int OUT = 16;
	private static final RandomSource RANDOM = RandomSource.create(42L);
	/** Fixed position for tint queries; only its X/Z matter (swamp grass noise). */
	private static final BlockPos TINT_POS = new BlockPos(0, 64, 0);

	private PhotoBaker() {
	}

	/**
	 * A baked face photo and whether it was biome-tinted. {@code tinted} drives
	 * the metadata tier: untinted faces are cached once and shared by every
	 * biome, tinted faces are cached per biome. {@code photo} is null when the
	 * face has no usable texture.
	 */
	public record Baked(int[] photo, boolean tinted) {
	}

	/** DH face order (VoxelConstants) -> vanilla Direction. */
	static final Direction[] FACE_DIR = {
		Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
	};

	/** @return the baked photo for {@code state}'s {@code face} in {@code biome}, or null if it has no usable texture. */
	public static Baked bakeFace(BlockState state, Biome biome, int face) {
		try {
			Minecraft mc = Minecraft.getInstance();
			BlockAndTintGetter tintGetter =
				(biome != null && mc.level != null) ? new BiomeTintGetter(mc.level, biome) : null;
			BakedModel model = mc.getBlockRenderer().getBlockModel(state);
			Direction dir = FACE_DIR[face];
			if (model != mc.getModelManager().getMissingModel()) {
				Baked composited = compositeFace(mc, model, state, dir, tintGetter);
				if (composited != null) {
					return composited;
				}
			}
			// Fluids (water/lava) render with no baked model.
			return bakeFluid(mc, state, biome);
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * Composites every quad facing {@code dir} (culled + general) in draw order,
	 * tinting only the tinted quads. This gets grass_block sides right: the
	 * untinted dirt base first, then the biome-tinted grass overlay over it,
	 * rather than one quad forcing the whole face all-green or all-dirt.
	 */
	private static Baked compositeFace(Minecraft mc, BakedModel model, BlockState state, Direction dir,
									   BlockAndTintGetter tintGetter) {
		java.util.List<BakedQuad> quads = new java.util.ArrayList<>(model.getQuads(state, dir, RANDOM));
		for (BakedQuad q : model.getQuads(state, null, RANDOM)) {
			if (q.getDirection() == dir) {
				quads.add(q);
			}
		}
		if (quads.isEmpty()) {
			// Cross models (grass, flowers, saplings) carry diagonal quads whose
			// direction matches no cube face, so a per-direction filter finds
			// nothing. Take every quad instead: the sprite is what the cross
			// silhouette is drawn from, and the atlas dedups the six identical
			// results into one slot.
			quads.addAll(model.getQuads(state, null, RANDOM));
		}
		if (quads.isEmpty()) {
			return null;
		}
		int[] acc = new int[16 * 16]; // 0 = transparent
		boolean any = false;
		boolean tinted = false;
		for (BakedQuad q : quads) {
			TextureAtlasSprite sprite = q.getSprite();
			NativeImage image = ((SpriteContentsAccessor) sprite.contents()).getOriginalImage();
			int[] layer = downsample(image, sprite.contents().width(), sprite.contents().height());
			if (layer == null) {
				continue;
			}
			if (q.isTinted()) {
				applyTint(layer, tintColor(mc, state, q.getTintIndex(), tintGetter));
				tinted = true;
			}
			over(acc, layer);
			any = true;
		}
		return any ? new Baked(acc, tinted) : null;
	}

	/** Src-over composite of {@code src} onto {@code dst}, both {@code 0xRRGGBBAA}. */
	private static void over(int[] dst, int[] src) {
		for (int i = 0; i < dst.length; i++) {
			int s = src[i];
			int sa = s & 0xFF;
			if (sa == 0) {
				continue;
			}
			int d = dst[i];
			int da = d & 0xFF;
			int outA = sa + da * (255 - sa) / 255;
			if (outA == 0) {
				dst[i] = 0;
				continue;
			}
			int sr = (s >>> 24) & 0xFF, sg = (s >>> 16) & 0xFF, sb = (s >>> 8) & 0xFF;
			int dr = (d >>> 24) & 0xFF, dg = (d >>> 16) & 0xFF, db = (d >>> 8) & 0xFF;
			int keep = da * (255 - sa) / 255;
			int r = (sr * sa + dr * keep) / outA;
			int g = (sg * sa + dg * keep) / outA;
			int b = (sb * sa + db * keep) / outA;
			dst[i] = (r << 24) | (g << 16) | (b << 8) | outA;
		}
	}

	/** Bakes a fluid's still texture (water/lava have no baked model), tinted so water reads its biome color. */
	private static Baked bakeFluid(Minecraft mc, BlockState state, Biome biome) {
		FluidState fluid = state.getFluidState();
		if (fluid.isEmpty()) {
			return null;
		}
		try {
			IClientFluidTypeExtensions ext = IClientFluidTypeExtensions.of(fluid);
			ResourceLocation still = ext.getStillTexture();
			if (still == null) {
				return null;
			}
			TextureAtlasSprite sprite = mc.getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(still);
			NativeImage image = ((SpriteContentsAccessor) sprite.contents()).getOriginalImage();
			int[] photo = downsample(image, sprite.contents().width(), sprite.contents().height());
			if (photo == null) {
				return null;
			}
			// Water's still texture is greyscale and biome-tinted; use the baked
			// biome's water color (swamp/cold/ocean differ). Other fluids (lava)
			// carry their own color, so leave them untinted.
			boolean water = fluid.is(FluidTags.WATER);
			if (water) {
				applyTint(photo, biome != null ? biome.getWaterColor() : 0x3F76E4);
			}
			return new Baked(photo, water);
		} catch (Throwable t) {
			return null;
		}
	}

	/** Biome tint for a tinted quad via {@code tintGetter} (or the default when none), {@code 0xRRGGBB}, or -1 if none. */
	private static int tintColor(Minecraft mc, BlockState state, int tintIndex, BlockAndTintGetter tintGetter) {
		try {
			int idx = tintIndex >= 0 ? tintIndex : 0;
			if (tintGetter != null) {
				return mc.getBlockColors().getColor(state, tintGetter, TINT_POS, idx);
			}
			return mc.getBlockColors().getColor(state, null, null, idx);
		} catch (Throwable t) {
			return -1;
		}
	}

	private static void applyTint(int[] photo, int tint) {
		if (tint == -1) {
			return;
		}
		int tr = (tint >> 16) & 0xFF, tg = (tint >> 8) & 0xFF, tb = tint & 0xFF;
		for (int i = 0; i < photo.length; i++) {
			int p = photo[i];
			int r = ((p >>> 24) & 0xFF) * tr / 255;
			int g = ((p >>> 16) & 0xFF) * tg / 255;
			int b = ((p >>> 8) & 0xFF) * tb / 255;
			photo[i] = (r << 24) | (g << 16) | (b << 8) | (p & 0xFF);
		}
	}

	/**
	 * Box-downsamples the sprite's first frame ({@code fw x fh}) to 16x16,
	 * alpha-weighted, output {@code 0xRRGGBBAA}. Returns null if fully
	 * transparent (no usable texture).
	 */
	private static int[] downsample(NativeImage image, int fw, int fh) {
		int w = Math.min(fw, image.getWidth());
		int h = Math.min(fh, image.getHeight());
		if (w <= 0 || h <= 0) {
			return null;
		}
		int[] out = new int[OUT * OUT];
		int total = 0;
		for (int oy = 0; oy < OUT; oy++) {
			for (int ox = 0; ox < OUT; ox++) {
				int x0 = ox * w / OUT, x1 = Math.max(x0 + 1, (ox + 1) * w / OUT);
				int y0 = oy * h / OUT, y1 = Math.max(y0 + 1, (oy + 1) * h / OUT);
				long r = 0, g = 0, b = 0, a = 0;
				int n = 0;
				for (int y = y0; y < y1; y++) {
					for (int x = x0; x < x1; x++) {
						int abgr = image.getPixelRGBA(x, y);
						int pa = (abgr >>> 24) & 0xFF;
						r += (abgr & 0xFF) * pa;
						g += ((abgr >>> 8) & 0xFF) * pa;
						b += ((abgr >>> 16) & 0xFF) * pa;
						a += pa;
						n++;
					}
				}
				int rr, gg, bb, aa;
				if (a > 0) {
					rr = (int) (r / a);
					gg = (int) (g / a);
					bb = (int) (b / a);
					aa = (int) (a / n);
					total += aa;
				} else {
					rr = gg = bb = aa = 0;
				}
				out[oy * OUT + ox] = (rr << 24) | (gg << 16) | (bb << 8) | aa;
			}
		}
		return total > 0 ? out : null;
	}
}
