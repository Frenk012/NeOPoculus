package net.irisshaders.iris.horizon;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Development harness: loads every shaderpack in turn and screenshots each one.
 *
 * <p>Judging LOD under shaders means looking at it, and looking at it by hand
 * costs a person several minutes per pass and produces no record. This drives
 * the same sequence unattended — apply a pack, let it compile and settle, grab a
 * frame, move on — so a full sweep of every pack lands in {@code screenshots/}
 * with the pack name on each file.
 *
 * <p>Only runs when {@code -Dhorizon.shadertest=true} is set (the
 * {@code runShaderTest} Gradle configuration does that and quick-plays straight
 * into a world), so it can never touch a real session. The camera is never
 * moved: whatever the world spawns you looking at is the comparison, which is
 * the point — every screenshot in a sweep frames the identical scene, so the
 * only thing that varies between them is the pack.
 */
public final class HorizonShaderTest {
	/** Ticks to wait after entering the world before the first pack. LOD has to mesh first. */
	private static final int WARMUP_TICKS = 400;
	/** Ticks between applying a pack and grabbing the frame: compile, then settle. */
	private static final int SETTLE_TICKS = 140;

	private static final HorizonShaderTest INSTANCE = new HorizonShaderTest();

	private boolean registered;
	private List<String> packs;
	private int index = -1;
	private int timer;
	private boolean finished;

	private HorizonShaderTest() {
	}

	public static void register() {
		if (INSTANCE.registered || !enabled()) {
			return;
		}
		INSTANCE.registered = true;
		NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
			ClientTickEvent.Post.class, INSTANCE::onClientTick);
		Iris.logger.info("Horizon shader test: armed, waiting for a world");
	}

	private static boolean enabled() {
		return Boolean.getBoolean("horizon.shadertest");
	}

	private void onClientTick(ClientTickEvent.Post event) {
		if (finished) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || mc.getOverlay() != null) {
			return; // still loading, or a progress overlay is covering the frame
		}
		if (timer > 0) {
			timer--;
			return;
		}

		if (packs == null) {
			packs = discoverPacks();
			if (packs.isEmpty()) {
				Iris.logger.warn("Horizon shader test: no shaderpacks found, nothing to do");
				finished = true;
				return;
			}
			Iris.logger.info("Horizon shader test: " + packs.size() + " packs to sweep, warming up");
			timer = WARMUP_TICKS;
			return;
		}

		// A pack is on screen and settled: record it before moving on.
		if (index >= 0 && index < packs.size()) {
			capture(mc, index, packs.get(index));
		}

		index++;
		if (index >= packs.size()) {
			finished = true;
			Iris.logger.info("Horizon shader test: sweep complete, "
				+ packs.size() + " screenshots in screenshots/");
			// Shut down on our own. An unattended run that leaves a window open
			// still needs a person to come and close it, which is most of what
			// this harness exists to avoid.
			mc.stop();
			return;
		}
		apply(packs.get(index));
		timer = SETTLE_TICKS;
	}

	private List<String> discoverPacks() {
		List<String> found = new ArrayList<>();
		try {
			found.addAll(Iris.getShaderpacksDirectoryManager().enumerate());
		} catch (Throwable t) {
			Iris.logger.error("Horizon shader test: could not enumerate shaderpacks", t);
		}
		return found;
	}

	private void apply(String name) {
		try {
			Iris.logger.info("Horizon shader test: applying " + name);
			Iris.clearShaderPackOptionQueue();
			Iris.getIrisConfig().setShaderPackName(name);
			IrisApi.getInstance().getConfig().setShadersEnabledAndApply(true);
		} catch (Throwable t) {
			// One bad pack must not end the sweep — the remaining packs are still
			// worth recording, and the missing screenshot is itself the finding.
			Iris.logger.error("Horizon shader test: failed to apply " + name, t);
		}
	}

	private void capture(Minecraft mc, int ordinal, String name) {
		// The extension is not implied: Screenshot.grab writes the name verbatim,
		// and a file without one is not openable as an image.
		String file = String.format("shadertest-%02d-%s.png", ordinal, sanitize(name));
		try {
			Screenshot.grab(mc.gameDirectory, file, mc.getMainRenderTarget(), message -> {
			});
			Iris.logger.info("Horizon shader test: captured " + file);
		} catch (Throwable t) {
			Iris.logger.error("Horizon shader test: could not capture " + name, t);
		}
	}

	/** Pack names are file names, and carry spaces, apostrophes and .zip. */
	private static String sanitize(String name) {
		String base = name.endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
		return base.replaceAll("[^A-Za-z0-9._-]", "_");
	}
}
