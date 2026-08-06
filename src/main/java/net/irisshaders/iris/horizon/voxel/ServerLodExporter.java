package net.irisshaders.iris.horizon.voxel;

import net.irisshaders.iris.Iris;

/**
 * Reads sections out of a server-side store for transfer (M5b phase B).
 *
 * <p>Lives in the engine package because the store, section and codec entry
 * points are package-private, and holds no client type: it runs on a dedicated
 * server. It hands back exactly the bytes the disk format uses, so the client
 * decodes with the same codec and no second serialisation exists to drift.
 */
public final class ServerLodExporter {
	private ServerLodExporter() {
	}

	/**
	 * The encoded cells of one section, or null when that section holds nothing
	 * worth sending — absent from the store, or entirely air.
	 *
	 * <p>Reads through {@code acquireBlocking}, so a section that has been
	 * evicted to disk is faulted back rather than reported missing; a genuinely
	 * unknown key simply yields null.
	 */
	public static byte[] exportSection(VoxelStore store, long sectionKey, long[] scratch) {
		if (store == null || scratch == null || scratch.length != VoxelConstants.SECTION_CELLS) {
			return null;
		}
		try {
			VoxelSection section = store.acquireBlocking(sectionKey);
			if (section == null || section.nonAirCount() == 0) {
				return null;
			}
			section.copyCellsInto(scratch);
			return VoxelSectionCodec.encodeCells(scratch);
		} catch (Throwable t) {
			Iris.logger.error("Horizon: could not export LOD section for transfer", t);
			return null;
		}
	}

	/** Section key from section coordinates, so callers need no key math. */
	public static long sectionKey(int level, int sx, int sy, int sz) {
		return SectionKey.pack(level, sx, sy, sz);
	}

	/** Section X for a world block X at a level. */
	public static int sectionKeyLevelX(int level, int blockX) {
		return SectionKey.blockToSection(blockX, level);
	}

	/** Section Z for a world block Z at a level. */
	public static int sectionKeyLevelZ(int level, int blockZ) {
		return SectionKey.blockToSection(blockZ, level);
	}

	/** Section-space Y bounds for a level, so a sender can walk a column. */
	public static int sectionY(int level, int blockY) {
		return SectionKey.blockToSection(blockY, level);
	}
}
