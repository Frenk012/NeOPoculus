package net.irisshaders.iris.horizon.voxel;

import net.irisshaders.iris.Iris;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Builds one draw region's mesh (design-mesh-render.md section 1): for every
 * section in the region, snapshot it with its neighbors, then greedy-scanline
 * mesh each of the six face directions into indexed quads. Level-blind — the
 * whole thing runs in cell space and the vertex writer multiplies by
 * {@code 2^level} to get blocks. Worker thread.
 *
 * <p>M3 is flat-color: each quad's color is the solid cell's vanilla MapColor
 * ({@link VoxelColorTable}); the photo atlas replaces it in M4. Backface
 * culling is left to the renderer; quads are wound consistently but the M3
 * renderer draws two-sided until winding is verified in-game.
 */
public final class VoxelMesher {
	private VoxelMesher() {
	}

	private static final int N = VoxelConstants.SECTION_SIZE; // 32
	private static final int CAP = VoxelConstants.MERGE_CAP;   // 16
	private static final long LIGHT_MASK = VoxelConstants.LIGHT_MASK;
	private static final int MAX_BYTES = VoxelConstants.MAX_QUADS_PER_REGION * 4 * LodVertexFormatV2.STRIDE;

	/**
	 * {@code bakeEpoch} is the bakery epoch read at the START of the build. The
	 * renderer stamps fallback regions with it rather than with the epoch at
	 * upload time, so a bake that lands while this region is still building
	 * still counts as newer and triggers exactly one more re-mesh — the region
	 * cannot be stranded flat by an epoch edge consumed before it was uploaded.
	 */
	public record MeshData(long regionKey, int level, ByteBuffer vertexData, int quads, float minY, float maxY,
						   boolean usedFallback, int bakeEpoch, int darkQuads) {
		public void free() {
			MemoryUtil.memFree(vertexData);
		}
	}

	/** @return the region mesh, or null when the whole region is empty. */
	public static MeshData buildRegion(VoxelStore store, VoxelColorTable colors, VoxelPalettes palettes,
									   net.irisshaders.iris.horizon.voxel.model.VoxelBakery bakery,
									   int level, int rx, int rz, long regionKey, int worldMinY, int worldMaxY) {
		int minSy = SectionKey.blockToSection(worldMinY, level);
		int maxSy = SectionKey.blockToSection(worldMaxY - 1, level);
		int cellSize = 1 << level;

		SectionSnapshot snap = new SectionSnapshot();
		long[] faceKey = new long[N * N];
		int[] faceLight = new int[N * N];

		ByteBuffer buf = MemoryUtil.memAlloc(1 << 21); // 2 MB, grows on demand
		int quads = 0;
		// Diagnostic: quads whose face light is fully dark (sky 0 AND block 0). A
		// region flipping from mostly-lit to mostly-dark across a re-mesh is the
		// signature of the LOD-goes-black bug; VoxelRenderer logs that transition.
		int darkQuads = 0;
		float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
		boolean capped = false;
		boolean[] usedFallback = {false};
		var metadata = bakery.metadata();
		int startEpoch = bakery.epoch();

		try {
			for (int sxLocal = 0; sxLocal < VoxelConstants.MESH_REGION_SECTIONS && !capped; sxLocal++) {
				int sx = rx * VoxelConstants.MESH_REGION_SECTIONS + sxLocal;
				for (int szLocal = 0; szLocal < VoxelConstants.MESH_REGION_SECTIONS && !capped; szLocal++) {
					int sz = rz * VoxelConstants.MESH_REGION_SECTIONS + szLocal;
					for (int sy = minSy; sy <= maxSy && !capped; sy++) {
						if (!snap.capture(store, level, sx, sy, sz)) {
							continue;
						}
						for (int face = 0; face < VoxelConstants.FACE_COUNT; face++) {
							for (int w = 0; w < N; w++) {
								if (!buildLayer(snap, palettes, face, w, faceKey, faceLight)) {
									continue;
								}
								// Greedy-merge this layer's faceKey grid into quads.
								for (int v = 0; v < N; v++) {
									for (int u = 0; u < N; u++) {
										long key = faceKey[v * N + u];
										if (key == 0) {
											continue;
										}
										int su = 1;
										while (u + su < N && su < CAP && faceKey[v * N + u + su] == key) {
											su++;
										}
										int sv = 1;
										grow:
										while (v + sv < N && sv < CAP) {
											for (int k = 0; k < su; k++) {
												if (faceKey[(v + sv) * N + u + k] != key) {
													break grow;
												}
											}
											sv++;
										}
										for (int j = 0; j < sv; j++) {
											for (int k = 0; k < su; k++) {
												faceKey[(v + j) * N + u + k] = 0;
											}
										}
										if (buf.position() + 4 * LodVertexFormatV2.STRIDE > buf.capacity()) {
											ByteBuffer grown = maybeGrow(buf);
											if (grown == null) {
												capped = true;
												Iris.logger.warn("Horizon: voxel region " + rx + "," + rz
													+ " L" + level + " hit the quad cap; truncated");
												break;
											}
											buf = grown;
										}
										int state = VoxelCell.stateId(key);
										int biome = VoxelCell.biomeId(key);
										int slot = metadata.slotOf(state, biome, face);
										// Only a PENDING bake justifies flagging this region for
										// a re-mesh. A state that is already baked and still has
										// no slot (no usable texture, or the atlas is full) is
										// permanently flat, and re-meshing it on every epoch bump
										// re-built the same regions forever — the repeated
										// "hit the quad cap; truncated" churn in the logs.
										if (slot == 0 && !metadata.isBaked(state, biome)) {
											bakery.requestBake(state, biome);
											usedFallback[0] = true;
										}
										int lightMeta = faceLight[v * N + u];
										if (lightMeta == 0) {
											darkQuads++;
										}
										float[] yspan = emitQuad(buf, colors, key, lightMeta, slot,
											face, w, u, v, su, sv, sxLocal, szLocal, sy, cellSize);
										minY = Math.min(minY, yspan[0]);
										maxY = Math.max(maxY, yspan[1]);
										quads++;
										if (capped) {
											break;
										}
									}
									if (capped) {
										break;
									}
								}
								if (capped) {
									break;
								}
							}
							if (capped) {
								break;
							}
						}
					}
				}
			}
		} catch (Throwable t) {
			MemoryUtil.memFree(buf);
			throw t;
		}

		if (quads == 0) {
			MemoryUtil.memFree(buf);
			return null;
		}
		buf.limit(buf.position());
		buf.position(0);
		return new MeshData(regionKey, level, buf, quads, minY, maxY, usedFallback[0], startEpoch, darkQuads);
	}

