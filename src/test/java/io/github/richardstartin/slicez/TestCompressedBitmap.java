package io.github.richardstartin.slicez;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.PrimitiveIterator;
import java.util.SplittableRandom;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Execution(ExecutionMode.CONCURRENT)
class TestCompressedBitmap {

	private static final int BLOCK = 1 << 16;

	/**
	 * Appends the given (ascending, distinct) row ids, builds the bitmap and
	 * asserts that both the bit iterator and the block iterator reproduce exactly
	 * the input.
	 */
	private static void assertRoundTrip(int[] rids) {
		var appender = CompressedBitmap.appender();
		for (int rid : rids) {
			appender.add(rid);
		}
		var bitmap = appender.build();
		assertArrayEquals(rids, collect(bitmap.iterator()), "bit iterator");
		assertArrayEquals(rids, collectViaBlocks(bitmap), "block iterator");
	}

	private static int[] collect(PrimitiveIterator.OfInt it) {
		int[] buf = new int[256];
		int n = 0;
		while (it.hasNext()) {
			if (n == buf.length) {
				buf = Arrays.copyOf(buf, buf.length * 2);
			}
			buf[n++] = it.nextInt();
		}
		return Arrays.copyOf(buf, n);
	}

	/**
	 * Reconstructs all row ids by decoding each block via the block iterator and
	 * combining the returned block id with the set bit positions.
	 */
	private static int[] collectViaBlocks(CompressedBitmap bitmap) {
		return collectBlocks(bitmap.blockIterator());
	}

	private static int[] range(int from, int to) {
		return IntStream.range(from, to).toArray();
	}

	@Test
	void empty() {
		assertRoundTrip(new int[]{});
	}

	@Test
	void emptyIteratorHasNoNext() {
		assertFalse(CompressedBitmap.appender().build().iterator().hasNext());
	}

	@Test
	void singleBit() {
		assertRoundTrip(new int[]{0});
	}

	@Test
	void singleBitFarFromOrigin() {
		assertRoundTrip(new int[]{1_000_000});
	}

	@Test
	void sparseWithinFirstBlock() {
		assertRoundTrip(new int[]{0, 5, 63, 64, 1000, 65535});
	}

	@Test
	void sparseAcrossAdjacentBlocks() {
		assertRoundTrip(new int[]{1, 2, 3, BLOCK + 10, BLOCK + 20, 2 * BLOCK});
	}

	@Test
	void sparseAcrossEmptyBlockGap() {
		// blocks 1..4 are absent
		assertRoundTrip(new int[]{1, 2, 3, 5 * BLOCK + 10, 5 * BLOCK + 20});
	}

	@Test
	void leadingGap() {
		// first row id lands in block 3, so blocks 0..2 are absent
		assertRoundTrip(new int[]{3 * BLOCK, 3 * BLOCK + 1, 3 * BLOCK + 5});
	}

	@Test
	void gapSpanningManyAbsentTypeWords() {
		// a gap longer than 64 blocks forces several fully-ABSENT type mask words
		assertRoundTrip(new int[]{7, 200 * BLOCK + 42, 200 * BLOCK + 9999});
	}

	@Test
	void gapLandingExactlyOnTypeWordBoundary() {
		// block 64 is the first block of the second type mask word
		assertRoundTrip(new int[]{0, 64 * BLOCK, 64 * BLOCK + 1});
	}

	@Test
	void denseBlock() {
		// every other bit set -> stored DENSE
		assertRoundTrip(IntStream.range(0, BLOCK).filter(i -> (i & 1) == 0).toArray());
	}

	@Test
	void fullBlock() {
		assertRoundTrip(range(0, BLOCK));
	}

	@Test
	void fullBlockThenSparseBlock() {
		int[] full = range(0, BLOCK);
		int[] tail = {3 * BLOCK + 1, 3 * BLOCK + 2};
		int[] rids = Arrays.copyOf(full, full.length + tail.length);
		System.arraycopy(tail, 0, rids, full.length, tail.length);
		assertRoundTrip(rids);
	}

