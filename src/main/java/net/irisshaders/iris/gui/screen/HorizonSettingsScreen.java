package net.irisshaders.iris.gui.screen;

import net.irisshaders.iris.horizon.HorizonConfig;
import net.irisshaders.iris.horizon.HorizonLod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/**
 * In-game settings for the Horizon extended LOD renderer. Every option
 * applies immediately: toggles take effect on the next frame, geometry
 * options trigger an async remesh, and everything is persisted to
 * neoculus-horizon.properties on change.
 */
public class HorizonSettingsScreen extends OptionsSubScreen {
	public HorizonSettingsScreen(Screen lastScreen) {
		super(lastScreen, Minecraft.getInstance().options, Component.translatable("options.iris.horizon.title"));
	}

	@Override
	protected void addOptions() {
		HorizonConfig config = HorizonConfig.get();

		OptionInstance<Boolean> enabled = OptionInstance.createBoolean(
			"options.iris.horizon.enabled", config.isEnabled(), value -> {
				config.setEnabled(value);
				config.save();
				HorizonLod.INSTANCE.onConfigChanged();
			});

		OptionInstance<Boolean> withShaders = OptionInstance.createBoolean(
			"options.iris.horizon.shaders", config.shouldRenderWithShaders(), value -> {
				config.setRenderWithShaders(value);
				config.save();
			});

		OptionInstance<Integer> distance = new OptionInstance<>(
			"options.iris.horizon.distance", OptionInstance.noTooltip(),
			(caption, value) -> Component.translatable("options.generic_value", caption, Component.translatable("options.chunks", value * 64)),
			new OptionInstance.IntRange(1, 64), Math.max(1, config.getLodDistanceChunks() / 64), value -> {
				config.setLodDistanceChunks(value * 64);
				config.save();
				HorizonLod.INSTANCE.onConfigChanged();
			});

		OptionInstance<Integer> baseScale = new OptionInstance<>(
			"options.iris.horizon.baseScale", OptionInstance.noTooltip(),
			(caption, value) -> Component.translatable("options.generic_value", caption, Component.literal((1 << value) + "x")),
			new OptionInstance.IntRange(0, 4), Integer.numberOfTrailingZeros(config.getBaseLodScale()), value -> {
				config.setBaseLodScale(1 << value);
				config.save();
				HorizonLod.INSTANCE.onConfigChanged();
			});

		OptionInstance<Integer> ringWidth = new OptionInstance<>(
			"options.iris.horizon.ringWidth", OptionInstance.noTooltip(),
			(caption, value) -> Component.translatable("options.generic_value", caption, Component.literal((value * 256) + "m")),
			new OptionInstance.IntRange(1, 32), Math.max(1, config.getLodRingWidth() / 256), value -> {
				config.setLodRingWidth(value * 256);
				config.save();
				HorizonLod.INSTANCE.onConfigChanged();
			});

		OptionInstance<Integer> uploads = new OptionInstance<>(
			"options.iris.horizon.uploads", OptionInstance.noTooltip(),
			(caption, value) -> Component.translatable("options.generic_value", caption, Component.literal(Integer.toString(value))),
			new OptionInstance.IntRange(1, 16), config.getMaxUploadsPerFrame(), value -> {
				config.setMaxUploadsPerFrame(value);
				config.save();
			});

		OptionInstance<Integer> saveInterval = new OptionInstance<>(
			"options.iris.horizon.saveInterval", OptionInstance.noTooltip(),
			(caption, value) -> Component.translatable("options.generic_value", caption, Component.literal((value * 10) + "s")),
			new OptionInstance.IntRange(1, 60), Math.max(1, config.getSaveIntervalSeconds() / 10), value -> {
				config.setSaveIntervalSeconds(value * 10);
				config.save();
			});

		this.list.addSmall(enabled, withShaders, distance, baseScale, ringWidth, uploads, saveInterval);
	}
}
