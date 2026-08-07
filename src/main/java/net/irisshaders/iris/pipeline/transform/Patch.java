package net.irisshaders.iris.pipeline.transform;

public enum Patch {
	VANILLA,
	DH_TERRAIN,
	/**
	 * A pack's own {@code gbuffers_terrain}, rewritten onto Horizon's vertex
	 * interface. Same rewrite as {@link #DH_TERRAIN} plus a real texture
	 * coordinate, because unlike a dh program a terrain program samples
	 * {@code gtexture}. This is what gives distant terrain to the packs that
	 * ship no dh program at all.
	 */
	HORIZON_TERRAIN,
	DH_GENERIC,
	SODIUM,
	COMPOSITE,
	COMPUTE,
	EMBEDDIUM
}
