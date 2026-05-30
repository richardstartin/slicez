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
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static io.github.richardstartin.slicez.SliceZ.BLOCK_SIZE;
import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT)
class TestBottomK {

	private static int[] collect(PrimitiveIterator.OfInt it) {
		int[] buf = new int[4096];
		int n = 0;
		while (it.hasNext()) {
			if (n == buf.length)
				buf = Arrays.copyOf(buf, buf.length * 2);
			buf[n++] = it.nextInt();
		}
		return Arrays.copyOf(buf, n);
	}

	/** Sort values of the returned row IDs by unsigned order. */
	private static long[] sortedValues(long[] data, int[] rowIds) {
		return Arrays.stream(rowIds).mapToLong(r -> data[r]).boxed().sorted(Long::compareUnsigned)
				.mapToLong(Long::longValue).toArray();
	}

	/** Brute-force reference: sort all values by unsigned order, return first k. */
	private static long[] referenceBottomK(long[] data, int k) {
		long[] sorted = Arrays.stream(data).boxed().sorted(Long::compareUnsigned).mapToLong(Long::longValue).toArray();
		return Arrays.copyOf(sorted, Math.min(k, sorted.length));
	}

	private static long[] referenceTopK(long[] data, int k) {
		long[] sorted = Arrays.stream(data).boxed().sorted(Long::compareUnsigned).mapToLong(Long::longValue).toArray();
		return Arrays.copyOfRange(sorted, Math.max(sorted.length - k, 0), sorted.length);
	}

	/**
	 * Assert that bottom(k) returns the right count and that the values of the
	 * returned rows, sorted by unsigned order, equal reference(data, k).
	 * Tiebreaking among rows with the same boundary value is unconstrained.
	 */
	private static void assertBottomK(long[] data, int k) {
		var appender = SliceZ.appender();
		for (long v : data)
			appender.add(v);
		var idx = appender.build();
		int[] rows = collect(idx.bottom(k));
		assertEquals(Math.min(k, data.length), rows.length, "count mismatch for k=" + k);
		assertArrayEquals(referenceBottomK(data, k), sortedValues(data, rows), "values mismatch for k=" + k);
	}

	private static void assertTopK(long[] data, int k) {
		var appender = SliceZ.appender();
		for (long v : data)
			appender.add(v);
		var idx = appender.build();
		int[] rows = collect(idx.top(k));
		assertEquals(Math.min(k, data.length), rows.length, "count mismatch for k=" + k);
		assertArrayEquals(referenceTopK(data, k), sortedValues(data, rows), "values mismatch for k=" + k);
	}

	// -------------------------------------------------------------------------
	// Contract / edge cases
	// -------------------------------------------------------------------------

	@Test
	void bottomZeroReturnsEmpty() {
		assertBottomK(new long[]{3, 1, 4, 1, 5}, 0);
	}

	@Test
	void topZeroReturnsEmpty() {
		assertTopK(new long[]{3, 1, 4, 1, 5}, 0);
	}

	@Test
	void bottomNegativeKThrows() {
		var idx = SliceZ.build(1, 2, 3);
		assertThrows(IllegalArgumentException.class, () -> idx.bottom(-1));
	}

	@Test
	void topNegativeKThrows() {
		var idx = SliceZ.build(1, 2, 3);
		assertThrows(IllegalArgumentException.class, () -> idx.top(-1));
	}

	@Test
	void bottomEmptyIndexAlwaysEmpty() {
		var idx = SliceZ.build();
		assertEquals(0, collect(idx.bottom(0)).length);
		assertEquals(0, collect(idx.bottom(1)).length);
		assertEquals(0, collect(idx.bottom(1000)).length);
	}

	@Test
	void topEmptyIndexAlwaysEmpty() {
		var idx = SliceZ.build();
		assertEquals(0, collect(idx.top(0)).length);
		assertEquals(0, collect(idx.top(1)).length);
		assertEquals(0, collect(idx.top(1000)).length);
	}

	@Test
	void bottomSingleElement() {
		assertBottomK(new long[]{42L}, 0);
		assertBottomK(new long[]{42L}, 1);
		assertBottomK(new long[]{42L}, 2);
	}

