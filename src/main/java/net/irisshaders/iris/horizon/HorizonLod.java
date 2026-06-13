package net.irisshaders.iris.horizon;

import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.Iris;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.fml.loading.FMLPaths;
import org.joml.Matrix4f;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Horizon: NeOculus's built-in extended-distance LOD terrain system.
 *
 * Explored chunks are downsampled into compact column data, persisted to
 * disk per world and dimension, and rendered far beyond the server view
 * distance as simplified terrain whose detail halves with each distance
 * ring. Everything heavy (disk IO, meshing) runs on one background worker;
 * the render thread only uploads finished buffers and issues draws.
 */
public final class HorizonLod {
	public static final HorizonLod INSTANCE = new HorizonLod();

	private final LodRenderer renderer = new LodRenderer();
	private final ExecutorService worker = Executors.newFixedThreadPool(
		HorizonConfig.get().getWorkerThreads(), r -> {
			Thread t = new Thread(r, "NeOPoculus Horizon Worker");
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY + 1);
			return t;
		});

	private volatile LodWorld world;
	private volatile LodStorage storage;
	private volatile int worldMinY;
	/** Dimension the current LodWorld belongs to; guards stray events. */
	private volatile net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> worldDimension;
	/** Render regions whose source data changed since their last mesh. */
	private final Set<Long> dirtyRenderRegions = ConcurrentHashMap.newKeySet();
	/** Render regions known to contain no data, to avoid rescheduling. */
	private final Set<Long> emptyRenderRegions = ConcurrentHashMap.newKeySet();

	private int tickCounter;
	private boolean registered;

	/** Incremental scan cursor: ring currently being swept and its center. */
	private int scanRing;
	private int scanCenterRx = Integer.MIN_VALUE;
	private int scanCenterRz = Integer.MIN_VALUE;

	private static final int MAX_SCHEDULED_PER_TICK = 32;
	private static final int MAX_VISITS_PER_TICK = 16384;

	private HorizonLod() {
	}

	public static void init() {
		if (INSTANCE.registered || !net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
			return;
		}
		INSTANCE.registered = true;
		NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ChunkEvent.Load.class, INSTANCE::onChunkLoad);
		NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ChunkEvent.Unload.class, INSTANCE::onChunkUnload);
		NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, LevelEvent.Unload.class, INSTANCE::onLevelUnload);
		NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ClientTickEvent.Post.class, INSTANCE::onClientTick);
		Iris.logger.info("Horizon extended LOD system initialized");
	}

	public boolean isActive() {
		if (!HorizonConfig.get().isEnabled() || world == null) {
			return false;
		}
		return HorizonConfig.get().shouldRenderWithShaders() || Iris.getCurrentPack().isEmpty();
	}

	/**
	 * Called by the settings screen after a mesh-affecting option changes:
	 * drops every built mesh so the scan loop rebuilds with the new values.
	 * Captured chunk data and on-disk storage are untouched.
	 */
	public void onConfigChanged() {
		emptyRenderRegions.clear();
		dirtyRenderRegions.clear();
		RenderSystem.recordRenderCall(renderer::clear);
	}

	/** Extends the projection far plane so LOD terrain is not clipped. */
	public float extendFarPlane(float vanillaFarPlane) {
		if (!isActive()) {
			return vanillaFarPlane;
		}
		return Math.max(vanillaFarPlane, HorizonConfig.get().getLodDistanceBlocks() * 1.6f);
	}

	private void onChunkLoad(ChunkEvent.Load event) {
		if (!HorizonConfig.get().isEnabled()) return;
		if (!(event.getLevel() instanceof ClientLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) {
			return;
		}
		ensureWorld(level);
		captureChunk(chunk);
	}

	private void onChunkUnload(ChunkEvent.Unload event) {
		if (!HorizonConfig.get().isEnabled()) return;
		if (!(event.getLevel() instanceof ClientLevel) || !(event.getChunk() instanceof LevelChunk chunk)) {
			return;
		}
		// Final snapshot: catches any block changes made while loaded.
		captureChunk(chunk);
	}

	private void captureChunk(LevelChunk chunk) {
		LodWorld w = world;
		if (w == null) {
			return;
		}
		// A stray event from a different dimension (mods firing events out
		// of order during dimension switches) must never pollute this world.
		if (worldDimension != null && chunk.getLevel().dimension() != worldDimension) {
			return;
		}
		try {
			LodChunk lod = LodCapture.capture(chunk);
			if (w.put(lod)) {
				markRenderRegionsDirty(lod.chunkX, lod.chunkZ);
			}
		} catch (Throwable t) {
			// A capture failure (exotic modded block states, mid-reload
			// texture access) must never crash the chunk load path.
			Iris.logger.error("Horizon: failed to capture chunk " + chunk.getPos(), t);
		}
	}

	private void markRenderRegionsDirty(int chunkX, int chunkZ) {
		// A chunk influences its own render region and, via the one-cell
		// sampling border, neighbor regions — but only when it actually
		// touches their edge. Marking all 9 unconditionally caused ~9x the
		// necessary remeshing for interior chunks.
		int rx = chunkX >> LodMesher.REGION_CHUNK_BITS;
		int rz = chunkZ >> LodMesher.REGION_CHUNK_BITS;
		int lx = chunkX & ((1 << LodMesher.REGION_CHUNK_BITS) - 1);
		int lz = chunkZ & ((1 << LodMesher.REGION_CHUNK_BITS) - 1);
		int last = (1 << LodMesher.REGION_CHUNK_BITS) - 1;

		for (int dx = (lx == 0 ? -1 : 0); dx <= (lx == last ? 1 : 0); dx++) {
			for (int dz = (lz == 0 ? -1 : 0); dz <= (lz == last ? 1 : 0); dz++) {
				long key = LodStorage.regionKey(rx + dx, rz + dz);
				dirtyRenderRegions.add(key);
				emptyRenderRegions.remove(key);
			}
		}
	}

	private void ensureWorld(ClientLevel level) {
		if (world != null) {
			return;
		}
		synchronized (this) {
			if (world != null) {
				return;
			}
			Minecraft mc = Minecraft.getInstance();
			String worldId;
			if (mc.getSingleplayerServer() != null) {
				worldId = "local_" + mc.getSingleplayerServer().getWorldData().getLevelName();
			} else if (mc.getCurrentServer() != null) {
				worldId = "server_" + mc.getCurrentServer().ip;
			} else if (mc.getConnection() != null && mc.getConnection().getConnection() != null
				&& mc.getConnection().getConnection().getRemoteAddress() != null) {
				// Realms and exotic connections: key by the remote address so
				// different worlds never share (and cross-pollute) LOD data.
				worldId = "remote_" + mc.getConnection().getConnection().getRemoteAddress();
			} else {
				worldId = "unknown";
			}
			String dimensionId = level.dimension().location().toString();
			worldMinY = level.getMinBuildHeight();
			worldDimension = level.dimension();
			storage = new LodStorage(FMLPaths.GAMEDIR.get(), worldId, dimensionId);
			world = new LodWorld();
			Iris.logger.info("Horizon: LOD world ready for " + worldId + " / " + dimensionId);
		}
	}

	private void onLevelUnload(LevelEvent.Unload event) {
		if (!(event.getLevel() instanceof ClientLevel)) {
			return;
		}
		LodWorld w = world;
		LodStorage s = storage;
		world = null;
		storage = null;
		worldDimension = null;
		dirtyRenderRegions.clear();
		emptyRenderRegions.clear();
		if (w != null && s != null) {
			// Flush every dirty storage region before dropping the world.
			// Failures are retried once; afterwards the world is gone and
			// the data only existed in memory, so log loudly.
			Set<Long> dirty = w.drainDirtyRegions();
			worker.submit(() -> {
				for (long key : dirty) {
					if (!s.saveRegion(w, (int) key, (int) (key >> 32))
						&& !s.saveRegion(w, (int) key, (int) (key >> 32))) {
						Iris.logger.error("Horizon: lost LOD data for storage region "
							+ (int) key + "," + (int) (key >> 32) + " (save failed twice on unload)");
					}
				}
			});
		}
		RenderSystem.recordRenderCall(renderer::clear);
	}

	private void onClientTick(ClientTickEvent.Post event) {
		if (!isActive()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return;
		}

		tickCounter++;
		scheduleMeshes(mc);

		// Empty-region markers are tiny but unbounded while exploring; a
		// periodic reset only costs a re-probe of genuinely empty areas.
		if (emptyRenderRegions.size() > 100_000) {
			emptyRenderRegions.clear();
		}

		int saveTicks = HorizonConfig.get().getSaveIntervalSeconds() * 20;
		if (tickCounter % saveTicks == 0) {
			LodWorld w = world;
			LodStorage s = storage;
			if (w != null && s != null) {
				Set<Long> dirty = w.drainDirtyRegions();
				int camChunkX = mc.player.chunkPosition().x;
				int camChunkZ = mc.player.chunkPosition().z;
				int keepRadius = HorizonConfig.get().getLodDistanceChunks() + 64;
				worker.submit(() -> {
					for (long key : dirty) {
						if (!s.saveRegion(w, (int) key, (int) (key >> 32))) {
							// Keep the data dirty: it stays in memory (the
							// eviction below skips dirty regions) and the
							// next interval retries the write.
							w.markDirty(key);
						}
					}
					// With everything saved, drop in-memory data far outside
					// the LOD distance; it reloads from disk on approach.
					for (long storageRegion : w.evictOutside(camChunkX, camChunkZ, keepRadius)) {
						s.markUnloaded(storageRegion);
					}
				});
			}
		}
	}

	private void scheduleMeshes(Minecraft mc) {
		LodWorld w = world;
		LodStorage s = storage;
		if (w == null || s == null) {
			return;
		}

		double camX = mc.player.getX();
		double camZ = mc.player.getZ();
		int centerRx = Math.floorDiv((int) Math.floor(camX), LodMesher.REGION_BLOCKS);
		int centerRz = Math.floorDiv((int) Math.floor(camZ), LodMesher.REGION_BLOCKS);
		int radiusRegions = (HorizonConfig.get().getLodDistanceBlocks() + LodMesher.REGION_BLOCKS - 1) / LodMesher.REGION_BLOCKS;

		renderer.evictOutside(centerRx, centerRz, radiusRegions + 2);

		if (centerRx != scanCenterRx || centerRz != scanCenterRz) {
			scanCenterRx = centerRx;
			scanCenterRz = centerRz;
			scanRing = 0;
		}

		int scheduled = 0;

		// Changed terrain first: these regions have a stale mesh regardless
		// of where the scan cursor is.
		var dirtyIt = dirtyRenderRegions.iterator();
		while (dirtyIt.hasNext() && scheduled < MAX_SCHEDULED_PER_TICK) {
			long key = dirtyIt.next();
			int rx = (int) key;
			int rz = (int) (key >> 32);
			if (Math.max(Math.abs(rx - centerRx), Math.abs(rz - centerRz)) > radiusRegions) {
				dirtyIt.remove();
				continue;
			}
			if (scheduleRegion(w, s, rx, rz, camX, camZ, true)) {
				dirtyIt.remove();
				scheduled++;
			}
			// If a build for this region is already in flight, keep it dirty
			// so the fresh data gets meshed once the current job finishes.
		}

		// Incremental outward sweep with a bounded visit budget per tick, so
		// huge LOD distances never stall the client thread. A full sweep at
		// 4096 chunks completes in a few seconds and then restarts.
		int visits = 0;
		while (scanRing <= radiusRegions && scheduled < MAX_SCHEDULED_PER_TICK && visits < MAX_VISITS_PER_TICK) {
			int ring = scanRing;
			if (ring == 0) {
				visits++;
				if (scheduleRegion(w, s, centerRx, centerRz, camX, camZ, false)) {
					scheduled++;
				}
			} else {
				for (int i = -ring; i <= ring && scheduled < MAX_SCHEDULED_PER_TICK; i++) {
					visits += 4;
					if (scheduleRegion(w, s, centerRx + i, centerRz - ring, camX, camZ, false)) scheduled++;
					if (scheduled < MAX_SCHEDULED_PER_TICK && scheduleRegion(w, s, centerRx + i, centerRz + ring, camX, camZ, false)) scheduled++;
					if (Math.abs(i) != ring) {
						if (scheduled < MAX_SCHEDULED_PER_TICK && scheduleRegion(w, s, centerRx - ring, centerRz + i, camX, camZ, false)) scheduled++;
						if (scheduled < MAX_SCHEDULED_PER_TICK && scheduleRegion(w, s, centerRx + ring, centerRz + i, camX, camZ, false)) scheduled++;
					}
				}
			}
			if (scheduled >= MAX_SCHEDULED_PER_TICK) {
				break; // resume this ring next tick
			}
			scanRing++;
		}
		if (scanRing > radiusRegions) {
			scanRing = 0; // periodic re-sweep catches ring/scale transitions
		}
	}

	/** @return true if a mesh build was queued for this region. */
	private boolean scheduleRegion(LodWorld w, LodStorage s, int rx, int rz, double camX, double camZ, boolean force) {
		long key = LodStorage.regionKey(rx, rz);
		int scale = desiredScale(rx, rz, camX, camZ);

		if (!force) {
			LodRegionMesh existing = renderer.getMesh(key);
			// Hysteresis: a mesh at the desired scale or one step finer is
			// good enough. Rebuild only when more detail is needed, or when
			// the mesh is wastefully fine (two or more steps). Halves the
			// rebuild churn from ring boundaries sweeping past as the
			// player travels.
			if (existing != null && existing.scale <= scale && scale <= existing.scale * 2) {
				return false;
			}
			if (existing == null && emptyRenderRegions.contains(key)) {
				return false;
			}
		}
		if (!renderer.markScheduled(key)) {
			return false;
		}

		final int frx = rx, frz = rz, fscale = scale;
		final int minY = worldMinY;
		final int jobEpoch = renderer.currentEpoch();
		worker.submit(() -> {
			try {
				// Pull any persisted data covering this render region (plus
				// its sampling border) into memory.
				int minBlockX = frx * LodMesher.REGION_BLOCKS - 16;
				int minBlockZ = frz * LodMesher.REGION_BLOCKS - 16;
				int maxBlockX = (frx + 1) * LodMesher.REGION_BLOCKS + 16;
				int maxBlockZ = (frz + 1) * LodMesher.REGION_BLOCKS + 16;
				for (int srx = minBlockX >> 9; srx <= maxBlockX >> 9; srx++) {
					for (int srz = minBlockZ >> 9; srz <= maxBlockZ >> 9; srz++) {
						s.loadRegionIfNeeded(w, srx, srz);
					}
				}

				LodMesher.MeshData data = LodMesher.build(w, frx, frz, fscale, minY);
				if (data == null) {
					emptyRenderRegions.add(key);
				}
				renderer.submit(key, data, jobEpoch);
			} catch (Throwable t) {
				renderer.submit(key, null, jobEpoch);
				Iris.logger.error("Horizon: mesh build failed for region " + frx + "," + frz, t);
			}
		});
		return true;
	}

	/**
	 * Cell size proportional to distance (constant screen-space error): one
	 * block per cell out to lodRingWidth, doubling with every doubling of
	 * distance after that. With the default 1024m ring the coarsest detail
	 * is only reached around 4000 chunks out.
	 *
	 * Regions in the "collar" around the real render distance are forced to
	 * full block resolution regardless of other settings: there the LOD is
	 * drawn in complete overlap with real terrain and must match it
	 * block-for-block for the seam to be invisible.
	 */
	private int desiredScale(int rx, int rz, double camX, double camZ) {
		double cx = (rx + 0.5) * LodMesher.REGION_BLOCKS - camX;
		double cz = (rz + 0.5) * LodMesher.REGION_BLOCKS - camZ;
		double dist = Math.sqrt(cx * cx + cz * cz);

		int rdBlocks = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
		// 91 ~= half the diagonal of a 128-block region: collar test uses
		// the region's nearest edge, not its center.
		if (dist - 91 < rdBlocks + 256) {
			return 1;
		}

		int proportional = Integer.highestOneBit(Math.max(1, (int) (dist / HorizonConfig.get().getLodRingWidth())));
		int scale = Math.max(HorizonConfig.get().getBaseLodScale(), proportional);
		return Math.min(scale, 64);
	}

	/**
	 * Render entry, called from the level renderer before the translucent
	 * terrain layer with the frame's matrices.
	 */
	public void render(Matrix4f modelView, Matrix4f projection) {
		if (!isActive()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null) {
			return;
		}

		renderer.processUploads(HorizonConfig.get().getMaxUploadsPerFrame());

		var camera = mc.gameRenderer.getMainCamera();
		double camX = camera.getPosition().x;
		double camY = camera.getPosition().y;
		double camZ = camera.getPosition().z;

		int lodDist = HorizonConfig.get().getLodDistanceBlocks();
		float fogStart = lodDist * 0.55f;
		float fogEnd = lodDist * 0.98f;

		float brightness;
		if (level.dimensionType().hasSkyLight()) {
			// getSkyDarken is 0 (full day) .. 11 (full night). Map to a wide
			// brightness range so the LOD visibly follows day/night even
			// without a shader pack handling it.
			float t = 1.0f - Math.min(1.0f, level.getSkyDarken() / 11.0f);
			brightness = 0.15f + t * 0.85f;
		} else {
			brightness = 0.75f;
		}

		renderer.render(modelView, projection, camX, camY, camZ,
			fogStart, fogEnd, RenderSystem.getShaderFogColor(), brightness,
			level, mc.options.getEffectiveRenderDistance());
	}
}