	@Test
	void sparseInvertedBlock() {
		// almost full: only a handful of positions missing -> stored SPARSE_INVERTED
		var dropped = new boolean[BLOCK];
		for (int p : new int[]{0, 1, 100, 32768, 65534, 65535}) {
			dropped[p] = true;
		}
		assertRoundTrip(IntStream.range(0, BLOCK).filter(i -> !dropped[i]).toArray());
	}

	@Test
	void sparseInvertedMissingFirstAndLast() {
		assertRoundTrip(IntStream.range(1, BLOCK - 1).toArray());
	}

	/**
	 * Cardinalities straddling the SPARSE / DENSE / SPARSE_INVERTED storage
	 * decisions, exercised as contiguous prefixes of a single block.
	 */
	@ParameterizedTest
	@ValueSource(ints = {1, 100, 32766, 32767, 32768, 32769, 65535, 65536})
	void storageTypeBoundaries(int cardinality) {
		assertRoundTrip(range(0, cardinality));
	}

	/**
	 * Block sizes around the 256-element batch boundary, to exercise resuming a
	 * block across {@code fillBatch} calls (including blocks that exactly fill a
	 * batch).
	 */
	@ParameterizedTest
	@ValueSource(ints = {1, 255, 256, 257, 511, 512, 513, 768, 1000})
	void batchBoundaries(int n) {
		assertRoundTrip(range(0, n));
	}

	static Stream<Arguments> fuzzCases() {
		var args = Stream.<Arguments>builder();
		for (long seed : new long[]{1, 7, 42, 1234, 99999}) {
			// (rowCount, valueRange) pairs spanning sparse single-block through
			// multi-block dense and inverted distributions
			args.add(Arguments.of(seed, 50, BLOCK));
			args.add(Arguments.of(seed, 5_000, BLOCK));
			args.add(Arguments.of(seed, 60_000, BLOCK));
			args.add(Arguments.of(seed, 100_000, 10 * BLOCK));
			args.add(Arguments.of(seed, 500_000, 8 * BLOCK));
			args.add(Arguments.of(seed, 200_000, 1_000 * BLOCK));
		}
		return args.build();
	}

	@ParameterizedTest
	@MethodSource("fuzzCases")
	void fuzz(long seed, int rowCount, int valueRange) {
		var rnd = new SplittableRandom(seed);
		// distinct, ascending row ids
		var present = new java.util.TreeSet<Integer>();
		while (present.size() < rowCount) {
			present.add(rnd.nextInt(valueRange));
		}
		assertRoundTrip(present.stream().mapToInt(Integer::intValue).toArray());
	}

	@Test
	void blockIteratorEmptyHasNoNext() {
		assertFalse(CompressedBitmap.appender().build().blockIterator().hasNext());
	}

	@Test
	void blockIteratorThrowsWhenExhausted() {
		var appender = CompressedBitmap.appender();
		appender.add(42);
		var it = appender.build().blockIterator();
		assertTrue(it.hasNext());
		it.nextBlock();
		assertFalse(it.hasNext());
		assertThrows(java.util.NoSuchElementException.class, () -> it.nextBlock());
	}

	@Test
	void blockIteratorReturnsHighBitsAndSkipsAbsentBlocks() {
		var appender = CompressedBitmap.appender();
		// present blocks: 0, 3 and 5; blocks 1, 2 and 4 are absent
		appender.add(10);
		appender.add(3 * BLOCK + 1);
		appender.add(5 * BLOCK + 2);
		var it = appender.build().blockIterator();
		var bits = it.getBits();

		assertTrue(it.hasNext());
		assertEquals(0, it.nextBlock());
		assertEquals(10, singleSetBit(bits.bits));

		assertTrue(it.hasNext());
		assertEquals(3, it.nextBlock());
		assertEquals(1, singleSetBit(bits.bits));

		assertTrue(it.hasNext());
		assertEquals(5, it.nextBlock());
		assertEquals(2, singleSetBit(bits.bits));

		assertFalse(it.hasNext());
	}

