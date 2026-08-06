package net.irisshaders.iris.horizon.server;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.horizon.HorizonConfig;
import net.irisshaders.iris.horizon.HorizonLod;
import net.irisshaders.iris.horizon.voxel.VoxelEngine;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Server-side entry point for LOD generation (M5b phase A): the
 * {@code /horizon lod} command tree plus the tick pump that drives
 * {@link LodGenerator}.
 *
 * <p>Everything here runs on the logical server. On an integrated server that is
 * the same process as the client, so generated cells land directly in the live
 * store and the existing scheduler meshes them — the player sees the panorama
 * fill in without exploring, which is the whole point of the milestone. Phase B
 * adds a dedicated-server store and the network path on top of this.
 *
 * <p>Deliberately free of any client-only type: it is registered from the common
 * event bus so the same code can serve a dedicated server later.
 */
public final class HorizonLodServer {
	private static LodGenerator active;

	private HorizonLodServer() {
	}

	private static boolean registered;
	/** Server-side LOD residency, created on server start when running headless. */
	private static net.irisshaders.iris.horizon.voxel.ServerVoxelStores stores;
	private static int tickCounter;

	/**
	 * The server's own LOD stores, created on demand. Only ever used when there
	 * is no client engine in this process; on an integrated server the generator
	 * writes into the client's live store so the player sees the result at once.
	 */
	public static synchronized net.irisshaders.iris.horizon.voxel.ServerVoxelStores serverStores(
			net.minecraft.server.MinecraftServer server) {
		if (stores == null && server != null) {
			stores = new net.irisshaders.iris.horizon.voxel.ServerVoxelStores(server);
		}
		return stores;
	}

	/** The server stores if they already exist, without creating them. Network code asks this way. */
	public static net.irisshaders.iris.horizon.voxel.ServerVoxelStores serverStoresIfPresent() {
		return stores;
	}

	/** True when this JVM has no client, so the generator must target the server store. */
	private static boolean headless() {
		try {
			return !net.neoforged.fml.loading.FMLLoader.getDist().isClient();
		} catch (Throwable t) {
			return true;
		}
	}

