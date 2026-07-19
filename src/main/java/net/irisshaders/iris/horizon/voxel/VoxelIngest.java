package net.irisshaders.iris.horizon.voxel;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;

/**
 * Worker-side merge of chunk data into the voxel section grid
 * (design-data-storage.md sections 3.3-3.5): converts a
 * {@link ChunkSnapshotter.ChunkSnapshot} through {@link ChunkPyramid} and
 * writes all five LOD levels, plus the sparse block-update and debounced
 * light-refresh write paths.
 *
 * <p>WHY all five levels unconditionally: capture only happens within server
 * view distance, so the extra L1-L4 sections touched per chunk are a
 * handful, and writing parents directly keeps them exact with no deferred
 * propagation on the bulk path — the incremental {@link VoxelMipper} exists
 * only for sparse edits.
 *
 * <p>Section-creation rule: sections are created on first non-air content
 * only ({@code VoxelWorld.acquireForWrite}); an all-air pyramid is still
 * written into <em>already-resident</em> targets (a re-ingested mined-out
 * section must clear previously solid cells) but never creates one — that
 * is the "air-only sections are never stored" invariant.
 */
final class VoxelIngest {
	private VoxelIngest() {
	}

	/**
	 * What one chunk ingest touched, for the caller ({@code VoxelEngine}) to
	 * forward to the mesh dirty tracker (DESIGN.md contract addition (b)):
	 * <ul>
	 * <li>every key in {@code touchedSections} dirties its own mesh
	 * region;</li>
	 * <li>every key in {@code createdSections} (a subset of touched)
	 * additionally dirties the mesh regions of its 6 face neighbors
	 * ({@link SectionKey#faceNeighbor}) — a new section can occlude faces a
	 * neighbor's mesh emitted against the missing-neighbor-is-air rule, and
	 * without the notification those frontier walls would persist.</li>
	 * </ul>
	 */
	record IngestResult(LongSet touchedSections, LongSet createdSections) {
		boolean isEmpty() {
			return touchedSections.isEmpty();
		}
	}

	/**
	 * Merges one snapshot into the world at all five levels. Worker thread
	 * only (uses the thread-local {@link ChunkPyramid} scratch).
	 */
	static IngestResult ingest(ChunkSnapshotter.ChunkSnapshot snapshot, VoxelWorld world, VoxelPalettes palettes) {
		LongOpenHashSet touched = new LongOpenHashSet();
		LongOpenHashSet created = new LongOpenHashSet();
		ChunkPyramid pyramid = ChunkPyramid.scratch();
		int cx = snapshot.chunkX();
		int cz = snapshot.chunkZ();

		for (int i = 0; i < snapshot.sectionCount(); i++) {
			int sy = snapshot.sectionY(i);
			if (snapshot.states()[i] == null && !anyResidentTarget(world, cx, sy, cz)) {
				// All-air with nothing resident to clear: skip before even
				// building the pyramid — the common far-sky case.
				continue;
			}
			pyramid.build(snapshot, i, palettes);
			boolean airOnly = pyramid.nonAirCount() == 0;

			for (int l = 0; l <= VoxelConstants.MAX_LEVEL; l++) {
				long key = targetKey(l, cx, sy, cz);
				VoxelSection section;
				if (airOnly) {
					section = world.get(key);
					if (section == null) {
						continue;
					}
				} else {
					// get-before-acquire to learn whether this write created
					// the section. A concurrent creator can make both racers
					// report "created"; the cost is one redundant neighbor
					// dirtying, never a missed one.
					boolean existed = world.get(key) != null;
					section = world.acquireForWrite(key);
					if (!existed) {
						created.add(key);
					}
				}
				int n = 16 >> l;
				section.writeBatch(
					baseCell(cx, l), baseCell(sy, l), baseCell(cz, l),
					n, n, n,
					pyramid.level(l), 0, n, n * n);
				section.markColumnPopulated(columnCoord(cx, l), columnCoord(cz, l));
				touched.add(key);
			}
		}
		return new IngestResult(touched, created);
	}