	@Test
	void topSingleElement() {
		assertTopK(new long[]{42L}, 0);
		assertTopK(new long[]{42L}, 1);
		assertTopK(new long[]{42L}, 2);
	}

	@Test
	void bottomKExceedsRowCountReturnsSortedAll() {
		long[] data = {3, 1, 4};
		assertBottomK(data, 100);
	}

	@Test
	void topKExceedsRowCountReturnsSortedAll() {
		long[] data = {3, 1, 4};
		assertTopK(data, 100);
	}

	// -------------------------------------------------------------------------
	// Result properties
	// -------------------------------------------------------------------------

	@Test
	void bottomResultLengthIsMinOfKAndRowCount() {
		long[] data = {3, 1, 4, 1, 5, 9, 2, 6};
		var appender = SliceZ.appender();
		for (long v : data)
			appender.add(v);
		var idx = appender.build();
		assertEquals(0, collect(idx.bottom(0)).length);
		assertEquals(1, collect(idx.bottom(1)).length);
		assertEquals(4, collect(idx.bottom(4)).length);
		assertEquals(8, collect(idx.bottom(8)).length);
		assertEquals(8, collect(idx.bottom(100)).length);
	}

	@Test
	void topResultLengthIsMinOfKAndRowCount() {
		long[] data = {3, 1, 4, 1, 5, 9, 2, 6};
		var appender = SliceZ.appender();
		for (long v : data)
			appender.add(v);
		var idx = appender.build();
		assertEquals(0, collect(idx.top(0)).length);
		assertEquals(1, collect(idx.top(1)).length);
		assertEquals(4, collect(idx.top(4)).length);
		assertEquals(8, collect(idx.top(8)).length);
		assertEquals(8, collect(idx.top(100)).length);
	}

	@Test
	void bottomFirstRowValueEqualsMin() {
		// The first row of bottom(1) must carry the minimum value.
		long[] data = {9, 3, 7, 1, 5};
		var appender = SliceZ.appender();
		for (long v : data)
			appender.add(v);
		var idx = appender.build();
		int[] rows = collect(idx.bottom(1));
		assertEquals(1, rows.length);
		assertEquals(idx.min(), data[rows[0]]);
	}

	@Test
	void topFirstRowValueEqualsMax() {
		// The first row of bottom(1) must carry the minimum value.
		long[] data = {9, 3, 7, 1, 5};
		var appender = SliceZ.appender();
		for (long v : data)
			appender.add(v);
		var idx = appender.build();
		int[] rows = collect(idx.top(1));
		assertEquals(1, rows.length);
		assertEquals(idx.max(), data[rows[0]]);
	}

	@Test
	void bottomKthValueConsistentWithCountLTE() {
		// For every k in [1, rowCount]: the k-th smallest value T satisfies
		// countLessThanOrEqual(T) >= k and countLessThan(T) < k
		long[] data = {3, 1, 4, 1, 5, 9, 2, 6};
		var appender = SliceZ.appender();
		for (long v : data)
			appender.add(v);
		var idx = appender.build();
		for (int k = 1; k <= data.length; k++) {
			long threshold = referenceBottomK(data, k)[k - 1];
			assertTrue(idx.countLessThanOrEqual(threshold) >= k, "LTE count too small at k=" + k);
			assertTrue(idx.countLessThan(threshold) < k, "LT count not below k at k=" + k);
		}
	}

	@Test
	void topKthValueConsistentWithCountGTE() {
		// For every k in [1, rowCount]: the k-th greatest value T satisfies
		// countGreaterThanOrEqual(T) >= k and countGreaterThan(T) < k
		long[] data = {3, 1, 4, 1, 5, 9, 2, 6};
		var appender = SliceZ.appender();
		for (long v : data)
			appender.add(v);
		var idx = appender.build();
		for (int k = 1; k <= data.length; k++) {
			long threshold = referenceTopK(data, k)[0];
			assertTrue(idx.countGreaterThanOrEqual(threshold) >= k, "GTE count too small at k=" + k);
			assertTrue(idx.countGreaterThan(threshold) < k, "GT count not below k at k=" + k);
		}
	}

