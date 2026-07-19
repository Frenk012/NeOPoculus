package net.irisshaders.iris.horizon.voxel;

import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;

/**
 * One process-wide u32 element buffer holding the {@code 4q + {0,1,2, 2,3,0}}
 * quad→triangle index pattern for every quad up to
 * {@link VoxelConstants#MAX_QUADS_PER_REGION}. Every {@link VoxelRegionMesh}
 * VAO binds it, so quads are stored as 4 vertices and expanded to 2 triangles
 * at draw time with no per-mesh index data. Filled once, lazily, on the render
 * thread; freed only at client shutdown.
 */
public final class SharedQuadIndexBuffer {
	private SharedQuadIndexBuffer() {
	}

	private static int ebo;

	/** Render thread. Returns the EBO name, creating it on first use. */
	public static int get() {
		if (ebo != 0) {
			return ebo;
		}
		int quads = VoxelConstants.MAX_QUADS_PER_REGION;
		IntBuffer data = MemoryUtil.memAllocInt(quads * 6);
		try {
			for (int q = 0; q < quads; q++) {
				int v = q * 4;
				int i = q * 6;
				data.put(i, v);
				data.put(i + 1, v + 1);
				data.put(i + 2, v + 2);
				data.put(i + 3, v + 2);
				data.put(i + 4, v + 3);
				data.put(i + 5, v);
			}
			ebo = GL33C.glGenBuffers();
			GL33C.glBindBuffer(GL33C.GL_ELEMENT_ARRAY_BUFFER, ebo);
			GL33C.glBufferData(GL33C.GL_ELEMENT_ARRAY_BUFFER, data, GL33C.GL_STATIC_DRAW);
			GL33C.glBindBuffer(GL33C.GL_ELEMENT_ARRAY_BUFFER, 0);
		} finally {
			MemoryUtil.memFree(data);
		}
		return ebo;
	}

	public static void destroy() {
		if (ebo != 0) {
			GL33C.glDeleteBuffers(ebo);
			ebo = 0;
		}
	}
}