	/**
	 * Applies one server-verified block change to its L0 cell, reusing the
	 * cell's existing biome and light bits (the correct light arrives later
	 * via the debounced {@link #applySectionLight} path), and enqueues the
	 * incremental remip. Worker thread.
	 *
	 * <p>Returns false when the containing section is not resident — the
	 * area has not been ingested yet (or was evicted), so the block will be
	 * captured by the snapshot path instead; writing a lone cell into a
	 * fresh, otherwise-empty section would fabricate terrain the population
	 * mask says was never captured.
	 */
	static boolean applyBlockUpdate(VoxelWorld world, VoxelPalettes palettes, VoxelMipper mipper,
			BlockPos pos, BlockState newState) {
		long key = SectionKey.ofBlock(0, pos.getX(), pos.getY(), pos.getZ());
		VoxelSection section = world.get(key);
		if (section == null) {
			return false;
		}
		int x = pos.getX() & VoxelConstants.SECTION_MASK;
		int y = pos.getY() & VoxelConstants.SECTION_MASK;
		int z = pos.getZ() & VoxelConstants.SECTION_MASK;
		int idx = VoxelSection.cellIndex(x, y, z);
		// Atomic read-modify-write under the section monitor: reading the old
		// biome/light and storing the new state in one lock hold is required so
		// a concurrent full-cell writer (another block-update batch or an
		// ingest writeBatch) cannot be clobbered by a stale-based store. A
		// no-op state change (e.g. rotation-only, collapsed in the palette)
		// returns false, skipping the remip.
		if (section.setStateId(idx, palettes.idFor(newState))) {
			mipper.enqueueRemip(key, idx);
		}
		return true;
	}

	/**
	 * Rewrites the light bits of every L0 cell covered by one vanilla
	 * section from freshly copied layers — the tail of the debounced
	 * light-refresh path (design section 3.5). Cells whose light did not
	 * change are left untouched, so a torch placed in a lit room enqueues a
	 * handful of remips, not 4096. Worker thread; the layers are
	 * client-thread copies owned by this call.
	 *
	 * <p>Only L0 is written directly; parent light is an average, so the
	 * remip queue propagates it. Returns the number of changed cells — the
	 * caller dirties this section's mesh region when it is nonzero (parents
	 * surface through {@code VoxelMipper.processRemips}).
	 */
	static int applySectionLight(VoxelWorld world, VoxelMipper mipper,
			int chunkX, int sectionY, int chunkZ,
			DataLayer block, DataLayer sky, boolean aboveHighestFilled) {
		long key = targetKey(0, chunkX, sectionY, chunkZ);
		VoxelSection section = world.get(key);
		if (section == null) {
			return 0;
		}
		int bx = baseCell(chunkX, 0);
		int by = baseCell(sectionY, 0);
		int bz = baseCell(chunkZ, 0);
		int skyFallback = aboveHighestFilled ? 15 : 0;
		int changed = 0;
		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					int bl = block == null ? 0 : block.get(x, y, z);
					int sl = sky == null ? skyFallback : sky.get(x, y, z);
					int idx = VoxelSection.cellIndex(bx + x, by + y, bz + z);
					// Atomic RMW under the monitor (see applyBlockUpdate): the
					// light rewrite must not clobber a concurrent block-state
					// write back to its old state.
					if (section.setLight(idx, bl, sl)) {
						mipper.enqueueRemip(key, idx);
						changed++;
					}
				}
			}
		}
		return changed;
	}

	/** Key of the level-l section containing vanilla section (cx, sy, cz). */
	static long targetKey(int level, int cx, int sy, int cz) {
		return SectionKey.pack(level, cx >> (level + 1), sy >> (level + 1), cz >> (level + 1));
	}

	/**
	 * Base cell offset of a vanilla section inside its level-l target:
	 * {@code ((c & ((2<<l)-1)) << 4) >> l}. The {@code &} is floorMod for
	 * powers of two, so negative section coords are safe; at l=0 this is
	 * (c&1)*16 — an L0 section spans 2x2x2 vanilla sections.
	 */
	private static int baseCell(int vanillaSectionCoord, int level) {
		return ((vanillaSectionCoord & ((2 << level) - 1)) << 4) >> level;
	}

	/** Local chunk-column coordinate of a chunk inside its level-l target section. */
	private static int columnCoord(int chunkCoord, int level) {
		return chunkCoord & ((2 << level) - 1);
	}

	private static boolean anyResidentTarget(VoxelWorld world, int cx, int sy, int cz) {
		for (int l = 0; l <= VoxelConstants.MAX_LEVEL; l++) {
			if (world.get(targetKey(l, cx, sy, cz)) != null) {
				return true;
			}
		}
		return false;
	}
}
