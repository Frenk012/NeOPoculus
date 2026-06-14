package net.irisshaders.iris.horizon;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Builds a LOD mesh for one render region (8x8 chunks, 128x128 blocks) at a
 * given cell scale. The ground is a heightmap (top quad + skirts per cell);
 * above-ground features (tree foliage) are meshed as voxel boxes from a
 * per-column [featureBottom, featureTop) span, giving trees a real 3D crown
 * shape. Runs on a worker thread; vertex data is off-heap for zero-copy upload.
 */
public final class LodMesher {
	public static final int REGION_BLOCKS = 128;
	public static final int REGION_CHUNK_BITS = 3; // 8 chunks
	private static final int UNKNOWN = Integer.MIN_VALUE;
	private static final int WATER_COLOR = 0x3F76E4;
	/** Features (trees) are only meshed at cell scales this fine; far LOD is terrain only. */
	private static final int FEATURE_MAX_SCALE = 2;
	/**
	 * Bytes per vertex: 3 shorts position (region-local x/z and absolute y
	 * all fit in 16 bits), 2 bytes pad for alignment, 4 bytes RGBA.
	 */
	public static final int STRIDE = 12;
	/** Byte offset of the y coordinate within a vertex. */
	public static final int Y_OFFSET = 2;
	/** Byte offset of the color within a vertex. */
	public static final int COLOR_OFFSET = 8;

	public record MeshData(int regionX, int regionZ, int scale, ByteBuffer vertexData, int vertexCount) {
		public void free() {
			MemoryUtil.memFree(vertexData);
		}
	}

	/**
	 * Per-thread scratch reused across builds; the queued result is copied
	 * into an exactly-sized buffer.
	 */
	private static final class Scratch {
		final ByteBuffer vertexScratch;
		final int[] heights;
		final int[] colors;
		final int[] featTop;
		final int[] featBottom;
		final int[] featColor;

		Scratch() {
			int maxCells = (REGION_BLOCKS + 2) * (REGION_BLOCKS + 2);
			// Up to 5 ground quads + 6 feature quads per cell, worst case.
			vertexScratch = MemoryUtil.memAlloc(REGION_BLOCKS * REGION_BLOCKS * 11 * 6 * STRIDE);
			heights = new int[maxCells];
			colors = new int[maxCells];
			featTop = new int[maxCells];
			featBottom = new int[maxCells];
			featColor = new int[maxCells];
		}
	}

	private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

	private LodMesher() {
	}