	public static void register() {
		if (registered) {
			return;
		}
		registered = true;
		NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
			RegisterCommandsEvent.class, HorizonLodServer::onRegisterCommands);
		NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
			ServerTickEvent.Post.class, HorizonLodServer::onServerTick);
		NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStoppingEvent.class, e -> {
			active = null;
			var s = stores;
			stores = null;
			if (s != null) {
				s.shutdown();
			}
		});
	}

	private static void onServerTick(ServerTickEvent.Post event) {
		LodGenerator generator = active;
		if (generator != null && !generator.tick()) {
			active = null;
		}
		// Periodic save of the server's own store (15 s), mirroring the client
		// engine's cadence. Nothing to do when the client engine owns the data.
		if (stores != null && ++tickCounter % 300 == 0) {
			stores.save();
		}
	}

	private static void onRegisterCommands(RegisterCommandsEvent event) {
		LiteralArgumentBuilder<CommandSourceStack> lod = Commands.literal("lod")
			// Level 4 (owner/console), not the usual 2: these commands can make a
			// server generate millions of chunks or delete an entire LOD cache,
			// which is not moderator-grade authority.
			.requires(src -> src.hasPermission(4))
			.then(Commands.literal("generate")
				.then(Commands.literal("radius")
					.then(Commands.argument("blocks", IntegerArgumentType.integer(16, 16384))
						.executes(ctx -> generate(ctx.getSource(),
							IntegerArgumentType.getInteger(ctx, "blocks"), false))
						.then(Commands.literal("generate-missing")
							.executes(ctx -> generate(ctx.getSource(),
								IntegerArgumentType.getInteger(ctx, "blocks"), true)))))
				.then(Commands.literal("world")
					.executes(ctx -> start(ctx.getSource(), src ->
						LodGenerator.wholeWorld(src.getLevel(), engineOrNull()), "the saved world")))
				.then(Commands.literal("region")
					.then(Commands.argument("x1", IntegerArgumentType.integer())
						.then(Commands.argument("z1", IntegerArgumentType.integer())
							.then(Commands.argument("x2", IntegerArgumentType.integer())
								.then(Commands.argument("z2", IntegerArgumentType.integer())
									.executes(ctx -> start(ctx.getSource(), src -> LodGenerator.region(
										src.getLevel(), engineOrNull(),
										IntegerArgumentType.getInteger(ctx, "x1"),
										IntegerArgumentType.getInteger(ctx, "z1"),
										IntegerArgumentType.getInteger(ctx, "x2"),
										IntegerArgumentType.getInteger(ctx, "z2"), false),
										"the given region"))))))))
			.then(Commands.literal("purge")
				.then(Commands.literal("confirm").executes(ctx -> purge(ctx.getSource()))))
			.then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
			.then(Commands.literal("pause").executes(ctx -> control(ctx.getSource(), "pause")))
			.then(Commands.literal("resume").executes(ctx -> control(ctx.getSource(), "resume")))
			.then(Commands.literal("cancel").executes(ctx -> control(ctx.getSource(), "cancel")));

		event.getDispatcher().register(Commands.literal("horizon").then(lod));
	}

	private static int generate(CommandSourceStack src, int radiusBlocks, boolean generateMissing) {
		var origin = src.getPosition();
		return start(src, s2 -> LodGenerator.radius(s2.getLevel(), engineOrNull(),
				(int) Math.floor(origin.x), (int) Math.floor(origin.z), radiusBlocks, generateMissing),
			radiusBlocks + " blocks around you"
				+ (generateMissing ? ", generating missing terrain" : ", existing terrain only"));
	}

	/** The live voxel engine, or null when the LOD is off or no world is loaded. */
	private static VoxelEngine engineOrNull() {
		// Never touch HorizonLod on a dedicated server: it holds client types.
		if (headless()) {
			return null;
		}
		VoxelEngine engine = HorizonLod.INSTANCE.voxel();
		return engine != null && engine.isActive() ? engine : null;
	}

	/** Shared prologue for the generate variants: validate, build, announce. */
	private static int start(CommandSourceStack src,
							 java.util.function.Function<CommandSourceStack, LodGenerator> factory,
							 String what) {
		if (active != null && !active.isDone()) {
			src.sendFailure(Component.literal("Horizon: a LOD generation is already running — /horizon lod status"));
			return 0;
		}
		if (engineOrNull() == null) {
			src.sendFailure(Component.literal(
				"Horizon: the voxel LOD engine is not active for this world"));
			return 0;
		}
		LodGenerator generator = factory.apply(src);
		if (generator == null || generator.totalChunks() == 0) {
			src.sendFailure(Component.literal("Horizon: nothing to generate for " + what));
			return 0;
		}
		active = generator;
		int total = generator.totalChunks();
		Iris.logger.info("Horizon: LOD generation started over " + what + ", " + total + " chunks");
		src.sendSuccess(() -> Component.literal("Horizon: generating LOD for " + what
			+ " — " + total + " chunks, /horizon lod status"), true);
		return 1;
	}

	/**
	 * Drops every generated LOD for the current world. Destructive, so it is
	 * spelled {@code /horizon lod purge confirm}: the data can only be rebuilt by
	 * generating or exploring again.
	 */
	private static int purge(CommandSourceStack src) {
		VoxelEngine engine = engineOrNull();
		if (engine == null) {
			src.sendFailure(Component.literal("Horizon: the voxel LOD engine is not active for this world"));
			return 0;
		}
		if (active != null) {
			active.cancel();
			active = null;
		}
		try {
			engine.purgeAll();
		} catch (Throwable t) {
			Iris.logger.error("Horizon: LOD purge failed", t);
			src.sendFailure(Component.literal("Horizon: purge failed — see the log"));
			return 0;
		}
		Iris.logger.info("Horizon: LOD purged on request");
		src.sendSuccess(() -> Component.literal(
			"Horizon: LOD purged for this world; it rebuilds as you generate or explore"), true);
		return 1;
	}

	private static int status(CommandSourceStack src) {
		LodGenerator generator = active;
		if (generator == null) {
			src.sendSuccess(() -> Component.literal("Horizon: no LOD generation running"), false);
			return 1;
		}
		String text = generator.status();
		src.sendSuccess(() -> Component.literal("Horizon: " + text), false);
		return 1;
	}

	private static int control(CommandSourceStack src, String action) {
		LodGenerator generator = active;
		if (generator == null) {
			src.sendFailure(Component.literal("Horizon: no LOD generation running"));
			return 0;
		}
		switch (action) {
			case "pause" -> generator.pause();
			case "resume" -> generator.resume();
			default -> {
				generator.cancel();
				active = null;
			}
		}
		src.sendSuccess(() -> Component.literal("Horizon: LOD generation " + action + "d"), true);
		return 1;
	}
}
