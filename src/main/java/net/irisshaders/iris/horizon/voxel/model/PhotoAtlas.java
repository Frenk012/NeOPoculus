package net.irisshaders.iris.horizon.voxel.model;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.horizon.voxel.VoxelConstants;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * The shared photo atlas for the voxel LOD (design-bakery-atlas.md, resolution
 * R2): one {@code GL_RGBA8} 2D texture of 16-pixel slots with a 5-level mip
 * chain, each slot holding one baked block face. Slot 0 is reserved as the
 * "no texture — use the flat vertex color" marker so unbaked states keep the
 * M3 MapColor look until their bake lands.
 *
 * <p>Render thread only. M4 v1 uses a fixed 2048² atlas (16 384 slots); growth
 * to 4096² and a CPU shadow are later refinements.
 */
public final class PhotoAtlas {
	public static final int SLOT_PX = VoxelConstants.SLOT_PX;       // 16
	public static final int MAX_MIP = VoxelConstants.ATLAS_MIP_LEVELS; // 4 -> levels 0..4
	private final int size = VoxelConstants.ATLAS_INITIAL_SIZE;     // 2048
	private final int slotsPerRow = size / SLOT_PX;                 // 128
	private final int maxSlots = slotsPerRow * slotsPerRow;         // 16384

	private int texture;
	private int nextSlot = 1; // 0 reserved
	private final ByteBuffer scratch = MemoryUtil.memAlloc(SLOT_PX * SLOT_PX * 4);

	public int slotsPerRow() {
		return slotsPerRow;
	}

	public int atlasSize() {
		return size;
	}

	public int textureId() {
		return texture;
	}

	public boolean full() {
		return nextSlot >= maxSlots;
	}

