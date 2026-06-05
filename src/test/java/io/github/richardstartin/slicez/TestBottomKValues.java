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

/**
 * Tests for {@link SliceZ#bottomValues(int)} and {@link SliceZ#topValues(int)}.
 *
 * <p>
 * These methods must be consistent with {@link SliceZ#bottom(int)} and
 * {@link SliceZ#top(int)}: they return the same multiset of rows, but yield the
 * <em>values</em> stored at those rows rather than the row indexes. Tiebreaking
 * among rows that share the boundary value is unconstrained, so all assertions
 * compare value multisets sorted by unsigned order.
 */
@Execution(ExecutionMode.CONCURRENT)
class TestBottomKValues {

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

	private static long[] collect(PrimitiveIterator.OfLong it) {
		long[] buf = new long[4096];
		int n = 0;
		while (it.hasNext()) {
			if (n == buf.length)
				buf = Arrays.copyOf(buf, buf.length * 2);
			buf[n++] = it.nextLong();
		}
		return Arrays.copyOf(buf, n);
	}

	private static long[] sortedUnsigned(long[] values) {
		return Arrays.stream(values).boxed().sorted(Long::compareUnsigned).mapToLong(Long::longValue).toArray();
	}

	/** Values of the given rows, sorted by unsigned order. */
	private static long[] sortedValues(long[] data, int[] rowIds) {
		return Arrays.stream(rowIds).mapToLong(r -> data[r]).boxed().sorted(Long::compareUnsigned)
				.mapToLong(Long::longValue).toArray();
	}

	/** Brute-force reference: sort all values by unsigned order, return first k. */
	private static long[] referenceBottomK(long[] data, int k) {
		long[] sorted = sortedUnsigned(data);
		return Arrays.copyOf(sorted, Math.min(k, sorted.length));
	}

	private static long[] referenceTopK(long[] data, int k) {
		long[] sorted = sortedUnsigned(data);
		return Arrays.copyOfRange(sorted, Math.max(sorted.length - k, 0), sorted.length);
	}

	private static SliceZ build(long[] data) {
		var appender = SliceZ.appender();
		for (long v : data)
			appender.add(v);
		return appender.build();
	}

	/**
	 * Assert that bottomValues(k) returns the right count, that the values sorted
	 * by unsigned order equal reference(data, k), and that it agrees with
	 * bottom(k).
	 */
	private static void assertBottomKValues(long[] data, int k) {
		var idx = build(data);
		long[] values = collect(idx.bottomValues(k));
		assertEquals(Math.min(k, data.length), values.length, "count mismatch for k=" + k);
		assertArrayEquals(referenceBottomK(data, k), sortedUnsigned(values), "values mismatch for k=" + k);
		// consistency with bottom(k): same multiset of values as the rows it returns
		int[] rows = collect(idx.bottom(k));
		assertArrayEquals(sortedValues(data, rows), sortedUnsigned(values),
				"bottomValues inconsistent with bottom for k=" + k);
	}

	private static void assertTopKValues(long[] data, int k) {
		var idx = build(data);
		long[] values = collect(idx.topValues(k));
		assertEquals(Math.min(k, data.length), values.length, "count mismatch for k=" + k);
		assertArrayEquals(referenceTopK(data, k), sortedUnsigned(values), "values mismatch for k=" + k);
		// consistency with top(k): same multiset of values as the rows it returns
		int[] rows = collect(idx.top(k));
		assertArrayEquals(sortedValues(data, rows), sortedUnsigned(values),
				"topValues inconsistent with top for k=" + k);
	}

	// -------------------------------------------------------------------------
	// Contract / edge cases
	// -------------------------------------------------------------------------

	@Test
	void bottomZeroReturnsEmpty() {
		assertBottomKValues(new long[]{3, 1, 4, 1, 5}, 0);
	}

	@Test
	void topZeroReturnsEmpty() {
		assertTopKValues(new long[]{3, 1, 4, 1, 5}, 0);
	}

