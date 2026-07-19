package net.irisshaders.iris.horizon.voxel;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.horizon.voxel.model.LeafLikePredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Global incremental palettes mapping {@link BlockState} to 20-bit cell ids
 * and biomes to 9-bit cell ids (design-data-storage.md section 2). Shared by
 * all dimensions of one world so a cell long means the same thing in every
 * {@code VoxelWorld} and every region file.
 *
 * <p>Reserved ids: state 0 = air (implicit — every {@code isAir()} state
 * collapses onto it so {@code VoxelCell.isAir} is a single mask test), state
 * 1 = stone and biome 0 = plains, both eagerly registered so fallback
 * lookups always resolve without null checks on the hot mesher path.
 *
 * <p>Concurrency: ingestion workers call {@link #idFor(BlockState)}
 * concurrently mid-conversion, so lookups must be lock-free — a
 * {@link ConcurrentHashMap} hit is the entire fast path. Registration of a
 * genuinely new state is rare (bounded by unique states seen in the world),
 * so it serializes on one lock; the reverse arrays grow by doubling and are
 * republished through volatile fields so id-indexed readers
 * ({@link #stateOf}, {@link #opacityOf}) never lock either.
 *
 * <p>Opacity is computed once here at registration rather than per cell in
 * the mipper because {@code getLightBlock} on modded states can be
 * arbitrarily expensive (or throw); caching it as a byte per id makes
 * {@code VoxelMipper.selectRepresentative} pure array math. The leaf-like
 * override delegates to {@link LeafLikePredicate} per DESIGN.md R8 — one
 * truth, cached here, so occlusion and the bakery's darkened-mip branch can
 * never disagree.
 */
public final class VoxelPalettes {
	/** Unknown/tombstoned state ids render as stone (design section 2). */
	public static final int FALLBACK_STATE_ID = 1;
	/** Unknown biome ids tint as plains (design section 2). */
	public static final int FALLBACK_BIOME_ID = 0;

	private static final int INITIAL_STATE_CAPACITY = 1024;

	/** Serializes registration; never held by lookups. */
	private final Object registerLock = new Object();

	private final ConcurrentHashMap<BlockState, Integer> stateToId = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<ResourceLocation, Integer> biomeToId = new ConcurrentHashMap<>();
	/**
	 * States/biomes seen after the id space filled, aliased to the fallback
	 * ids. Kept separate from the real maps so the M2 NBT writer never
	 * persists an alias as if it owned the fallback id.
	 */
	private final Set<BlockState> stateOverflow = ConcurrentHashMap.newKeySet();
	private final Set<ResourceLocation> biomeOverflow = ConcurrentHashMap.newKeySet();
	private final AtomicInteger overflowCount = new AtomicInteger();
	private volatile boolean overflowLogged;
	private volatile boolean keylessBiomeLogged;

	// Reverse lookups, indexed by id. Grown under registerLock, republished
	// volatile; an id only reaches a reader after its slot is filled, so
	// readers see initialized entries without locking.
	private volatile BlockState[] statesById;
	private volatile byte[] opacityById;
	private volatile boolean[] leafById;
	/** Fixed 512 entries: MAX_BIOME_IDS is small enough to never grow. */
	private volatile ResourceLocation[] biomesById;

	private final AtomicInteger nextStateId = new AtomicInteger();
	private final AtomicInteger nextBiomeId = new AtomicInteger();
	/**
	 * Bumped on every registration (and on {@link #clear()}), monotonic for
	 * the whole session: the bakery and mesher compare it to a remembered
	 * value to notice new states without subscribing to callbacks.
	 */
	private final AtomicInteger version = new AtomicInteger();
	/** Set on registration, consumed by the M2 save cycle. */
	private volatile boolean dirty;

	public VoxelPalettes() {
		bootstrap();
	}

	/**
	 * Installs the reserved entries. Air lives only in the reverse array
	 * (id 0 is implicit and must never appear in the persisted palette);
	 * stone and plains get real registrations so the fallback ids resolve.
	 */
	private void bootstrap() {
		statesById = new BlockState[INITIAL_STATE_CAPACITY];
		opacityById = new byte[INITIAL_STATE_CAPACITY];
		leafById = new boolean[INITIAL_STATE_CAPACITY];
		biomesById = new ResourceLocation[VoxelConstants.MAX_BIOME_IDS];

		statesById[0] = Blocks.AIR.defaultBlockState();
		opacityById[0] = 0;
		nextStateId.set(1);

		biomesById[0] = Biomes.PLAINS.location();
		biomeToId.put(Biomes.PLAINS.location(), 0);
		nextBiomeId.set(1);

		int stoneId = registerState(Blocks.STONE.defaultBlockState());
		if (stoneId != FALLBACK_STATE_ID) {
			// Impossible unless the reserved-id scheme changed; loud because
			// every fallback in the engine assumes stone == 1.
			Iris.logger.error("Horizon: stone registered as state id " + stoneId + ", expected " + FALLBACK_STATE_ID);
		}
		// Reserved entries are implied by the format, not user data: a
		// palette holding only them has nothing worth persisting.
		dirty = false;
	}

	/**
	 * Id for a block state, registering it if new. Any thread. Never
	 * throws: past the 20-bit cap new states alias to stone (a wrong-looking
	 * distant block beats a crashed ingest worker).
	 */
	public int idFor(BlockState state) {
		if (state.isAir()) {
			// Collapse air/cave_air/void_air onto the reserved id so the
			// cell-level isAir test stays a single mask comparison.
			return 0;
		}
		Integer id = stateToId.get(state);
		if (id != null) {
			return id;
		}
		return registerState(state);
	}

	private int registerState(BlockState state) {
		synchronized (registerLock) {
			Integer raced = stateToId.get(state);
			if (raced != null) {
				return raced;
			}
			int id = nextStateId.get();
			if (id >= VoxelConstants.MAX_STATE_IDS) {
				stateOverflow.add(state);
				logOverflow("block state", state.toString());
				return FALLBACK_STATE_ID;
			}
			if (id >= statesById.length) {
				growStateArrays();
			}
			statesById[id] = state;
			boolean leaf = safeIsLeafLike(state);
			leafById[id] = leaf;
			opacityById[id] = computeOpacity(state, leaf);
			// Slot filled before the id is published anywhere: the map put
			// (and the caller storing the id into a section under its
			// monitor) supplies the happens-before for lock-free readers.
			nextStateId.set(id + 1);
			dirty = true;
			version.incrementAndGet();
			stateToId.put(state, id);
			return id;
		}
	}

	/**
	 * Id for a biome holder, registering it if new. Any thread. Keyed by
	 * the registry {@link ResourceLocation} rather than the holder instance
	 * so ids are stable across dimensions and re-entries within a session
	 * (holders are per-registry-access objects; names are not).
	 */
	public int idFor(Holder<Biome> biome) {
		ResourceLocation name = biome.unwrapKey().map(ResourceKey::location).orElse(null);
		if (name == null) {
			// Direct (inline) holders have no registry key and therefore no
			// stable identity to persist; tint them as plains.
			if (!keylessBiomeLogged) {
				keylessBiomeLogged = true;
				Iris.logger.warn("Horizon: biome holder without registry key; using plains fallback");
			}
			return FALLBACK_BIOME_ID;
		}
		Integer id = biomeToId.get(name);
		if (id != null) {
			return id;
		}
		return registerBiome(name);
	}

	private int registerBiome(ResourceLocation name) {
		synchronized (registerLock) {
			Integer raced = biomeToId.get(name);
			if (raced != null) {
				return raced;
			}
			int id = nextBiomeId.get();
			if (id >= VoxelConstants.MAX_BIOME_IDS) {
				biomeOverflow.add(name);
				logOverflow("biome", name.toString());
				return FALLBACK_BIOME_ID;
			}
			biomesById[id] = name;
			nextBiomeId.set(id + 1);
			dirty = true;
			version.incrementAndGet();
			biomeToId.put(name, id);
			return id;
		}
	}

	/**
	 * State for an id; lock-free array read. Out-of-range or unfilled ids
	 * (possible for cells decoded from a stale M2 file) resolve to stone —
	 * the tombstone rule from design section 2.
	 */
	public BlockState stateOf(int id) {
		BlockState[] states = statesById;
		if (id >= 0 && id < states.length) {
			BlockState state = states[id];
			if (state != null) {
				return state;
			}
		}
		return states[FALLBACK_STATE_ID];
	}

	/** Biome registry name for an id; plains for anything unresolvable. */
	public ResourceLocation biomeOf(int id) {
		ResourceLocation[] biomes = biomesById;
		if (id >= 0 && id < biomes.length) {
			ResourceLocation name = biomes[id];
			if (name != null) {
				return name;
			}
		}
		return biomes[FALLBACK_BIOME_ID];
	}

	/**
	 * Cached opacity (0-15) for a state id; the mipper's ranking key and the
	 * mesher's occlusion test. Invalid ids read as stone's opacity so a
	 * corrupt cell occludes rather than punches a hole.
	 */
	public int opacityOf(int stateId) {
		byte[] opacity = opacityById;
		if (stateId >= 0 && stateId < opacity.length) {
			return opacity[stateId];
		}
		return opacity[FALLBACK_STATE_ID];
	}

	/** Cached {@link LeafLikePredicate} result for a state id. */
	public boolean isLeaf(int stateId) {
		boolean[] leaf = leafById;
		return stateId >= 0 && stateId < leaf.length && leaf[stateId];
	}

	/** Monotonic registration counter; see field javadoc. */
	public int paletteVersion() {
		return version.get();
	}

	/** Registered state ids including reserved air/stone (== next free id). */
	public int stateCount() {
		return nextStateId.get();
	}

	/** Registered biome ids including reserved plains. */
	public int biomeCount() {
		return nextBiomeId.get();
	}

	/**
	 * M2 seam: reads {@code palette.nbt} and replays every persisted entry
	 * (resolving via the given lookups, tombstoning failures per design
	 * section 2) before any region file is read. Worker thread, once at
	 * world open. No-op in M1 — there are no files yet, ids simply start
	 * fresh each session.
	 */
	public void load(Path file, HolderGetter<Block> blocks, Registry<Biome> biomes) {
		// Implemented in M2 alongside VoxelRegionStorage.
	}

	/**
	 * M2 seam: persists both palettes (atomic tmp+move) if anything was
	 * registered since the last save. Must run before any region flush —
	 * the palette-first invariant is what makes every id on disk defined
	 * after a crash. Returns whether a write happened. Always false in M1
	 * (nothing to persist; the dirty flag is still maintained so M2's save
	 * cycle works unchanged).
	 */
	public boolean saveIfDirty(Path file) {
		// Implemented in M2 alongside VoxelRegionStorage.
		return false;
	}

	/**
	 * Full reset for level unload: a new world means new registries, and
	 * stale BlockState keys would pin the old world's objects. Keeps the
	 * version counter monotonic so any consumer holding a pre-clear version
	 * still sees "something changed".
	 */
	public void clear() {
		synchronized (registerLock) {
			stateToId.clear();
			biomeToId.clear();
			stateOverflow.clear();
			biomeOverflow.clear();
			overflowCount.set(0);
			overflowLogged = false;
			keylessBiomeLogged = false;
			bootstrap();
			version.incrementAndGet();
		}
	}

	private void growStateArrays() {
		int newLength = (int) Math.min((long) statesById.length * 2, VoxelConstants.MAX_STATE_IDS);
		BlockState[] states = new BlockState[newLength];
		byte[] opacity = new byte[newLength];
		boolean[] leaf = new boolean[newLength];
		System.arraycopy(statesById, 0, states, 0, statesById.length);
		System.arraycopy(opacityById, 0, opacity, 0, opacityById.length);
		System.arraycopy(leafById, 0, leaf, 0, leafById.length);
		// Republish fully-copied arrays; readers hold either the old or the
		// new reference, both consistent for every already-issued id.
		statesById = states;
		opacityById = opacity;
		leafById = leaf;
	}

	/**
	 * Opacity per design section 4: air 0; leaf-like forced 15 (solid
	 * distant canopies); otherwise the state's light block value, guarded
	 * because modded overrides may throw off-thread — then occluders count
	 * as 15 and everything else as barely-there 1.
	 */
	private static byte computeOpacity(BlockState state, boolean leafLike) {
		if (state.isAir()) {
			return 0;
		}
		if (leafLike) {
			return 15;
		}
		try {
			int light = state.getLightBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
			return (byte) Math.max(0, Math.min(15, light));
		} catch (Throwable t) {
			return (byte) (state.canOcclude() ? 15 : 1);
		}
	}

	/** Tag lookups can throw before tags are bound; treat that as not-leaf. */
	private static boolean safeIsLeafLike(BlockState state) {
		try {
			return LeafLikePredicate.test(state);
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * Overflow is a safety valve, not an expected path (incremental
	 * registration only ids states actually seen), so one WARN with a
	 * running count beats per-state spam.
	 */
	private void logOverflow(String kind, String entry) {
		int count = overflowCount.incrementAndGet();
		if (!overflowLogged) {
			overflowLogged = true;
			Iris.logger.warn("Horizon: " + kind + " palette full; aliasing " + entry
				+ " (and future overflow, " + count + " so far) to the fallback id");
		}
	}
}
