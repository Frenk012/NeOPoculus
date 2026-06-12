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
	private int lodDistanceChunks = 128;
	/**
	 * Base cell size (blocks per LOD cell) for the nearest LOD ring. Each
	 * successive ring doubles the cell size.
	 */
	private int baseLodScale = 4;
	/**
	 * Width of each LOD ring, in blocks. Past each ring boundary the LOD
	 * cell size doubles, halving geometry density.
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
	 * Whether to render LOD terrain while a shader pack is active. The LOD
	 * pass renders into the shader pipeline's terrain buffers with flat
	 * shading; disable if a specific pack misbehaves.
	 */
	private boolean renderWithShaders = true;

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
		lodDistanceChunks = clamp(parseInt(props, "lodDistanceChunks", lodDistanceChunks), 16, 1024);
		baseLodScale = clampPow2(parseInt(props, "baseLodScale", baseLodScale), 1, 16);
		lodRingWidth = clamp(parseInt(props, "lodRingWidth", lodRingWidth), 256, 8192);
		maxUploadsPerFrame = clamp(parseInt(props, "maxUploadsPerFrame", maxUploadsPerFrame), 1, 64);
		saveIntervalSeconds = clamp(parseInt(props, "saveIntervalSeconds", saveIntervalSeconds), 10, 3600);
		renderWithShaders = Boolean.parseBoolean(props.getProperty("renderWithShaders", Boolean.toString(renderWithShaders)));

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
		props.setProperty("renderWithShaders", Boolean.toString(renderWithShaders));

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
		this.lodDistanceChunks = clamp(value, 16, 1024);
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
}