	@Test
	void bottomNegativeKThrows() {
		var idx = SliceZ.build(1, 2, 3);
		assertThrows(IllegalArgumentException.class, () -> idx.bottomValues(-1));
	}

	@Test
	void topNegativeKThrows() {
		var idx = SliceZ.build(1, 2, 3);
		assertThrows(IllegalArgumentException.class, () -> idx.topValues(-1));
	}

	@Test
	void bottomEmptyIndexAlwaysEmpty() {
		var idx = SliceZ.build();
		assertEquals(0, collect(idx.bottomValues(0)).length);
		assertEquals(0, collect(idx.bottomValues(1)).length);
		assertEquals(0, collect(idx.bottomValues(1000)).length);
	}

	@Test
	void topEmptyIndexAlwaysEmpty() {
		var idx = SliceZ.build();
		assertEquals(0, collect(idx.topValues(0)).length);
		assertEquals(0, collect(idx.topValues(1)).length);
		assertEquals(0, collect(idx.topValues(1000)).length);
	}

	@Test
	void bottomSingleElement() {
		assertBottomKValues(new long[]{42L}, 0);
		assertBottomKValues(new long[]{42L}, 1);
		assertBottomKValues(new long[]{42L}, 2);
	}

	@Test
	void topSingleElement() {
		assertTopKValues(new long[]{42L}, 0);
		assertTopKValues(new long[]{42L}, 1);
		assertTopKValues(new long[]{42L}, 2);
	}

	@Test
	void bottomKExceedsRowCountReturnsSortedAll() {
		assertBottomKValues(new long[]{3, 1, 4}, 100);
	}

	@Test
	void topKExceedsRowCountReturnsSortedAll() {
		assertTopKValues(new long[]{3, 1, 4}, 100);
	}

	// -------------------------------------------------------------------------
	// Result properties
	// -------------------------------------------------------------------------

	@Test
	void bottomResultLengthIsMinOfKAndRowCount() {
		var idx = build(new long[]{3, 1, 4, 1, 5, 9, 2, 6});
		assertEquals(0, collect(idx.bottomValues(0)).length);
		assertEquals(1, collect(idx.bottomValues(1)).length);
		assertEquals(4, collect(idx.bottomValues(4)).length);
		assertEquals(8, collect(idx.bottomValues(8)).length);
		assertEquals(8, collect(idx.bottomValues(100)).length);
	}

	@Test
	void topResultLengthIsMinOfKAndRowCount() {
		var idx = build(new long[]{3, 1, 4, 1, 5, 9, 2, 6});
		assertEquals(0, collect(idx.topValues(0)).length);
		assertEquals(1, collect(idx.topValues(1)).length);
		assertEquals(4, collect(idx.topValues(4)).length);
		assertEquals(8, collect(idx.topValues(8)).length);
		assertEquals(8, collect(idx.topValues(100)).length);
	}

	@Test
	void bottomSingleValueIsMin() {
		long[] data = {9, 3, 7, 1, 5};
		var idx = build(data);
		long[] values = collect(idx.bottomValues(1));
		assertEquals(1, values.length);
		assertEquals(idx.min(), values[0]);
	}

	@Test
	void topSingleValueIsMax() {
		long[] data = {9, 3, 7, 1, 5};
		var idx = build(data);
		long[] values = collect(idx.topValues(1));
		assertEquals(1, values.length);
		assertEquals(idx.max(), values[0]);
	}

	@Test
	void bottomIsMonotonePrefixByValues() {
		long[] data = {3, 1, 4, 1, 5, 9, 2, 6};
		var idx = build(data);
		long[] all = referenceBottomK(data, data.length);
		for (int k = 1; k <= data.length; k++) {
			long[] values = collect(idx.bottomValues(k));
			assertArrayEquals(Arrays.copyOf(all, k), sortedUnsigned(values), "prefix mismatch at k=" + k);
		}
	}

