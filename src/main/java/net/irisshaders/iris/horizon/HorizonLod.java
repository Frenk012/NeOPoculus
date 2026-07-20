package net.irisshaders.iris.horizon;

import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.horizon.voxel.VoxelColorTable;
import net.irisshaders.iris.horizon.voxel.VoxelConstants;
import net.irisshaders.iris.horizon.voxel.VoxelEngine;
import net.irisshaders.iris.horizon.voxel.VoxelLodSelector;
import net.irisshaders.iris.horizon.voxel.VoxelMesher;
import net.irisshaders.iris.horizon.voxel.VoxelPalettes;
import net.irisshaders.iris.horizon.voxel.VoxelRegionKey;
import net.irisshaders.iris.horizon.voxel.VoxelRenderer;
import net.irisshaders.iris.horizon.voxel.VoxelStore;
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

	/**
	 * The voxel-engine orchestrator (docs/horizon-voxel/DESIGN.md), sharing
	 * the worker pool above per the unified design's thread model. Behind
	 * HorizonConfig.isVoxelEngine() the event handlers below delegate to it
	 * and the whole classic pipeline (capture, mesh, save, render) parks.
	 * Declared after the worker field: initializers run in order.
	 */
	private final VoxelEngine voxelEngine = new VoxelEngine(worker);
	/** Render-thread owner of the voxel LOD meshes (M3). Renders when engine=voxel. */
	private final VoxelRenderer voxelRenderer = new VoxelRenderer();
	/** Flat MapColor per state id — the fallback while a block's photo bake is pending (M4). */
	private final VoxelColorTable voxelColorTable = new VoxelColorTable(voxelEngine.palettes());
	/** Photo-atlas bakery: renders each block face into the atlas the voxel LOD samples (M4). */
	private final net.irisshaders.iris.horizon.voxel.model.VoxelBakery voxelBakery = new net.irisshaders.iris.horizon.voxel.model.VoxelBakery();
	/** Bakes uploaded to the atlas per frame (render thread). */
	private static final int MAX_VOXEL_BAKES_PER_FRAME = 8;
	/** Bounded voxel mesh builds queued per client tick. */
	private static final int MAX_VOXEL_SCHEDULED_PER_TICK = 32;
	/** Voxel regions a build found empty (unexplored/all-air); skipped until a chunk loads there. */
	private final Set<Long> emptyVoxelRegions = ConcurrentHashMap.newKeySet();

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

	/** Chunks awaiting LOD capture; drained a few per tick to avoid spikes when moving. */
	private record CaptureTask(LevelChunk chunk, boolean preserveColors) {
	}

	private final java.util.concurrent.ConcurrentLinkedQueue<CaptureTask> captureQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
	private static final int MAX_CAPTURES_PER_TICK = 8;

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
		NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, net.neoforged.neoforge.client.event.ViewportEvent.RenderFog.class, INSTANCE::onRenderFog);
		NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent.DebugText.class, INSTANCE::onDebugText);
		Iris.logger.info("Horizon extended LOD system initialized");
	}

	/** Voxel-engine orchestrator; the block-update mixin and the GUI reach it here. */
	public VoxelEngine voxel() {
		return voxelEngine;
	}

	/** Resource pack reload: drop the photo atlas + flat-color cache and re-mesh so textures re-bake. */
	public void onResourceReload() {
		voxelColorTable.clear();
		RenderSystem.recordRenderCall(() -> {
			voxelBakery.clear();
			voxelRenderer.clear();
		});
	}

	public boolean isActive() {
		if (!HorizonConfig.get().isEnabled() || world == null) {
			return false;
		}
		// In voxel mode the classic engine is fully parked — no capture, no
		// meshing, no rendering, no fog/far-plane overrides — even if a
		// classic world was created before the engine toggle flipped. This
		// gate is the render-side twin of the delegation in the handlers.
		if (HorizonConfig.get().isVoxelEngine()) {
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
		emptyVoxelRegions.clear();
		dirtyRenderRegions.clear();
		RenderSystem.recordRenderCall(renderer::clear);
		RenderSystem.recordRenderCall(voxelRenderer::clear);
		voxelEngine.onConfigChanged();
	}

	/**
	 * Render-thread callback from IrisRenderingPipeline.destroy(): releases
	 * the per-pipeline GL resources the LOD renderer caches (dh_terrain
	 * program + framebuffer) and re-arms the shader path.
	 */
	public void onPipelineDestroyed(Object pipeline) {
		renderer.onPipelineDestroyed(pipeline);
		voxelRenderer.onPipelineDestroyed(pipeline);
	}

	/**
	 * True when either engine is actively drawing extended terrain this frame.
	 * The classic {@link #isActive()} is false in voxel mode by design, so the
	 * far-plane extension must consult the voxel engine separately or distant
	 * voxel LOD gets clipped away.
	 */
	private boolean renderingExtended() {
		if (!HorizonConfig.get().isEnabled()) {
			return false;
		}
		if (HorizonConfig.get().isVoxelEngine()) {
			return voxelEngine.store() != null;
		}
		return isActive();
	}

	/** Extends the projection far plane so LOD terrain (classic or voxel) is not clipped. */
	public float extendFarPlane(float vanillaFarPlane) {
		if (!renderingExtended()) {
			return vanillaFarPlane;
		}
		return Math.max(vanillaFarPlane, HorizonConfig.get().getLodDistanceBlocks() * 1.6f);
	}

	/**
	 * Pushes the vanilla terrain distance-fog out to the LOD distance so the
	 * last ring of real chunks no longer fades to sky color before the LOD
	 * begins — that fade was the pale halo at the loaded-chunk edge. Sky fog
	 * (horizon haze) is left untouched, and shader packs handle their own fog.
	 */
	private void onRenderFog(net.neoforged.neoforge.client.event.ViewportEvent.RenderFog event) {
		if (!isActive() || Iris.getCurrentPack().isPresent()) {
			return;
		}
		if (event.getMode() != net.minecraft.client.renderer.FogRenderer.FogMode.FOG_TERRAIN) {
			return;
		}
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null || !level.dimensionType().hasSkyLight()) {
			return;
		}
		float far = Math.max(event.getFarPlaneDistance(), HorizonConfig.get().getLodDistanceBlocks());
		event.setFarPlaneDistance(far);
		event.setNearPlaneDistance(far * 0.95f);
		event.setCanceled(true);
	}

	private void onChunkLoad(ChunkEvent.Load event) {
		if (!HorizonConfig.get().isEnabled()) return;
		if (!(event.getLevel() instanceof ClientLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) {
			return;
		}
		if (HorizonConfig.get().isVoxelEngine()) {
			voxelEngine.onChunkLoad(level, chunk);
			// New data in this column: let its regions (one per level) be
			// re-scheduled even if a prior build found them empty.
			int cbx = chunk.getPos().getMinBlockX();
			int cbz = chunk.getPos().getMinBlockZ();
			for (int lvl = 0; lvl <= VoxelConstants.MAX_LEVEL; lvl++) {
				int span = VoxelRegionKey.regionSpanBlocks(lvl);
				emptyVoxelRegions.remove(VoxelRegionKey.pack(lvl,
					Math.floorDiv(cbx, span), Math.floorDiv(cbz, span)));
			}
			return;
		}
		ensureWorld(level);
		captureQueue.add(new CaptureTask(chunk, false));
	}

	private void onChunkUnload(ChunkEvent.Unload event) {
		if (!HorizonConfig.get().isEnabled()) return;
		if (!(event.getLevel() instanceof ClientLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) {
			return;
		}
		if (HorizonConfig.get().isVoxelEngine()) {
			voxelEngine.onChunkUnload(level, chunk);
			return;
		}
		// Final snapshot: catches any block changes made while loaded. The
		// chunk's neighbors may already be out of the client cache, so biome
		// blending degrades to defaults -- preserve stored colors for columns
		// whose shape did not change instead of clobbering good tints.
		captureQueue.add(new CaptureTask(chunk, true));
	}

	private void captureChunk(LevelChunk chunk, boolean preserveColors) {
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
			if (w.put(lod, preserveColors)) {
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
		// Always reset the voxel side too: harmless when it never ran, and
		// the engine toggle may have flipped mid-session.
		voxelEngine.onLevelUnload();
		LodWorld w = world;
		LodStorage s = storage;
		world = null;
		storage = null;
		worldDimension = null;
		dirtyRenderRegions.clear();
		emptyRenderRegions.clear();
		captureQueue.clear();
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
		RenderSystem.recordRenderCall(voxelRenderer::clear);
		emptyVoxelRegions.clear();
	}

	/** F3 overlay: the voxel engine's per-level section counters (M1 acceptance line). */
	private void onDebugText(net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent.DebugText event) {
		if (HorizonConfig.get().isEnabled() && HorizonConfig.get().isVoxelEngine()) {
			voxelEngine.addDebugText(event.getLeft());
			event.getLeft().add("Horizon/voxel render: meshes " + voxelRenderer.meshCount()
				+ " drawn " + voxelRenderer.drawnLastFrame()
				+ " built " + voxelRenderer.totalUploaded());
		}
	}

	/**
	 * Client-thread voxel mesh scheduler (M3): sweeps each LOD level's region
	 * grid within its ring annulus and submits build jobs for regions that
	 * belong to that level and are not yet meshed. Mirrors the classic
	 * scheduleMeshes but per level. Bounded per tick so huge distances never
	 * stall the client thread.
	 */
	private void scheduleVoxelMeshes(Minecraft mc) {
		VoxelStore store = voxelEngine.store();
		ClientLevel level = mc.level;
		if (store == null || level == null || mc.player == null) {
			return;
		}
		final double camX = mc.player.getX();
		final double camZ = mc.player.getZ();
		RenderSystem.recordRenderCall(() -> voxelRenderer.evict(camX, camZ));

		final int worldMinY = level.getMinBuildHeight();
		final int worldMaxY = level.getMaxBuildHeight();
		final VoxelColorTable colors = voxelColorTable;
		final VoxelPalettes palettes = voxelEngine.palettes();
		int rdBlocks = VoxelLodSelector.renderDistanceBlocks();
		int lodDist = HorizonConfig.get().getLodDistanceBlocks();

		int[] scheduled = {0};

		// Re-mesh regions whose data just grew (new chunk ingested): only the
		// ones already built — a not-yet-meshed region is left to the ring
		// sweep, so this pass just refreshes stale partial meshes into complete
		// ones instead of re-doing initial work.
		for (long key : voxelEngine.drainDirtyMeshRegions()) {
			if (scheduled[0] >= MAX_VOXEL_SCHEDULED_PER_TICK) {
				break;
			}
			if (voxelRenderer.hasMesh(key)) {
				submitVoxelBuild(store, colors, palettes, key, worldMinY, worldMaxY, scheduled);
			}
		}

		// New photo bakes landed → re-mesh the regions still showing flat
		// fallback color so they pick up the real textures. Level-triggered per
		// region (each fallback region carries the epoch it was meshed at), so a
		// region that joins the set after an epoch bump is still re-meshed rather
		// than being stranded flat by a missed global edge.
		int bakeEpoch = voxelBakery.epoch();
		for (long key : voxelRenderer.fallbackRegionsStaleAt(bakeEpoch)) {
			if (scheduled[0] >= MAX_VOXEL_SCHEDULED_PER_TICK) {
				break;
			}
			submitVoxelBuild(store, colors, palettes, key, worldMinY, worldMaxY, scheduled);
		}
		for (int lvl = 0; lvl <= VoxelConstants.MAX_LEVEL && scheduled[0] < MAX_VOXEL_SCHEDULED_PER_TICK; lvl++) {
			int span = VoxelRegionKey.regionSpanBlocks(lvl);
			int radius = VoxelLodSelector.radiusRegions(lvl);
			if (lvl == 0) {
				// Ensure the collar (which levelFor forces to L0 out to rd+256)
				// is fully swept even when it exceeds L0's own ring.
				radius = Math.max(radius, (rdBlocks + 256) / span + 2);
			}
			int camRx = Math.floorDiv((int) Math.floor(camX), span);
			int camRz = Math.floorDiv((int) Math.floor(camZ), span);
			// Sweep from the camera OUTWARD, ring by ring: the near regions (the
			// ones that actually hold data and the player can see) get the
			// per-tick budget first. A center-anchored square sweep spent the
			// whole budget on the far empty corner every tick and never reached
			// the player's own region.
			for (int ring = 0; ring <= radius && scheduled[0] < MAX_VOXEL_SCHEDULED_PER_TICK; ring++) {
				if (ring == 0) {
					trySchedule(store, colors, palettes, lvl, camRx, camRz, span, camX, camZ,
						rdBlocks, lodDist, worldMinY, worldMaxY, scheduled);
					continue;
				}
				for (int i = -ring; i <= ring && scheduled[0] < MAX_VOXEL_SCHEDULED_PER_TICK; i++) {
					trySchedule(store, colors, palettes, lvl, camRx + i, camRz - ring, span, camX, camZ,
						rdBlocks, lodDist, worldMinY, worldMaxY, scheduled);
					trySchedule(store, colors, palettes, lvl, camRx + i, camRz + ring, span, camX, camZ,
						rdBlocks, lodDist, worldMinY, worldMaxY, scheduled);
					if (Math.abs(i) != ring) {
						trySchedule(store, colors, palettes, lvl, camRx - ring, camRz + i, span, camX, camZ,
							rdBlocks, lodDist, worldMinY, worldMaxY, scheduled);
						trySchedule(store, colors, palettes, lvl, camRx + ring, camRz + i, span, camX, camZ,
							rdBlocks, lodDist, worldMinY, worldMaxY, scheduled);
					}
				}
			}
		}
	}

	/** Queues one region's build if it belongs to {@code lvl}, has data-bearing potential, and is not already meshed/empty. */
	private void trySchedule(VoxelStore store, VoxelColorTable colors, VoxelPalettes palettes,
							 int lvl, int rx, int rz, int span, double camX, double camZ,
							 int rdBlocks, int lodDist, int worldMinY, int worldMaxY, int[] scheduled) {
		if (scheduled[0] >= MAX_VOXEL_SCHEDULED_PER_TICK) {
			return;
		}
		double ccx = (rx + 0.5) * span - camX;
		double ccz = (rz + 0.5) * span - camZ;
		double dist = Math.sqrt(ccx * ccx + ccz * ccz);
		if (dist > lodDist + span) {
			return;
		}
		if (VoxelLodSelector.levelFor(dist, rdBlocks) != lvl) {
			return; // a coarser/finer level owns this region
		}
		final long key = VoxelRegionKey.pack(lvl, rx, rz);
		if (voxelRenderer.hasMesh(key) || emptyVoxelRegions.contains(key)) {
			return;
		}
		submitVoxelBuild(store, colors, palettes, key, worldMinY, worldMaxY, scheduled);
	}

	/**
	 * Submits one region build to a worker (initial mesh or dirty re-mesh),
	 * replacing any existing mesh for the key. {@code markScheduled} dedupes an
	 * in-flight build. The caller decides whether the region is eligible; this
	 * just does the submit + bookkeeping.
	 */
	private void submitVoxelBuild(VoxelStore store, VoxelColorTable colors, VoxelPalettes palettes,
								  long key, int worldMinY, int worldMaxY, int[] scheduled) {
		if (!voxelRenderer.markScheduled(key)) {
			return; // already building
		}
		emptyVoxelRegions.remove(key);
		final int lvl = VoxelRegionKey.level(key);
		final int rx = VoxelRegionKey.rx(key);
		final int rz = VoxelRegionKey.rz(key);
		final int fmin = worldMinY, fmax = worldMaxY;
		final int jobEpoch = voxelRenderer.currentEpoch();
		worker.submit(() -> {
			try {
				VoxelMesher.MeshData data = VoxelMesher.buildRegion(store, colors, palettes, voxelBakery,
					lvl, rx, rz, key, fmin, fmax);
				if (data == null) {
					// No data here (unexplored / all-air). Remember it so the
					// budget stops re-scheduling it every tick; a chunk load in
					// this area clears the flag so it retries.
					emptyVoxelRegions.add(key);
				}
				voxelRenderer.submit(key, data, jobEpoch);
			} catch (Throwable t) {
				voxelRenderer.submit(key, null, jobEpoch);
				Iris.logger.error("Horizon: voxel mesh build failed for L" + lvl
					+ " region " + rx + "," + rz, t);
			}
		});
		scheduled[0]++;
	}

	private void onClientTick(ClientTickEvent.Post event) {
		if (HorizonConfig.get().isEnabled() && HorizonConfig.get().isVoxelEngine()) {
			// Voxel path: the classic scheduler below must not run (it would
			// capture, mesh and save 2.5D data alongside the voxel engine).
			// Symmetric with the isActive() gate on the render side.
			Minecraft mcv = Minecraft.getInstance();
			voxelEngine.onClientTick(mcv);
			try {
				scheduleVoxelMeshes(mcv);
			} catch (Throwable t) {
				Iris.logger.error("Horizon: voxel mesh scheduling failed", t);
			}
			return;
		}
		if (!isActive()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return;
		}

		tickCounter++;

		// Spread chunk captures over ticks so fast movement (many chunk
		// loads at once) doesn't stall the client thread in a burst.
		for (int n = 0; n < MAX_CAPTURES_PER_TICK; n++) {
			CaptureTask task = captureQueue.poll();
			if (task == null) {
				break;
			}
			captureChunk(task.chunk(), task.preserveColors());
		}

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
		if (HorizonConfig.get().isEnabled() && HorizonConfig.get().isVoxelEngine()) {
			if (voxelEngine.store() != null) {
				voxelBakery.process(MAX_VOXEL_BAKES_PER_FRAME, voxelEngine.palettes());
				voxelRenderer.render(modelView, projection, voxelBakery);
			}
			return;
		}
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
		// Keep the contact zone with loaded chunks fog-free; only fade the
		// far horizon. A near fog start put a colored band right at the seam.
		float fogStart = lodDist * 0.80f;
		float fogEnd = lodDist * 1.0f;

		float brightness;
		if (level.dimensionType().hasSkyLight()) {
			// Drive brightness from the actual sun angle so the LOD follows
			// the visible day/night cycle (day ~1.0, night ~0.15), even when
			// no shader pack handles it.
			float f = level.getTimeOfDay(1.0f);
			float d = net.minecraft.util.Mth.clamp(net.minecraft.util.Mth.cos(f * 6.2831855f) * 2.0f + 0.5f, 0.0f, 1.0f);
			brightness = 0.15f + d * 0.85f;
		} else {
			brightness = 0.75f;
		}

		renderer.render(modelView, projection, camX, camY, camZ,
			fogStart, fogEnd, RenderSystem.getShaderFogColor(), brightness,
			level, mc.options.getEffectiveRenderDistance());
	}
}
