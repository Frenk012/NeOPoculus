package net.irisshaders.iris.horizon.voxel;

import net.irisshaders.iris.Iris;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The dedicated server's own LOD residency: one {@link VoxelStore} per
 * dimension and one shared {@link VoxelPalettes}, rooted inside the world folder
 * at {@code <world>/horizon-lod/}. This is what lets a headless server generate
 * and hold LOD without a client engine (M5b phase B).
 *
 * <p>Lives in the engine package because the store, palette and ingest entry
 * points are package-private, but it references no client type: it must load in
 * a JVM that has none. Whatever it writes is what clients will later be sent, so
 * it deliberately reuses the very same capture and ingest code the client uses —
 * a second implementation would drift and the cells would stop meaning the same
 * thing on both sides.
 */
public final class ServerVoxelStores {
	private final MinecraftServer server;
	private final Path lodRoot;
	private final VoxelPalettes palettes = new VoxelPalettes();
	private final Map<ResourceKey<Level>, VoxelStore> stores = new ConcurrentHashMap<>();
	private final ExecutorService worker;
	private boolean paletteLoaded;

	public ServerVoxelStores(MinecraftServer server) {
		this.server = server;
		this.lodRoot = server.getWorldPath(LevelResource.ROOT).resolve("horizon-lod");
		this.worker = Executors.newFixedThreadPool(2, r -> {
			Thread t = new Thread(r, "Horizon LOD Server Worker");
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY + 1);
			return t;
		});
	}

	public VoxelPalettes palettes() {
		return palettes;
	}

	/** The store for a dimension, created (and its palette loaded once) on first use. Server thread. */
	public synchronized VoxelStore storeFor(ServerLevel level) {
		loadPaletteOnce();
		return stores.computeIfAbsent(level.dimension(), key -> {
			VoxelRegionStorage disk = new VoxelRegionStorage(lodRoot, key.location().toString());
			VoxelStore store = new VoxelStore(new VoxelWorld(), palettes, disk, paletteFile());
			Iris.logger.info("Horizon: server LOD store ready for " + key.location());
			return store;
		});
	}

	private Path paletteFile() {
		return lodRoot.resolve("palette.nbt");
	}

	/**
	 * Loads the persisted palette exactly once, before any id is handed out.
	 * Ids are assigned in first-encounter order, so a palette loaded after
	 * ingestion had started would collide with ids already written into cells.
	 */
	private void loadPaletteOnce() {
		if (paletteLoaded) {
			return;
		}
		paletteLoaded = true;
		try {
			var registries = server.registryAccess();
			palettes.load(paletteFile(),
				registries.lookupOrThrow(Registries.BLOCK),
				registries.registryOrThrow(Registries.BIOME));
		} catch (Throwable t) {
			Iris.logger.error("Horizon: server LOD palette load failed; starting fresh", t);
		}
	}

	/**
	 * Captures one chunk into the server's LOD. Server thread — it owns the
	 * chunk's containers.
	 *
	 * <p>Returns false when the chunk's light has not settled, so the caller
	 * retries instead of baking darkness in permanently. Server light is what
	 * clients mirror, so this gate is the same one the client capture path uses.
	 *
	 * @return true when the chunk was submitted for ingest.
	 */
	public boolean captureChunk(ServerLevel level, LevelChunk chunk) {
		if (chunk == null) {
			return false;
		}
		VoxelStore store = storeFor(level);
		if (!VoxelLightReadiness.isReady(chunk)) {
			return false;
		}
		try {
			var snapshot = ChunkSnapshotter.snapshot(chunk, true);
			if (snapshot == null) {
				return true;
			}
			worker.submit(() -> {
				try {
					VoxelIngest.ingest(snapshot, store, palettes);
				} catch (Throwable t) {
					Iris.logger.error("Horizon: server LOD ingest failed for chunk " + chunk.getPos(), t);
				}
			});
			return true;
		} catch (Throwable t) {
			Iris.logger.error("Horizon: server LOD capture failed for chunk " + chunk.getPos(), t);
			return true; // do not spin on a chunk that cannot be captured
		}
	}

	/** Periodic save; called from the server tick on a slow cadence. */
	public void save() {
		for (VoxelStore store : stores.values()) {
			VoxelStore s = store;
			worker.submit(() -> {
				try {
					s.runSaveAndEvictCycle(0, 0, false);
				} catch (Throwable t) {
					Iris.logger.error("Horizon: server LOD save cycle failed", t);
				}
			});
		}
	}

	/** Flushes everything to disk and stops the worker. Server shutdown. */
	public void shutdown() {
		for (VoxelStore store : stores.values()) {
			try {
				store.flushPalette();
				store.flushAll();
			} catch (Throwable t) {
				Iris.logger.error("Horizon: server LOD flush failed on shutdown", t);
			}
		}
		stores.clear();
		worker.shutdown();
	}
}