	@Test
	void topIsMonotoneSuffixByValues() {
		long[] data = {3, 1, 4, 1, 5, 9, 2, 6};
		var idx = build(data);
		long[] all = referenceTopK(data, data.length);
		for (int k = 1; k <= data.length; k++) {
			long[] values = collect(idx.topValues(k));
			assertArrayEquals(Arrays.copyOfRange(all, Math.max(0, all.length - k), all.length), sortedUnsigned(values),
					"suffix mismatch at k=" + k);
		}
	}

	// -------------------------------------------------------------------------
	// Consistency with bottom/top across all k
	// -------------------------------------------------------------------------

	@Test
	void bottomValuesMatchBottomRowsForEveryK() {
		long[] data = {3, 1, 4, 1, 5, 9, 2, 6};
		var idx = build(data);
		for (int k = 0; k <= data.length + 2; k++) {
			long[] values = sortedUnsigned(collect(idx.bottomValues(k)));
			long[] fromRows = sortedValues(data, collect(idx.bottom(k)));
			assertArrayEquals(fromRows, values, "mismatch at k=" + k);
		}
	}

	@Test
	void topValuesMatchTopRowsForEveryK() {
		long[] data = {3, 1, 4, 1, 5, 9, 2, 6};
		var idx = build(data);
		for (int k = 0; k <= data.length + 2; k++) {
			long[] values = sortedUnsigned(collect(idx.topValues(k)));
			long[] fromRows = sortedValues(data, collect(idx.top(k)));
			assertArrayEquals(fromRows, values, "mismatch at k=" + k);
		}
	}

	// -------------------------------------------------------------------------
	// Duplicates and ties
	// -------------------------------------------------------------------------

	@Test
	void bottomAllSameValue() {
		assertBottomKValues(new long[]{7, 7, 7, 7, 7}, 3);
		assertBottomKValues(new long[]{7, 7, 7, 7, 7}, 5);
		assertBottomKValues(new long[]{7, 7, 7, 7, 7}, 10);
	}

	@Test
	void topAllSameValue() {
		assertTopKValues(new long[]{7, 7, 7, 7, 7}, 3);
		assertTopKValues(new long[]{7, 7, 7, 7, 7}, 5);
		assertTopKValues(new long[]{7, 7, 7, 7, 7}, 10);
	}

	@Test
	void bottomDuplicatesBeforeBoundary() {
		assertBottomKValues(new long[]{1, 1, 2, 3}, 3);
	}

	@Test
	void topDuplicatesBeforeBoundary() {
		assertTopKValues(new long[]{1, 1, 2, 3}, 3);
	}

	@Test
	void topDuplicatesAfterBoundary() {
		assertTopKValues(new long[]{1, 2, 3, 3}, 3);
	}

	@Test
	void bottomDuplicatesAtBoundary() {
		assertBottomKValues(new long[]{1, 2, 3, 3, 3, 4}, 4);
	}

	@Test
	void topDuplicatesAtBoundary() {
		assertTopKValues(new long[]{1, 2, 3, 3, 3, 4}, 4);
	}

	@Test
	void bottomExactlyKDuplicatesAtEnd() {
		assertBottomKValues(new long[]{1, 2, 3, 3}, 4);
	}

	@Test
	void topExactlyKDuplicatesAtEnd() {
		assertTopKValues(new long[]{1, 2, 3, 3}, 4);
	}

	// -------------------------------------------------------------------------
	// Unsigned semantics
	// -------------------------------------------------------------------------

	@Test
	void bottomUnsignedOrder() {
		// unsigned: 0 < Long.MAX_VALUE < Long.MIN_VALUE < -1L
		long[] data = {0L, Long.MAX_VALUE, Long.MIN_VALUE, -1L};
		assertBottomKValues(data, 1);
		assertBottomKValues(data, 2);
		assertBottomKValues(data, 3);
		assertBottomKValues(data, 4);
	}

	@Test
	void topUnsignedOrder() {
		long[] data = {0L, Long.MAX_VALUE, Long.MIN_VALUE, -1L};
		assertTopKValues(data, 1);
		assertTopKValues(data, 2);
		assertTopKValues(data, 3);
		assertTopKValues(data, 4);
	}

