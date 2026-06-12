package net.irisshaders.iris.horizon;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Builds a column-style LOD mesh for one render region (8x8 chunks, 128x128
 * blocks) at a given cell scale. Each cell becomes a flat top quad plus
 * skirt quads down to lower neighbors, which is the cheapest watertight
 * representation of distant terrain. Runs entirely on a worker thread and
 * allocates the vertex data off-heap for a zero-copy GPU upload.
 */
public final class LodMesher {
	public static final int REGION_BLOCKS = 128;
	public static final int REGION_CHUNK_BITS = 3; // 8 chunks
	private static final int UNKNOWN = Integer.MIN_VALUE;
	private static final int WATER_COLOR = 0x3F76E4;
	/** Bytes per vertex: 3 floats position + 4 bytes RGBA. */
	public static final int STRIDE = 16;

	public record MeshData(int regionX, int regionZ, int scale, ByteBuffer vertexData, int vertexCount) {
		public void free() {
			MemoryUtil.memFree(vertexData);
		}
	}

	private LodMesher() {
	}

	/**
	 * @param regionX region coordinate (world block x = regionX * 128)
	 * @param scale   cell edge length in blocks, power of two, <= 128
	 * @return mesh data, or null if the region holds no LOD data at all
	 */
	public static MeshData build(LodWorld world, int regionX, int regionZ, int scale, int worldMinY) {
		int n = REGION_BLOCKS / scale;

		// Sample an (n+2)^2 grid including a one-cell border from
		// neighboring regions so skirts at region edges line up.
		int[] heights = new int[(n + 2) * (n + 2)];
		int[] colors = new int[(n + 2) * (n + 2)];

		boolean any = false;
		for (int cz = -1; cz <= n; cz++) {
			for (int cx = -1; cx <= n; cx++) {
				int gi = (cx + 1) + (cz + 1) * (n + 2);
				long sample = sampleCell(world, regionX * REGION_BLOCKS + cx * scale, regionZ * REGION_BLOCKS + cz * scale, scale, worldMinY);
				if (sample == Long.MIN_VALUE) {
					heights[gi] = UNKNOWN;
				} else {
					heights[gi] = (int) (sample >> 32);
					colors[gi] = (int) sample;
					if (cx >= 0 && cx < n && cz >= 0 && cz < n) {
						any = true;
					}
				}
			}
		}

		if (!any) {
			return null;
		}

		// Worst case: every cell emits a top plus four skirts.
		int maxVerts = n * n * 5 * 6;
		ByteBuffer buf = MemoryUtil.memAlloc(maxVerts * STRIDE);

		int verts = 0;
		float ox, oz;
		for (int cz = 0; cz < n; cz++) {
			for (int cx = 0; cx < n; cx++) {
				int gi = (cx + 1) + (cz + 1) * (n + 2);
				int h = heights[gi];
				if (h == UNKNOWN) {
					continue;
				}
				int color = colors[gi];
				ox = cx * scale;
				oz = cz * scale;
				float s = scale;

				// Top face.
				verts += quad(buf,
					ox, h, oz,
					ox + s, h, oz,
					ox + s, h, oz + s,
					ox, h, oz + s,
					shade(color, 1.0f));

				// Skirts toward lower or unknown neighbors.
				verts += skirt(buf, heights[gi - 1], h, ox, oz, ox, oz + s, color, 0.6f, worldMinY);             // -X
				verts += skirt(buf, heights[gi + 1], h, ox + s, oz + s, ox + s, oz, color, 0.6f, worldMinY);     // +X
				verts += skirt(buf, heights[gi - (n + 2)], h, ox + s, oz, ox, oz, color, 0.8f, worldMinY);       // -Z
				verts += skirt(buf, heights[gi + (n + 2)], h, ox, oz + s, ox + s, oz + s, color, 0.8f, worldMinY); // +Z
			}
		}

		if (verts == 0) {
			MemoryUtil.memFree(buf);
			return null;
		}

		buf.flip();
		return new MeshData(regionX, regionZ, scale, buf, verts);
	}

	/**
	 * Samples one cell: max surface height (water surface wins) and average
	 * color, water-tinted by depth. Returns Long.MIN_VALUE when no column in
	 * the cell has data, otherwise packs (height << 32 | rgb).
	 */
	private static long sampleCell(LodWorld world, int blockX, int blockZ, int scale, int worldMinY) {
		int maxH = UNKNOWN;
		long r = 0, g = 0, b = 0;
		int samples = 0;
		int waterColumns = 0;
		long waterDepth = 0;

		LodChunk cached = null;
		int cachedCx = Integer.MIN_VALUE, cachedCz = Integer.MIN_VALUE;

		// For large cells, sampling every column is wasteful; step so we
		// take at most 8x8 samples per cell.
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
			// Mostly water: blend floor color toward water color by depth.
			float depth = waterDepth / (float) waterColumns;
			float t = Math.min(0.95f, 0.55f + depth * 0.02f);
			rr = (int) (rr * (1 - t) + ((WATER_COLOR >> 16) & 0xFF) * t);
			gg = (int) (gg * (1 - t) + ((WATER_COLOR >> 8) & 0xFF) * t);
			bb = (int) (bb * (1 - t) + (WATER_COLOR & 0xFF) * t);
		}

		return ((long) maxH << 32) | ((rr << 16) | (gg << 8) | bb);
	}

	private static int skirt(ByteBuffer buf, int neighborH, int h, float x1, float z1, float x2, float z2, int baseColor, float shade, int worldMinY) {
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

	private static int shade(int rgb, float factor) {
		int r = (int) (((rgb >> 16) & 0xFF) * factor);
		int g = (int) (((rgb >> 8) & 0xFF) * factor);
		int b = (int) ((rgb & 0xFF) * factor);
		return (r << 16) | (g << 8) | b;
	}

	private static int quad(ByteBuffer buf, float x1, float y1, float z1, float x2, float y2, float z2,
							float x3, float y3, float z3, float x4, float y4, float z4, int color) {
		vertex(buf, x1, y1, z1, color);
		vertex(buf, x2, y2, z2, color);
		vertex(buf, x3, y3, z3, color);
		vertex(buf, x3, y3, z3, color);
		vertex(buf, x4, y4, z4, color);
		vertex(buf, x1, y1, z1, color);
		return 6;
	}

	private static void vertex(ByteBuffer buf, float x, float y, float z, int rgb) {
		buf.putFloat(x);
		buf.putFloat(y);
		buf.putFloat(z);
		buf.put((byte) ((rgb >> 16) & 0xFF));
		buf.put((byte) ((rgb >> 8) & 0xFF));
		buf.put((byte) (rgb & 0xFF));
		buf.put((byte) 255);
	}
}