	/**
	 * @param regionX region coordinate (world block x = regionX * 128)
	 * @param scale   cell edge length in blocks, power of two, <= 128
	 * @return mesh data, or null if the region holds no LOD data at all
	 */
	public static MeshData build(LodWorld world, int regionX, int regionZ, int scale, int worldMinY) {
		int n = REGION_BLOCKS / scale;

		Scratch scratch = SCRATCH.get();
		int[] heights = scratch.heights;
		int[] colors = scratch.colors;
		int[] featTop = scratch.featTop;
		int[] featBottom = scratch.featBottom;
		int[] featColor = scratch.featColor;
		boolean meshFeatures = scale <= FEATURE_MAX_SCALE;

		boolean any = false;
		for (int cz = -1; cz <= n; cz++) {
			for (int cx = -1; cx <= n; cx++) {
				int gi = (cx + 1) + (cz + 1) * (n + 2);
				int bx = regionX * REGION_BLOCKS + cx * scale;
				int bz = regionZ * REGION_BLOCKS + cz * scale;
				long sample = sampleCell(world, bx, bz, scale, worldMinY);
				if (sample == Long.MIN_VALUE) {
					heights[gi] = UNKNOWN;
				} else {
					heights[gi] = (int) (sample >> 32);
					colors[gi] = (int) sample & 0xFFFFFF;
					if (cx >= 0 && cx < n && cz >= 0 && cz < n) {
						any = true;
					}
				}

				if (meshFeatures) {
					long f = sampleFeature(world, bx, bz, scale);
					if (f == Long.MIN_VALUE) {
						featTop[gi] = (int) LodChunk.NO_FEATURE;
					} else {
						featTop[gi] = (short) (f >> 48);
						featBottom[gi] = (short) (f >> 32);
						featColor[gi] = (int) (f & 0xFFFFFF);
					}
				}
			}
		}

		if (!any) {
			return null;
		}

		ByteBuffer buf = scratch.vertexScratch;
		buf.clear();

		int verts = 0;
		for (int cz = 0; cz < n; cz++) {
			int z0 = cz * scale;
			int z1 = z0 + scale;
			int cx = 0;
			while (cx < n) {
				int gi = (cx + 1) + (cz + 1) * (n + 2);
				int h = heights[gi];
				if (h == UNKNOWN) {
					cx++;
					continue;
				}
				int topColor = shade(colors[gi], slopeShade(heights, gi, n, scale));

				int runEnd = cx + 1;
				while (runEnd < n) {
					int gj = (runEnd + 1) + (cz + 1) * (n + 2);
					if (heights[gj] != h || shade(colors[gj], slopeShade(heights, gj, n, scale)) != topColor) {
						break;
					}
					runEnd++;
				}

				int x0 = cx * scale;
				int x1 = runEnd * scale;
				verts += quad(buf, x0, h, z0, x1, h, z0, x1, h, z1, x0, h, z1, topColor);

				int giLast = runEnd + (cz + 1) * (n + 2);
				verts += skirt(buf, heights[gi - 1], h, x0, z0, x0, z1, colors[gi], 0.6f, worldMinY);
				verts += skirt(buf, heights[giLast + 1], h, x1, z1, x1, z0, colors[giLast], 0.6f, worldMinY);

				for (int k = cx; k < runEnd; k++) {
					int gk = (k + 1) + (cz + 1) * (n + 2);
					int ox = k * scale;
					verts += skirt(buf, heights[gk - (n + 2)], h, ox + scale, z0, ox, z0, colors[gk], 0.8f, worldMinY);
					verts += skirt(buf, heights[gk + (n + 2)], h, ox, z1, ox + scale, z1, colors[gk], 0.8f, worldMinY);
				}

				cx = runEnd;
			}
		}

		// Feature (tree) voxel boxes.
		if (meshFeatures) {
			for (int cz = 0; cz < n; cz++) {
				int z0 = cz * scale;
				int z1 = z0 + scale;
				for (int cx = 0; cx < n; cx++) {
					int gi = (cx + 1) + (cz + 1) * (n + 2);
					int ft = featTop[gi];
					if (ft == (int) LodChunk.NO_FEATURE) {
						continue;
					}
					int fb = featBottom[gi];
					if (fb >= ft) {
						continue;
					}
					int color = featColor[gi];
					int x0 = cx * scale;
					int x1 = x0 + scale;

					// Top (lit) and bottom (the crown's underside overhang).
					verts += quad(buf, x0, ft, z0, x1, ft, z0, x1, ft, z1, x0, ft, z1, shade(color, 1.0f));
					verts += quad(buf, x0, fb, z0, x1, fb, z0, x1, fb, z1, x0, fb, z1, shade(color, 0.55f));

					// Sides only toward neighbors without a feature (silhouette);
					// shared faces with adjacent foliage are skipped.
					if (featTop[gi - 1] == (int) LodChunk.NO_FEATURE) {
						verts += quad(buf, x0, ft, z0, x0, ft, z1, x0, fb, z1, x0, fb, z0, shade(color, 0.7f));
					}
					if (featTop[gi + 1] == (int) LodChunk.NO_FEATURE) {
						verts += quad(buf, x1, ft, z1, x1, ft, z0, x1, fb, z0, x1, fb, z1, shade(color, 0.7f));
					}
					if (featTop[gi - (n + 2)] == (int) LodChunk.NO_FEATURE) {
						verts += quad(buf, x1, ft, z0, x0, ft, z0, x0, fb, z0, x1, fb, z0, shade(color, 0.8f));
					}
					if (featTop[gi + (n + 2)] == (int) LodChunk.NO_FEATURE) {
						verts += quad(buf, x0, ft, z1, x1, ft, z1, x1, fb, z1, x0, fb, z1, shade(color, 0.8f));
					}
				}
			}
		}

		if (verts == 0) {
			return null;
		}

		// Copy from the START of the scratch buffer (memAddress0 ignores the
		// buffer position, which is at the end of the written data).
		ByteBuffer exact = MemoryUtil.memAlloc(verts * STRIDE);
		MemoryUtil.memCopy(MemoryUtil.memAddress0(buf), MemoryUtil.memAddress0(exact), (long) verts * STRIDE);
		return new MeshData(regionX, regionZ, scale, exact, verts);
	}