	@Test
	void bottomNegativeLongsAreUnsignedLarge() {
		assertBottomKValues(new long[]{0L, 1L, -2L, -1L}, 2);
	}

	@Test
	void topNegativeLongsAreUnsignedLarge() {
		assertTopKValues(new long[]{0L, 1L, -2L, -1L}, 2);
	}

	@Test
	void bottomZeroIsUnsignedMinimum() {
		assertBottomKValues(new long[]{Long.MIN_VALUE, -1L, 0L}, 1);
	}

	@Test
	void topZeroIsUnsignedMax() {
		assertTopKValues(new long[]{Long.MIN_VALUE, -1L, 0L}, 1);
	}

	// -------------------------------------------------------------------------
	// Multi-block
	// -------------------------------------------------------------------------

	@Test
	void bottomMultiBlockSmallKFromFirstBlock() {
		assertBottomKValues(LongStream.range(0, BLOCK_SIZE + 10).toArray(), 5);
	}

	@Test
	void topMultiBlockSmallKFromSecondBlock() {
		assertTopKValues(LongStream.range(0, BLOCK_SIZE + 10).toArray(), 5);
	}

	@Test
	void topMultiBlockSmallKFromFirstBlock() {
		assertTopKValues(IntStream.range(0, BLOCK_SIZE + 10).mapToLong(i -> BLOCK_SIZE + 10 - i).toArray(), 5);
	}

	@Test
	void bottomMultiBlockKCrossesBlockBoundary() {
		assertBottomKValues(LongStream.range(0, BLOCK_SIZE + 10).toArray(), BLOCK_SIZE + 5);
	}

	@Test
	void topMultiBlockKCrossesBlockBoundary() {
		assertTopKValues(LongStream.range(0, BLOCK_SIZE + 10).toArray(), 20);
	}

	@Test
	void bottomMultiBlockMinInLaterBlock() {
		// global minimum lives in block 1, not block 0
		long[] data = new long[BLOCK_SIZE + 1];
		for (int i = 0; i < BLOCK_SIZE; i++)
			data[i] = 101 + i;
		data[BLOCK_SIZE] = 1L;
		assertBottomKValues(data, 3);
	}

	@Test
	void bottomMultiBlockAllSameValueFullSlices() {
		long[] data = new long[BLOCK_SIZE + 1];
		Arrays.fill(data, 42L);
		assertBottomKValues(data, BLOCK_SIZE);
		assertBottomKValues(data, BLOCK_SIZE + 1);
	}

	@Test
	void topMultiBlockMaxInLaterBlock() {
		// global maximum lives in block 1, not block 0
		long[] data = new long[BLOCK_SIZE + 1];
		for (int i = 0; i < BLOCK_SIZE; i++)
			data[i] = i + 1;
		data[BLOCK_SIZE] = BLOCK_SIZE + 1000L;
		assertTopKValues(data, 3);
	}

	@Test
	void topMultiBlockAllSameValueFullSlices() {
		long[] data = new long[BLOCK_SIZE + 1];
		Arrays.fill(data, 42L);
		assertTopKValues(data, BLOCK_SIZE);
		assertTopKValues(data, BLOCK_SIZE + 1);
	}

	@ParameterizedTest
	@ValueSource(ints = {0xFFFF, 0x10001, 100_000})
	void bottomMultiBlockKEqualsOne(int size) {
		assertBottomKValues(LongStream.range(0, size).toArray(), 1);
	}

	@ParameterizedTest
	@ValueSource(ints = {0xFFFF, 0x10001, 100_000})
	void topMultiBlockKEqualsOne(int size) {
		assertTopKValues(LongStream.range(0, size).toArray(), 1);
	}

	@ParameterizedTest
	@ValueSource(ints = {0xFFFF, 0x10001, 100_000})
	void bottomMultiBlockKEqualsSize(int size) {
		assertBottomKValues(LongStream.range(0, size).toArray(), size);
	}

