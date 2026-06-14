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
	/** Bit 24 of the packed sampleCell low word flags a vegetation (tree) cell. */
	private static final int VEG_BIT = 1 << 24;
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
	 * Per-thread scratch: the worst-case vertex buffer and sampling grids
	 * are reused across builds, and the queued result is copied into an
	 * exactly-sized buffer. Without this, a burst of in-flight scale-1
	 * builds could pin hundreds of MB of native memory in the upload queue.
	 */
	private static final class Scratch {
		final ByteBuffer vertexScratch;
		final int[] heights;
		final int[] colors;
		final boolean[] veg;

		Scratch() {
			int maxCells = (REGION_BLOCKS + 2) * (REGION_BLOCKS + 2);
			vertexScratch = MemoryUtil.memAlloc(REGION_BLOCKS * REGION_BLOCKS * 5 * 6 * STRIDE);
			heights = new int[maxCells];
			colors = new int[maxCells];
			veg = new boolean[maxCells];
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

		// Sample an (n+2)^2 grid including a one-cell border from
		// neighboring regions so skirts at region edges line up. The grids
		// are pooled per thread; every used slot is written below before
		// being read, so stale data from previous builds is harmless.
		Scratch scratch = SCRATCH.get();
		int[] heights = scratch.heights;
		int[] colors = scratch.colors;
		boolean[] veg = scratch.veg;

		boolean any = false;
		for (int cz = -1; cz <= n; cz++) {
			for (int cx = -1; cx <= n; cx++) {
				int gi = (cx + 1) + (cz + 1) * (n + 2);
				long sample = sampleCell(world, regionX * REGION_BLOCKS + cx * scale, regionZ * REGION_BLOCKS + cz * scale, scale, worldMinY);
				if (sample == Long.MIN_VALUE) {
					heights[gi] = UNKNOWN;
					veg[gi] = false;
				} else {
					int low = (int) sample;
					heights[gi] = (int) (sample >> 32);
					colors[gi] = low & 0xFFFFFF;
					veg[gi] = (low & VEG_BIT) != 0;
					if (cx >= 0 && cx < n && cz >= 0 && cz < n) {
						any = true;
					}
				}
			}
		}

		if (!any) {
			return null;
		}

		// Build into the reusable worst-case scratch buffer; the result is
		// copied into an exactly-sized allocation before being queued.
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

				// Greedy run: merge consecutive cells along X that share the
				// same height and lit color into one top quad. Plains, water
				// and snowfields collapse into a handful of quads.
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
				verts += quad(buf,
					x0, h, z0,
					x1, h, z0,
					x1, h, z1,
					x0, h, z1,
					topColor);

				// X skirts only at the run boundaries; inside the run all
				// heights are equal so no faces are possible there.
				int giLast = runEnd + (cz + 1) * (n + 2);
				verts += skirt(buf, heights[gi - 1], h, x0, z0, x0, z1, colors[gi], 0.6f, worldMinY, veg[gi]);
				verts += skirt(buf, heights[giLast + 1], h, x1, z1, x1, z0, colors[giLast], 0.6f, worldMinY, veg[giLast]);

				// Z skirts per cell: they only emit where the row neighbor
				// is lower, so flat areas stay free.
				for (int k = cx; k < runEnd; k++) {
					int gk = (k + 1) + (cz + 1) * (n + 2);
					int ox = k * scale;
					verts += skirt(buf, heights[gk - (n + 2)], h, ox + scale, z0, ox, z0, colors[gk], 0.8f, worldMinY, veg[gk]);
					verts += skirt(buf, heights[gk + (n + 2)], h, ox, z1, ox + scale, z1, colors[gk], 0.8f, worldMinY, veg[gk]);
				}

				cx = runEnd;
			}
		}

		if (verts == 0) {
			return null;
		}

		// Copy from the START of the scratch buffer. memAddress() returns the
		// address at the buffer's CURRENT position, which here is the end of
		// the written data — using it copied uninitialized memory (and stale
		// vertices left by the previous region built on this reused per-thread
		// scratch), scattering garbage geometry. memAddress0() ignores position.
		ByteBuffer exact = MemoryUtil.memAlloc(verts * STRIDE);
		MemoryUtil.memCopy(MemoryUtil.memAddress0(buf), MemoryUtil.memAddress0(exact), (long) verts * STRIDE);
		return new MeshData(regionX, regionZ, scale, exact, verts);
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
		int vegColumns = 0;
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
				if (cached.vegetation[index]) {
					vegColumns++;
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

		int vegFlag = (vegColumns * 2 >= samples) ? VEG_BIT : 0;
		return ((long) maxH << 32) | ((long) (vegFlag | (rr << 16) | (gg << 8) | bb) & 0xFFFFFFFFL);
	}

	/** Max blocks a tree-crown skirt drops, so the canopy floats instead of forming a pillar. */
	private static final int VEG_SKIRT_CAP = 5;

	private static int skirt(ByteBuffer buf, int neighborH, int h, int x1, int z1, int x2, int z2, int baseColor, float shade, int worldMinY, boolean veg) {
		int bottom = neighborH == UNKNOWN ? Math.max(worldMinY, h - 32) : neighborH;
		if (veg) {
			// Float the crown: only skirt a few blocks down instead of all the
			// way to the ground neighbor, which would form a solid green pillar.
			bottom = Math.max(bottom, h - VEG_SKIRT_CAP);
		}
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
	 * This is what makes hills read as hills instead of color noise.
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
