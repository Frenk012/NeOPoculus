package net.irisshaders.iris.horizon.voxel;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.horizon.voxel.model.LeafLikePredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
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

	/**
	 * palette.nbt layout version, independent of the {@code .hlod} region
	 * {@link VoxelConstants#STORAGE_VERSION}. A file whose version does not
	 * match is discarded (moved aside), not migrated — the locked
	 * regenerate-don't-migrate rule (design section 2): the palette is derived
	 * from the live registries, so a fresh one costs only re-registration, and
	 * region rows citing now-unknown ids decode to stone until re-captured.
	 */
	private static final int PALETTE_FORMAT_VERSION = 1;
	private static final String TAG_VERSION = "version";
	private static final String TAG_STATES = "states";
	private static final String TAG_BIOMES = "biomes";
	private static final String TAG_ID = "id";
	private static final String TAG_STATE = "state";
	private static final String TAG_NAME = "name";

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
	/**
	 * Persisted state ids whose block no longer resolves this session (mod
	 * removed): id -> the original saved NBT, kept verbatim so the id round-trips
	 * and is re-persisted unchanged (design section 2). {@link #stateOf} already
	 * renders these as stone via the null-slot fallback; retaining the blob means
	 * re-adding the mod restores the exact state at the same id with zero
	 * migration. Guarded by {@link #registerLock}; only touched at load/save/clear.
	 */
	private final Map<Integer, CompoundTag> tombstoneStateNbt = new HashMap<>();
	private final AtomicInteger overflowCount = new AtomicInteger();
	private volatile boolean overflowLogged;
	private volatile boolean keylessBiomeLogged;

	// Reverse lookups, indexed by id. Grown under registerLock, republished
	// volatile; an id only reaches a reader after its slot is filled, so
	// readers see initialized entries without locking.
	private volatile BlockState[] statesById;
	private volatile byte[] opacityById;
	private volatile boolean[] leafById;
	/** Packed {@link VoxelShapeClass} per state: how the block occupies its cell. */
	private volatile int[] shapeById;
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
		shapeById = new int[INITIAL_STATE_CAPACITY];
		biomesById = new ResourceLocation[VoxelConstants.MAX_BIOME_IDS];

		statesById[0] = Blocks.AIR.defaultBlockState();
		opacityById[0] = 0;
		shapeById[0] = VoxelShapeClass.DEFAULT;
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
			int shape = VoxelShapeClass.classify(state);
			shapeById[id] = shape;
			opacityById[id] = computeOpacity(state, leaf, shape);
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

	/**
	 * Packed {@link VoxelShapeClass} for a state id: how the block fills its
	 * cell. Any thread; an id past the array (a torn read during growth) falls
	 * back to a full cube, which is the pre-shapes behaviour.
	 */
	public int shapeOf(int stateId) {
		int[] shape = shapeById;
		if (stateId >= 0 && stateId < shape.length) {
			return shape[stateId];
		}
		return VoxelShapeClass.DEFAULT;
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
	 * Reads {@code palette.nbt} and replays every persisted entry at its exact
	 * saved id (resolving via the given lookups, tombstoning failures per design
	 * section 2) before any region file is read. Worker thread, once at world
	 * open. A missing file is the fresh-world path (ids start from the reserved
	 * set); a corrupt or wrong-version file is quarantined and treated as absent
	 * — region rows citing ids we then no longer know decode to stone, the
	 * documented graceful-degradation path, never a crash.
	 *
	 * <p>Must complete before any {@link #idFor} runs for this world: it rebuilds
	 * the id space wholesale under {@link #registerLock}, and a registration
	 * racing the replay could claim an id the file wants for a different state.
	 * The engine guarantees this ordering (load, then start ingestion); the lock
	 * only protects against an accidental concurrent lookup, not against a caller
	 * that ingests before load returns.
	 */
	public void load(Path file, HolderGetter<Block> blocks, Registry<Biome> biomes) {
		if (file == null) {
			return;
		}
		if (!Files.exists(file)) {
			// Fresh world, or first run since the voxel engine was enabled:
			// nothing to replay, ids start from the bootstrap reserved set.
			return;
		}

		CompoundTag root;
		try {
			root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
		} catch (IOException | RuntimeException e) {
			// gzip CRC failure / truncation / malformed NBT. The palette is the
			// linchpin of id stability, so a damaged one cannot be trusted at all.
			// Quarantine so it does not re-fail every session, then start fresh.
			Iris.logger.error("Horizon: corrupt voxel palette " + file
				+ "; quarantining and starting fresh", e);
			quarantine(file, ".corrupt");
			return;
		}

		int fileVersion = root.getInt(TAG_VERSION);
		if (fileVersion != PALETTE_FORMAT_VERSION) {
			Iris.logger.warn("Horizon: voxel palette version " + fileVersion + " != "
				+ PALETTE_FORMAT_VERSION + "; discarding " + file + " (caches will regenerate)");
			quarantine(file, ".old");
			return;
		}

		synchronized (registerLock) {
			// Wholesale reset so a re-open (dimension/level switch without an
			// intervening clear) can never stack two worlds' entries, then replay
			// the file verbatim on top of the reserved set.
			resetToBootstrapLocked();

			int stateTombstones = replayStates(root.getList(TAG_STATES, Tag.TAG_COMPOUND), blocks);
			int biomeTombstones = replayBiomes(root.getList(TAG_BIOMES, Tag.TAG_COMPOUND), biomes);

			// Memory now mirrors disk: nothing to flush until the next
			// registration (which will re-arm the dirty flag itself).
			dirty = false;
			version.incrementAndGet();

			Iris.logger.info("Horizon: loaded voxel palette " + file + " — "
				+ nextStateId.get() + " states (" + stateTombstones + " tombstoned), "
				+ nextBiomeId.get() + " biomes (" + biomeTombstones + " tombstoned)");
		}
	}

	/**
	 * Replays persisted block states into the reverse array and forward map at
	 * their exact saved ids. An entry whose block no longer resolves (removed
	 * mod: {@link NbtUtils#readBlockState} yields air, and air is never persisted)
	 * becomes a tombstone — its slot is reserved so later ids keep their value,
	 * and its original NBT is retained for verbatim re-persist. Caller holds
	 * {@link #registerLock}. Returns the tombstone count.
	 */
	private int replayStates(ListTag states, HolderGetter<Block> blocks) {
		int maxValidId = FALLBACK_STATE_ID;
		for (int i = 0; i < states.size(); i++) {
			int id = states.getCompound(i).getInt(TAG_ID);
			if (id > maxValidId && id < VoxelConstants.MAX_STATE_IDS) {
				maxValidId = id;
			}
		}
		ensureStateCapacity(maxValidId);

		int tombstones = 0;
		for (int i = 0; i < states.size(); i++) {
			CompoundTag entry = states.getCompound(i);
			int id = entry.getInt(TAG_ID);
			// id 0 is implicit air (never persisted); protect the air/stone
			// reserved slots and drop anything past the cap or corrupt.
			if (id <= FALLBACK_STATE_ID || id >= VoxelConstants.MAX_STATE_IDS) {
				continue;
			}
			CompoundTag stateNbt = entry.getCompound(TAG_STATE);
			BlockState state = safeReadBlockState(blocks, stateNbt);
			if (state == null || state.isAir()) {
				tombstoneStateNbt.put(id, stateNbt.copy());
				tombstones++;
				// statesById[id] stays null → stateOf(id) falls back to stone.
				// Deliberately not added to stateToId: there is no live state key.
			} else {
				statesById[id] = state;
				boolean leaf = safeIsLeafLike(state);
				leafById[id] = leaf;
				// Shape is recomputed on load exactly like opacity: it is derived
				// from the live BlockState, never persisted, so a loaded palette
				// can never carry a stale class after a resource/mod change.
				int shape = VoxelShapeClass.classify(state);
				shapeById[id] = shape;
				opacityById[id] = computeOpacity(state, leaf, shape);
				stateToId.put(state, id);
			}
		}
		// Reserve every persisted slot so new registrations never collide with a
		// tombstone's id (append-only across sessions).
		nextStateId.set(maxValidId + 1);
		return tombstones;
	}

	/**
	 * Replays persisted biome names at their saved ids. Biomes need no NBT
	 * retention: the {@link ResourceLocation} name is the whole persistent form,
	 * so it always round-trips and re-adding a datapack restores correct tinting
	 * automatically. A name that does not resolve in the current registry is
	 * counted as a tombstone (it still tints as plains via {@link #biomeOf}'s
	 * consumers) but its slot and name are kept. Caller holds {@link #registerLock}.
	 * Returns the tombstone count.
	 */
	private int replayBiomes(ListTag biomeList, Registry<Biome> biomes) {
		int maxValidId = FALLBACK_BIOME_ID;
		int tombstones = 0;
		for (int i = 0; i < biomeList.size(); i++) {
			CompoundTag entry = biomeList.getCompound(i);
			int id = entry.getInt(TAG_ID);
			// Protect the reserved plains slot (id 0) and drop out-of-range ids.
			if (id <= FALLBACK_BIOME_ID || id >= VoxelConstants.MAX_BIOME_IDS) {
				continue;
			}
			ResourceLocation name = ResourceLocation.tryParse(entry.getString(TAG_NAME));
			if (name == null) {
				continue; // unparseable name; slot left empty, resolves to plains
			}
			biomesById[id] = name;
			biomeToId.put(name, id);
			if (!biomes.containsKey(name)) {
				tombstones++;
			}
			if (id > maxValidId) {
				maxValidId = id;
			}
		}
		nextBiomeId.set(maxValidId + 1);
		return tombstones;
	}

	/**
	 * Persists both palettes (atomic tmp+move) if anything was registered since
	 * the last save; no-op and returns false otherwise. Worker/IO thread.
	 *
	 * <p><b>Palette-first save invariant (design section 2).</b> The save cycle
	 * MUST call this as its very first step, before it encodes or writes any
	 * section row. The guarantee it buys: every id that was registered at the
	 * instant this call snapshots the tables is durable on disk once the call
	 * returns true. Because ids are append-only and monotonic, and a cell can
	 * only cite an id that was registered before the cell was written, a row
	 * written later in the same cycle references either (a) an id already in this
	 * snapshot — on disk — or (b) an id registered during this flush, whose
	 * section therefore also became dirty during the flush and will be written in
	 * a later cycle that runs its own palette save first. The only residual
	 * window (an id registered mid-flush that also lands in a row written this
	 * same cycle) is closed on the read side: the codec remaps any cell whose
	 * {@code stateId >= paletteSize} to stone (design section 6), so a crash in
	 * that window costs one stone-coloured cell until re-capture, never a
	 * dangling reference or a load failure.
	 *
	 * <p>Concurrency: the tables are snapshotted (and the dirty flag cleared)
	 * under the same {@link #registerLock} that {@link #idFor} registers under,
	 * so a registration is either fully in the snapshot or fully after it. The
	 * gzip write itself runs outside the lock over a fully detached
	 * {@link CompoundTag}, so concurrent lookups never block on disk I/O. A
	 * registration that races in after the snapshot re-arms the dirty flag
	 * itself; a failed write re-arms it too, so no registration is ever lost.
	 */
	public boolean saveIfDirty(Path file) {
		if (file == null) {
			return false;
		}

		CompoundTag root;
		synchronized (registerLock) {
			if (!dirty) {
				return false;
			}
			root = snapshotToTagLocked();
			// Clear under the lock at the snapshot instant: any later registration
			// re-sets dirty itself, so clearing here can never drop an id. If the
			// write below fails we re-arm dirty and retry next cycle.
			dirty = false;
		}

		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try {
			Path parent = file.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			NbtIo.writeCompressed(root, tmp);
			moveAtomic(tmp, file);
			return true;
		} catch (IOException | RuntimeException e) {
			Iris.logger.error("Horizon: failed to save voxel palette " + file
				+ "; re-arming dirty for retry next cycle", e);
			dirty = true; // never lose the registrations captured in the snapshot
			try {
				Files.deleteIfExists(tmp);
			} catch (IOException ignored) {
				// best-effort cleanup; a stale .tmp is overwritten next attempt
			}
			return false;
		}
	}

	/**
	 * Builds a fully detached {@link CompoundTag} of the current palettes so the
	 * gzip write can run outside {@link #registerLock}. Caller holds the lock.
	 * States are written from id 1 (skipping implicit air at 0); a tombstoned
	 * slot re-emits its retained NBT verbatim so removed-mod ids survive the
	 * round-trip. Biomes are written from id 1 (skipping reserved plains at 0).
	 */
	private CompoundTag snapshotToTagLocked() {
		CompoundTag root = new CompoundTag();
		root.putInt(TAG_VERSION, PALETTE_FORMAT_VERSION);

		ListTag stateList = new ListTag();
		BlockState[] states = statesById;
		int stateEnd = nextStateId.get();
		for (int id = FALLBACK_STATE_ID; id < stateEnd; id++) {
			CompoundTag stateNbt;
			BlockState state = id < states.length ? states[id] : null;
			if (state != null) {
				stateNbt = NbtUtils.writeBlockState(state);
			} else {
				CompoundTag tomb = tombstoneStateNbt.get(id);
				if (tomb == null) {
					// Empty slot with no retained blob: unreachable for a
					// well-formed palette, but skip rather than emit garbage.
					continue;
				}
				stateNbt = tomb.copy();
			}
			CompoundTag entry = new CompoundTag();
			entry.putInt(TAG_ID, id);
			entry.put(TAG_STATE, stateNbt);
			stateList.add(entry);
		}
		root.put(TAG_STATES, stateList);

		ListTag biomeList = new ListTag();
		ResourceLocation[] biomes = biomesById;
		int biomeEnd = nextBiomeId.get();
		for (int id = FALLBACK_BIOME_ID + 1; id < biomeEnd; id++) {
			ResourceLocation name = id < biomes.length ? biomes[id] : null;
			if (name == null) {
				continue;
			}
			CompoundTag entry = new CompoundTag();
			entry.putInt(TAG_ID, id);
			entry.putString(TAG_NAME, name.toString());
			biomeList.add(entry);
		}
		root.put(TAG_BIOMES, biomeList);
		return root;
	}

	/** Atomic replace where the filesystem supports it, plain replace otherwise. */
	private static void moveAtomic(Path tmp, Path dest) throws IOException {
		try {
			Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Renames a bad palette file aside so it does not re-fail every session. */
	private static void quarantine(Path file, String suffix) {
		try {
			Path dest = file.resolveSibling(file.getFileName() + suffix);
			Files.deleteIfExists(dest);
			Files.move(file, dest, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException | RuntimeException e) {
			Iris.logger.warn("Horizon: could not quarantine voxel palette " + file, e);
		}
	}

	/** {@link NbtUtils#readBlockState} guarded: corrupt property NBT tombstones rather than aborts world load. */
	private static BlockState safeReadBlockState(HolderGetter<Block> blocks, CompoundTag tag) {
		try {
			return NbtUtils.readBlockState(blocks, tag);
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * Full reset for level unload: a new world means new registries, and
	 * stale BlockState keys would pin the old world's objects. Keeps the
	 * version counter monotonic so any consumer holding a pre-clear version
	 * still sees "something changed".
	 */
	public void clear() {
		synchronized (registerLock) {
			resetToBootstrapLocked();
			version.incrementAndGet();
		}
	}

	/**
	 * Drops every registration back to the reserved set. Shared by {@link #clear}
	 * (level unload) and {@link #load} (which replays a file on top of a clean
	 * slate). Caller holds {@link #registerLock}; the version bump is the
	 * caller's so load can order it after the replay.
	 */
	private void resetToBootstrapLocked() {
		stateToId.clear();
		biomeToId.clear();
		stateOverflow.clear();
		biomeOverflow.clear();
		tombstoneStateNbt.clear();
		overflowCount.set(0);
		overflowLogged = false;
		keylessBiomeLogged = false;
		bootstrap();
	}

	private void growStateArrays() {
		growStateArraysTo((int) Math.min((long) statesById.length * 2, VoxelConstants.MAX_STATE_IDS));
	}

	/**
	 * Ensures the reverse arrays can index {@code maxId} directly, doubling until
	 * they fit (capped at {@link VoxelConstants#MAX_STATE_IDS}). Used by the M2
	 * loader, which places states at their exact persisted ids rather than
	 * appending one at a time.
	 */
	private void ensureStateCapacity(int maxId) {
		if (maxId < statesById.length) {
			return;
		}
		long newLength = statesById.length;
		while (newLength <= maxId && newLength < VoxelConstants.MAX_STATE_IDS) {
			newLength <<= 1;
		}
		growStateArraysTo((int) Math.min(newLength, VoxelConstants.MAX_STATE_IDS));
	}

	private void growStateArraysTo(int newLength) {
		if (newLength <= statesById.length) {
			return;
		}
		BlockState[] states = new BlockState[newLength];
		byte[] opacity = new byte[newLength];
		boolean[] leaf = new boolean[newLength];
		int[] shape = new int[newLength];
		System.arraycopy(statesById, 0, states, 0, statesById.length);
		System.arraycopy(opacityById, 0, opacity, 0, opacityById.length);
		System.arraycopy(leafById, 0, leaf, 0, leafById.length);
		System.arraycopy(shapeById, 0, shape, 0, shapeById.length);
		// Republish fully-copied arrays; readers hold either the old or the
		// new reference, both consistent for every already-issued id.
		statesById = states;
		opacityById = opacity;
		leafById = leaf;
		shapeById = shape;
	}

	/**
	 * Opacity per design section 4: air 0; leaf-like forced 15 (solid
	 * distant canopies); otherwise the state's light block value, guarded
	 * because modded overrides may throw off-thread — then occluders count
	 * as 15 and everything else as barely-there 1.
	 */
	private static byte computeOpacity(BlockState state, boolean leafLike, int shape) {
		if (state.isAir()) {
			return 0;
		}
		// A state that does not fill its cell must never occlude: opacity 15 would
		// cull the face of whatever is behind a fence or beside a slab, would let
		// a lone fence post outrank solid rock in VoxelMipper.selectRepresentative
		// (ballooning into a 16-block cube at L4), and would exclude the cell from
		// the mip light average. All three read this same byte.
		if (VoxelShapeClass.classOf(shape) != VoxelShapeClass.FULL_CUBE) {
			return 0;
		}
		if (leafLike) {
			return 15;
		}
		try {
			// A full opaque occluding cube hides the face behind it → opacity 15.
			// canOcclude() is the reliable signal here: getLightBlock with an
			// EmptyBlockGetter reports 0 for most solid blocks, which made the
			// mesher emit an internal face at every block-type boundary.
			if (state.canOcclude()) {
				return 15;
			}
			int light = state.getLightBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
			return (byte) Math.max(0, Math.min(14, light));
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
