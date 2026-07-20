package net.irisshaders.iris.horizon.voxel;

import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * CLIENT-THREAD-only cheap copy of one {@link LevelChunk} into plain arrays
 * the worker pool can consume (design-data-storage.md section 3.2).
 *
 * <p>WHY the copy must stay on the client thread: chunk data is not safely
 * readable off-thread — a vanilla {@code PalettedContainer} swaps its packed
 * storage on palette resize with no happens-before edge for foreign readers,
 * so a worker reading live containers could see torn palette/storage pairs.
 * Only the copy runs here; conversion, palette registration and the mip
 * pyramid all happen on workers ({@link ChunkPyramid}, {@link VoxelIngest}).
 *
 * <p>Cost per chunk is the container copies (~4 KB packed bits + palette per
 * non-empty section, ~100 KB/chunk) plus two nibble-array copies per section
 * — well under the 1 ms target. No per-block reads happen here at all; the
 * 4096-entry decode runs on the worker via {@code getAll}.
 *
 * <p>The caller ({@code VoxelEngine}) owns all queueing: the 4-chunks/tick
 * budget, the pending cap and the retry set. This class is a pure function
 * from a live chunk to a {@link ChunkSnapshot}.
 */
final class ChunkSnapshotter {
	private ChunkSnapshotter() {
	}

	/**
	 * Immutable plain-array snapshot of one chunk. Arrays are indexed by
	 * chunk section index {@code i} (vanilla section y = {@code minSectionY
	 * + i}); {@code states[i]} is null where the section
	 * {@code hasOnlyAir()}, and {@code biomes[i]} is null exactly where
	 * {@code states[i]} is (an all-air section's biome only ever tints air).
	 *
	 * <p>{@code biomes[i]} holds the section's 64 biome quarts (4x4x4 grid,
	 * one per 4x4x4 blocks) in vanilla container order
	 * {@code (qy<<4)|(qz<<2)|qx}. WHY holders instead of a container copy:
	 * 1.21.1 declares {@code copy()} on the concrete PalettedContainer only,
	 * not on the read-only interface {@code getBiomes()} returns, and 64
	 * references are a cheaper, cast-free copy anyway.
	 *
	 * <p>Light layers may be null; convert-time semantics (design section
	 * 3.2): block null reads as 0, sky null reads as 15 above
	 * {@code highestFilledSectionIndex} and 0 at or below it — unlit sky
	 * sections are fully sky-lit, unlit buried sections are dark.
	 */
	record ChunkSnapshot(int chunkX, int chunkZ, int minSectionY, int sectionCount,
			PalettedContainer<BlockState>[] states,
			Holder<Biome>[][] biomes,
			DataLayer[] blockLight, DataLayer[] skyLight,
			int highestFilledSectionIndex) {

		/** Vanilla section y coordinate for a section index. */
		int sectionY(int index) {
			return minSectionY + index;
		}

		/** True when every section is all-air (nothing to ingest unless targets are resident). */
		boolean hasOnlyAir() {
			for (PalettedContainer<BlockState> section : states) {
				if (section != null) {
					return false;
				}
			}
			return true;
		}
	}

	/**
	 * Copies one chunk. Client thread only; target well under 1 ms.
	 */
	@SuppressWarnings("unchecked")
	static ChunkSnapshot snapshot(LevelChunk chunk) {
		int chunkX = chunk.getPos().x;
		int chunkZ = chunk.getPos().z;
		int minSectionY = chunk.getMinSection();
		int sectionCount = chunk.getSectionsCount();
		LevelChunkSection[] sections = chunk.getSections();

		PalettedContainer<BlockState>[] states =
			(PalettedContainer<BlockState>[]) new PalettedContainer<?>[sectionCount];
		Holder<Biome>[][] biomes = (Holder<Biome>[][]) new Holder<?>[sectionCount][];
		DataLayer[] blockLight = new DataLayer[sectionCount];
		DataLayer[] skyLight = new DataLayer[sectionCount];

		LevelLightEngine lightEngine = chunk.getLevel().getLightEngine();
		LayerLightEventListener blockListener = lightEngine.getLayerListener(LightLayer.BLOCK);
		LayerLightEventListener skyListener = lightEngine.getLayerListener(LightLayer.SKY);

		for (int i = 0; i < sectionCount; i++) {
			LevelChunkSection section = sections[i];
			if (!section.hasOnlyAir()) {
				states[i] = section.getStates().copy();
				biomes[i] = copyBiomes(section.getBiomes());
			}
			// Light is captured for every section, air-only ones included:
			// air cells written over resident sections (mined-out terrain)
			// carry the light the mesher will shade neighboring faces with.
			int sectionY = minSectionY + i;
			SectionPos pos = SectionPos.of(chunkX, sectionY, chunkZ);
			DataLayer block = blockListener.getDataLayerData(pos);
			if (block != null) {
				blockLight[i] = block.copy();
			}
			DataLayer sky = skyListener.getDataLayerData(pos);
			if (sky != null) {
				skyLight[i] = sky.copy();
			}
		}

		return new ChunkSnapshot(chunkX, chunkZ, minSectionY, sectionCount,
			states, biomes, blockLight, skyLight, chunk.getHighestFilledSectionIndex());
	}

	/**
	 * Copies the 64 biome quarts out of a section's read-only container by
	 * reading every quart cell. WHY not {@code PalettedContainerRO.getAll}: like
	 * {@code PalettedContainer.getAll} for states, it enumerates the palette's
	 * unique values (once per distinct biome), NOT the 64 cells — so a
	 * single-biome section filled only {@code copy[0]} and left every other
	 * quart null (treated as plains), which is why distant LOD tinted almost
	 * everything as plains regardless of biome. The index {@code (qy<<4)|(qz<<2)|qx}
	 * matches both the vanilla biome container and {@code ChunkPyramid.resolveBiomes}.
	 */
	@SuppressWarnings("unchecked")
	private static Holder<Biome>[] copyBiomes(PalettedContainerRO<Holder<Biome>> container) {
		Holder<Biome>[] copy = (Holder<Biome>[]) new Holder<?>[VoxelConstants.QUARTS_PER_VANILLA_SECTION];
		for (int qy = 0; qy < 4; qy++) {
			for (int qz = 0; qz < 4; qz++) {
				for (int qx = 0; qx < 4; qx++) {
					copy[(qy << 4) | (qz << 2) | qx] = container.get(qx, qy, qz);
				}
			}
		}
		return copy;
	}
}
