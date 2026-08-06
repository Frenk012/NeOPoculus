package net.irisshaders.iris.horizon.net;

import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Installs the client-bound handlers, in a class a dedicated server never loads.
 *
 * <p>The indirection is the point: registering a handler means naming the class
 * that implements it, and the client handlers touch client-only types. Keeping
 * that reference behind this bridge — only called under a dist check — is what
 * stops a headless JVM from linking them.
 */
final class ClientLodNetworkBridge {
	private ClientLodNetworkBridge() {
	}

	static void registerClientHandlers(PayloadRegistrar registrar) {
		registrar.playToClient(HorizonPayloads.Hello.TYPE, HorizonPayloads.Hello.CODEC,
			ClientLodNetwork::onHello);
		registrar.playToClient(HorizonPayloads.Palette.TYPE, HorizonPayloads.Palette.CODEC,
			ClientLodNetwork::onPalette);
		registrar.playToClient(HorizonPayloads.SectionData.TYPE, HorizonPayloads.SectionData.CODEC,
			ClientLodNetwork::onSectionData);
	}
}
