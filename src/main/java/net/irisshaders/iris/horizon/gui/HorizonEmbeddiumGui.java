package net.irisshaders.iris.horizon.gui;

import com.google.common.collect.ImmutableList;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.horizon.HorizonConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.embeddedt.embeddium.api.OptionGUIConstructionEvent;
import org.embeddedt.embeddium.api.options.binding.GenericBinding;
import org.embeddedt.embeddium.api.options.control.ControlValueFormatter;
import org.embeddedt.embeddium.api.options.control.SliderControl;
import org.embeddedt.embeddium.api.options.control.TickBoxControl;
import org.embeddedt.embeddium.api.options.structure.Option;
import org.embeddedt.embeddium.api.options.structure.OptionGroup;
import org.embeddedt.embeddium.api.options.OptionIdentifier;
import org.embeddedt.embeddium.api.options.structure.OptionImpact;
import org.embeddedt.embeddium.api.options.structure.OptionImpl;
import org.embeddedt.embeddium.api.options.structure.OptionPage;

/**
 * Adds the "Horizon LOD" page to Embeddium's video settings screen, which
 * replaces the vanilla one when Embeddium is installed.
 */
@EventBusSubscriber
public class HorizonEmbeddiumGui {
	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(Iris.MODID, path);
	}

	private static Component name(String key) {
		return Component.translatable("options.iris.horizon." + key);
	}

	private static Component tooltip(String key) {
		return Component.translatable("options.iris.horizon." + key + ".tooltip");
	}

	@SubscribeEvent
	public static void onGui(OptionGUIConstructionEvent event) {
		Option<Boolean> enabled = OptionImpl.createBuilder(Boolean.TYPE, HorizonConfigStorage.INSTANCE)
			.setId(id("horizon/enabled"))
			.setName(name("enabled"))
			.setTooltip(tooltip("enabled"))
			.setControl(TickBoxControl::new)
			.setBinding(new GenericBinding<>(HorizonConfig::setEnabled, HorizonConfig::isEnabled))
			.setImpact(OptionImpact.HIGH)
			.build();

		Option<Boolean> voxelEngine = OptionImpl.createBuilder(Boolean.TYPE, HorizonConfigStorage.INSTANCE)
			.setId(id("horizon/engine"))
			.setName(name("engine"))
			.setTooltip(tooltip("engine"))
			.setControl(TickBoxControl::new)
			.setBinding(new GenericBinding<>(HorizonConfig::setVoxelEngine, HorizonConfig::isVoxelEngine))
			.setImpact(OptionImpact.HIGH)
			.build();

		Option<Boolean> withShaders = OptionImpl.createBuilder(Boolean.TYPE, HorizonConfigStorage.INSTANCE)
			.setId(id("horizon/shaders"))
			.setName(name("shaders"))
			.setTooltip(tooltip("shaders"))
			.setControl(TickBoxControl::new)
			.setBinding(new GenericBinding<>(HorizonConfig::setRenderWithShaders, HorizonConfig::shouldRenderWithShaders))
			.setImpact(OptionImpact.MEDIUM)
			.build();

		Option<Integer> distance = OptionImpl.createBuilder(Integer.TYPE, HorizonConfigStorage.INSTANCE)
			.setId(id("horizon/distance"))
			.setName(name("distance"))
			.setTooltip(tooltip("distance"))
			.setControl(o -> new SliderControl(o, 64, 4096, 64, ControlValueFormatter.number()))
			.setBinding(new GenericBinding<>(HorizonConfig::setLodDistanceChunks, HorizonConfig::getLodDistanceChunks))
			.setImpact(OptionImpact.HIGH)
			.build();

		Option<Integer> baseScale = OptionImpl.createBuilder(Integer.TYPE, HorizonConfigStorage.INSTANCE)
			.setId(id("horizon/base_scale"))
			.setName(name("baseScale"))
			.setTooltip(tooltip("baseScale"))
			.setControl(o -> new SliderControl(o, 1, 16, 1, ControlValueFormatter.number()))
			.setBinding(new GenericBinding<>(HorizonConfig::setBaseLodScale, HorizonConfig::getBaseLodScale))
			.setImpact(OptionImpact.MEDIUM)
			.build();

		Option<Integer> ringWidth = OptionImpl.createBuilder(Integer.TYPE, HorizonConfigStorage.INSTANCE)
			.setId(id("horizon/ring_width"))
			.setName(name("ringWidth"))
			.setTooltip(tooltip("ringWidth"))
			.setControl(o -> new SliderControl(o, 256, 8192, 256, ControlValueFormatter.number()))
			.setBinding(new GenericBinding<>(HorizonConfig::setLodRingWidth, HorizonConfig::getLodRingWidth))
			.setImpact(OptionImpact.MEDIUM)
			.build();

		Option<Integer> uploads = OptionImpl.createBuilder(Integer.TYPE, HorizonConfigStorage.INSTANCE)
			.setId(id("horizon/uploads"))
			.setName(name("uploads"))
			.setTooltip(tooltip("uploads"))
			.setControl(o -> new SliderControl(o, 1, 16, 1, ControlValueFormatter.number()))
			.setBinding(new GenericBinding<>(HorizonConfig::setMaxUploadsPerFrame, HorizonConfig::getMaxUploadsPerFrame))
			.setImpact(OptionImpact.LOW)
			.build();

		Option<Integer> workerThreads = OptionImpl.createBuilder(Integer.TYPE, HorizonConfigStorage.INSTANCE)
			.setId(id("horizon/worker_threads"))
			.setName(name("threads"))
			.setTooltip(tooltip("threads"))
			.setControl(o -> new SliderControl(o, 1, 4, 1, ControlValueFormatter.number()))
			.setBinding(new GenericBinding<>(HorizonConfig::setWorkerThreads, HorizonConfig::getWorkerThreads))
			.setImpact(OptionImpact.MEDIUM)
			.build();

		Option<Integer> saveInterval = OptionImpl.createBuilder(Integer.TYPE, HorizonConfigStorage.INSTANCE)
			.setId(id("horizon/save_interval"))
			.setName(name("saveInterval"))
			.setTooltip(tooltip("saveInterval"))
			.setControl(o -> new SliderControl(o, 10, 600, 10, ControlValueFormatter.number()))
			.setBinding(new GenericBinding<>(HorizonConfig::setSaveIntervalSeconds, HorizonConfig::getSaveIntervalSeconds))
			.setImpact(OptionImpact.LOW)
			.build();

		OptionGroup main = OptionGroup.createBuilder()
			.setId(OptionIdentifier.create(id("horizon/main")))
			.add(enabled)
			.add(voxelEngine)
			.add(withShaders)
			.add(distance)
			.build();

		OptionGroup detail = OptionGroup.createBuilder()
			.setId(OptionIdentifier.create(id("horizon/detail")))
			.add(baseScale)
			.add(ringWidth)
			.build();

		OptionGroup performance = OptionGroup.createBuilder()
			.setId(OptionIdentifier.create(id("horizon/performance")))
			.add(uploads)
			.add(workerThreads)
			.add(saveInterval)
			.build();

		event.addPage(new OptionPage(OptionIdentifier.create(id("horizon")),
			Component.translatable("options.iris.horizon.title"),
			ImmutableList.of(main, detail, performance)));
	}
}