	private static ByteBuffer maybeGrow(ByteBuffer buf) {
		int cap = buf.capacity();
		if (cap >= MAX_BYTES) {
			return null;
		}
		int newCap = Math.min(MAX_BYTES, cap * 2);
		ByteBuffer grown = MemoryUtil.memAlloc(newCap);
		int pos = buf.position();
		buf.position(0);
		buf.limit(pos);
		grown.put(buf);
		MemoryUtil.memFree(buf);
		return grown;
	}

	/**
	 * Fills {@code faceKey} (32×32) for one layer of one face direction; a
	 * non-zero entry is a visible face carrying its merge key (geometry+state+
	 * biome from the solid cell, light from the adjacent cell). Returns false
	 * (skip the layer) when nothing is visible.
	 */
	private static boolean buildLayer(SectionSnapshot snap, VoxelPalettes palettes, int face, int w,
									  long[] faceKey, int[] faceLight) {
		int sign = (face & 1) == 0 ? -1 : 1; // faces 0,2,4 negative; 1,3,5 positive
		boolean any = false;
		for (int v = 0; v < N; v++) {
			for (int u = 0; u < N; u++) {
				long c = readFace(snap, face, w, u, v);
				if (VoxelCell.isAir(c)) {
					faceKey[v * N + u] = 0;
					continue;
				}
				long n = readFace(snap, face, w + sign, u, v);
				if (!visible(palettes, c, n)) {
					faceKey[v * N + u] = 0;
					continue;
				}
				// Merge on geometry+state+biome only (light excluded), so a
				// surface merges into large plates instead of fragmenting into
				// one-cell strips wherever the adjacent cell's light varies. The
				// light is kept in a parallel array and the quad takes its
				// origin cell's value — approximate across a merged plate but
				// real (day/night, caves), unlike the old fixed-bright value.
				faceKey[v * N + u] = c & ~LIGHT_MASK;
				// Light from the adjacent (air/translucent) cell, vanilla-style.
				int bl = VoxelCell.blockLight(n);
				int sl = VoxelCell.skyLight(n);
				if (n == VoxelConstants.AIR_CELL) {
					// Missing-neighbour plane (a not-yet-meshed neighbour region
					// at the frontier): treat as open sky so the temporary
					// boundary wall is lit instead of pitch black. Real air with
					// captured light, and dark cave air, keep their own value.
					sl = 15;
				}
				faceLight[v * N + u] = (bl << 4) | sl;
				any = true;
			}
		}
		return any;
	}

