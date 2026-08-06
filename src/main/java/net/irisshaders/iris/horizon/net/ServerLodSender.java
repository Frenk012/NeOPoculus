package net.irisshaders.iris.horizon.net;

import net.irisshaders.iris.horizon.server.HorizonLodServer;
import net.irisshaders.iris.horizon.voxel.ServerLodExporter;
import net.irisshaders.iris.horizon.voxel.VoxelConstants;
import net.irisshaders.iris.horizon.voxel.VoxelStore;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Streams LOD sections to subscribed players, nearest first (M5b phase B).
 *
 * <p>Bounded on purpose: a fixed number of sections per player per tick, so
 * pre-generated LOD for a large world arrives steadily instead of as a burst
 * that stalls the server or floods a connection. Each player has their own
 * cursor and their own set of already-sent keys, so no section is sent twice and
 * a player who moves simply starts finding nearer sections to send.
 *
 * <p>Server-side only; no client types.
 */
public final class ServerLodSender {
	/** Sections per player per tick. One section is a few kB encoded. */
	private static final int SECTIONS_PER_PLAYER_PER_TICK = 4;
	/** How far out to offer LOD, in sections of the level being walked. */
	private static final int RADIUS_SECTIONS = 24;

	private static final Map<UUID, Cursor> cursors = new HashMap<>();

	private ServerLodSender() {
	}

	private static final class Cursor {
		final Set<Long> sent = new HashSet<>();
		int ring;
		int centerSx = Integer.MIN_VALUE;
		int centerSz;
		final long[] scratch = new long[VoxelConstants.SECTION_CELLS];
	}

	public static void register() {
		NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
			ServerTickEvent.Post.class, ServerLodSender::onTick);
	}

	private static void onTick(ServerTickEvent.Post event) {
		var stores = HorizonLodServer.serverStoresIfPresent();
		if (stores == null) {
			return;
		}
		for (ServerLevel level : event.getServer().getAllLevels()) {
			for (ServerPlayer player : level.players()) {
				if (ServerLodNetwork.subscriptionOf(player) == null) {
					continue;
				}
				pump(stores.storeFor(level), player);
			}
		}
	}

	/**
	 * Sends this player's next few sections. Walks outward in rings from the
	 * player's own section so the terrain they can see arrives first; the ring
	 * resets whenever they move far enough that the old order is stale.
	 */
	private static void pump(VoxelStore store, ServerPlayer player) {
		Cursor cursor = cursors.computeIfAbsent(player.getUUID(), id -> new Cursor());
		int sx = ServerLodExporter.sectionKeyLevelX(0, player.getBlockX());
		int sz = ServerLodExporter.sectionKeyLevelZ(0, player.getBlockZ());
		if (cursor.centerSx == Integer.MIN_VALUE
			|| Math.abs(sx - cursor.centerSx) > 2 || Math.abs(sz - cursor.centerSz) > 2) {
			cursor.centerSx = sx;
			cursor.centerSz = sz;
			cursor.ring = 0;
		}

		int sent = 0;
		while (sent < SECTIONS_PER_PLAYER_PER_TICK && cursor.ring <= RADIUS_SECTIONS) {
			boolean anyInRing = false;
			for (int dz = -cursor.ring; dz <= cursor.ring && sent < SECTIONS_PER_PLAYER_PER_TICK; dz++) {
				for (int dx = -cursor.ring; dx <= cursor.ring && sent < SECTIONS_PER_PLAYER_PER_TICK; dx++) {
					// Ring, not filled square: the inner area was covered already.
					if (Math.max(Math.abs(dx), Math.abs(dz)) != cursor.ring) {
						continue;
					}
					anyInRing = true;
					sent += sendColumn(store, player, cursor, cursor.centerSx + dx, cursor.centerSz + dz);
				}
			}
			if (sent < SECTIONS_PER_PLAYER_PER_TICK) {
				cursor.ring++;
				if (!anyInRing && cursor.ring > RADIUS_SECTIONS) {
					break;
				}
			}
		}
	}

	/** Sends every non-empty section of one column at level 0; returns how many went out. */
	private static int sendColumn(VoxelStore store, ServerPlayer player, Cursor cursor, int sx, int sz) {
		int sent = 0;
		int minSy = ServerLodExporter.sectionY(0, player.level().getMinBuildHeight());
		int maxSy = ServerLodExporter.sectionY(0, player.level().getMaxBuildHeight() - 1);
		for (int sy = minSy; sy <= maxSy; sy++) {
			long key = ServerLodExporter.sectionKey(0, sx, sy, sz);
			if (!cursor.sent.add(key)) {
				continue;
			}
			byte[] encoded = ServerLodExporter.exportSection(store, key, cursor.scratch);
			if (encoded == null || encoded.length > HorizonPayloads.MAX_FRAGMENT_BYTES) {
				continue;
			}
			player.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
				new HorizonPayloads.SectionData(key, encoded)));
			sent++;
		}
		return sent;
	}

	/** Forgets a player's progress when they leave, so a rejoin re-offers everything. */
	public static void forget(UUID playerId) {
		cursors.remove(playerId);
	}
}