	@Test
	void bottomIsMonotonePrefixByValues() {
		// The values of bottom(k) must be a prefix of the values of bottom(rowCount).
		long[] data = {3, 1, 4, 1, 5, 9, 2, 6};
		var appender = SliceZ.appender();
		for (long v : data)
			appender.add(v);
		var idx = appender.build();
		long[] all = referenceBottomK(data, data.length);
		for (int k = 1; k <= data.length; k++) {
			int[] rows = collect(idx.bottom(k));
			assertArrayEquals(Arrays.copyOf(all, k), sortedValues(data, rows), "prefix mismatch at k=" + k);
		}
	}

	@Test
	void topIsMonotoneSuffixByValues() {
		// The values of bottom(k) must be a prefix of the values of bottom(rowCount).
		long[] data = {3, 1, 4, 1, 5, 9, 2, 6};
		var appender = SliceZ.appender();
		for (long v : data)
			appender.add(v);
		var idx = appender.build();
		long[] all = referenceTopK(data, data.length);
		for (int k = 1; k <= data.length; k++) {
			int[] rows = collect(idx.top(k));
			assertArrayEquals(Arrays.copyOfRange(all, Math.max(0, all.length - k), all.length),
					sortedValues(data, rows), "prefix mismatch at k=" + k);
		}
	}

	// -------------------------------------------------------------------------
	// Duplicates and ties
	// -------------------------------------------------------------------------

	@Test
	void bottomAllSameValue() {
		assertBottomK(new long[]{7, 7, 7, 7, 7}, 3);
		assertBottomK(new long[]{7, 7, 7, 7, 7}, 5);
		assertBottomK(new long[]{7, 7, 7, 7, 7}, 10);
	}

	@Test
	void topAllSameValue() {
		assertTopK(new long[]{7, 7, 7, 7, 7}, 3);
		assertTopK(new long[]{7, 7, 7, 7, 7}, 5);
		assertTopK(new long[]{7, 7, 7, 7, 7}, 10);
	}

	@Test
	void bottomDuplicatesBeforeBoundary() {
		// [1, 1, 2, 3] — bottom(3) values: [1, 1, 2]
		assertBottomK(new long[]{1, 1, 2, 3}, 3);
	}

	@Test
	void topDuplicatesBeforeBoundary() {
		// [1, 1, 2, 3] — top(3) values: [1, 2, 3]
		assertTopK(new long[]{1, 1, 2, 3}, 3);
	}

	@Test
	void topDuplicatesAfterBoundary() {
		// [1, 2, 3, 3] — top(3) values: [2, 3, 3]
		assertTopK(new long[]{1, 2, 3, 3}, 3);
	}

	@Test
	void bottomDuplicatesAtBoundary() {
		// [1, 2, 3, 3, 3, 4] — bottom(4) values: [1, 2, 3, 3]
		assertBottomK(new long[]{1, 2, 3, 3, 3, 4}, 4);
	}

	@Test
	void topDuplicatesAtBoundary() {
		// [1, 2, 3, 3, 3, 4] — top(4) values: [3, 3, 3, 4]
		assertTopK(new long[]{1, 2, 3, 3, 3, 4}, 4);
	}

	@Test
	void bottomExactlyKDuplicatesAtEnd() {
		// [1, 2, 3, 3] — bottom(4): all four rows, values [1, 2, 3, 3]
		assertBottomK(new long[]{1, 2, 3, 3}, 4);
	}

	@Test
	void topExactlyKDuplicatesAtEnd() {
		// [1, 2, 3, 3] — top(4): all four rows, values [1, 2, 3, 3]
		assertTopK(new long[]{1, 2, 3, 3}, 4);
	}

	// -------------------------------------------------------------------------
	// Unsigned semantics
	// -------------------------------------------------------------------------

	@Test
	void bottomUnsignedOrder() {
		// unsigned: 0 < Long.MAX_VALUE < Long.MIN_VALUE < -1L
		long[] data = {0L, Long.MAX_VALUE, Long.MIN_VALUE, -1L};
		assertBottomK(data, 1);
		assertBottomK(data, 2);
		assertBottomK(data, 3);
		assertBottomK(data, 4);
	}

