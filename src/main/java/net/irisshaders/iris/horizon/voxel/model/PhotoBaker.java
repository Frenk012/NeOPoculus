package net.irisshaders.iris.horizon.voxel.model;

import com.mojang.blaze3d.platform.NativeImage;
import net.irisshaders.iris.mixin.texture.SpriteContentsAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;

import java.util.List;

/**
 * Bakes one block face into a 16x16 RGBA "photo" for the atlas (M4 v1). Rather
 * than software-rasterizing the 3D model, it reads the face quad's sprite
 * pixels directly (via {@link SpriteContentsAccessor}), downsamples to 16x16,
 * and multiplies in the default (plains) biome tint for tinted quads so grass,
 * foliage and water are coloured. This is exact for full cubes — the vast
 * majority of terrain — and a reasonable approximation for non-cube blocks. A
 * true 3D bake per design-bakery-atlas.md is a later refinement.
 *
 * <p>Runs on the render/client thread (the vanilla model + sprite APIs are not
 * thread-safe). Output ints are {@code 0xRRGGBBAA}, row-major {@code y*16 + x}.
 */
public final class PhotoBaker {
	private static final int OUT = 16;
	private static final RandomSource RANDOM = RandomSource.create(42L);

	private PhotoBaker() {
	}

	/** DH face order (VoxelConstants) -> vanilla Direction. */
	static final Direction[] FACE_DIR = {
		Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
	};

	/** @return the 256-pixel RGBA photo for {@code state}'s {@code face}, or null if it has no usable texture. */
	public static int[] bakeFace(BlockState state, int face) {
		try {
			Minecraft mc = Minecraft.getInstance();
			BakedModel model = mc.getBlockRenderer().getBlockModel(state);
			Direction dir = FACE_DIR[face];
			if (model != mc.getModelManager().getMissingModel()) {
				int[] composited = compositeFace(mc, model, state, dir);
				if (composited != null) {
					return composited;
				}
			}
			// Fluids (water/lava) render with no baked model.
			return bakeFluid(mc, state);
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
	private static int[] compositeFace(Minecraft mc, BakedModel model, BlockState state, Direction dir) {
		java.util.List<BakedQuad> quads = new java.util.ArrayList<>(model.getQuads(state, dir, RANDOM));
		for (BakedQuad q : model.getQuads(state, null, RANDOM)) {
			if (q.getDirection() == dir) {
				quads.add(q);
			}
		}
		if (quads.isEmpty()) {
			return null;
		}
		int[] acc = new int[16 * 16]; // 0 = transparent
		boolean any = false;
		for (BakedQuad q : quads) {
			TextureAtlasSprite sprite = q.getSprite();
			NativeImage image = ((SpriteContentsAccessor) sprite.contents()).getOriginalImage();
			int[] layer = downsample(image, sprite.contents().width(), sprite.contents().height());
			if (layer == null) {
				continue;
			}
			if (q.isTinted()) {
				applyTint(layer, tintColor(mc, state, q.getTintIndex()));
			}
			over(acc, layer);
			any = true;
		}
		return any ? acc : null;
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

	/** Bakes a fluid's still texture (water/lava have no baked model), tinted so water reads blue. */
	private static int[] bakeFluid(Minecraft mc, BlockState state) {
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
			// The still texture is greyscale; the tint colors it. getTintColor is
			// often white (biome-driven), so fall back to the plains water color.
			int tint = ext.getTintColor() & 0xFFFFFF;
			if (tint == 0xFFFFFF) {
				tint = 0x3F76E4; // plains water
			}
			applyTint(photo, tint);
			return photo;
		} catch (Throwable t) {
			return null;
		}
	}

	/** Default (no position) tint for a tinted quad, {@code 0xRRGGBB}, or -1 if none. */
	private static int tintColor(Minecraft mc, BlockState state, int tintIndex) {
		try {
			int idx = tintIndex >= 0 ? tintIndex : 0;
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
