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
	private static long[] reference(long[] data, int k) {
		long[] sorted = Arrays.stream(data).boxed().sorted(Long::compareUnsigned).mapToLong(Long::longValue).toArray();
		return Arrays.copyOf(sorted, Math.min(k, sorted.length));
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
		assertArrayEquals(reference(data, k), sortedValues(data, rows), "values mismatch for k=" + k);
	}

	// -------------------------------------------------------------------------
	// Contract / edge cases
	// -------------------------------------------------------------------------

	@Test
	void bottomZeroReturnsEmpty() {
		assertBottomK(new long[]{3, 1, 4, 1, 5}, 0);
	}

	@Test
	void bottomNegativeKThrows() {
		var idx = SliceZ.build(1, 2, 3);
		assertThrows(IllegalArgumentException.class, () -> idx.bottom(-1));
	}

	@Test
	void bottomEmptyIndexAlwaysEmpty() {
		var idx = SliceZ.build();
		assertEquals(0, collect(idx.bottom(0)).length);
		assertEquals(0, collect(idx.bottom(1)).length);
		assertEquals(0, collect(idx.bottom(1000)).length);
	}

	@Test
	void bottomSingleElement() {
		assertBottomK(new long[]{42L}, 0);
		assertBottomK(new long[]{42L}, 1);
		assertBottomK(new long[]{42L}, 2);
	}

	@Test
	void bottomKExceedsRowCountReturnsSortedAll() {
		long[] data = {3, 1, 4};
		assertBottomK(data, 100);
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
	void bottomKthValueConsistentWithCountLTE() {
		// For every k in [1, rowCount]: the k-th smallest value T satisfies
		// countLessThanOrEqual(T) >= k and countLessThan(T) < k
		long[] data = {3, 1, 4, 1, 5, 9, 2, 6};
		var appender = SliceZ.appender();
		for (long v : data)
			appender.add(v);
		var idx = appender.build();
		for (int k = 1; k <= data.length; k++) {
			long threshold = reference(data, k)[k - 1];
			assertTrue(idx.countLessThanOrEqual(threshold) >= k, "LTE count too small at k=" + k);
			assertTrue(idx.countLessThan(threshold) < k, "LT count not below k at k=" + k);
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
		long[] all = reference(data, data.length);
		for (int k = 1; k <= data.length; k++) {
			int[] rows = collect(idx.bottom(k));
			assertArrayEquals(Arrays.copyOf(all, k), sortedValues(data, rows), "prefix mismatch at k=" + k);
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
	void bottomDuplicatesBeforeBoundary() {
		// [1, 1, 2, 3] — bottom(3) values: [1, 1, 2]
		assertBottomK(new long[]{1, 1, 2, 3}, 3);
	}

	@Test
	void bottomDuplicatesAtBoundary() {
		// [1, 2, 3, 3, 3, 4] — bottom(4) values: [1, 2, 3, 3]
		assertBottomK(new long[]{1, 2, 3, 3, 3, 4}, 4);
	}

	@Test
	void bottomExactlyKDuplicatesAtEnd() {
		// [1, 2, 3, 3] — bottom(4): all four rows, values [1, 2, 3, 3]
		assertBottomK(new long[]{1, 2, 3, 3}, 4);
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
	void bottomNegativeLongsAreUnsignedLarge() {
		// -2L and -1L are unsigned-large; bottom(2) picks 0L and 1L
		assertBottomK(new long[]{0L, 1L, -2L, -1L}, 2);
	}

	@Test
	void bottomZeroIsUnsignedMinimum() {
		assertBottomK(new long[]{Long.MIN_VALUE, -1L, 0L}, 1);
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
	void bottomMultiBlockKCrossesBlockBoundary() {
		long[] data = LongStream.range(0, BLOCK_SIZE + 10).toArray();
		assertBottomK(data, BLOCK_SIZE + 5);
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
		assertArrayEquals(reference(data, 3), sortedValues(data, rows));
	}

	@Test
	void bottomMultiBlockAllSameValueFullSlices() {
		// all FULL slices across two blocks: BLOCK_SIZE + 1 rows of value 42
		long[] data = new long[BLOCK_SIZE + 1];
		Arrays.fill(data, 42L);
		assertBottomK(data, BLOCK_SIZE);
		assertBottomK(data, BLOCK_SIZE + 1);
	}

	@ParameterizedTest
	@ValueSource(ints = {0xFFFF, 0x10001, 100_000})
	void bottomMultiBlockKEqualsOne(int size) {
		long[] data = LongStream.range(0, size).toArray();
		assertBottomK(data, 1);
	}

	@ParameterizedTest
	@ValueSource(ints = {0xFFFF, 0x10001, 100_000})
	void bottomMultiBlockKEqualsSize(int size) {
		long[] data = LongStream.range(0, size).toArray();
		assertBottomK(data, size);
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

	// -------------------------------------------------------------------------
	// Cross-check against brute-force reference
	// -------------------------------------------------------------------------

	@ParameterizedTest
	@MethodSource("bottomDistributions")
	void bottomCrossCheck(long[] data, int k) {
		assertBottomK(data, k);
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
}
