package net.irisshaders.iris.horizon.voxel;

import java.nio.ByteBuffer;

/**
 * Vertex format v2 (design-mesh-render.md section 2): one 24-byte stride, two
 * VAOs reading subsets. Attributes 0/1/2 (position+light, color, irisExtra)
 * sit at offsets 0/8/12 exactly as the classic DH-compatible format, so the
 * shaderpack path (M6) binds them unchanged; bytes 16–23 (atlas slot, biome,
 * face meta) are the textured-path extension consumed by the no-pack shader.
 *
 * <p>4 vertices per quad, indexed via {@link SharedQuadIndexBuffer} → 96 B per
 * quad, identical to the classic 6×16 B. The buffer must be native byte order
 * (LWJGL {@code memAlloc} already is) so the GPU reads the fields correctly.
 */
public final class LodVertexFormatV2 {
	private LodVertexFormatV2() {
	}

	public static final int STRIDE = VoxelConstants.VERTEX_STRIDE; // 24

	/**
	 * Positions are stored in 1/16-block sub-units, not whole blocks: a vertex
	 * may sit at any sixteenth of a cell, which is what lets a slab be half a
	 * block high or a fence a thin post. The layout is unchanged (still 3 × u16
	 * at offset 0), only the scale — the vertex shader divides by
	 * {@link #POS_UNITS_PER_BLOCK} before anything else, so every downstream
	 * consumer keeps working in blocks. Worst case is a region-local 2048 blocks
	 * → 32768 sub-units, which is within u16 range as an unsigned bit pattern.
	 */
	public static final int POS_UNITS_PER_BLOCK = 16;

	public static final int POS_OFFSET = 0;        // 3 × u16 ((region-local x, worldY+Y_BIAS, z) × 16)
	public static final int LIGHT_OFFSET = 6;      // u16 light meta (sky 0-3, block 4-7)
	public static final int COLOR_OFFSET = 8;      // 4 × u8 RGBA
	public static final int MATERIAL_OFFSET = 12;  // u8 DH material id
	public static final int NORMAL_OFFSET = 13;    // u8 normal/face index (DH order)
	public static final int EXTRA_OFFSET = 12;     // irisExtra uvec4 base (material, normal, 0, 0)
	public static final int ATLAS_SLOT_OFFSET = 16; // u16
	public static final int BIOME_OFFSET = 18;     // u16
	public static final int FACEMETA_OFFSET = 20;  // u8 (bits 0-2 face)
	public static final int FLAGS_OFFSET = 21;     // u8

	/**
	 * Writes one vertex at the buffer's current position. Coordinates are in
	 * 1/16-block sub-units (see {@link #POS_UNITS_PER_BLOCK}): region-local X/Z
	 * in 0..32768, {@code posYBiased} = (worldY + {@link VoxelConstants#Y_BIAS})
	 * × 16.
	 */
	public static void writeVertex(ByteBuffer buf, int rlx, int posYBiased, int rlz,
								   int lightMeta, int rgb, int material, int normalIdx,
								   int atlasSlot, int biomeId, int faceMeta, int flags) {
		int base = buf.position();
		buf.putShort(base + POS_OFFSET, (short) rlx);
		buf.putShort(base + POS_OFFSET + 2, (short) posYBiased);
		buf.putShort(base + POS_OFFSET + 4, (short) rlz);
		buf.putShort(base + LIGHT_OFFSET, (short) lightMeta);
		buf.put(base + COLOR_OFFSET, (byte) (rgb >> 16));
		buf.put(base + COLOR_OFFSET + 1, (byte) (rgb >> 8));
		buf.put(base + COLOR_OFFSET + 2, (byte) rgb);
		buf.put(base + COLOR_OFFSET + 3, (byte) 0xFF);
		buf.put(base + MATERIAL_OFFSET, (byte) material);
		buf.put(base + NORMAL_OFFSET, (byte) normalIdx);
		buf.put(base + EXTRA_OFFSET + 2, (byte) 0);
		buf.put(base + EXTRA_OFFSET + 3, (byte) 0);
		buf.putShort(base + ATLAS_SLOT_OFFSET, (short) atlasSlot);
		buf.putShort(base + BIOME_OFFSET, (short) biomeId);
		buf.put(base + FACEMETA_OFFSET, (byte) faceMeta);
		buf.put(base + FLAGS_OFFSET, (byte) flags);
		buf.putShort(base + 22, (short) 0);
		buf.position(base + STRIDE);
	}
}