	@Test
	void topUnsignedOrder() {
		// unsigned: 0 < Long.MAX_VALUE < Long.MIN_VALUE < -1L
		long[] data = {0L, Long.MAX_VALUE, Long.MIN_VALUE, -1L};
		assertTopK(data, 1);
		assertTopK(data, 2);
		assertTopK(data, 3);
		assertTopK(data, 4);
	}

	@Test
	void bottomNegativeLongsAreUnsignedLarge() {
		// -2L and -1L are unsigned-large; bottom(2) picks 0L and 1L
		assertBottomK(new long[]{0L, 1L, -2L, -1L}, 2);
	}

	@Test
	void topNegativeLongsAreUnsignedLarge() {
		// -2L and -1L are unsigned-large; top(2) picks -2L and -1L
		assertTopK(new long[]{0L, 1L, -2L, -1L}, 2);
	}

	@Test
	void bottomZeroIsUnsignedMinimum() {
		assertBottomK(new long[]{Long.MIN_VALUE, -1L, 0L}, 1);
	}

	@Test
	void topZeroIsUnsignedMax() {
		assertTopK(new long[]{Long.MIN_VALUE, -1L, 0L}, 1);
	}

	// -------------------------------------------------------------------------
	// Multi-block
	// -------------------------------------------------------------------------

	@Test
	void bottomMultiBlockSmallKFromFirstBlock() {
		long[] data = LongStream.range(0, BLOCK_SIZE + 10).toArray();
		assertBottomK(data, 5);
	}

	@Test
	void topMultiBlockSmallKFromSecondBlock() {
		long[] data = LongStream.range(0, BLOCK_SIZE + 10).toArray();
		assertTopK(data, 5);
	}

	@Test
	void topMultiBlockSmallKFromFirstBlock() {
		long[] data = IntStream.range(0, BLOCK_SIZE + 10).mapToLong(i -> BLOCK_SIZE + 10 - i).toArray();
		assertTopK(data, 5);
	}

	@Test
	void bottomMultiBlockKCrossesBlockBoundary() {
		long[] data = LongStream.range(0, BLOCK_SIZE + 10).toArray();
		assertBottomK(data, BLOCK_SIZE + 5);
	}

	@Test
	void topMultiBlockKCrossesBlockBoundary() {
		long[] data = LongStream.range(0, BLOCK_SIZE + 10).toArray();
		assertTopK(data, 20);
	}

	@Test
	void bottomMultiBlockMinInLaterBlock() {
		// global minimum lives in block 1, not block 0
		var appender = SliceZ.appender();
		for (int i = 1; i < BLOCK_SIZE + 1; i++)
			appender.add(100 + i);
		appender.add(1L);
		var idx = appender.build();
		// build matching data array for value lookup: rows 0..BLOCK_SIZE-1 have
		// 101..200,
		// row BLOCK_SIZE has value 1
		long[] data = new long[BLOCK_SIZE + 1];
		for (int i = 0; i < BLOCK_SIZE; i++)
			data[i] = 101 + i;
		data[BLOCK_SIZE] = 1L;
		int[] rows = collect(idx.bottom(3));
		assertEquals(3, rows.length);
		assertArrayEquals(referenceBottomK(data, 3), sortedValues(data, rows));
	}

	@Test
	void bottomMultiBlockAllSameValueFullSlices() {
		// all FULL slices across two blocks: BLOCK_SIZE + 1 rows of value 42
		long[] data = new long[BLOCK_SIZE + 1];
		Arrays.fill(data, 42L);
		assertBottomK(data, BLOCK_SIZE);
		assertBottomK(data, BLOCK_SIZE + 1);
	}

	@Test
	void topMultiBlockMaxInLaterBlock() {
		// global maximum lives in block 1, not block 0
		var appender = SliceZ.appender();
		for (int i = 0; i < BLOCK_SIZE; i++)
			appender.add(i + 1);
		appender.add(BLOCK_SIZE + 1000L);
		var idx = appender.build();
		long[] data = new long[BLOCK_SIZE + 1];
		for (int i = 0; i < BLOCK_SIZE; i++)
			data[i] = i + 1;
		data[BLOCK_SIZE] = BLOCK_SIZE + 1000L;
		int[] rows = collect(idx.top(3));
		assertEquals(3, rows.length);
		assertArrayEquals(referenceTopK(data, 3), sortedValues(data, rows));
	}

