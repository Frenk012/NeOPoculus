package net.irisshaders.iris.horizon;

import net.irisshaders.iris.Iris;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Configuration for the Horizon extended-distance LOD renderer.
 * Stored separately from the main Iris config so that external tools
 * and launchers depending on oculus.properties are unaffected.
 */
public class HorizonConfig {
	private static final HorizonConfig INSTANCE = new HorizonConfig();

	private boolean enabled = true;
	/**
	 * Maximum LOD render distance, in chunks, measured from the camera.
	 */
	private int lodDistanceChunks = 256;
	/**
	 * Minimum cell size (blocks per LOD cell). 1 keeps the nearest LOD ring
	 * at full block resolution, visually seamless with real chunks.
	 */
	private int baseLodScale = 1;
	/**
	 * Distance at which cells are still one block (with baseLodScale 1);
	 * past it the cell size doubles with every doubling of distance, which
	 * keeps the on-screen detail density constant. With 1024m the coarsest
	 * level (64 blocks) is only reached around 4000 chunks away.
	 */
	private int lodRingWidth = 1024;
	/**
	 * How many freshly-built region meshes may be uploaded to the GPU per
	 * frame. Keeps frame pacing stable while exploring.
	 */
	private int maxUploadsPerFrame = 4;
	/**
	 * Interval, in seconds, between asynchronous saves of dirty LOD data.
	 */
	private int saveIntervalSeconds = 60;
	/**
	 * Background threads for meshing and disk IO. Applied at game start.
	 */
	private int workerThreads = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() / 4));
	/**
	 * Whether to render LOD terrain while a shader pack is active. The LOD
	 * pass renders into the shader pipeline's terrain buffers with flat
	 * shading; disable if a specific pack misbehaves.
	 */
	private boolean renderWithShaders = true;
	/**
	 * Which LOD engine drives the extended horizon. false = "classic", the
	 * proven 2.5D color-extrusion renderer; true = "voxel", the experimental
	 * textured voxel engine (docs/horizon-voxel/DESIGN.md). Applied on
	 * world reload.
	 */
	private boolean voxelEngine = false;
	/**
	 * RAM budget for resident voxel sections (the WARM tier), in MiB.
	 */
	private int voxelMemoryBudgetMb = 256;
	/**
	 * VRAM ceiling for voxel LOD meshes + atlases, in MiB. When exceeded the
	 * effective LOD distance is clamped down (logged once).
	 */
	private int maxLodVramMb = 512;
	/**
	 * Byte budget for voxel mesh uploads per frame, alongside the
	 * maxUploadsPerFrame mesh-count budget.
	 */
	private int maxUploadBytesPerFrame = 8 * 1024 * 1024;
	/**
	 * Disk budget for LOD downloaded from a server, in MiB, per world. Reaching
	 * it stops the client accepting more (it never deletes anything on its own),
	 * so distant terrain simply stops filling in — which is why the player is
	 * told when it happens. Sized for real worlds: a 300 m radius measured about
	 * 5 MB, and area grows with the square of the radius, so a 4 km
	 * pre-generated server lands near 900 MB.
	 */
	private int serverLodDiskBudgetMb = 2048;
	/**
	 * Delete the farthest cached LOD regions when the disk budget is reached,
	 * instead of refusing to store more. Off by default: silently deleting a
	 * player's cached terrain should be something they asked for.
	 */
	private boolean lodAutoClean = false;
	/**
	 * Draw distant terrain through the pack's own gbuffers_terrain even when it
	 * ships a dh program. A dh program is vertex-coloured by design and never
	 * samples a texture, so its LOD is flat colour per quad; the terrain program
	 * samples our photo atlas and shows real block textures. The trade is the
	 * pack's dedicated distant-terrain handling — its own LOD fog and its
	 * dhMaterialId branches — which only the dh program has.
	 */
	private boolean texturedLodUnderShaders = false;
	/**
	 * Emit one quad per cell instead of merging runs of identical faces.
	 * A shaderpack's terrain program computes its own texture coordinate, so a
	 * merged quad can only stretch one atlas slot across the run; one quad per
	 * cell is what makes distant block textures read correctly under shaders.
	 * Costs quads, which is why it can be turned off again.
	 */
	private boolean perCellLodTextures = true;
	/** Armed state of the GUI's cache-clear action; paired with {@link #lodClearConfirm}. */
	private boolean lodClearArmed = false;
	/** Second half of the GUI's cache-clear action; both must be on for it to run. */
	private boolean lodClearConfirm = false;

	private HorizonConfig() {
		load();
	}

	public static HorizonConfig get() {
		return INSTANCE;
	}

	private Path path() {
		return FMLPaths.CONFIGDIR.get().resolve("neoculus-horizon.properties");
	}

	public void load() {
		Properties props = new Properties();
		Path file = path();
		if (Files.exists(file)) {
			try (InputStream in = Files.newInputStream(file)) {
				props.load(in);
			} catch (IOException e) {
				Iris.logger.error("Horizon: failed to read config, using defaults", e);
			}
		}

		enabled = Boolean.parseBoolean(props.getProperty("enabled", Boolean.toString(enabled)));
		lodDistanceChunks = clamp(parseInt(props, "lodDistanceChunks", lodDistanceChunks), 16, 4096);
		baseLodScale = clampPow2(parseInt(props, "baseLodScale", baseLodScale), 1, 16);
		lodRingWidth = clamp(parseInt(props, "lodRingWidth", lodRingWidth), 256, 8192);
		maxUploadsPerFrame = clamp(parseInt(props, "maxUploadsPerFrame", maxUploadsPerFrame), 1, 64);
		saveIntervalSeconds = clamp(parseInt(props, "saveIntervalSeconds", saveIntervalSeconds), 10, 3600);
		workerThreads = clamp(parseInt(props, "workerThreads", workerThreads), 1, 4);
		renderWithShaders = Boolean.parseBoolean(props.getProperty("renderWithShaders", Boolean.toString(renderWithShaders)));
		voxelEngine = "voxel".equalsIgnoreCase(props.getProperty("engine", voxelEngine ? "voxel" : "classic").trim());
		voxelMemoryBudgetMb = clamp(parseInt(props, "voxelMemoryBudgetMb", voxelMemoryBudgetMb), 64, 2048);
		maxLodVramMb = clamp(parseInt(props, "maxLodVramMb", maxLodVramMb), 128, 4096);
		maxUploadBytesPerFrame = clamp(parseInt(props, "maxUploadBytesPerFrame", maxUploadBytesPerFrame), 1 << 20, 64 << 20);
		serverLodDiskBudgetMb = clamp(parseInt(props, "serverLodDiskBudgetMb", serverLodDiskBudgetMb), 64, 65536);
		lodAutoClean = Boolean.parseBoolean(props.getProperty("lodAutoClean", Boolean.toString(lodAutoClean)));
		texturedLodUnderShaders = Boolean.parseBoolean(props.getProperty("texturedLodUnderShaders", Boolean.toString(texturedLodUnderShaders)));
		perCellLodTextures = Boolean.parseBoolean(props.getProperty("perCellLodTextures", Boolean.toString(perCellLodTextures)));

		if (!Files.exists(file)) {
			save();
		}
	}

	public void save() {
		Properties props = new Properties();
		props.setProperty("enabled", Boolean.toString(enabled));
		props.setProperty("lodDistanceChunks", Integer.toString(lodDistanceChunks));
		props.setProperty("baseLodScale", Integer.toString(baseLodScale));
		props.setProperty("lodRingWidth", Integer.toString(lodRingWidth));
		props.setProperty("maxUploadsPerFrame", Integer.toString(maxUploadsPerFrame));
		props.setProperty("saveIntervalSeconds", Integer.toString(saveIntervalSeconds));
		props.setProperty("workerThreads", Integer.toString(workerThreads));
		props.setProperty("renderWithShaders", Boolean.toString(renderWithShaders));
		props.setProperty("engine", voxelEngine ? "voxel" : "classic");
		props.setProperty("voxelMemoryBudgetMb", Integer.toString(voxelMemoryBudgetMb));
		props.setProperty("serverLodDiskBudgetMb", Integer.toString(serverLodDiskBudgetMb));
		props.setProperty("lodAutoClean", Boolean.toString(lodAutoClean));
		props.setProperty("texturedLodUnderShaders", Boolean.toString(texturedLodUnderShaders));
		props.setProperty("perCellLodTextures", Boolean.toString(perCellLodTextures));
		props.setProperty("maxLodVramMb", Integer.toString(maxLodVramMb));
		props.setProperty("maxUploadBytesPerFrame", Integer.toString(maxUploadBytesPerFrame));

		try (OutputStream out = Files.newOutputStream(path())) {
			props.store(out, "NeOculus Horizon extended LOD renderer settings");
		} catch (IOException e) {
			Iris.logger.error("Horizon: failed to save config", e);
		}
	}

	private static int parseInt(Properties props, String key, int fallback) {
		try {
			return Integer.parseInt(props.getProperty(key, Integer.toString(fallback)).trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	private static int clampPow2(int v, int min, int max) {
		int p = Integer.highestOneBit(clamp(v, min, max));
		return Math.max(min, p);
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public int getLodDistanceChunks() {
		return lodDistanceChunks;
	}

	public void setLodDistanceChunks(int value) {
		this.lodDistanceChunks = clamp(value, 16, 4096);
	}

	public void setBaseLodScale(int value) {
		this.baseLodScale = clampPow2(value, 1, 16);
	}

	public void setLodRingWidth(int value) {
		this.lodRingWidth = clamp(value, 256, 8192);
	}

	public void setMaxUploadsPerFrame(int value) {
		this.maxUploadsPerFrame = clamp(value, 1, 64);
	}

	public void setSaveIntervalSeconds(int value) {
		this.saveIntervalSeconds = clamp(value, 10, 3600);
	}

	public int getWorkerThreads() {
		return workerThreads;
	}

	public void setWorkerThreads(int value) {
		this.workerThreads = clamp(value, 1, 4);
	}

	public void setRenderWithShaders(boolean value) {
		this.renderWithShaders = value;
	}

	public int getLodDistanceBlocks() {
		return lodDistanceChunks * 16;
	}

	public int getBaseLodScale() {
		return baseLodScale;
	}

	public int getLodRingWidth() {
		return lodRingWidth;
	}

	public int getMaxUploadsPerFrame() {
		return maxUploadsPerFrame;
	}

	public int getSaveIntervalSeconds() {
		return saveIntervalSeconds;
	}

	public boolean shouldRenderWithShaders() {
		return renderWithShaders;
	}

	public boolean isVoxelEngine() {
		return voxelEngine;
	}

	public void setVoxelEngine(boolean value) {
		this.voxelEngine = value;
	}

	public int getVoxelMemoryBudgetMb() {
		return voxelMemoryBudgetMb;
	}

	public void setVoxelMemoryBudgetMb(int value) {
		this.voxelMemoryBudgetMb = clamp(value, 64, 2048);
	}

	public int getServerLodDiskBudgetMb() {
		return serverLodDiskBudgetMb;
	}

	public void setServerLodDiskBudgetMb(int value) {
		this.serverLodDiskBudgetMb = clamp(value, 64, 65536);
	}

	public boolean isPerCellLodTextures() {
		return perCellLodTextures;
	}

	public void setPerCellLodTextures(boolean value) {
		this.perCellLodTextures = value;
	}

	public boolean isTexturedLodUnderShaders() {
		return texturedLodUnderShaders;
	}

	public void setTexturedLodUnderShaders(boolean value) {
		this.texturedLodUnderShaders = value;
	}

	public boolean isLodAutoClean() {
		return lodAutoClean;
	}

	public void setLodAutoClean(boolean value) {
		this.lodAutoClean = value;
	}

	public boolean isLodClearArmed() {
		return lodClearArmed;
	}

	public void setLodClearArmed(boolean value) {
		this.lodClearArmed = value;
	}

	public boolean isLodClearConfirm() {
		return lodClearConfirm;
	}

	public void setLodClearConfirm(boolean value) {
		this.lodClearConfirm = value;
	}

	public int getMaxLodVramMb() {
		return maxLodVramMb;
	}

	public void setMaxLodVramMb(int value) {
		this.maxLodVramMb = clamp(value, 128, 4096);
	}

	public int getMaxUploadBytesPerFrame() {
		return maxUploadBytesPerFrame;
	}

	public void setMaxUploadBytesPerFrame(int value) {
		this.maxUploadBytesPerFrame = clamp(value, 1 << 20, 64 << 20);
	}
}