	/** Cell at (perp=w, in-plane u,v) for a face direction, mapped to snapshot axes. */
	private static long readFace(SectionSnapshot snap, int face, int w, int u, int v) {
		return switch (face) {
			case VoxelConstants.FACE_NEG_Y, VoxelConstants.FACE_POS_Y -> snap.cell(u, w, v); // perp Y, u=x, v=z
			case VoxelConstants.FACE_NEG_Z, VoxelConstants.FACE_POS_Z -> snap.cell(u, v, w); // perp Z, u=x, v=y
			default -> snap.cell(w, v, u);                                                   // perp X, u=z, v=y
		};
	}

	private static boolean visible(VoxelPalettes palettes, long c, long n) {
		if (VoxelCell.isAir(n)) {
			return true;
		}
		int ns = VoxelCell.stateId(n);
		return palettes.opacityOf(ns) < 15 && ns != VoxelCell.stateId(c);
	}

	/**
	 * Emits the 4 vertices of one merged quad. Corner cell coords (0..32) are
	 * turned into region-local block X/Z and biased world Y. Returns
	 * {@code [minY, maxY]} of the emitted vertices (world-space, un-biased).
	 */
	private static float[] emitQuad(ByteBuffer buf, VoxelColorTable colors, long key, int lightMeta, int atlasSlot,
									int face, int w, int u, int v, int su, int sv,
									int sxLocal, int szLocal, int sy, int cellSize) {
		int state = VoxelCell.stateId(key);
		int rgb = colors.colorOf(state);
		int biome = VoxelCell.biomeId(key);

		// Corner cell coords per face (see class doc); cx/cz in 0..32 relative
		// to the section, cy in 0..32 relative to the section.
		int[] cx = new int[4], cy = new int[4], cz = new int[4];
		switch (face) {
			case VoxelConstants.FACE_NEG_Y, VoxelConstants.FACE_POS_Y -> {
				int y = face == VoxelConstants.FACE_POS_Y ? w + 1 : w;
				set(cx, u, u + su, u + su, u); set(cz, v, v, v + sv, v + sv); fill(cy, y);
			}
			case VoxelConstants.FACE_NEG_Z, VoxelConstants.FACE_POS_Z -> {
				int z = face == VoxelConstants.FACE_POS_Z ? w + 1 : w;
				set(cx, u, u + su, u + su, u); set(cy, v, v, v + sv, v + sv); fill(cz, z);
			}
			default -> { // ±X: u=z, v=y
				int x = face == VoxelConstants.FACE_POS_X ? w + 1 : w;
				set(cz, u, u + su, u + su, u); set(cy, v, v, v + sv, v + sv); fill(cx, x);
			}
		}

		int baseX = sxLocal * N;
		int baseZ = szLocal * N;
		int baseWorldYCells = sy * N;
		float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
		for (int i = 0; i < 4; i++) {
			int rlx = (baseX + cx[i]) * cellSize;
			int rlz = (baseZ + cz[i]) * cellSize;
			int worldY = (baseWorldYCells + cy[i]) * cellSize;
			int posY = worldY + VoxelConstants.Y_BIAS;
			minY = Math.min(minY, worldY);
			maxY = Math.max(maxY, worldY);
			LodVertexFormatV2.writeVertex(buf, rlx, posY, rlz, lightMeta, rgb,
				0 /*material*/, face, atlasSlot, biome, face /*faceMeta*/, 0 /*flags*/);
		}
		return new float[]{minY, maxY};
	}

	private static void set(int[] a, int p0, int p1, int p2, int p3) {
		a[0] = p0; a[1] = p1; a[2] = p2; a[3] = p3;
	}

	private static void fill(int[] a, int val) {
		a[0] = a[1] = a[2] = a[3] = val;
	}
}
