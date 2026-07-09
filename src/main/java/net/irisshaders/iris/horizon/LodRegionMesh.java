package net.irisshaders.iris.horizon;

import org.lwjgl.opengl.GL33C;

/**
 * GPU-side mesh for one LOD render region. Created and destroyed on the
 * render thread only.
 */
public final class LodRegionMesh {
	// Coverage-vs-chunk-mask result, cached per mask rebuild (see LodRenderer.maskEpoch).
	long coveredEpoch;
	boolean coveredValue;
	public final int regionX;
	public final int regionZ;
	public final int scale;
	public final int vertexCount;
	public final float minY;
	public final float maxY;
	private int vao;
	private int vbo;
	/**
	 * Second view over the same VBO for the Iris shaderpack path: positions
	 * exposed as an integer uvec4 (x, y, z, lightMeta) matching the DH
	 * terrain vertex format the pack's dh_terrain program expects. Created
	 * lazily since it's only needed while a pack is active.
	 */
	private int irisVao;

	public LodRegionMesh(LodMesher.MeshData data) {
		this.regionX = data.regionX();
		this.regionZ = data.regionZ();
		this.scale = data.scale();
		this.vertexCount = data.vertexCount();

		// Scan vertex heights once for a tight culling AABB. Stored y is
		// biased by Y_BIAS (see LodMesher.vertex); un-bias for world-space.
		float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
		var buf = data.vertexData();
		for (int i = 0; i < data.vertexCount(); i++) {
			float y = buf.getShort(i * LodMesher.STRIDE + LodMesher.Y_OFFSET) - LodMesher.Y_BIAS;
			if (y < min) min = y;
			if (y > max) max = y;
		}
		this.minY = min;
		this.maxY = max;

		vao = GL33C.glGenVertexArrays();
		vbo = GL33C.glGenBuffers();
		GL33C.glBindVertexArray(vao);
		GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, vbo);
		GL33C.glBufferData(GL33C.GL_ARRAY_BUFFER, data.vertexData(), GL33C.GL_STATIC_DRAW);
		GL33C.glEnableVertexAttribArray(0);
		GL33C.glVertexAttribPointer(0, 3, GL33C.GL_SHORT, false, LodMesher.STRIDE, 0);
		GL33C.glEnableVertexAttribArray(1);
		GL33C.glVertexAttribPointer(1, 4, GL33C.GL_UNSIGNED_BYTE, true, LodMesher.STRIDE, LodMesher.COLOR_OFFSET);
		GL33C.glBindVertexArray(0);
		GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, 0);
	}

	public void draw() {
		GL33C.glBindVertexArray(vao);
		GL33C.glDrawArrays(GL33C.GL_TRIANGLES, 0, vertexCount);
	}

	/** Draws through the DH-format VAO for the Iris shaderpack path. */
	public void drawIris() {
		if (irisVao == 0) {
			irisVao = GL33C.glGenVertexArrays();
			GL33C.glBindVertexArray(irisVao);
			GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, vbo);
			GL33C.glEnableVertexAttribArray(0);
			// uvec4: x, y (Y_BIAS-ed, never negative), z, light meta.
			GL33C.glVertexAttribIPointer(0, 4, GL33C.GL_UNSIGNED_SHORT, LodMesher.STRIDE, 0);
			GL33C.glEnableVertexAttribArray(1);
			GL33C.glVertexAttribPointer(1, 4, GL33C.GL_UNSIGNED_BYTE, true, LodMesher.STRIDE, LodMesher.COLOR_OFFSET);
			// irisExtra uvec4: material id, normal index, 0, 0 (per vertex).
			GL33C.glEnableVertexAttribArray(2);
			GL33C.glVertexAttribIPointer(2, 4, GL33C.GL_UNSIGNED_BYTE, LodMesher.STRIDE, LodMesher.EXTRA_OFFSET);
			GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, 0);
		} else {
			GL33C.glBindVertexArray(irisVao);
		}
		GL33C.glDrawArrays(GL33C.GL_TRIANGLES, 0, vertexCount);
	}

	public void delete() {
		if (vao != 0) {
			GL33C.glDeleteVertexArrays(vao);
			GL33C.glDeleteBuffers(vbo);
			vao = 0;
			vbo = 0;
		}
		if (irisVao != 0) {
			GL33C.glDeleteVertexArrays(irisVao);
			irisVao = 0;
		}
	}
}
