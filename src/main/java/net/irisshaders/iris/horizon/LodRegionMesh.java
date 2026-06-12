package net.irisshaders.iris.horizon;

import org.lwjgl.opengl.GL33C;

/**
 * GPU-side mesh for one LOD render region. Created and destroyed on the
 * render thread only.
 */
public final class LodRegionMesh {
	public final int regionX;
	public final int regionZ;
	public final int scale;
	public final int vertexCount;
	public final float minY;
	public final float maxY;
	private int vao;
	private int vbo;

	public LodRegionMesh(LodMesher.MeshData data) {
		this.regionX = data.regionX();
		this.regionZ = data.regionZ();
		this.scale = data.scale();
		this.vertexCount = data.vertexCount();

		// Scan vertex heights once for a tight culling AABB.
		float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
		var buf = data.vertexData();
		for (int i = 0; i < data.vertexCount(); i++) {
			float y = buf.getFloat(i * LodMesher.STRIDE + 4);
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
		GL33C.glVertexAttribPointer(0, 3, GL33C.GL_FLOAT, false, LodMesher.STRIDE, 0);
		GL33C.glEnableVertexAttribArray(1);
		GL33C.glVertexAttribPointer(1, 4, GL33C.GL_UNSIGNED_BYTE, true, LodMesher.STRIDE, 12);
		GL33C.glBindVertexArray(0);
		GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, 0);
	}

	public void draw() {
		GL33C.glBindVertexArray(vao);
		GL33C.glDrawArrays(GL33C.GL_TRIANGLES, 0, vertexCount);
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
