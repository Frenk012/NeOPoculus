package net.irisshaders.iris.horizon.net;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.horizon.server.HorizonLodServer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server half of the LOD channel: greet joining players, accept subscriptions,
 * and hand out the palette. Region streaming lives in the sender (step B3).
 *
 * <p>No client types anywhere — this runs on a dedicated server.
 */
public final class ServerLodNetwork {
	/** Players who asked for LOD, and the dimension they asked about. */
	private static final Map<java.util.UUID, ResourceLocation> subscribers = new ConcurrentHashMap<>();
	/** Subscribers already sent the palette this session. */
	private static final Set<java.util.UUID> paletteSent = ConcurrentHashMap.newKeySet();

	private ServerLodNetwork() {
	}

	public static void register() {
		NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
			PlayerEvent.PlayerLoggedInEvent.class, ServerLodNetwork::onLogin);
		NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
			PlayerEvent.PlayerLoggedOutEvent.class, ServerLodNetwork::onLogout);
		NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
			PlayerEvent.PlayerChangedDimensionEvent.class, ServerLodNetwork::onChangedDimension);
	}

	private static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
		if (event.getEntity() instanceof ServerPlayer player) {
			greet(player);
		}
	}

	private static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
		if (event.getEntity() instanceof ServerPlayer player) {
			subscribers.remove(player.getUUID());
			greet(player);
		}
	}

	private static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
		subscribers.remove(event.getEntity().getUUID());
		paletteSent.remove(event.getEntity().getUUID());
		ServerLodSender.forget(event.getEntity().getUUID());
	}

	/**
	 * Offers LOD to a player, but only when there is something to offer and the
	 * connection actually speaks this channel — a vanilla client must never be
	 * sent a packet it cannot read.
	 */
	private static void greet(ServerPlayer player) {
		var stores = HorizonLodServer.serverStoresIfPresent();
		if (stores == null) {
			return;
		}
		try {
			ResourceLocation dim = player.level().dimension().location();
			player.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
				new HorizonPayloads.Hello(dim, stores.palettes().stateCount(), 0)));
		} catch (Throwable t) {
			// An unsupported channel throws here on some paths; that is a normal
			// vanilla client, not an error worth logging loudly.
			Iris.logger.debug("Horizon: could not greet " + player.getGameProfile().getName()
				+ " on the LOD channel (likely a client without the mod)");
		}
	}

	/** Client accepted: remember it and send the palette once. */
	static void onSubscribe(HorizonPayloads.Subscribe payload, IPayloadContext context) {
		if (!(context.player() instanceof ServerPlayer player)) {
			return;
		}
		var stores = HorizonLodServer.serverStoresIfPresent();
		if (stores == null) {
			return;
		}
		subscribers.put(player.getUUID(), payload.dimension());
		if (!paletteSent.add(player.getUUID())) {
			return;
		}
		byte[] blob = stores.palettes().toNbtBytes();
		if (blob == null) {
			return;
		}
		context.reply(new HorizonPayloads.Palette(stores.palettes().stateCount(), blob));
		Iris.logger.info("Horizon: sent LOD palette (" + blob.length + " B) to "
			+ player.getGameProfile().getName());
	}

	/** Dimension a player subscribed for, or null when they did not. */
	public static ResourceLocation subscriptionOf(ServerPlayer player) {
		return subscribers.get(player.getUUID());
	}
}
