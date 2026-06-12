package net.irisshaders.iris.horizon;

import com.mojang.blaze3d.platform.NativeImage;
import net.irisshaders.iris.mixin.texture.SpriteContentsAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes the on-screen color of a block column for LOD capture. Instead
 * of one flat average per block type, the top-face texture is softened into
 * an 8x8 grid and each world column samples the grid texel matching its
 * position inside the block pattern — so near LOD terrain reproduces the
 * actual texture look (slightly blurred) rather than a flat color, and the
 * transition from real chunks stays subtle. Biome tints (grass, foliage,
 * water) are applied per position. Client thread only.
 */
public final class LodColors {
	private static final int FALLBACK = 0x7F7F7F;
	private static final int GRID = 8;
	private static final RandomSource RANDOM = RandomSource.create(42L);
	// Concurrent as cheap insurance: capture is client-thread, but modded
	// event buses have been seen firing chunk events off-thread.
	private static final Map<BlockState, Entry> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

	private record Entry(int[] grid, int tintIndex) {
	}

	private LodColors() {
	}

	/** Drops all cached colors; called when resource packs reload. */
	public static void clearCache() {
		CACHE.clear();
	}

	public static int colorOf(BlockState state, ClientLevel level, BlockPos pos) {
		Entry entry = CACHE.computeIfAbsent(state, LodColors::computeEntry);
		// Map the column's position within the 16x16 block pattern onto the
		// softened texture grid, mirroring how the real texture would tile.
		int gx = (pos.getX() & 15) >> 1;
		int gz = (pos.getZ() & 15) >> 1;
		int base = entry.grid[gx + gz * GRID];
		if (entry.tintIndex < 0) {
			return base;
		}
		try {
			int tint = Minecraft.getInstance().getBlockColors().getColor(state, level, pos, entry.tintIndex);
			if (tint == -1) {
				return base;
			}
			return multiply(base, tint);
		} catch (Throwable t) {
			// Modded tint providers may assume contexts we don't have.
			return base;
		}
	}

	private static Entry computeEntry(BlockState state) {
		try {
			BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
			List<BakedQuad> quads = model.getQuads(state, Direction.UP, RANDOM);
			BakedQuad quad = quads.isEmpty() ? null : quads.get(0);
			var sprite = quad != null ? quad.getSprite() : model.getParticleIcon();
			int tintIndex = quad != null && quad.isTinted() ? quad.getTintIndex() : -1;

			NativeImage image = ((SpriteContentsAccessor) sprite.contents()).getOriginalImage();
			// Sample the first animation frame only; the strip below holds
			// the remaining frames.
			int[] grid = buildGrid(image, sprite.contents().width(), sprite.contents().height());
			if (grid == null) {
				return uniform(mapColorOf(state), -1);
			}
			return new Entry(grid, tintIndex);
		} catch (Throwable t) {
			return uniform(mapColorOf(state), -1);
		}
	}

	/**
	 * Downsamples a frame into an 8x8 grid of averaged texels: enough to
	 * keep the texture's character without the pixel noise that would make
	 * the LOD boundary stand out.
	 */
	private static int[] buildGrid(NativeImage image, int frameW, int frameH) {
		int w = Math.min(frameW, image.getWidth());
		int h = Math.min(frameH, image.getHeight());
		if (w <= 0 || h <= 0) {
			return null;
		}

		int[] grid = new int[GRID * GRID];
		long totR = 0, totG = 0, totB = 0;
		int totSamples = 0;

		for (int gy = 0; gy < GRID; gy++) {
			for (int gx = 0; gx < GRID; gx++) {
				int x0 = gx * w / GRID, x1 = Math.max(x0 + 1, (gx + 1) * w / GRID);
				int y0 = gy * h / GRID, y1 = Math.max(y0 + 1, (gy + 1) * h / GRID);
				long r = 0, g = 0, b = 0;
				int samples = 0;
				for (int y = y0; y < y1; y++) {
					for (int x = x0; x < x1; x++) {
						int abgr = image.getPixelRGBA(x, y);
						if (((abgr >> 24) & 0xFF) < 128) {
							continue;
						}
						r += abgr & 0xFF;
						g += (abgr >> 8) & 0xFF;
						b += (abgr >> 16) & 0xFF;
						samples++;
					}
				}
				if (samples > 0) {
					grid[gx + gy * GRID] = (int) ((r / samples) << 16 | (g / samples) << 8 | (b / samples));
					totR += r;
					totG += g;
					totB += b;
					totSamples += samples;
				} else {
					grid[gx + gy * GRID] = -1; // fill with average below
				}
			}
		}

		if (totSamples == 0) {
			return null;
		}
		int avg = (int) ((totR / totSamples) << 16 | (totG / totSamples) << 8 | (totB / totSamples));
		for (int i = 0; i < grid.length; i++) {
			if (grid[i] == -1) {
				grid[i] = avg;
			}
		}
		return grid;
	}

	private static Entry uniform(int color, int tintIndex) {
		int[] grid = new int[GRID * GRID];
		java.util.Arrays.fill(grid, color);
		return new Entry(grid, tintIndex);
	}

	private static int mapColorOf(BlockState state) {
		try {
			MapColor mapColor = state.getMapColor(null, null);
			return mapColor == MapColor.NONE ? FALLBACK : mapColor.col;
		} catch (Throwable t) {
			return FALLBACK;
		}
	}

	private static int multiply(int rgb, int tint) {
		int r = ((rgb >> 16) & 0xFF) * ((tint >> 16) & 0xFF) / 255;
		int g = ((rgb >> 8) & 0xFF) * ((tint >> 8) & 0xFF) / 255;
		int b = (rgb & 0xFF) * (tint & 0xFF) / 255;
		return (r << 16) | (g << 8) | b;
	}
}