	@Test
	void topMultiBlockAllSameValueFullSlices() {
		// all FULL slices across two blocks: BLOCK_SIZE + 1 rows of value 42
		long[] data = new long[BLOCK_SIZE + 1];
		Arrays.fill(data, 42L);
		assertTopK(data, BLOCK_SIZE);
		assertTopK(data, BLOCK_SIZE + 1);
	}

	@ParameterizedTest
	@ValueSource(ints = {0xFFFF, 0x10001, 100_000})
	void bottomMultiBlockKEqualsOne(int size) {
		long[] data = LongStream.range(0, size).toArray();
		assertBottomK(data, 1);
	}

	@ParameterizedTest
	@ValueSource(ints = {0xFFFF, 0x10001, 100_000})
	void topMultiBlockKEqualsOne(int size) {
		long[] data = LongStream.range(0, size).toArray();
		assertTopK(data, 1);
	}

	@ParameterizedTest
	@ValueSource(ints = {0xFFFF, 0x10001, 100_000})
	void bottomMultiBlockKEqualsSize(int size) {
		long[] data = LongStream.range(0, size).toArray();
		assertBottomK(data, size);
	}

	@ParameterizedTest
	@ValueSource(ints = {0xFFFF, 0x10001, 100_000})
	void topMultiBlockKEqualsSize(int size) {
		long[] data = LongStream.range(0, size).toArray();
		assertTopK(data, size);
	}

	// -------------------------------------------------------------------------
	// Block-boundary structural cases
	// -------------------------------------------------------------------------

	@Test
	void bottomKEqualsBlockSizeExactly() {
		long[] data = LongStream.range(0, (long) BLOCK_SIZE * 2).toArray();
		assertBottomK(data, BLOCK_SIZE);
	}

	@Test
	void bottomKOneLessThanBlockSize() {
		long[] data = LongStream.range(0, (long) BLOCK_SIZE * 2).toArray();
		assertBottomK(data, BLOCK_SIZE - 1);
	}

	@Test
	void bottomKOneMoreThanBlockSize() {
		long[] data = LongStream.range(0, (long) BLOCK_SIZE * 2).toArray();
		assertBottomK(data, BLOCK_SIZE + 1);
	}

	@Test
	void bottomAllValuesInSecondBlockAreSmaller() {
		// block 0: BLOCK_SIZE..2*BLOCK_SIZE-1, block 1: 0..BLOCK_SIZE-1
		long[] data = new long[BLOCK_SIZE * 2];
		for (int i = 0; i < BLOCK_SIZE; i++)
			data[i] = BLOCK_SIZE + i;
		for (int i = 0; i < BLOCK_SIZE; i++)
			data[BLOCK_SIZE + i] = i;
		assertBottomK(data, BLOCK_SIZE + 5);
	}

	@Test
	void bottomReversedContiguousInput() {
		long[] data = LongStream.range(0, 200L).map(i -> 199 - i).toArray();
		assertBottomK(data, 5);
	}

	@Test
	void bottomSingleRowInSecondBlock() {
		// row BLOCK_SIZE holds the global minimum
		long[] data = new long[BLOCK_SIZE + 1];
		for (int i = 0; i < BLOCK_SIZE; i++)
			data[i] = i + 101;
		data[BLOCK_SIZE] = 1L;
		assertBottomK(data, 1);
		assertBottomK(data, 2);
	}

	@Test
	void topKEqualsBlockSizeExactly() {
		long[] data = LongStream.range(0, (long) BLOCK_SIZE * 2).toArray();
		assertTopK(data, BLOCK_SIZE);
	}

	@Test
	void topKOneLessThanBlockSize() {
		long[] data = LongStream.range(0, (long) BLOCK_SIZE * 2).toArray();
		assertTopK(data, BLOCK_SIZE - 1);
	}

	@Test
	void topKOneMoreThanBlockSize() {
		long[] data = LongStream.range(0, (long) BLOCK_SIZE * 2).toArray();
		assertTopK(data, BLOCK_SIZE + 1);
	}

