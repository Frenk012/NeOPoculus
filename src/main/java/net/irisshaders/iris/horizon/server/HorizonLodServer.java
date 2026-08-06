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

	public static void register() {
		NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
			RegisterCommandsEvent.class, HorizonLodServer::onRegisterCommands);
		NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
			ServerTickEvent.Post.class, HorizonLodServer::onServerTick);
		NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
			ServerStoppingEvent.class, e -> active = null);
	}

	private static void onServerTick(ServerTickEvent.Post event) {
		LodGenerator generator = active;
		if (generator != null && !generator.tick()) {
			active = null;
		}
	}

	private static void onRegisterCommands(RegisterCommandsEvent event) {
		LiteralArgumentBuilder<CommandSourceStack> lod = Commands.literal("lod")
			.requires(src -> src.hasPermission(2))
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
		if (active != null && !active.isDone()) {
			src.sendFailure(Component.literal("Horizon: a LOD generation is already running — /horizon lod status"));
			return 0;
		}
		if (!HorizonConfig.get().isEnabled() || !HorizonConfig.get().isVoxelEngine()) {
			src.sendFailure(Component.literal("Horizon: the voxel LOD engine is not enabled"));
			return 0;
		}
		VoxelEngine engine = HorizonLod.INSTANCE.voxel();
		if (engine == null || !engine.isActive()) {
			src.sendFailure(Component.literal(
				"Horizon: no LOD store for this world yet — load into the world first"));
			return 0;
		}
		ServerLevel level = src.getLevel();
		var origin = src.getPosition();
		LodGenerator generator = LodGenerator.radius(level, engine,
			(int) Math.floor(origin.x), (int) Math.floor(origin.z), radiusBlocks, generateMissing);
		active = generator;
		Iris.logger.info("Horizon: LOD generation started, radius " + radiusBlocks + " blocks"
			+ (generateMissing ? " (generating missing terrain)" : " (existing chunks only)"));
		src.sendSuccess(() -> Component.literal("Horizon: generating LOD within " + radiusBlocks
			+ " blocks" + (generateMissing ? ", generating missing terrain" : ", existing chunks only")
			+ " — /horizon lod status"), true);
		return 1;
	}

	/** The live voxel engine, or null when the LOD is off or no world is loaded. */
	private static VoxelEngine engineOrNull() {
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
