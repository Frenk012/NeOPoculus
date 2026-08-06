package net.irisshaders.iris.horizon.net;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.horizon.HorizonConfig;
import net.irisshaders.iris.horizon.HorizonLod;
import net.irisshaders.iris.horizon.voxel.ClientLodInstall;
import net.irisshaders.iris.horizon.voxel.VoxelEngine;
import net.irisshaders.iris.horizon.voxel.VoxelPalettes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client half of the LOD channel: decide whether to accept what a server offers,
 * translate its palette, and install the sections it sends.
 *
 * <p>Everything arriving here is UNTRUSTED. A server may be hostile or simply
 * broken, so the worst it may achieve is wasted disk and wrong-looking distant
 * terrain — never a crash, and never adoption of ids the client cannot verify.
 * Loaded only on a client (see {@link ClientLodNetworkBridge}).
 */
final class ClientLodNetwork {
	private ClientLodNetwork() {
	}

	/** Remap tables built from the server's palette; null until one arrives. */
	private static volatile ClientLodInstall installer;

	static void onHello(HorizonPayloads.Hello payload, IPayloadContext context) {
		if (!HorizonConfig.get().isEnabled() || !HorizonConfig.get().isVoxelEngine()) {
			return; // LOD off: stay silent and the server sends nothing
		}
		Iris.logger.info("Horizon: server offers LOD for " + payload.dimension()
			+ " (palette " + payload.paletteVersion() + ")");
		context.reply(new HorizonPayloads.Subscribe(payload.dimension()));
	}

	static void onPalette(HorizonPayloads.Palette payload, IPayloadContext context) {
		CompoundTag tag = VoxelPalettes.readNbtBytes(payload.nbt(), HorizonPayloads.MAX_PALETTE_BYTES);
		if (tag == null) {
			return;
		}
		VoxelEngine engine = HorizonLod.INSTANCE.voxel();
		if (engine == null) {
			return;
		}
		var mc = net.minecraft.client.Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		ClientLodInstall built = ClientLodInstall.fromServerPalette(tag, payload.version(),
			engine.palettes(), mc.level.registryAccess().lookupOrThrow(Registries.BLOCK));
		if (built == null) {
			return;
		}
		installer = built;
		Iris.logger.info("Horizon: mapped the server LOD palette onto local ids ("
			+ built.mappedStates() + " states, " + built.mappedBiomes() + " biomes)");
	}

	static void onSectionData(HorizonPayloads.SectionData payload, IPayloadContext context) {
		ClientLodInstall target = installer;
		if (target == null) {
			// Section data before a palette is meaningless: its ids cannot be
			// translated, and guessing would install wrong blocks silently.
			return;
		}
		VoxelEngine engine = HorizonLod.INSTANCE.voxel();
		if (engine == null) {
			return;
		}
		engine.installServerSection(target, payload.sectionKey(), payload.cells());
	}
}