	/**
	 * Samples one ground cell: max surface height (water wins) and average
	 * color. Returns Long.MIN_VALUE when no column has data, else packs
	 * (height << 32 | rgb).
	 */
	private static long sampleCell(LodWorld world, int blockX, int blockZ, int scale, int worldMinY) {
		int maxH = UNKNOWN;
		long r = 0, g = 0, b = 0;
		int samples = 0;
		int waterColumns = 0;
		long waterDepth = 0;

		LodChunk cached = null;
		int cachedCx = Integer.MIN_VALUE, cachedCz = Integer.MIN_VALUE;
		int step = Math.max(1, scale / 8);

		for (int dz = 0; dz < scale; dz += step) {
			for (int dx = 0; dx < scale; dx += step) {
				int wx = blockX + dx;
				int wz = blockZ + dz;
				int cx = wx >> 4, cz = wz >> 4;
				if (cx != cachedCx || cz != cachedCz) {
					cached = world.get(cx, cz);
					cachedCx = cx;
					cachedCz = cz;
				}
				if (cached == null) {
					continue;
				}
				int index = (wx & 15) + (wz & 15) * 16;
				int terrain = cached.height[index];
				int color = cached.color[index];
				if (color == 0 && terrain <= worldMinY) {
					continue; // void column
				}
				int water = cached.waterHeight[index];
				int h = terrain;
				if (water != LodChunk.NO_WATER && water > h) {
					waterColumns++;
					waterDepth += (water - terrain);
					h = water;
				}
				if (h > maxH) {
					maxH = h;
				}
				r += (color >> 16) & 0xFF;
				g += (color >> 8) & 0xFF;
				b += color & 0xFF;
				samples++;
			}
		}

		if (samples == 0) {
			return Long.MIN_VALUE;
		}

		int rr = (int) (r / samples);
		int gg = (int) (g / samples);
		int bb = (int) (b / samples);

		if (waterColumns * 2 >= samples) {
			float depth = waterDepth / (float) waterColumns;
			float t = Math.min(0.95f, 0.55f + depth * 0.02f);
			rr = (int) (rr * (1 - t) + ((WATER_COLOR >> 16) & 0xFF) * t);
			gg = (int) (gg * (1 - t) + ((WATER_COLOR >> 8) & 0xFF) * t);
			bb = (int) (bb * (1 - t) + (WATER_COLOR & 0xFF) * t);
		}

		return ((long) maxH << 32) | ((long) ((rr << 16) | (gg << 8) | bb) & 0xFFFFFFFFL);
	}