	@ParameterizedTest
	@ValueSource(ints = {0xFFFF, 0x10001, 100_000})
	void topMultiBlockKEqualsSize(int size) {
		assertTopKValues(LongStream.range(0, size).toArray(), size);
	}

	// -------------------------------------------------------------------------
	// Block-boundary structural cases
	// -------------------------------------------------------------------------

	@Test
	void bottomKEqualsBlockSizeExactly() {
		assertBottomKValues(LongStream.range(0, (long) BLOCK_SIZE * 2).toArray(), BLOCK_SIZE);
	}

	@Test
	void bottomKOneLessThanBlockSize() {
		assertBottomKValues(LongStream.range(0, (long) BLOCK_SIZE * 2).toArray(), BLOCK_SIZE - 1);
	}

	@Test
	void bottomKOneMoreThanBlockSize() {
		assertBottomKValues(LongStream.range(0, (long) BLOCK_SIZE * 2).toArray(), BLOCK_SIZE + 1);
	}

	@Test
	void bottomAllValuesInSecondBlockAreSmaller() {
		long[] data = new long[BLOCK_SIZE * 2];
		for (int i = 0; i < BLOCK_SIZE; i++)
			data[i] = BLOCK_SIZE + i;
		for (int i = 0; i < BLOCK_SIZE; i++)
			data[BLOCK_SIZE + i] = i;
		assertBottomKValues(data, BLOCK_SIZE + 5);
	}

	@Test
	void bottomReversedContiguousInput() {
		assertBottomKValues(LongStream.range(0, 200L).map(i -> 199 - i).toArray(), 5);
	}

	@Test
	void bottomSingleRowInSecondBlock() {
		long[] data = new long[BLOCK_SIZE + 1];
		for (int i = 0; i < BLOCK_SIZE; i++)
			data[i] = i + 101;
		data[BLOCK_SIZE] = 1L;
		assertBottomKValues(data, 1);
		assertBottomKValues(data, 2);
	}

	@Test
	void topKEqualsBlockSizeExactly() {
		assertTopKValues(LongStream.range(0, (long) BLOCK_SIZE * 2).toArray(), BLOCK_SIZE);
	}

	@Test
	void topKOneLessThanBlockSize() {
		assertTopKValues(LongStream.range(0, (long) BLOCK_SIZE * 2).toArray(), BLOCK_SIZE - 1);
	}

	@Test
	void topKOneMoreThanBlockSize() {
		assertTopKValues(LongStream.range(0, (long) BLOCK_SIZE * 2).toArray(), BLOCK_SIZE + 1);
	}

	@Test
	void topAllValuesInFirstBlockAreLarger() {
		long[] data = new long[BLOCK_SIZE * 2];
		for (int i = 0; i < BLOCK_SIZE; i++)
			data[i] = BLOCK_SIZE + i;
		for (int i = 0; i < BLOCK_SIZE; i++)
			data[BLOCK_SIZE + i] = i;
		assertTopKValues(data, BLOCK_SIZE + 5);
	}

	@Test
	void topReversedContiguousInput() {
		assertTopKValues(LongStream.range(0, 200L).map(i -> 199 - i).toArray(), 5);
	}

	@Test
	void topSingleRowInSecondBlock() {
		long[] data = new long[BLOCK_SIZE + 1];
		for (int i = 0; i < BLOCK_SIZE; i++)
			data[i] = i + 1;
		data[BLOCK_SIZE] = BLOCK_SIZE + 1000L;
		assertTopKValues(data, 1);
		assertTopKValues(data, 2);
	}

	// -------------------------------------------------------------------------
	// Cross-check against brute-force reference
	// -------------------------------------------------------------------------

	@ParameterizedTest
	@MethodSource("distributions")
	void bottomCrossCheck(long[] data, int k) {
		assertBottomKValues(data, k);
	}

	@ParameterizedTest
	@MethodSource("distributions")
	void topCrossCheck(long[] data, int k) {
		assertTopKValues(data, k);
	}

	static Stream<Arguments> distributions() {
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