	@Test
	void topAllValuesInFirstBlockAreLarger() {
		// block 0: BLOCK_SIZE..2*BLOCK_SIZE-1 (larger), block 1: 0..BLOCK_SIZE-1
		// (smaller)
		long[] data = new long[BLOCK_SIZE * 2];
		for (int i = 0; i < BLOCK_SIZE; i++)
			data[i] = BLOCK_SIZE + i;
		for (int i = 0; i < BLOCK_SIZE; i++)
			data[BLOCK_SIZE + i] = i;
		assertTopK(data, BLOCK_SIZE + 5);
	}

	@Test
	void topReversedContiguousInput() {
		long[] data = LongStream.range(0, 200L).map(i -> 199 - i).toArray();
		assertTopK(data, 5);
	}

	@Test
	void topSingleRowInSecondBlock() {
		// row BLOCK_SIZE holds the global maximum
		long[] data = new long[BLOCK_SIZE + 1];
		for (int i = 0; i < BLOCK_SIZE; i++)
			data[i] = i + 1;
		data[BLOCK_SIZE] = BLOCK_SIZE + 1000L;
		assertTopK(data, 1);
		assertTopK(data, 2);
	}

	// -------------------------------------------------------------------------
	// Cross-check against brute-force reference
	// -------------------------------------------------------------------------

	@ParameterizedTest
	@MethodSource("bottomDistributions")
	void bottomCrossCheck(long[] data, int k) {
		assertBottomK(data, k);
	}

	@ParameterizedTest
	@MethodSource("topDistributions")
	void topCrossCheck(long[] data, int k) {
		assertTopK(data, k);
	}

	static Stream<Arguments> bottomDistributions() {
		SplittableRandom rng = new SplittableRandom(42);
		return Stream.of(
				// small range with duplicates, various k
				Arguments.of(rng.longs(200, 0, 100).toArray(), 10), Arguments.of(rng.longs(200, 0, 100).toArray(), 100),
				Arguments.of(rng.longs(200, 0, 100).toArray(), 200),
				// large range
				Arguments.of(rng.longs(5_000, 0, 1_000_000).toArray(), 50),
				Arguments.of(rng.longs(5_000, 0, 1_000_000).toArray(), 1_000),
				// all same value
				Arguments.of(new long[]{7, 7, 7, 7, 7, 7, 7, 7, 7, 7}, 7),
				// includes unsigned-large values
				Arguments.of(new long[]{0L, Long.MAX_VALUE, Long.MIN_VALUE, -1L, 1L, -2L}, 4),
				// multi-block contiguous
				Arguments.of(LongStream.range(0, (long) BLOCK_SIZE * 3).toArray(), 100),
				Arguments.of(LongStream.range(0, (long) BLOCK_SIZE * 3).toArray(), BLOCK_SIZE + 100),
				// multi-block reversed
				Arguments.of(LongStream.range(0, 500L).map(i -> 499 - i).toArray(), 20));
	}

	static Stream<Arguments> topDistributions() {
		SplittableRandom rng = new SplittableRandom(42);
		return Stream.of(
				// small range with duplicates, various k
				Arguments.of(rng.longs(200, 0, 100).toArray(), 10), Arguments.of(rng.longs(200, 0, 100).toArray(), 100),
				Arguments.of(rng.longs(200, 0, 100).toArray(), 200),
				// large range
				Arguments.of(rng.longs(5_000, 0, 1_000_000).toArray(), 50),
				Arguments.of(rng.longs(5_000, 0, 1_000_000).toArray(), 1_000),
				// all same value
				Arguments.of(new long[]{7, 7, 7, 7, 7, 7, 7, 7, 7, 7}, 7),
				// includes unsigned-large values
				Arguments.of(new long[]{0L, Long.MAX_VALUE, Long.MIN_VALUE, -1L, 1L, -2L}, 4),
				// multi-block contiguous
				Arguments.of(LongStream.range(0, (long) BLOCK_SIZE * 3).toArray(), 100),
				Arguments.of(LongStream.range(0, (long) BLOCK_SIZE * 3).toArray(), BLOCK_SIZE + 100),
				// multi-block reversed
				Arguments.of(LongStream.range(0, 500L).map(i -> 499 - i).toArray(), 20));
	}
}
