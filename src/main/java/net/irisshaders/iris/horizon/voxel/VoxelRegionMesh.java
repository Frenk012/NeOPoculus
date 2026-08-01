package net.irisshaders.iris.horizon.voxel;

import org.lwjgl.opengl.GL33C;

/**
 * GPU mesh for one draw region: one VBO of v2 vertices sharing the global
 * {@link SharedQuadIndexBuffer}. Created and destroyed on the render thread.
 * The single VAO exposes attributes 0/1/2 (position+light, color, irisExtra)
 * — byte-compatible with the shaderpack path added in M6; M4 adds attribute 3.
 */
public final class VoxelRegionMesh {
	public final long regionKey;
	public final int level;
	public final int quads;
	public final float minY;
	public final float maxY;
	/** True if any face used the flat-color fallback (an unbaked state); the renderer re-meshes it when bakes land. */
	public final boolean usedFallback;
	/** Bakery epoch read when this mesh's build STARTED; the fallback re-mesh compares against it. */
	public final int bakeEpoch;
	/** Quads whose face light was fully dark (sky 0 and block 0); diagnostic for the LOD-blackening bug. */
	public final int darkQuads;
	private int vao;
	private int vbo;

	public VoxelRegionMesh(VoxelMesher.MeshData data) {
		this.regionKey = data.regionKey();
		this.level = data.level();
		this.quads = data.quads();
		this.minY = data.minY();
		this.maxY = data.maxY();
		this.usedFallback = data.usedFallback();
		this.bakeEpoch = data.bakeEpoch();
		this.darkQuads = data.darkQuads();

		vao = GL33C.glGenVertexArrays();
		vbo = GL33C.glGenBuffers();
		GL33C.glBindVertexArray(vao);
		GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, vbo);
		GL33C.glBufferData(GL33C.GL_ARRAY_BUFFER, data.vertexData(), GL33C.GL_STATIC_DRAW);
		int stride = LodVertexFormatV2.STRIDE;
		// attr0: uvec4 (x, y+bias, z, lightMeta)
		GL33C.glEnableVertexAttribArray(0);
		GL33C.glVertexAttribIPointer(0, 4, GL33C.GL_UNSIGNED_SHORT, stride, LodVertexFormatV2.POS_OFFSET);
		// attr1: vec4 color (normalized)
		GL33C.glEnableVertexAttribArray(1);
		GL33C.glVertexAttribPointer(1, 4, GL33C.GL_UNSIGNED_BYTE, true, stride, LodVertexFormatV2.COLOR_OFFSET);
		// attr2: uvec4 irisExtra (material, normal/face, 0, 0)
		GL33C.glEnableVertexAttribArray(2);
		GL33C.glVertexAttribIPointer(2, 4, GL33C.GL_UNSIGNED_BYTE, stride, LodVertexFormatV2.EXTRA_OFFSET);
		// attr3: uvec2 (atlasSlot, biomeId) — the textured no-pack path (M4).
		GL33C.glEnableVertexAttribArray(3);
		GL33C.glVertexAttribIPointer(3, 2, GL33C.GL_UNSIGNED_SHORT, stride, LodVertexFormatV2.ATLAS_SLOT_OFFSET);
		// Bind the shared quad index buffer into this VAO.
		GL33C.glBindBuffer(GL33C.GL_ELEMENT_ARRAY_BUFFER, SharedQuadIndexBuffer.get());
		GL33C.glBindVertexArray(0);
		GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, 0);
		GL33C.glBindBuffer(GL33C.GL_ELEMENT_ARRAY_BUFFER, 0);
	}

	public void draw() {
		GL33C.glBindVertexArray(vao);
		GL33C.glDrawElements(GL33C.GL_TRIANGLES, quads * 6, GL33C.GL_UNSIGNED_INT, 0L);
	}

	public void delete() {
		if (vao != 0) {
			GL33C.glDeleteVertexArrays(vao);
			GL33C.glDeleteBuffers(vbo);
			vao = 0;
			vbo = 0;
		}
	}
}