	/**
	 * Samples the above-ground feature (tree) span of a cell: featureTop is
	 * the highest foliage, featureBottom the lowest across the cell's columns.
	 * Returns Long.MIN_VALUE when the cell has no feature, else packs
	 * (featTop << 48 | featBottom << 32 | rgb).
	 */
	private static long sampleFeature(LodWorld world, int blockX, int blockZ, int scale) {
		int maxTop = Integer.MIN_VALUE;
		int minBottom = Integer.MAX_VALUE;
		long r = 0, g = 0, b = 0;
		int samples = 0;

		LodChunk cached = null;
		int cachedCx = Integer.MIN_VALUE, cachedCz = Integer.MIN_VALUE;
		int step = Math.max(1, scale / 8);

		for (int dz = 0; dz < scale; dz += step) {
			for (int dx = 0; dx < scale; dx += step) {
				int wx = blockX + dx;
				int wz = blockZ + dz;
				int cx = wx >> 4, cz = wz >> 4;
				if (cx != cachedCx || cz != cachedCz) {
					cached = world.get(cx, cz);
					cachedCx = cx;
					cachedCz = cz;
				}
				if (cached == null) {
					continue;
				}
				int index = (wx & 15) + (wz & 15) * 16;
				int top = cached.featureTop[index];
				if (top == LodChunk.NO_FEATURE) {
					continue;
				}
				int bottom = cached.featureBottom[index];
				int color = cached.featureColor[index];
				if (top > maxTop) maxTop = top;
				if (bottom < minBottom) minBottom = bottom;
				r += (color >> 16) & 0xFF;
				g += (color >> 8) & 0xFF;
				b += color & 0xFF;
				samples++;
			}
		}

		if (samples == 0) {
			return Long.MIN_VALUE;
		}
		int rgb = ((int) (r / samples) << 16) | ((int) (g / samples) << 8) | (int) (b / samples);
		return ((long) (maxTop & 0xFFFF) << 48) | ((long) (minBottom & 0xFFFF) << 32) | (rgb & 0xFFFFFFL);
	}

	private static int skirt(ByteBuffer buf, int neighborH, int h, int x1, int z1, int x2, int z2, int baseColor, float shade, int worldMinY) {
		int bottom = neighborH == UNKNOWN ? Math.max(worldMinY, h - 32) : neighborH;
		if (bottom >= h) {
			return 0;
		}
		return quad(buf,
			x1, h, z1,
			x2, h, z2,
			x2, bottom, z2,
			x1, bottom, z1,
			shade(baseColor, shade));
	}

	/**
	 * Directional shading from the terrain gradient (light from the
	 * north-west): slopes facing the light brighten, slopes away darken.
	 */
	private static float slopeShade(int[] heights, int gi, int n, int scale) {
		int h = heights[gi];
		int hw = heights[gi - 1] == UNKNOWN ? h : heights[gi - 1];
		int he = heights[gi + 1] == UNKNOWN ? h : heights[gi + 1];
		int hn = heights[gi - (n + 2)] == UNKNOWN ? h : heights[gi - (n + 2)];
		int hs = heights[gi + (n + 2)] == UNKNOWN ? h : heights[gi + (n + 2)];
		float dx = (he - hw) / (2.0f * scale);
		float dz = (hs - hn) / (2.0f * scale);
		float shadeValue = 1.0f - dx * 0.10f - dz * 0.13f;
		return Math.clamp(shadeValue, 0.65f, 1.15f);
	}

	private static int shade(int rgb, float factor) {
		int r = Math.min(255, (int) (((rgb >> 16) & 0xFF) * factor));
		int g = Math.min(255, (int) (((rgb >> 8) & 0xFF) * factor));
		int b = Math.min(255, (int) ((rgb & 0xFF) * factor));
		return (r << 16) | (g << 8) | b;
	}

	private static int quad(ByteBuffer buf, int x1, int y1, int z1, int x2, int y2, int z2,
							int x3, int y3, int z3, int x4, int y4, int z4, int color) {
		vertex(buf, x1, y1, z1, color);
		vertex(buf, x2, y2, z2, color);
		vertex(buf, x3, y3, z3, color);
		vertex(buf, x3, y3, z3, color);
		vertex(buf, x4, y4, z4, color);
		vertex(buf, x1, y1, z1, color);
		return 6;
	}

	private static void vertex(ByteBuffer buf, int x, int y, int z, int rgb) {
		buf.putShort((short) x);
		buf.putShort((short) y);
		buf.putShort((short) z);
		buf.putShort((short) 0); // pad to a 4-byte boundary
		buf.put((byte) ((rgb >> 16) & 0xFF));
		buf.put((byte) ((rgb >> 8) & 0xFF));
		buf.put((byte) (rgb & 0xFF));
		buf.put((byte) 255);
	}
}
