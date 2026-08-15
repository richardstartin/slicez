package io.github.richardstartin.slicez;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static io.github.richardstartin.slicez.SliceZ.BLOCK_SIZE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for the filtered histogram overload
 * {@link SliceZ#histogram(BlockIterator, long, long...)}.
 *
 * <p>
 * The semantics are identical to the unfiltered
 * {@link SliceZ#histogram(long, long...)} except that only rows present in the
 * supplied {@link BlockIterator} filter contribute to the buckets. A row counts
 * towards bucket {@code i} when its value {@code v} satisfies
 * {@code lower(i) <= v < upperBounds[i]} (unsigned) <em>and</em> the row id is
 * present in the filter. Values below {@code min}, values at or above the final
 * bound, and rows excluded by the filter are not counted.
 *
 * <p>
 * The filter is sourced from {@link BlockedBitmap#blockIterator()},
 * {@link BlockedBitmap#and} and {@link BlockedBitmap#or}, mirroring
 * {@link TestSliceZBlockFilter}.
 */
@Execution(ExecutionMode.CONCURRENT)
class TestFilteredHistogram {

	/**
	 * Brute-force reference: a row contributes only when its id is in
	 * {@code allowed}, its value is at or above {@code min}, and it falls below
	 * some bound; it lands in the first bucket whose exclusive upper bound is
	 * strictly greater than its value (unsigned).
	 */
	private static long[] refHistogram(long[] data, TreeSet<Integer> allowed, long min, long... upperBounds) {
		long[] counts = new long[upperBounds.length];
		for (int r = 0; r < data.length; r++) {
			if (!allowed.contains(r)) {
				continue;
			}
			long v = data[r];
			if (Long.compareUnsigned(v, min) < 0) {
				continue;
			}
			for (int i = 0; i < upperBounds.length; i++) {
				if (Long.compareUnsigned(v, upperBounds[i]) < 0) {
					counts[i]++;
					break;
				}
			}
		}
		return counts;
	}

	private static SliceZ buildIdx(long... data) {
		var appender = SliceZ.appender();
		for (long v : data) {
			appender.add(v);
		}
		return appender.build();
	}

	private static BlockedBitmap bitmap(int... rids) {
		var appender = BlockedBitmap.appender();
		for (int rid : rids) {
			appender.add(rid);
		}
		return appender.build();
	}

	private static TreeSet<Integer> rowSet(int... rids) {
		var set = new TreeSet<Integer>();
		for (int rid : rids) {
			set.add(rid);
		}
		return set;
	}

	// -------------------------------------------------------------------------
	// Shape of the result
	// -------------------------------------------------------------------------

	@Test
	void resultLengthMatchesBoundsLength() {
		var idx = buildIdx(0, 1, 2, 3, 4);
		assertEquals(3, idx.histogram(bitmap(0, 1, 2, 3, 4).blockIterator(), 0, 1, 2, 3).length);
	}

	@Test
	void emptyBoundsReturnsEmptyArray() {
		var idx = buildIdx(0, 1, 2, 3, 4);
		assertArrayEquals(new long[0], idx.histogram(bitmap(0, 1, 2, 3, 4).blockIterator(), 0));
	}

	// -------------------------------------------------------------------------
	// Basic filtered behaviour
	// -------------------------------------------------------------------------

	@Test
	void specExample() {
		// data {1, 2, 2}, filter {2}: only row 2 (value 2) survives, bucket [2,3)
		var idx = buildIdx(1, 2, 2);
		assertArrayEquals(new long[]{0, 1}, idx.histogram(bitmap(2).blockIterator(), 0, 2, 3));
	}

	@Test
	void filterExcludesRowsFromBuckets() {
		// values {0,1,2,3,4,5}; only rows {0,2,4} (values 0,2,4) pass the filter
		long[] data = {0, 1, 2, 3, 4, 5};
		var idx = buildIdx(data);
		var filter = bitmap(0, 2, 4);
		var allowed = rowSet(0, 2, 4);
		// buckets [0,2),[2,4),[4,6): values 0 -> b0, 2 -> b1, 4 -> b2
		assertArrayEquals(refHistogram(data, allowed, 0, 2, 4, 6), idx.histogram(filter.blockIterator(), 0, 2, 4, 6));
	}

	@Test
	void emptyFilterCountsNothing() {
		long[] data = {0, 1, 2, 3, 4, 5};
		var idx = buildIdx(data);
		assertArrayEquals(new long[]{0, 0, 0}, idx.histogram(bitmap().blockIterator(), 0, 2, 4, 6));
	}

	@Test
	void fullFilterMatchesUnfilteredHistogram() {
		// a filter containing every row must reproduce the unfiltered histogram
		long[] data = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
		var idx = buildIdx(data);
		var all = bitmap(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
		assertArrayEquals(idx.histogram(0, 3, 6, 9), idx.histogram(all.blockIterator(), 0, 3, 6, 9));
	}

	@Test
	void filterCombinedWithMinExclusion() {
		// min 2 drops {0,1} by value; filter {0,1,2,3} drops rows 4,5 by id
		long[] data = {0, 1, 2, 3, 4, 5};
		var idx = buildIdx(data);
		var filter = bitmap(0, 1, 2, 3);
		var allowed = rowSet(0, 1, 2, 3);
		// only rows 2,3 (values 2,3) remain; buckets [2,4),[4,6)
		assertArrayEquals(refHistogram(data, allowed, 2, 4, 6), idx.histogram(filter.blockIterator(), 2, 4, 6));
	}

	@Test
	void duplicateValuesWithFilter() {
		// values {1,1,1,2,2}; filter keeps rows {0,3} (one '1' and one '2')
		long[] data = {1, 1, 1, 2, 2};
		var idx = buildIdx(data);
		var filter = bitmap(0, 3);
		assertArrayEquals(new long[]{1, 1}, idx.histogram(filter.blockIterator(), 0, 2, 3));
	}

	// -------------------------------------------------------------------------
	// and() / or() as the filter
	// -------------------------------------------------------------------------

	@Test
	void andFilter() {
		long[] data = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
		var idx = buildIdx(data);
		var a = bitmap(1, 2, 3, 4, 5, 6);
		var b = bitmap(3, 4, 5, 6, 7, 8);
		var allowed = rowSet(3, 4, 5, 6); // intersection
		assertArrayEquals(refHistogram(data, allowed, 0, 3, 6, 9), idx.histogram(a.and(b), 0, 3, 6, 9));
	}

	@Test
	void orFilter() {
		long[] data = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
		var idx = buildIdx(data);
		var a = bitmap(0, 1, 2);
		var b = bitmap(7, 8);
		var allowed = rowSet(0, 1, 2, 7, 8); // union
		assertArrayEquals(refHistogram(data, allowed, 0, 3, 6, 9), idx.histogram(a.or(b), 0, 3, 6, 9));
	}

	// -------------------------------------------------------------------------
	// Validation: every bound must be >= min (independent of the filter)
	// -------------------------------------------------------------------------

	@Test
	void boundBelowMinThrows() {
		var idx = buildIdx(0, 1, 2, 3, 4);
		var filter = bitmap(0, 1, 2, 3, 4).blockIterator();
		assertThrows(IllegalArgumentException.class, () -> idx.histogram(filter, 3, 2, 4));
	}

	@Test
	void boundEqualToMinIsAllowed() {
		long[] data = {0, 1, 2, 3, 4};
		var idx = buildIdx(data);
		var filter = bitmap(0, 1, 2, 3, 4);
		var allowed = rowSet(0, 1, 2, 3, 4);
		assertArrayEquals(refHistogram(data, allowed, 2, 2, 4), idx.histogram(filter.blockIterator(), 2, 2, 4));
	}

	// -------------------------------------------------------------------------
	// Non-zero blockMin exercises per-block offset arithmetic with a filter
	// -------------------------------------------------------------------------

	@Test
	void nonZeroBlockMin() {
		long[] data = {100, 101, 102, 103, 104};
		var idx = buildIdx(data);
		var filter = bitmap(1, 2, 4);
		var allowed = rowSet(1, 2, 4);
		assertArrayEquals(refHistogram(data, allowed, 101, 102, 104, 106),
				idx.histogram(filter.blockIterator(), 101, 102, 104, 106));
	}

	// -------------------------------------------------------------------------
	// Multiple blocks: the filter skips a whole block and masks rows within the
	// blocks it keeps.
	// -------------------------------------------------------------------------

	@Test
	void multiBlockBlockAndBitLevel() {
		int n = 200_000; // blocks 0, 1 and 2 (the last partial)
		long[] data = new long[n];
		for (int r = 0; r < n; r++) {
			data[r] = r % 10;
		}
		var idx = buildIdx(data);
		// rows in block 0 and block 2; block 1 ([BLOCK_SIZE, 2*BLOCK_SIZE)) is excluded
		int b = (int) BLOCK_SIZE;
		var filter = bitmap(3, 13, 100, 2 * b + 931, 2 * b + 18931);
		var allowed = rowSet(3, 13, 100, 2 * b + 931, 2 * b + 18931);
		assertArrayEquals(refHistogram(data, allowed, 0, 3, 6, 10), idx.histogram(filter.blockIterator(), 0, 3, 6, 10));
	}

	// -------------------------------------------------------------------------
	// Cross-check against the reference over a variety of data shapes
	// -------------------------------------------------------------------------

	@ParameterizedTest
	@MethodSource("testCases")
	void crossCheck(long[] data, int[] rows, long min, long[] bounds) {
		var idx = buildIdx(data);
		var allowed = rowSet(rows);
		assertArrayEquals(refHistogram(data, allowed, min, bounds),
				idx.histogram(bitmap(rows).blockIterator(), min, bounds));
	}

	static Stream<Arguments> testCases() {
		return Stream.of(
				Arguments.of(new long[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, new int[]{0, 2, 4, 6, 8}, 0L,
						new long[]{3, 6, 9}),
				Arguments.of(new long[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, new int[]{1, 3, 5, 7, 9}, 3L,
						new long[]{3, 6, 9}),
				Arguments.of(new long[]{3, 1, 4, 1, 5, 9, 2, 6}, new int[]{0, 1, 2, 4, 6}, 2L, new long[]{2, 5, 10}),
				Arguments.of(new long[]{100, 101, 102, 103, 104}, new int[]{0, 2, 4}, 101L, new long[]{101, 103, 105}),
				Arguments.of(LongStream.range(0, 200).toArray(),
						LongStream.range(0, 100).mapToInt(i -> (int) (i * 2)).toArray(), 50L,
						new long[]{50, 100, 150, 200}),
				Arguments.of(LongStream.range(0, (long) BLOCK_SIZE + 100).toArray(),
						LongStream.range(0, BLOCK_SIZE + 100).filter(i -> i % 3 == 0).mapToInt(i -> (int) i).toArray(),
						(long) (BLOCK_SIZE / 4), new long[]{BLOCK_SIZE / 2, BLOCK_SIZE, BLOCK_SIZE + 100}));
	}

	// -------------------------------------------------------------------------
	// Property-based testing: the filtered histogram must agree with a linear
	// scan that drops rows excluded by the filter or below min and routes the
	// rest to a bucket via an unsigned binary search over the exclusive bounds.
	// -------------------------------------------------------------------------

	private static long[] scanHistogram(long[] values, TreeSet<Integer> allowed, long min, long[] bounds) {
		long[] counts = new long[bounds.length];
		for (int r = 0; r < values.length; r++) {
			if (!allowed.contains(r)) {
				continue;
			}
			long v = values[r];
			if (Long.compareUnsigned(v, min) < 0) {
				continue;
			}
			int b = bucket(bounds, v);
			if (b < counts.length) {
				counts[b]++;
			}
		}
		return counts;
	}

	private static int bucket(long[] bounds, long value) {
		int lo = 0;
		int hi = bounds.length;
		while (lo < hi) {
			int mid = (lo + hi) >>> 1;
			if (Long.compareUnsigned(bounds[mid], value) <= 0) {
				lo = mid + 1;
			} else {
				hi = mid;
			}
		}
		return lo;
	}

	@ParameterizedTest
	@ValueSource(longs = {1, 2, 3, 42, 123, 2024, 7777, 999_999})
	void matchesScanOracle(long seed) {
		var random = new SplittableRandom(seed);
		for (int trial = 0; trial < 25; trial++) {
			long[] values = randomData(random);
			long[] sorted = unsignedSorted(values);
			long min = randomMin(random, sorted);
			long[] bounds = randomBounds(random, sorted, min);
			TreeSet<Integer> allowed = randomFilter(random, values.length);
			var idx = buildIdx(values);
			var filter = bitmap(allowed.stream().mapToInt(Integer::intValue).toArray());
			assertArrayEquals(scanHistogram(values, allowed, min, bounds),
					idx.histogram(filter.blockIterator(), min, bounds),
					() -> "seed=" + seed + " size=" + values.length + " min=" + Long.toUnsignedString(min) + " bounds="
							+ Arrays.toString(bounds) + " |filter|=" + allowed.size());
		}
	}

	/** Selects a random subset of {@code [0, size)} as the filtered row ids. */
	private static TreeSet<Integer> randomFilter(SplittableRandom r, int size) {
		var allowed = new TreeSet<Integer>();
		switch (r.nextInt(4)) {
			case 0 -> {
				// empty filter
			}
			case 1 -> {
				// every row
				for (int i = 0; i < size; i++) {
					allowed.add(i);
				}
			}
			case 2 -> {
				// sparse: a few random rows
				int k = r.nextInt(0, Math.min(size, 64) + 1);
				for (int i = 0; i < k; i++) {
					allowed.add(r.nextInt(size));
				}
			}
			default -> {
				// dense: each row kept with probability ~0.5
				for (int i = 0; i < size; i++) {
					if (r.nextBoolean()) {
						allowed.add(i);
					}
				}
			}
		}
		return allowed;
	}

	private static long[] randomData(SplittableRandom r) {
		int size = r.nextInt(1, 3 * BLOCK_SIZE);
		long[] values = new long[size];
		switch (r.nextInt(6)) {
			case 0 -> {
				long bound = r.nextLong(1, 64);
				for (int i = 0; i < size; i++)
					values[i] = r.nextLong(bound);
			}
			case 1 -> {
				for (int i = 0; i < size; i++)
					values[i] = r.nextLong();
			}
			case 2 -> {
				long base = r.nextLong(1, 1_000_000);
				long range = r.nextLong(1, 1_000_000);
				for (int i = 0; i < size; i++)
					values[i] = base + r.nextLong(range);
			}
			case 3 -> {
				double rate = 0.0001;
				for (int i = 0; i < size; i++)
					values[i] = (long) -(Math.log(r.nextDouble() + 1e-12) / rate);
			}
			case 4 -> {
				long point = r.nextLong();
				for (int i = 0; i < size; i++)
					values[i] = r.nextInt(100) == 0 ? r.nextLong() : point;
			}
			default -> {
				for (int i = 0; i < size; i++)
					values[i] = r.nextLong(0, 1_000_000);
			}
		}
		return values;
	}

	private static long randomMin(SplittableRandom r, long[] sorted) {
		return switch (r.nextInt(5)) {
			case 0 -> 0L;
			case 1 -> sorted[0];
			case 2 -> sorted[r.nextInt(sorted.length)];
			case 3 -> sorted[sorted.length - 1] + 1;
			default -> r.nextLong();
		};
	}

	private static long[] randomBounds(SplittableRandom r, long[] sorted, long min) {
		int k = r.nextInt(1, 11);
		long[] bounds = new long[k];
		for (int i = 0; i < k; i++) {
			long candidate = switch (r.nextInt(4)) {
				case 0 -> sorted[r.nextInt(sorted.length)];
				case 1 -> sorted[sorted.length - 1] + 1;
				case 2 -> min;
				default -> r.nextLong();
			};
			bounds[i] = Long.compareUnsigned(candidate, min) < 0 ? min : candidate;
		}
		sortUnsigned(bounds);
		return bounds;
	}

	private static long[] unsignedSorted(long[] values) {
		long[] sorted = values.clone();
		sortUnsigned(sorted);
		return sorted;
	}

	private static void sortUnsigned(long[] values) {
		for (int i = 0; i < values.length; i++)
			values[i] ^= Long.MIN_VALUE;
		Arrays.sort(values);
		for (int i = 0; i < values.length; i++)
			values[i] ^= Long.MIN_VALUE;
	}
}