	@Test
	void blockIteratorReusesBitsArrayWithoutStaleBits() {
		var appender = CompressedBitmap.appender();
		// a dense block followed by a sparse block: decoding the sparse block must not
		// leave behind any bits from the dense one in the reused buffer
		for (int i = 0; i < BLOCK; i += 2) {
			appender.add(i);
		}
		appender.add(BLOCK + 7);
		var it = appender.build().blockIterator();
		var bits = it.getBits();

		assertEquals(0, it.nextBlock());
		assertEquals(BLOCK / 2, cardinality(bits.bits));

		assertEquals(1, it.nextBlock());
		assertEquals(7, singleSetBit(bits.bits));
	}

	private static int singleSetBit(long[] bits) {
		assertEquals(1, cardinality(bits), "expected exactly one set bit");
		for (int w = 0; w < bits.length; w++) {
			if (bits[w] != 0) {
				return (w << 6) + Long.numberOfTrailingZeros(bits[w]);
			}
		}
		throw new AssertionError("no set bit");
	}

	private static int cardinality(long[] bits) {
		int c = 0;
		for (long word : bits) {
			c += Long.bitCount(word);
		}
		return c;
	}

	@Test
	void denselyClusteredBlocksWithSparseGaps() {
		// alternate fully-dense blocks with single-bit blocks across a wide range
		var rids = new java.util.ArrayList<Integer>();
		for (int block = 0; block < 130; block++) {
			if ((block & 1) == 0) {
				for (int i = 0; i < BLOCK; i += 3) {
					rids.add(block * BLOCK + i);
				}
			} else {
				rids.add(block * BLOCK + 12345);
			}
		}
		assertRoundTrip(rids.stream().mapToInt(Integer::intValue).toArray());
	}

	// -- and (intersection) --------------------------------------------------

	// per-block cardinalities that land in each storage type (SPARSE < 4095,
	// 4095 <= DENSE < 61441, SPARSE_INVERTED >= 61441)
	private static final int SPARSE_CARD = 1_000;
	private static final int DENSE_CARD = 30_000;
	private static final int INVERTED_CARD = 64_800;

	private static CompressedBitmap build(int[] rids) {
		var appender = CompressedBitmap.appender();
		for (int rid : rids) {
			appender.add(rid);
		}
		return appender.build();
	}

	private static int[] toArray(java.util.Collection<Integer> values) {
		return values.stream().mapToInt(Integer::intValue).toArray();
	}

	/**
	 * Adds {@code count} distinct random positions within {@code block} to
	 * {@code set}. The seed is deterministic, so passing the same seed for two
	 * blocks makes the smaller a subset of the larger (guaranteeing a non-empty
	 * intersection).
	 */
	private static void addBlock(java.util.TreeSet<Integer> set, int block, int count, long seed) {
		var random = new SplittableRandom(seed);
		int base = block * BLOCK;
		int added = 0;
		while (added < count) {
			if (set.add(base + random.nextInt(BLOCK))) {
				added++;
			}
		}
	}

	private static int[] expectedIntersection(int[] a, int[] b) {
		var present = new java.util.HashSet<Integer>();
		for (int x : a) {
			present.add(x);
		}
		var out = new java.util.ArrayList<Integer>();
		for (int x : b) {
			if (present.contains(x)) {
				out.add(x);
			}
		}
		return out.stream().mapToInt(Integer::intValue).sorted().toArray();
	}

