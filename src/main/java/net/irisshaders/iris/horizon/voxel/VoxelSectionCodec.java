package net.irisshaders.iris.horizon.voxel;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Pure encode/decode of one section's 32768-cell {@code long[]} to and from a
 * self-describing byte payload (design-data-storage.md section 6, the {@code .hlod}
 * v4 row encodings). No file IO, no threading, no palette or registry access —
 * cells carry global palette ids already, so the codec is a deterministic byte
 * transform and nothing else.
 *
 * <h2>Separation of concerns (M2 architecture decision)</h2>
 * This codec owns exactly the <em>cell payload</em>: {@code [encoding id byte][body]}.
 * The surrounding row frame — {@code sectionKey}, {@code nonAirCount},
 * {@code populationMask}, {@code payloadLength} — belongs to
 * {@code VoxelRegionStorage}, which treats the payload as an opaque blob. This
 * is a deliberate narrowing of the section-6 frame sketch (which listed the
 * encoding byte as a framed field): keeping the encoding id inside the payload
 * lets the codec be tested and reasoned about in complete isolation, and means
 * storage never has to understand the encoding it is persisting. Consequently
 * {@code nonAirCount} is <em>not</em> recomputed here on decode — storage reads
 * the framed census and hands it to {@link VoxelSection#VoxelSection(long, long[], int)}
 * (the adoption ctor), skipping a redundant 32768-cell scan on every inflate.
 * {@link #countNonAir(long[])} is exposed for the rare caller that must derive
 * the census itself (e.g. validating a freshly encoded hot section before framing).
 *
 * <h2>Encodings (section 6)</h2>
 * <ul>
 * <li>{@code 3 UNIFORM} — body = 1 long: the whole section is one cell value
 *     (all-stone underground packs to a 9-byte payload).</li>
 * <li>{@code 1 PALETTIZED_BITPACK} — varint {@code N}, then {@code N} longs (the
 *     local palette of distinct values in first-appearance order), then
 *     {@code 32768 * b} index bits with {@code b = max(1, ceil(log2 N))} packed
 *     little-endian into {@code ceil(32768*b/64)} longs, cell order
 *     {@code (y<<10)|(z<<5)|x} (= array order).</li>
 * <li>{@code 4 PALETTIZED_RLE} — same local palette, then varint {@code runCount},
 *     then {@code runCount} pairs of (varint localIdx, varint runLen) in the same
 *     cell order.</li>
 * <li>{@code 2 RAW} — 32768 longs verbatim; the {@code N > 4096} escape.</li>
 * </ul>
 *
 * <h2>Encoding choice</h2>
 * One counting pass over the cells builds the local palette (first-appearance
 * order) while tallying the run count and the exact byte cost of the RLE index
 * stream. Then: {@code UNIFORM} if {@code N == 1}; {@code RAW} if
 * {@code N > RAW_ESCAPE_PALETTE_SIZE}; otherwise {@code RLE} when its index
 * stream is strictly smaller than the bitpacked one, else {@code BITPACK}. Both
 * palettized forms share the identical {@code varint N + N longs} palette, so the
 * choice reduces to comparing only the two index streams. The outer gzip applied
 * by {@code VoxelRegionStorage} mops up residual redundancy.
 *
 * <h2>Determinism &amp; round-trip invariant (self-check)</h2>
 * For every {@code long[32768]} {@code c}, {@code decodeCells(encodeCells(c))}
 * equals {@code c} element-for-element, across all four encodings and every
 * boundary palette size {@code N} in {1, 2, 4096, 4097}. {@code encodeCells} is
 * additionally byte-for-byte reproducible: the palette order comes from a linear
 * scan (a {@link LongArrayList}), never from hash-map iteration, and every
 * serialization walk is a linear pass — so identical input always yields an
 * identical payload regardless of JVM or {@link Long2IntOpenHashMap} internals.
 * The bit width {@code b} and packed-long count are derived from {@code N} on
 * both sides via {@link #bitsForPalette(int)} / {@link #packedLongCount(int)},
 * so they can never disagree.
 */
final class VoxelSectionCodec {
	// --- Encoding ids (payload byte 0). Codec-local: the payload is opaque to
	// storage, so these live here rather than in VoxelConstants (section 7 homes
	// the codec's format constants on this class). ---
	static final int ENC_BITPACK = 1;
	static final int ENC_RAW = 2;
	static final int ENC_UNIFORM = 3;
	static final int ENC_RLE = 4;

	/**
	 * Distinct-value ceiling for the palettized forms; beyond it the section
	 * escapes to {@code RAW}. A section with more than 4096 distinct cell values
	 * is pathological (surface L0 sections run 20-60), so RAW is a safety valve,
	 * not an expected path.
	 */
	static final int RAW_ESCAPE_PALETTE_SIZE = 4096;

	private static final int CELLS = VoxelConstants.SECTION_CELLS;

	private VoxelSectionCodec() {
	}

	// --- Encode ---------------------------------------------------------------

	/**
	 * Encodes a full section's cell array into a self-describing payload
	 * ({@code [encoding id][body]}). The array must be exactly
	 * {@link VoxelConstants#SECTION_CELLS} longs; a wrong length is a caller bug
	 * and fails fast rather than producing a silently truncated row.
	 *
	 * <p>Does one counting pass to pick the encoding, then one serialization
	 * pass to emit it — never mutates {@code cells}.
	 */
	static byte[] encodeCells(long[] cells) {
		if (cells.length != CELLS) {
			throw new IllegalArgumentException("expected " + CELLS + " cells, got " + cells.length);
		}

		// Counting pass: local palette (first-appearance order), run tally, and
		// the exact RLE index-stream cost. Interning as we go also gives the
		// value->localId map the serialization passes reuse.
		Long2IntOpenHashMap valueToLocal = new Long2IntOpenHashMap(64);
		valueToLocal.defaultReturnValue(-1);
		LongArrayList palette = new LongArrayList();

		long cur = cells[0];
		int curLocal = intern(cur, valueToLocal, palette);
		int runLen = 1;
		int runCount = 0;
		long runPairsBytes = 0;

		for (int i = 1; i < CELLS; i++) {
			long v = cells[i];
			if (v == cur) {
				runLen++;
				continue;
			}
			runCount++;
			runPairsBytes += varIntSize(curLocal) + varIntSize(runLen);
			int local = intern(v, valueToLocal, palette);
			// Distinct count blew the palette cap: no palettized form applies,
			// so bail straight to RAW without finishing the run accounting.
			if (palette.size() > RAW_ESCAPE_PALETTE_SIZE) {
				return encodeRaw(cells);
			}
			cur = v;
			curLocal = local;
			runLen = 1;
		}
		// Close the final run.
		runCount++;
		runPairsBytes += varIntSize(curLocal) + varIntSize(runLen);

		int n = palette.size();
		if (n == 1) {
			return encodeUniform(cells[0]);
		}

		int b = bitsForPalette(n);
		long bitpackIndexBytes = (long) packedLongCount(b) * Long.BYTES;
		long rleIndexBytes = varIntSize(runCount) + runPairsBytes;

		if (rleIndexBytes < bitpackIndexBytes) {
			return encodeRle(cells, valueToLocal, palette, runCount, (int) rleIndexBytes);
		}
		return encodeBitpack(cells, valueToLocal, palette, b);
	}

	private static byte[] encodeUniform(long value) {
		ByteBuffer buf = ByteBuffer.allocate(1 + Long.BYTES);
		buf.put((byte) ENC_UNIFORM);
		buf.putLong(value);
		return buf.array();
	}

	private static byte[] encodeRaw(long[] cells) {
		ByteBuffer buf = ByteBuffer.allocate(1 + CELLS * Long.BYTES);
		buf.put((byte) ENC_RAW);
		for (int i = 0; i < CELLS; i++) {
			buf.putLong(cells[i]);
		}
		return buf.array();
	}

	private static byte[] encodeBitpack(long[] cells, Long2IntOpenHashMap valueToLocal, LongArrayList palette, int b) {
		int n = palette.size();
		int packedLongs = packedLongCount(b);
		int size = 1 + varIntSize(n) + n * Long.BYTES + packedLongs * Long.BYTES;
		ByteBuffer buf = ByteBuffer.allocate(size);
		buf.put((byte) ENC_BITPACK);
		writeVarInt(buf, n);
		for (int i = 0; i < n; i++) {
			buf.putLong(palette.getLong(i));
		}

		// Pack the index stream little-endian into longs: index i's b bits start
		// at absolute bit position i*b; a value spans at most two longs (b<=12<64).
		long[] packed = new long[packedLongs];
		long mask = (1L << b) - 1;
		long bitPos = 0;
		for (int i = 0; i < CELLS; i++) {
			long id = valueToLocal.get(cells[i]) & mask;
			int wi = (int) (bitPos >>> 6);
			int off = (int) (bitPos & 63);
			packed[wi] |= id << off;
			if (off + b > 64) {
				packed[wi + 1] |= id >>> (64 - off);
			}
			bitPos += b;
		}
		for (int i = 0; i < packedLongs; i++) {
			buf.putLong(packed[i]);
		}
		return buf.array();
	}

	private static byte[] encodeRle(long[] cells, Long2IntOpenHashMap valueToLocal, LongArrayList palette,
			int runCount, int rleIndexBytes) {
		int n = palette.size();
		int size = 1 + varIntSize(n) + n * Long.BYTES + rleIndexBytes;
		ByteBuffer buf = ByteBuffer.allocate(size);
		buf.put((byte) ENC_RLE);
		writeVarInt(buf, n);
		for (int i = 0; i < n; i++) {
			buf.putLong(palette.getLong(i));
		}
		writeVarInt(buf, runCount);

		// Re-walk emitting (localIdx, runLen) pairs. The transition logic is
		// identical to the counting pass, so exactly runCount runs are written
		// and the buffer fills precisely (rleIndexBytes was measured from it).
		long cur = cells[0];
		int curLocal = valueToLocal.get(cur);
		int runLen = 1;
		for (int i = 1; i < CELLS; i++) {
			long v = cells[i];
			if (v == cur) {
				runLen++;
				continue;
			}
			writeVarInt(buf, curLocal);
			writeVarInt(buf, runLen);
			cur = v;
			curLocal = valueToLocal.get(v);
			runLen = 1;
		}
		writeVarInt(buf, curLocal);
		writeVarInt(buf, runLen);
		return buf.array();
	}

	// --- Decode ---------------------------------------------------------------

	/**
	 * Decodes a payload into a freshly {@link SectionPool#acquire() pooled}
	 * cell array and returns it — the warm/disk inflate path. The caller adopts
	 * the array via {@link VoxelSection#VoxelSection(long, long[], int)} with the
	 * framed {@code nonAirCount}, so no census runs here.
	 *
	 * <p>On any malformed input (unknown encoding, buffer underflow from a
	 * truncated row, out-of-range local index) the pooled array is returned to
	 * the pool before the exception propagates — a corrupt region must not
	 * starve the pool. {@code VoxelRegionStorage} catches the throw, quarantines
	 * the file, and treats the section as absent.
	 */
	static long[] decodeCells(byte[] payload) {
		ByteBuffer buf = ByteBuffer.wrap(payload);
		long[] dst = SectionPool.acquire(); // zeroed
		try {
			int enc = buf.get() & 0xFF;
			switch (enc) {
				case ENC_UNIFORM -> {
					long value = buf.getLong();
					if (value != 0L) {
						Arrays.fill(dst, value);
					}
					// value == 0 -> section is all-air; the pooled array is
					// already zero, so nothing to do.
				}
				case ENC_RAW -> {
					for (int i = 0; i < CELLS; i++) {
						dst[i] = buf.getLong();
					}
				}
				case ENC_BITPACK -> decodeBitpack(buf, dst);
				case ENC_RLE -> decodeRle(buf, dst);
				default -> throw new IllegalArgumentException("unknown voxel row encoding " + enc);
			}
		} catch (RuntimeException e) {
			SectionPool.release(dst);
			throw e;
		}
		return dst;
	}

	private static void decodeBitpack(ByteBuffer buf, long[] dst) {
		int n = checkedPaletteSize(readVarInt(buf));
		long[] palette = new long[n];
		for (int i = 0; i < n; i++) {
			palette[i] = buf.getLong();
		}
		int b = bitsForPalette(n);
		int packedLongs = packedLongCount(b);
		long[] packed = new long[packedLongs];
		for (int i = 0; i < packedLongs; i++) {
			packed[i] = buf.getLong();
		}
		long mask = (1L << b) - 1;
		long bitPos = 0;
		for (int i = 0; i < CELLS; i++) {
			int wi = (int) (bitPos >>> 6);
			int off = (int) (bitPos & 63);
			long id = (packed[wi] >>> off) & mask;
			if (off + b > 64) {
				id |= (packed[wi + 1] << (64 - off)) & mask;
			}
			dst[i] = palette[(int) id];
			bitPos += b;
		}
	}

	private static void decodeRle(ByteBuffer buf, long[] dst) {
		int n = checkedPaletteSize(readVarInt(buf));
		long[] palette = new long[n];
		for (int i = 0; i < n; i++) {
			palette[i] = buf.getLong();
		}
		int runCount = readVarInt(buf);
		if (runCount < 0 || runCount > CELLS) {
			throw new IllegalArgumentException("run count out of range: " + runCount);
		}
		int pos = 0;
		for (int r = 0; r < runCount; r++) {
			int localIdx = readVarInt(buf);
			int runLen = readVarInt(buf);
			if (localIdx < 0 || localIdx >= n || runLen <= 0 || runLen > CELLS - pos) {
				throw new IllegalArgumentException("malformed RLE run");
			}
			Arrays.fill(dst, pos, pos + runLen, palette[localIdx]);
			pos += runLen;
		}
		if (pos != CELLS) {
			throw new IllegalArgumentException("RLE covered " + pos + " cells, expected " + CELLS);
		}
	}

	/**
	 * Validates a decoded palette size before it becomes an allocation.
	 *
	 * <p>These payloads used to come only from files this code wrote, so the
	 * length was implicitly trusted; they now also arrive from a server over the
	 * network. An unchecked size lets a crafted payload request an array of
	 * billions of longs, and the resulting OutOfMemoryError is an Error, not a
	 * RuntimeException — so it escapes the decode path's own recovery and takes
	 * the process down. A section can hold at most one distinct value per cell.
	 */
	private static int checkedPaletteSize(int n) {
		if (n <= 0 || n > CELLS) {
			throw new IllegalArgumentException("palette size out of range: " + n);
		}
		return n;
	}

	// --- Census helper --------------------------------------------------------

	/**
	 * Counts non-air cells (stateId != 0). Not used by the decode path — storage
	 * frames the census and feeds the adoption ctor — but exposed for callers
	 * that must derive it (e.g. validating a hot section's array before framing,
	 * or a round-trip self-test).
	 */
	static int countNonAir(long[] cells) {
		int count = 0;
		for (int i = 0; i < CELLS; i++) {
			if (!VoxelCell.isAir(cells[i])) {
				count++;
			}
		}
		return count;
	}

	// --- Bit-width / packing geometry (shared by encode and decode) -----------

	/**
	 * Bits per index for a local palette of {@code n} entries:
	 * {@code max(1, ceil(log2 n))}. {@code n} is always &ge; 2 on the bitpack
	 * path (n == 1 is UNIFORM), so this yields 1..12 for n up to 4096. Using the
	 * same derivation on encode and decode guarantees the packed stream is read
	 * back with the identical width.
	 */
	private static int bitsForPalette(int n) {
		return Math.max(1, 32 - Integer.numberOfLeadingZeros(n - 1));
	}

	/** Longs needed to hold {@code 32768 * b} index bits: {@code ceil(32768*b/64)}. */
	private static int packedLongCount(int b) {
		return (CELLS * b + 63) >>> 6;
	}

	// --- Unsigned LEB128 varint helpers (package-visible for row-frame reuse) --

	/** Writes {@code value} (&ge; 0) as an unsigned LEB128 varint. */
	static void writeVarInt(ByteBuffer buf, int value) {
		while ((value & ~0x7F) != 0) {
			buf.put((byte) ((value & 0x7F) | 0x80));
			value >>>= 7;
		}
		buf.put((byte) value);
	}

	/**
	 * Reads an unsigned LEB128 varint. Guards against a runaway continuation
	 * chain (corrupt row) rather than looping forever.
	 */
	static int readVarInt(ByteBuffer buf) {
		int result = 0;
		int shift = 0;
		while (true) {
			int b = buf.get() & 0xFF;
			result |= (b & 0x7F) << shift;
			if ((b & 0x80) == 0) {
				return result;
			}
			shift += 7;
			if (shift >= 35) {
				throw new IllegalArgumentException("varint too long");
			}
		}
	}

	/** Byte length of {@code value} (&ge; 0) as an unsigned LEB128 varint. */
	static int varIntSize(int value) {
		int size = 1;
		while ((value & ~0x7F) != 0) {
			value >>>= 7;
			size++;
		}
		return size;
	}

	private static int intern(long value, Long2IntOpenHashMap valueToLocal, LongArrayList palette) {
		int id = valueToLocal.get(value);
		if (id < 0) {
			id = palette.size();
			valueToLocal.put(value, id);
			palette.add(value);
		}
		return id;
	}
}
