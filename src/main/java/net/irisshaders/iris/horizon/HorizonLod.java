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
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "NeOculus Horizon Worker");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY + 1);
		return t;
	});

	private volatile LodWorld world;
	private volatile LodStorage storage;
	private volatile int worldMinY;
	/** Render regions whose source data changed since their last mesh. */
	private final Set<Long> dirtyRenderRegions = ConcurrentHashMap.newKeySet();
	/** Render regions known to contain no data, to avoid rescheduling. */
	private final Set<Long> emptyRenderRegions = ConcurrentHashMap.newKeySet();

	private int tickCounter;
	private boolean registered;

	private HorizonLod() {
	}

	public static void init() {
		if (INSTANCE.registered) {
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
		if (!HorizonConfig.get().isEnabled() || world == null) {
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
		LodChunk lod = LodCapture.capture(chunk);
		if (w.put(lod)) {
			markRenderRegionsDirty(lod.chunkX, lod.chunkZ);
		}
	}

	private void markRenderRegionsDirty(int chunkX, int chunkZ) {
		// A chunk influences its own render region and, via the sampling
		// border, any adjacent region it touches.
		int rx = chunkX >> LodMesher.REGION_CHUNK_BITS;
		int rz = chunkZ >> LodMesher.REGION_CHUNK_BITS;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
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
			} else {
				worldId = "unknown";
			}
			String dimensionId = level.dimension().location().toString();
			worldMinY = level.getMinBuildHeight();
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
		dirtyRenderRegions.clear();
		emptyRenderRegions.clear();
		if (w != null && s != null) {
			// Flush every dirty storage region before dropping the world.
			Set<Long> dirty = w.drainDirtyRegions();
			worker.submit(() -> {
				for (long key : dirty) {
					s.saveRegion(w, (int) key, (int) (key >> 32));
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

		int saveTicks = HorizonConfig.get().getSaveIntervalSeconds() * 20;
		if (tickCounter % saveTicks == 0) {
			LodWorld w = world;
			LodStorage s = storage;
			if (w != null && s != null) {
				Set<Long> dirty = w.drainDirtyRegions();
				if (!dirty.isEmpty()) {
					worker.submit(() -> {
						for (long key : dirty) {
							s.saveRegion(w, (int) key, (int) (key >> 32));
						}
					});
				}
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
		int centerRx = Math.floorDiv((int) camX, LodMesher.REGION_BLOCKS);
		int centerRz = Math.floorDiv((int) camZ, LodMesher.REGION_BLOCKS);
		int radiusRegions = (HorizonConfig.get().getLodDistanceBlocks() + LodMesher.REGION_BLOCKS - 1) / LodMesher.REGION_BLOCKS;

		renderer.evictOutside(centerRx, centerRz, radiusRegions + 2);

		int scheduled = 0;
		// Spiral-ish scan: ring by ring outward so near terrain meshes first.
		for (int ring = 0; ring <= radiusRegions && scheduled < 32; ring++) {
			for (int dx = -ring; dx <= ring && scheduled < 32; dx++) {
				for (int dz = -ring; dz <= ring && scheduled < 32; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
						continue; // only the ring shell
					}
					int rx = centerRx + dx;
					int rz = centerRz + dz;
					long key = LodStorage.regionKey(rx, rz);

					int scale = desiredScale(rx, rz, camX, camZ);
					boolean dirty = dirtyRenderRegions.contains(key);
					LodRegionMesh existing = renderer.getMesh(key);
					if (!dirty && existing != null && existing.scale == scale) {
						continue;
					}
					if (!dirty && existing == null && emptyRenderRegions.contains(key)) {
						continue;
					}
					if (!renderer.markScheduled(key)) {
						continue;
					}
					dirtyRenderRegions.remove(key);
					scheduled++;

					final int frx = rx, frz = rz, fscale = scale;
					final int minY = worldMinY;
					final int jobEpoch = renderer.currentEpoch();
					worker.submit(() -> {
						try {
							// Pull any persisted data covering this render
							// region (plus its sampling border) into memory.
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
				}
			}
		}
	}

	private int desiredScale(int rx, int rz, double camX, double camZ) {
		double cx = (rx + 0.5) * LodMesher.REGION_BLOCKS - camX;
		double cz = (rz + 0.5) * LodMesher.REGION_BLOCKS - camZ;
		double dist = Math.sqrt(cx * cx + cz * cz);
		int ring = (int) (dist / HorizonConfig.get().getLodRingWidth());
		int scale = HorizonConfig.get().getBaseLodScale() << Math.min(ring, 5);
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
			brightness = Math.max(0.25f, 1.0f - level.getSkyDarken() * 0.068f);
		} else {
			brightness = 0.75f;
		}

		// getEffectiveRenderDistance is already min(client option, server view
		// distance): real chunks cover everything inside it except the very
		// last ring, which LOD overlaps to hide late-loading edge chunks.
		int skipRadius = Math.max(0, (mc.options.getEffectiveRenderDistance() - 1) * 16);

		renderer.render(modelView, projection, camX, camY, camZ,
			fogStart, fogEnd, RenderSystem.getShaderFogColor(), brightness, skipRadius);
	}
}