	/**
	 * Reconstructs all row ids yielded by a block iterator, asserting the blocks
	 * come back strictly ascending, that no empty block is ever yielded, and that a
	 * block reported {@link Bits#isFull() full} really does hold every bit.
	 */
	private static int[] collectBlocks(CompressedBitmap.BlockIterator it) {
		Bits bits = it.getBits();
		int[] buf = new int[256];
		int n = 0;
		int previousBlock = -1;
		while (it.hasNext()) {
			int blockId = it.nextBlock();
			assertTrue(blockId > previousBlock, "block ids must be strictly ascending");
			previousBlock = blockId;
			int base = blockId * BLOCK;
			int cardinality = 0;
			for (int w = 0; w < bits.bits.length; w++) {
				long word = bits.bits[w];
				cardinality += Long.bitCount(word);
				while (word != 0) {
					int b = Long.numberOfTrailingZeros(word);
					word &= (word - 1);
					if (n == buf.length) {
						buf = Arrays.copyOf(buf, buf.length * 2);
					}
					buf[n++] = base + (w << 6) + b;
				}
			}
			assertTrue(cardinality > 0, "iterated blocks must be non-empty");
			if (bits.isFull()) {
				assertEquals(BLOCK, cardinality, "a block reported full must hold every bit");
			}
		}
		return Arrays.copyOf(buf, n);
	}

	private static int[] collectViaAnd(CompressedBitmap x, CompressedBitmap y) {
		return collectBlocks(x.and(y));
	}

	private static int[] collectViaOr(CompressedBitmap x, CompressedBitmap y) {
		return collectBlocks(x.or(y));
	}

	private static int[] expectedUnion(int[] a, int[] b) {
		var union = new java.util.TreeSet<Integer>();
		for (int x : a) {
			union.add(x);
		}
		for (int x : b) {
			union.add(x);
		}
		return toArray(union);
	}

	/**
	 * Asserts {@code a OR b} matches the true set union, checked in both operand
	 * orders.
	 */
	private static void assertOr(int[] a, int[] b) {
		var x = build(a);
		var y = build(b);
		int[] expected = expectedUnion(a, b);
		assertArrayEquals(expected, collectViaOr(x, y), "x OR y");
		assertArrayEquals(expected, collectViaOr(y, x), "y OR x");
	}

	/**
	 * Asserts {@code a AND b} matches the true set intersection, checked in both
	 * operand orders (intersection is commutative and the dispatch orders by type).
	 */
	private static void assertAnd(int[] a, int[] b) {
		var x = build(a);
		var y = build(b);
		int[] expected = expectedIntersection(a, b);
		assertArrayEquals(expected, collectViaAnd(x, y), "x AND y");
		assertArrayEquals(expected, collectViaAnd(y, x), "y AND x");
	}

