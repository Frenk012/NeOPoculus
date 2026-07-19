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
		GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, 0);
		GL33C.glPixelStorei(GL33C.GL_UNPACK_ROW_LENGTH, 0);
		GL33C.glPixelStorei(GL33C.GL_UNPACK_ALIGNMENT, 1);
		GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);

		int[] level = rgba;
		int dim = SLOT_PX;
		for (int mip = 0; mip <= MAX_MIP; mip++) {
			uploadLevel(mip, sx >> mip, sy >> mip, dim, level);
			if (mip < MAX_MIP) {
				level = downsample(level, dim);
				dim >>= 1;
			}
		}

		GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, prev);
		GL33C.glPixelStorei(GL33C.GL_UNPACK_ROW_LENGTH, prevRow);
		GL33C.glPixelStorei(GL33C.GL_UNPACK_ALIGNMENT, prevAlign);
		GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, prevUnpack);
		return slot;
	}

	private void uploadLevel(int mip, int x, int y, int dim, int[] pixels) {
		scratch.clear();
		for (int i = 0; i < dim * dim; i++) {
			int p = pixels[i];
			scratch.put((byte) (p >>> 24)).put((byte) (p >>> 16)).put((byte) (p >>> 8)).put((byte) p);
		}
		scratch.flip();
		GL33C.glTexSubImage2D(GL33C.GL_TEXTURE_2D, mip, x, y, dim, dim,
			GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, scratch);
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