	/** Lazily creates the GL texture (RGBA8, mip 0..MAX_MIP, sampler set). Render thread. */
	public void ensure() {
		if (texture != 0) {
			return;
		}
		int prev = GL33C.glGetInteger(GL33C.GL_TEXTURE_BINDING_2D);
		texture = GL33C.glGenTextures();
		GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
		for (int mip = 0; mip <= MAX_MIP; mip++) {
			int dim = size >> mip;
			GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, mip, GL33C.GL_RGBA8, dim, dim, 0,
				GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
		}
		GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST_MIPMAP_LINEAR);
		GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);
		GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_S, GL33C.GL_CLAMP_TO_EDGE);
		GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_T, GL33C.GL_CLAMP_TO_EDGE);
		GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAX_LEVEL, MAX_MIP);
		GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, prev);
	}

	/**
	 * Allocates a slot and uploads one 16x16 RGBA face photo plus its mip
	 * chain (box-filtered on the CPU in linear-ish space). Returns the slot, or
	 * 0 (flat-color fallback) when the atlas is full. Render thread.
	 *
	 * @param rgba 256 pixels, 0xRRGGBBAA per int (row-major, y*16+x).
	 */
	public int upload(int[] rgba) {
		return upload(rgba, false);
	}

	/**
	 * @param leafLike when true the mip chain is sealed and darkened: holes are
	 *                 filled with the photo's mean colour and alpha forced opaque
	 *                 so the fragment shader's {@code alpha < 0.5} cutout stops
	 *                 punching through distant canopies (which shimmered as mips
	 *                 averaged the gaps), and each level is darkened slightly so
	 *                 a far forest reads as dense shaded mass rather than flat.
	 */
	public int upload(int[] rgba, boolean leafLike) {
		ensure();
		if (nextSlot >= maxSlots) {
			return 0;
		}
		int slot = nextSlot++;
		int sx = (slot % slotsPerRow) * SLOT_PX;
		int sy = (slot / slotsPerRow) * SLOT_PX;

		int prev = GL33C.glGetInteger(GL33C.GL_TEXTURE_BINDING_2D);
		int prevUnpack = GL33C.glGetInteger(GL33C.GL_PIXEL_UNPACK_BUFFER_BINDING);
		int prevRow = GL33C.glGetInteger(GL33C.GL_UNPACK_ROW_LENGTH);
		int prevAlign = GL33C.glGetInteger(GL33C.GL_UNPACK_ALIGNMENT);
		// SKIP_PIXELS/SKIP_ROWS must be zeroed too, not just row length and
		// alignment. Vanilla's NativeImage.upload sets them and does not always
		// restore them, and a non-zero value makes the driver start reading this
		// 1 KB scratch buffer at an offset and run off its end — a hard access
		// violation inside the GL driver, which is exactly how this crashed.
		int prevSkipPx = GL33C.glGetInteger(GL33C.GL_UNPACK_SKIP_PIXELS);
		int prevSkipRows = GL33C.glGetInteger(GL33C.GL_UNPACK_SKIP_ROWS);
		GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, 0);
		GL33C.glPixelStorei(GL33C.GL_UNPACK_ROW_LENGTH, 0);
		GL33C.glPixelStorei(GL33C.GL_UNPACK_ALIGNMENT, 1);
		GL33C.glPixelStorei(GL33C.GL_UNPACK_SKIP_PIXELS, 0);
		GL33C.glPixelStorei(GL33C.GL_UNPACK_SKIP_ROWS, 0);
		GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);

		int[] level = rgba;
		int dim = SLOT_PX;
		int leafMean = leafLike ? meanColor(rgba) : 0;
		for (int mip = 0; mip <= MAX_MIP; mip++) {
			uploadLevel(mip, sx >> mip, sy >> mip, dim, level);
			if (mip < MAX_MIP) {
				level = downsample(level, dim);
				dim >>= 1;
				if (leafLike) {
					sealLeafMip(level, leafMean, mip + 1);
				}
			}
		}

		GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, prev);
		GL33C.glPixelStorei(GL33C.GL_UNPACK_ROW_LENGTH, prevRow);
		GL33C.glPixelStorei(GL33C.GL_UNPACK_ALIGNMENT, prevAlign);
		GL33C.glPixelStorei(GL33C.GL_UNPACK_SKIP_PIXELS, prevSkipPx);
		GL33C.glPixelStorei(GL33C.GL_UNPACK_SKIP_ROWS, prevSkipRows);
		GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, prevUnpack);
		return slot;
	}

	private void uploadLevel(int mip, int x, int y, int dim, int[] pixels) {
		int texels = dim * dim;
		// The driver reads exactly dim*dim*4 bytes from this buffer with no
		// bounds check of its own, so a short source array would be a native
		// crash rather than an exception. Refuse the upload instead.
		if (dim <= 0 || pixels.length < texels || texels * 4 > scratch.capacity()) {
			Iris.logger.warn("Horizon: skipped a malformed atlas upload (mip " + mip
				+ ", dim " + dim + ", " + pixels.length + " texels)");
			return;
		}
		scratch.clear();
		for (int i = 0; i < texels; i++) {
			int p = pixels[i];
			scratch.put((byte) (p >>> 24)).put((byte) (p >>> 16)).put((byte) (p >>> 8)).put((byte) p);
		}
		scratch.flip();
		GL33C.glTexSubImage2D(GL33C.GL_TEXTURE_2D, mip, x, y, dim, dim,
			GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, scratch);
	}

	/** Alpha-weighted mean colour of a photo, used to fill leaf gaps at coarse mips. */
	private static int meanColor(int[] photo) {
		long r = 0, g = 0, b = 0, aw = 0;
		for (int p : photo) {
			int a = p & 0xFF;
			r += ((p >>> 24) & 0xFF) * a;
			g += ((p >>> 16) & 0xFF) * a;
			b += ((p >>> 8) & 0xFF) * a;
			aw += a;
		}
		if (aw == 0) {
			return 0;
		}
		return (int) ((r / aw) << 24 | (g / aw) << 16 | (b / aw) << 8) | 0xFF;
	}

	/**
	 * Fills a leaf mip's transparent texels with the photo mean and forces it
	 * opaque, then darkens it by 0.85 per level. Distant canopies otherwise
	 * flicker: the mip average drags alpha across the shader's 0.5 cutout and
	 * gaps blink in and out as the LOD level changes.
	 */
	private static void sealLeafMip(int[] level, int mean, int mipLevel) {
		// design-bakery-atlas.md specifies LEAF_MIP_DARKEN = 0.85 per level as a
		// LINEAR-space multiply. Applying it straight to sRGB bytes (the first
		// version of this) darkened far foliage roughly 40% too much and made
		// canopies read as black patches against everything around them. For a
		// pure scalar the conversion is exact: scaling linear by k is the same as
		// scaling sRGB by k^(1/2.2).
		float darken = (float) Math.pow(0.85, mipLevel / 2.2);
		int mr = (mean >>> 24) & 0xFF, mg = (mean >>> 16) & 0xFF, mb = (mean >>> 8) & 0xFF;
		for (int i = 0; i < level.length; i++) {
			int p = level[i];
			int a = p & 0xFF;
			int r = (p >>> 24) & 0xFF, g = (p >>> 16) & 0xFF, b = (p >>> 8) & 0xFF;
			if (a < 255) {
				// Blend toward the canopy's own average rather than toward black.
				r = (r * a + mr * (255 - a)) / 255;
				g = (g * a + mg * (255 - a)) / 255;
				b = (b * a + mb * (255 - a)) / 255;
			}
			r = Math.min(255, (int) (r * darken));
			g = Math.min(255, (int) (g * darken));
			b = Math.min(255, (int) (b * darken));
			level[i] = (r << 24) | (g << 16) | (b << 8) | 0xFF;
		}
	}

	/** Box-filter 2x2 downsample, alpha-weighted so transparent texels don't darken the mip. */
	private static int[] downsample(int[] src, int dim) {
		int half = dim >> 1;
		int[] dst = new int[half * half];
		for (int y = 0; y < half; y++) {
			for (int x = 0; x < half; x++) {
				int r = 0, g = 0, b = 0, a = 0, aw = 0;
				for (int dy = 0; dy < 2; dy++) {
					for (int dx = 0; dx < 2; dx++) {
						int p = src[(y * 2 + dy) * dim + (x * 2 + dx)];
						int pa = p & 0xFF;
						r += ((p >>> 24) & 0xFF) * pa;
						g += ((p >>> 16) & 0xFF) * pa;
						b += ((p >>> 8) & 0xFF) * pa;
						a += pa;
						aw += pa;
					}
				}
				int rr, gg, bb;
				if (aw > 0) {
					rr = r / aw;
					gg = g / aw;
					bb = b / aw;
				} else {
					rr = gg = bb = 0;
				}
				dst[y * half + x] = (rr << 24) | (gg << 16) | (bb << 8) | (a / 4);
			}
		}
		return dst;
	}

	public void destroy() {
		if (texture != 0) {
			GL33C.glDeleteTextures(texture);
			texture = 0;
		}
		nextSlot = 1;
	}
}
