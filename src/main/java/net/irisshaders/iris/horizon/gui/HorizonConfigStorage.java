package net.irisshaders.iris.horizon.gui;

import net.irisshaders.iris.horizon.HorizonConfig;
import net.irisshaders.iris.horizon.HorizonLod;
import org.embeddedt.embeddium.api.options.structure.OptionStorage;

/**
 * Bridges HorizonConfig into Embeddium's options framework. Applying the
 * settings persists the properties file and triggers an async remesh so
 * changes take effect live, without leaving the world.
 */
public class HorizonConfigStorage implements OptionStorage<HorizonConfig> {
	public static final HorizonConfigStorage INSTANCE = new HorizonConfigStorage();

	@Override
	public HorizonConfig getData() {
		return HorizonConfig.get();
	}

	@Override
	public void save() {
		// Both ticks together mean "delete this world's LOD cache". They are
		// cleared immediately afterwards so the action cannot fire twice, and so
		// an armed tick left behind cannot surprise the player next time.
		if (HorizonConfig.get().isLodClearArmed() && HorizonConfig.get().isLodClearConfirm()) {
			HorizonConfig.get().setLodClearArmed(false);
			HorizonConfig.get().setLodClearConfirm(false);
			HorizonLod.INSTANCE.clearLodCache();
		}
		HorizonConfig.get().save();
		HorizonLod.INSTANCE.onConfigChanged();
	}
}
