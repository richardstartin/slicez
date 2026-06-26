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
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static io.github.richardstartin.slicez.SliceZ.BLOCK_SIZE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link SliceZ#histogram(long, long...)}.
 *
 * <p>
 * Buckets are defined by an inclusive {@code min} lower bound and exclusive
 * upper bounds in ascending unsigned order: bucket {@code i} counts values
 * {@code v} with {@code lower(i) <= v < upperBounds[i]}, where {@code lower(0)}
 * is {@code min} and {@code lower(i)} is {@code upperBounds[i - 1]} otherwise.
 * Values below {@code min} or at or above the final bound are not counted. The
 * reference is {@link #refHistogram}, which drops values below {@code min} and
 * assigns the rest to the first bucket whose exclusive upper bound exceeds
 * them.
 */
@Execution(ExecutionMode.CONCURRENT)
class TestHistogram {

	/**
	 * Brute-force reference: values below {@code min} are dropped, the rest land in
	 * the first bucket whose exclusive upper bound is strictly greater than them
	 * (unsigned); values not below any bound are dropped.
	 */
	private static long[] refHistogram(long[] data, long min, long... upperBounds) {
		long[] counts = new long[upperBounds.length];
		for (long v : data) {
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
		for (long v : data)
			appender.add(v);
		return appender.build();
	}

	// -------------------------------------------------------------------------
	// Shape of the result
	// -------------------------------------------------------------------------

	@Test
	void resultLengthMatchesBoundsLength() {
		assertEquals(3, buildIdx(0, 1, 2, 3, 4).histogram(0, 1, 2, 3).length);
	}

	@Test
	void emptyBoundsReturnsEmptyArray() {
		assertArrayEquals(new long[0], buildIdx(0, 1, 2, 3, 4).histogram(0));
	}

	// -------------------------------------------------------------------------
	// Basic single-block behaviour
	// -------------------------------------------------------------------------

	@Test
	void basicAdjacentBucketsFromZero() {
		// bucket 0: [0,2) -> {0,1}; bucket 1: [2,4) -> {2,3}; bucket 2: [4,6) -> {4,5}
		assertArrayEquals(new long[]{2, 2, 2}, buildIdx(0, 1, 2, 3, 4, 5).histogram(0, 2, 4, 6));
	}

	@Test
	void valuesAtOrAboveFinalBoundAreExcluded() {
		// only 0,1,2 fall below the single bound 3
		assertArrayEquals(new long[]{3}, buildIdx(0, 1, 2, 3, 4, 5).histogram(0, 3));
	}

	@Test
	void valuesBelowMinAreExcluded() {
		// min 2 drops {0,1}; bucket 0: [2,4) -> {2,3}; bucket 1: [4,6) -> {4,5}
		assertArrayEquals(new long[]{2, 2}, buildIdx(0, 1, 2, 3, 4, 5).histogram(2, 4, 6));
	}

	@Test
	void nonZeroMinFirstBucketStartsAtMin() {
		// min 3 drops {0,1,2}; bucket 0: [3,5) -> {3,4}
		assertArrayEquals(new long[]{2}, buildIdx(0, 1, 2, 3, 4, 5, 6).histogram(3, 5));
	}

	@Test
	void minEqualToFirstBoundGivesEmptyLeadingBucket() {
		// bucket 0: [3,3) -> {}; bucket 1: [3,5) -> {3,4}
		assertArrayEquals(new long[]{0, 2}, buildIdx(0, 1, 2, 3, 4, 5).histogram(3, 3, 5));
	}

	@Test
	void bucketsAreContiguousBetweenBounds() {
		// min 0; bucket 0: [0,2) -> {0,1}; bucket 1: [2,7) -> {2..6}
		assertArrayEquals(new long[]{2, 5}, buildIdx(0, 1, 2, 3, 4, 5, 6).histogram(0, 2, 7));
	}

	@Test
	void duplicateValues() {
		assertArrayEquals(new long[]{3, 2}, buildIdx(1, 1, 1, 2, 2).histogram(0, 2, 3));
	}

	// -------------------------------------------------------------------------
	// Validation: every bound must be >= min
	// -------------------------------------------------------------------------

	@Test
	void boundBelowMinThrows() {
		var idx = buildIdx(0, 1, 2, 3, 4);
		assertThrows(IllegalArgumentException.class, () -> idx.histogram(3, 2, 4));
	}

	@Test
	void firstBoundBelowMinThrows() {
		var idx = buildIdx(0, 1, 2, 3, 4);
		assertThrows(IllegalArgumentException.class, () -> idx.histogram(5, 4, 6));
	}

	@Test
	void boundEqualToMinIsAllowed() {
		// min == first bound is legal and yields an empty leading bucket
		assertArrayEquals(new long[]{0, 2}, buildIdx(0, 1, 2, 3, 4).histogram(2, 2, 4));
	}

	// -------------------------------------------------------------------------
	// Non-zero blockMin exercises per-block offset arithmetic
	// -------------------------------------------------------------------------

	@Test
	void nonZeroBlockMin() {
		long[] data = {100, 101, 102, 103, 104};
		assertArrayEquals(refHistogram(data, 101, 102, 104, 106), buildIdx(data).histogram(101, 102, 104, 106));
	}

	// -------------------------------------------------------------------------
	// A range of min values, including zero and non-zero
	// -------------------------------------------------------------------------

	@ParameterizedTest
	@ValueSource(longs = {0, 1, 3, 5, 7, 9, 10})
	void variousMinValues(long min) {
		long[] data = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
		long[] bounds = {2, 4, 6, 8, 10};
		// only retain bounds >= min so the call is valid
		long[] valid = LongStream.of(bounds).filter(b -> Long.compareUnsigned(b, min) >= 0).toArray();
		assertArrayEquals(refHistogram(data, min, valid), buildIdx(data).histogram(min, valid));
	}

	// -------------------------------------------------------------------------
	// Multi-block
	// -------------------------------------------------------------------------

	@ParameterizedTest
	@ValueSource(ints = {0xFFFF, 0x10001, 100_000})
	void multiBlockContiguous(int size) {
		long[] data = LongStream.range(0, size).toArray();
		long min = BLOCK_SIZE / 4L;
		long b0 = BLOCK_SIZE / 2L;
		long b1 = BLOCK_SIZE + BLOCK_SIZE / 2L;
		long b2 = (long) size; // captures the remainder up to the end of the data
		assertArrayEquals(refHistogram(data, min, b0, b1, b2), buildIdx(data).histogram(min, b0, b1, b2));
	}

	// -------------------------------------------------------------------------
	// Cross-check against the reference over a variety of data shapes
	// -------------------------------------------------------------------------

	@ParameterizedTest
	@MethodSource("testCases")
	void crossCheck(long[] data, long min, long[] bounds) {
		assertArrayEquals(refHistogram(data, min, bounds), buildIdx(data).histogram(min, bounds));
	}

	// -------------------------------------------------------------------------
	// Property-based testing against the scan oracle
	//
	// The oracle below is duplicated from HistogramBenchmark.baselineScan / bucket:
	// a linear scan that drops values below min and routes the rest to a bucket via
	// an unsigned binary search over the exclusive upper bounds. It is an
	// independent reference for SliceZ.histogram over randomly generated data.
	// -------------------------------------------------------------------------

	/**
	 * Oracle: a linear scan that drops values below {@code min} and routes each
	 * remaining value to its bucket via an unsigned binary search over the
	 * exclusive upper bounds. Duplicated from
	 * {@code HistogramBenchmark.baselineScan}.
	 */
	private static long[] scanHistogram(long[] values, long min, long[] bounds) {
		long[] counts = new long[bounds.length];
		for (long v : values) {
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

	/**
	 * Returns the index of the bucket {@code value} falls into: the first index
	 * {@code i} with {@code value < bounds[i]} (unsigned), or {@code bounds.length}
	 * if {@code value} is at or above every bound. Duplicated from
	 * {@code HistogramBenchmark.bucket}.
	 */
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
			var idx = buildIdx(values);
			assertArrayEquals(scanHistogram(values, min, bounds), idx.histogram(min, bounds),
					() -> "seed=" + seed + " size=" + values.length + " min=" + Long.toUnsignedString(min) + " bounds="
							+ Arrays.toString(bounds));
		}
	}

	/** Generates a data set from one of several distributions and sizes. */
	private static long[] randomData(SplittableRandom r) {
		int size = r.nextInt(1, 3 * BLOCK_SIZE);
		long[] values = new long[size];
		switch (r.nextInt(6)) {
			case 0 -> { // narrow range: heavy duplicates, full/sparse-inverted slices
				long bound = r.nextLong(1, 64);
				for (int i = 0; i < size; i++)
					values[i] = r.nextLong(bound);
			}
			case 1 -> { // full unsigned range: dense slices
				for (int i = 0; i < size; i++)
					values[i] = r.nextLong();
			}
			case 2 -> { // offset range: non-zero blockMin
				long base = r.nextLong(1, 1_000_000);
				long range = r.nextLong(1, 1_000_000);
				for (int i = 0; i < size; i++)
					values[i] = base + r.nextLong(range);
			}
			case 3 -> { // exponential
				double rate = 0.0001;
				for (int i = 0; i < size; i++)
					values[i] = (long) -(Math.log(r.nextDouble() + 1e-12) / rate);
			}
			case 4 -> { // near-constant with rare outliers
				long point = r.nextLong();
				for (int i = 0; i < size; i++)
					values[i] = r.nextInt(100) == 0 ? r.nextLong() : point;
			}
			default -> { // moderate uniform
				for (int i = 0; i < size; i++)
					values[i] = r.nextLong(0, 1_000_000);
			}
		}
		return values;
	}

	/** Picks a min value, biased towards boundary cases. */
	private static long randomMin(SplittableRandom r, long[] sorted) {
		return switch (r.nextInt(5)) {
			case 0 -> 0L; // include everything from zero
			case 1 -> sorted[0]; // the true unsigned min of the data
			case 2 -> sorted[r.nextInt(sorted.length)]; // some present value
			case 3 -> sorted[sorted.length - 1] + 1; // above max: excludes everything
			default -> r.nextLong(); // arbitrary
		};
	}

	/** Builds 1..10 ascending bounds, all >= min, biased towards boundary cases. */
	private static long[] randomBounds(SplittableRandom r, long[] sorted, long min) {
		int k = r.nextInt(1, 11);
		long[] bounds = new long[k];
		for (int i = 0; i < k; i++) {
			long candidate = switch (r.nextInt(4)) {
				case 0 -> sorted[r.nextInt(sorted.length)]; // a present value
				case 1 -> sorted[sorted.length - 1] + 1; // beyond max: captures all remaining
				case 2 -> min; // empty leading / boundary bucket
				default -> r.nextLong(); // arbitrary
			};
			// every bound must be >= min in unsigned order
			bounds[i] = Long.compareUnsigned(candidate, min) < 0 ? min : candidate;
		}
		sortUnsigned(bounds);
		return bounds;
	}

	/** Sorts a copy of {@code values} into ascending unsigned order. */
	private static long[] unsignedSorted(long[] values) {
		long[] sorted = values.clone();
		sortUnsigned(sorted);
		return sorted;
	}

	/** Sorts {@code values} in place into ascending unsigned order. */
	private static void sortUnsigned(long[] values) {
		// flip the sign bit so a signed sort yields unsigned order, then flip back
		for (int i = 0; i < values.length; i++)
			values[i] ^= Long.MIN_VALUE;
		Arrays.sort(values);
		for (int i = 0; i < values.length; i++)
			values[i] ^= Long.MIN_VALUE;
	}

	static Stream<Arguments> testCases() {
		return Stream.of(Arguments.of(new long[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, 0L, new long[]{3, 6, 9}),
				Arguments.of(new long[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, 3L, new long[]{3, 6, 9}),
				Arguments.of(new long[]{3, 1, 4, 1, 5, 9, 2, 6}, 2L, new long[]{2, 5, 10}),
				Arguments.of(new long[]{0, 0, 0, 1, 2}, 0L, new long[]{1, 2, 3}),
				// non-zero blockMin
				Arguments.of(new long[]{100, 101, 102, 103, 104}, 101L, new long[]{101, 103, 105}),
				Arguments.of(new long[]{1000, 2000, 3000, 1500, 2500}, 1500L, new long[]{1500, 2500, 3500}),
				// contiguous ranges
				Arguments.of(LongStream.range(0, 200).toArray(), 50L, new long[]{50, 100, 150, 200}),
				Arguments.of(LongStream.range(500, 700).toArray(), 550L, new long[]{550, 600, 650}),
				// multi-block
				Arguments.of(LongStream.range(0, (long) BLOCK_SIZE + 100).toArray(), (long) (BLOCK_SIZE / 4),
						new long[]{BLOCK_SIZE / 2, BLOCK_SIZE, BLOCK_SIZE + 100}));
	}
}
