package net.irisshaders.iris.horizon.net;

import net.irisshaders.iris.Iris;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * The wire types for server-provided LOD (M5b phase B). One versioned, OPTIONAL
 * channel: a vanilla client may join a server carrying this mod and vice versa,
 * because an optional registrar makes the channel's absence a non-event rather
 * than a connection refusal.
 *
 * <p>The payload is CELLS, never meshes: the client installs what it receives
 * into its own store and its existing scheduler and renderer take it from there,
 * so nothing about the render path is part of this contract.
 *
 * <p>Sizes are bounded at the codec level. Everything arriving here is untrusted
 * input — a client must not be crashable by a hostile server, nor a server by a
 * hostile client — so every length is capped before an array is allocated.
 */
public final class HorizonPayloads {
	private HorizonPayloads() {
	}

	/** Bumped whenever the meaning of any payload changes; mismatched peers simply do not sync. */
	public static final String PROTOCOL_VERSION = "1";

	/** Hard ceiling on a palette blob (16 MB); far above any real palette, far below a memory attack. */
	public static final int MAX_PALETTE_BYTES = 16 * 1024 * 1024;
	/** Hard ceiling on one region-data fragment. */
	public static final int MAX_FRAGMENT_BYTES = 64 * 1024;

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath("neopoculus", path);
	}

	/**
	 * Server → client on join and dimension change: what this server has to
	 * offer. The client answers with {@link Subscribe} only if it wants it.
	 */
	public record Hello(ResourceLocation dimension, int paletteVersion, int regionCount)
		implements CustomPacketPayload {
		public static final Type<Hello> TYPE = new Type<>(id("horizon_lod_hello"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Hello> CODEC = StreamCodec.of(
			(buf, v) -> {
				buf.writeResourceLocation(v.dimension());
				buf.writeVarInt(v.paletteVersion());
				buf.writeVarInt(v.regionCount());
			},
			buf -> new Hello(buf.readResourceLocation(), buf.readVarInt(), buf.readVarInt()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client → server: "send me LOD for this dimension". */
	public record Subscribe(ResourceLocation dimension) implements CustomPacketPayload {
		public static final Type<Subscribe> TYPE = new Type<>(id("horizon_lod_subscribe"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Subscribe> CODEC = StreamCodec.of(
			(buf, v) -> buf.writeResourceLocation(v.dimension()),
			buf -> new Subscribe(buf.readResourceLocation()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * Server → client: the server's palette, in the same {@code palette.nbt}
	 * layout the disk format uses. The client does NOT adopt these ids — it maps
	 * them onto its own (see {@code ClientLodInstaller}); the version lets it
	 * notice when the server has registered new states since.
	 */
	public record Palette(int version, byte[] nbt) implements CustomPacketPayload {
		public static final Type<Palette> TYPE = new Type<>(id("horizon_lod_palette"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Palette> CODEC = StreamCodec.of(
			(buf, v) -> {
				buf.writeVarInt(v.version());
				buf.writeByteArray(v.nbt());
			},
			buf -> new Palette(buf.readVarInt(), buf.readByteArray(MAX_PALETTE_BYTES)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * Server → client: one 32³ section of cells, codec-encoded exactly as the
	 * disk format stores it. Sections rather than whole region files because the
	 * client must rewrite every cell's ids anyway (it never adopts server ids),
	 * and a section is the unit that rewrite and installation both work in.
	 */
	public record SectionData(long sectionKey, byte[] cells) implements CustomPacketPayload {
		public static final Type<SectionData> TYPE = new Type<>(id("horizon_lod_section"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SectionData> CODEC = StreamCodec.of(
			(buf, v) -> {
				buf.writeLong(v.sectionKey());
				buf.writeByteArray(v.cells());
			},
			buf -> new SectionData(buf.readLong(), buf.readByteArray(MAX_FRAGMENT_BYTES)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * Registers the channel. Called from the mod event bus on both dists; the
	 * client-bound handlers are installed only on a client, so a dedicated server
	 * never links a class that touches client types.
	 */
	public static void register(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {
		var registrar = event.registrar(PROTOCOL_VERSION).optional();
		registrar.playToServer(Subscribe.TYPE, Subscribe.CODEC, ServerLodNetwork::onSubscribe);
		boolean client;
		try {
			client = net.neoforged.fml.loading.FMLLoader.getDist().isClient();
		} catch (Throwable t) {
			client = false;
		}
		if (client) {
			ClientLodNetworkBridge.registerClientHandlers(registrar);
		} else {
			// A dedicated server still declares the client-bound types so it may
			// send them; it just never handles one.
			registrar.playToClient(Hello.TYPE, Hello.CODEC, (p, c) -> {
			});
			registrar.playToClient(Palette.TYPE, Palette.CODEC, (p, c) -> {
			});
			registrar.playToClient(SectionData.TYPE, SectionData.CODEC, (p, c) -> {
			});
		}
		Iris.logger.info("Horizon: LOD network channel registered (protocol " + PROTOCOL_VERSION + ")");
	}
}