	static Stream<Arguments> storageTypePairs() {
		return Stream.of(Arguments.of("dense & dense", DENSE_CARD, DENSE_CARD),
				Arguments.of("sparse & dense", SPARSE_CARD, DENSE_CARD),
				Arguments.of("sparse & sparse", SPARSE_CARD, SPARSE_CARD),
				Arguments.of("sparse & inverted", SPARSE_CARD, INVERTED_CARD),
				Arguments.of("inverted & inverted", INVERTED_CARD, INVERTED_CARD),
				Arguments.of("inverted & dense", INVERTED_CARD, DENSE_CARD));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("storageTypePairs")
	void andAcrossStorageTypes(String name, int card1, int card2) {
		var s1 = new java.util.TreeSet<Integer>();
		var s2 = new java.util.TreeSet<Integer>();
		// same seed -> the smaller block is a subset of the larger -> non-empty result
		addBlock(s1, 0, card1, 1);
		addBlock(s2, 0, card2, 1);
		assertAnd(toArray(s1), toArray(s2));
	}

	@Test
	void andSkipsEmptyIntersectionBlocks() {
		var a = new java.util.TreeSet<Integer>();
		var b = new java.util.TreeSet<Integer>();
		// block 0: disjoint position ranges -> empty intersection (must be skipped)
		for (int i = 0; i < 500; i++) {
			a.add(i);
			b.add(30_000 + i);
		}
		// block 1: a single shared bit -> the only block that should be yielded
		a.add(BLOCK + 5);
		b.add(BLOCK + 5);
		assertAnd(toArray(a), toArray(b));

		var it = build(toArray(a)).and(build(toArray(b)));
		var bits = it.getBits();
		assertTrue(it.hasNext());
		assertEquals(1, it.nextBlock());
		assertEquals(5, singleSetBit(bits.bits));
		assertFalse(it.hasNext());
	}

	@Test
	void andWithNoCommonBlocks() {
		assertAnd(new int[]{1, 2, 3}, new int[]{BLOCK + 1, BLOCK + 2});
		assertFalse(build(new int[]{1, 2, 3}).and(build(new int[]{BLOCK + 1})).hasNext());
	}

	@Test
	void andWithEmptyOperands() {
		assertAnd(new int[]{}, new int[]{1, 2, 3});
		assertAnd(new int[]{1, 2, 3}, new int[]{});
		assertAnd(new int[]{}, new int[]{});
	}

	@Test
	void andThrowsWhenExhausted() {
		var it = build(new int[]{1}).and(build(new int[]{1}));
		assertTrue(it.hasNext());
		it.nextBlock();
		assertFalse(it.hasNext());
		assertThrows(java.util.NoSuchElementException.class, () -> it.nextBlock());
	}

	@Test
	void andWithItselfYieldsItself() {
		var set = new java.util.TreeSet<Integer>();
		addBlock(set, 0, DENSE_CARD, 1);
		addBlock(set, 1, SPARSE_CARD, 2);
		addBlock(set, 2, INVERTED_CARD, 3);
		int[] rids = toArray(set);
		assertArrayEquals(rids, collectViaAnd(build(rids), build(rids)));
	}

	@Test
	void andOfTwoFullBlocksIsFull() {
		// a full block is stored SPARSE_INVERTED with zero unset positions;
		// intersecting
		// two of them is the only way to produce a full result
		int[] full = range(0, BLOCK);
		assertAnd(full, full);
		assertEquals(BLOCK, collectViaAnd(build(full), build(full)).length);

		var it = build(full).and(build(full));
		var bits = it.getBits();
		assertTrue(it.hasNext());
		it.nextBlock();
		assertTrue(bits.isFull(), "intersection of two full blocks must be marked full");
	}

	@ParameterizedTest
	@ValueSource(longs = {1, 7, 42, 1234, 99999})
	void andFuzz(long seed) {
		var random = new SplittableRandom(seed);
		var s1 = new java.util.TreeSet<Integer>();
		var s2 = new java.util.TreeSet<Integer>();
		// mix of absent, sparse, dense and inverted blocks over a 40-block range
		int[] cardinalities = {0, 0, SPARSE_CARD, DENSE_CARD, INVERTED_CARD, 200, 5_000};
		for (int block = 0; block < 40; block++) {
			int c1 = cardinalities[random.nextInt(cardinalities.length)];
			int c2 = cardinalities[random.nextInt(cardinalities.length)];
			if (c1 > 0) {
				addBlock(s1, block, c1, random.nextLong());
			}
			if (c2 > 0) {
				addBlock(s2, block, c2, random.nextLong());
			}
		}
		assertAnd(toArray(s1), toArray(s2));
	}

	// -- or (union) ----------------------------------------------------------

	@ParameterizedTest(name = "{0}")
	@MethodSource("storageTypePairs")
	void orAcrossStorageTypes(String name, int card1, int card2) {
		var s1 = new java.util.TreeSet<Integer>();
		var s2 = new java.util.TreeSet<Integer>();
		// different seeds -> genuinely different blocks of each storage type
		addBlock(s1, 0, card1, 1);
		addBlock(s2, 0, card2, 2);
		assertOr(toArray(s1), toArray(s2));
	}

	@Test
	void orWithBlocksPresentInOnlyOneBitmap() {
		// no block ids overlap, so every block is decoded from a single side
		var a = new java.util.TreeSet<Integer>();
		var b = new java.util.TreeSet<Integer>();
		addBlock(a, 0, SPARSE_CARD, 1);
		addBlock(a, 7, DENSE_CARD, 2);
		addBlock(b, 3, INVERTED_CARD, 3);
		addBlock(b, 9, SPARSE_CARD, 4);
		assertOr(toArray(a), toArray(b));
	}

	@Test
	void orWithMixedSharedAndDisjointBlocks() {
		var a = new java.util.TreeSet<Integer>();
		var b = new java.util.TreeSet<Integer>();
		addBlock(a, 0, DENSE_CARD, 1); // block 0 shared
		addBlock(b, 0, SPARSE_CARD, 2);
		addBlock(a, 1, SPARSE_CARD, 3); // block 1 only in a
		addBlock(b, 2, INVERTED_CARD, 4); // block 2 only in b
		assertOr(toArray(a), toArray(b));
	}

	@Test
	void orWithEmptyOperands() {
		assertOr(new int[]{}, new int[]{1, 2, BLOCK + 3});
		assertOr(new int[]{1, 2, BLOCK + 3}, new int[]{});
		assertOr(new int[]{}, new int[]{});
	}

	@Test
	void orThrowsWhenExhausted() {
		// distinct block ids -> two yielded blocks, then exhausted
		var it = build(new int[]{1}).or(build(new int[]{BLOCK + 1}));
		var bits = it.getBits();
		assertTrue(it.hasNext());
		it.nextBlock();
		assertTrue(it.hasNext());
		it.nextBlock();
		assertFalse(it.hasNext());
		assertThrows(java.util.NoSuchElementException.class, () -> it.nextBlock());
	}

	@Test
	void orWithItselfYieldsItself() {
		var set = new java.util.TreeSet<Integer>();
		addBlock(set, 0, DENSE_CARD, 1);
		addBlock(set, 1, SPARSE_CARD, 2);
		addBlock(set, 2, INVERTED_CARD, 3);
		int[] rids = toArray(set);
		assertArrayEquals(rids, collectViaOr(build(rids), build(rids)));
	}

	@Test
	void orOfTwoFullBlocksIsFull() {
		int[] full = range(0, BLOCK);
		assertOr(full, full);
		assertEquals(BLOCK, collectViaOr(build(full), build(full)).length);

		var it = build(full).or(build(full));
		var bits = it.getBits();
		assertTrue(it.hasNext());
		it.nextBlock();
		assertTrue(bits.isFull(), "union of two full blocks must be marked full");
	}

	@Test
	void orMarksFullWhenComplementaryBlocksCoverEverything() {
		// even positions in one bitmap, odd positions in the other: the union is the
		// whole block although neither input is full
		var even = new java.util.TreeSet<Integer>();
		var odd = new java.util.TreeSet<Integer>();
		for (int i = 0; i < BLOCK; i++) {
			(((i & 1) == 0) ? even : odd).add(i);
		}
		assertOr(toArray(even), toArray(odd));

		var it = build(toArray(even)).or(build(toArray(odd)));
		var bits = it.getBits();
		assertTrue(it.hasNext());
		it.nextBlock();
		assertTrue(bits.isFull(), "a union that covers the block must be marked full");
		assertFalse(it.hasNext());
	}

	@ParameterizedTest
	@ValueSource(longs = {1, 7, 42, 1234, 99999})
	void orFuzz(long seed) {
		var random = new SplittableRandom(seed);
		var s1 = new java.util.TreeSet<Integer>();
		var s2 = new java.util.TreeSet<Integer>();
		// mix of absent, sparse, dense and inverted blocks over a 40-block range
		int[] cardinalities = {0, 0, SPARSE_CARD, DENSE_CARD, INVERTED_CARD, 200, 5_000};
		for (int block = 0; block < 40; block++) {
			int c1 = cardinalities[random.nextInt(cardinalities.length)];
			int c2 = cardinalities[random.nextInt(cardinalities.length)];
			if (c1 > 0) {
				addBlock(s1, block, c1, random.nextLong());
			}
			if (c2 > 0) {
				addBlock(s2, block, c2, random.nextLong());
			}
		}
		assertOr(toArray(s1), toArray(s2));
	}
}
