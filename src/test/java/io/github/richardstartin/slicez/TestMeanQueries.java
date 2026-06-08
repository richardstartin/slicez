package io.github.richardstartin.slicez;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.function.LongPredicate;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static io.github.richardstartin.slicez.SliceZ.BLOCK_SIZE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@code mean*} family of methods on {@link SliceZ}.
 *
 * <p>
 * The core invariant checked throughout is:
 * 
 * <pre>
 *   mean(q) = sum(q) / count(q)   when count(q) &gt; 0
 *   mean(q) = 0                   when count(q) = 0
 * </pre>
 * 
 * The reference for cross-checks is {@link #refMean}: compute the mean of all
 * indexed values matching the predicate, returning {@code 0} for empty match
 * sets. Data sets with a non-zero {@code blockMin} are deliberately included to
 * exercise the per-block offset arithmetic inherited from {@code sum()}.
 */
@Execution(ExecutionMode.CONCURRENT)
class TestMeanQueries {

	private static double refMean(long[] data, LongPredicate pred) {
		double sum = 0.0;
		long count = 0;
		for (long v : data) {
			if (pred.test(v)) {
				sum += v;
				count++;
			}
		}
		return count == 0 ? 0.0 : sum / count;
	}

	private static SliceZ buildIdx(long... data) {
		var appender = SliceZ.appender();
		for (long v : data)
			appender.add(v);
		return appender.build();
	}

	private static final double DELTA = 1e-9;

	// -------------------------------------------------------------------------
	// Core invariant: mean = sum / count for every query variant
	// -------------------------------------------------------------------------

	@Test
	void meanEqualsSumDividedByCount() {
		long[] data = {0, 1, 2, 3, 4};
		var idx = buildIdx(data);
		for (long v = 0; v <= 5; v++) {
			assertMeanConsistency(idx.sumLessThan(v), idx.countLessThan(v), idx.meanLessThan(v), "LessThan v=" + v);
			assertMeanConsistency(idx.sumLessThanOrEqual(v), idx.countLessThanOrEqual(v), idx.meanLessThanOrEqual(v),
					"LessThanOrEqual v=" + v);
			assertMeanConsistency(idx.sumGreaterThan(v), idx.countGreaterThan(v), idx.meanGreaterThan(v),
					"GreaterThan v=" + v);
			assertMeanConsistency(idx.sumGreaterThanOrEqual(v), idx.countGreaterThanOrEqual(v),
					idx.meanGreaterThanOrEqual(v), "GreaterThanOrEqual v=" + v);
			assertMeanConsistency(idx.sumEqual(v), idx.countEqual(v), idx.meanEqual(v), "Equal v=" + v);
			assertMeanConsistency(idx.sumNotEqual(v), idx.countNotEqual(v), idx.meanNotEqual(v), "NotEqual v=" + v);
		}
	}

	private static void assertMeanConsistency(double sum, int count, double mean, String label) {
		if (count == 0) {
			assertEquals(0.0, mean, 0.0, label + ": mean should be 0 when count is 0");
		} else {
			assertEquals(sum / count, mean, DELTA, label + ": mean should equal sum/count");
		}
	}

	// -------------------------------------------------------------------------
	// meanLessThan / meanLessThanOrEqual
	// -------------------------------------------------------------------------

	@Test
	void meanLessThanZeroIsZero() {
		assertEquals(0.0, buildIdx(1, 2, 3).meanLessThan(0), 0.0);
	}

	@Test
	void meanLessThanBasic() {
		// {0,1,2} < 3; mean = (0+1+2)/3 = 1.0
		assertEquals(1.0, buildIdx(0, 1, 2, 3, 4).meanLessThan(3), DELTA);
	}

	@Test
	void meanLessThanOrEqualBasic() {
		// {0,1,2,3} <= 3; mean = (0+1+2+3)/4 = 1.5
		assertEquals(1.5, buildIdx(0, 1, 2, 3, 4).meanLessThanOrEqual(3), DELTA);
	}

	@Test
	void meanLessThanNoneMatch() {
		assertEquals(0.0, buildIdx(5, 6, 7).meanLessThan(5), 0.0);
	}

	@Test
	void meanLessThanAllMatch() {
		// mean of {0,1,2,3,4} = 10/5 = 2.0
		assertEquals(2.0, buildIdx(0, 1, 2, 3, 4).meanLessThan(100), DELTA);
	}

	// -------------------------------------------------------------------------
	// meanGreaterThan / meanGreaterThanOrEqual
	// -------------------------------------------------------------------------

	@Test
	void meanGreaterThanBasic() {
		// {3,4} > 2; mean = (3+4)/2 = 3.5
		assertEquals(3.5, buildIdx(0, 1, 2, 3, 4).meanGreaterThan(2), DELTA);
	}

	@Test
	void meanGreaterThanOrEqualBasic() {
		// {2,3,4} >= 2; mean = (2+3+4)/3 = 3.0
		assertEquals(3.0, buildIdx(0, 1, 2, 3, 4).meanGreaterThanOrEqual(2), DELTA);
	}

	@Test
	void meanGreaterThanNoneMatch() {
		assertEquals(0.0, buildIdx(0, 1, 2).meanGreaterThan(2), 0.0);
	}

	@Test
	void meanGreaterThanOrEqualZeroReturnsOverallMean() {
		// all values match; mean of {3,1,4,1,5} = 14/5 = 2.8
		assertEquals((3.0 + 1 + 4 + 1 + 5) / 5, buildIdx(3, 1, 4, 1, 5).meanGreaterThanOrEqual(0), DELTA);
	}

	// -------------------------------------------------------------------------
	// mean and the weighted-average identity
	// totalSum = mean(lt) * count(lt) + mean(gte) * count(gte)
	// -------------------------------------------------------------------------

	@Test
	void meanCombinesWithCountToRecoverTotalSum() {
		long[] data = {0, 1, 2, 3, 4};
		var idx = buildIdx(data);
		double totalSum = 0 + 1 + 2 + 3 + 4;
		for (long v = 0; v <= 5; v++) {
			double reconstructed = idx.meanLessThan(v) * idx.countLessThan(v)
					+ idx.meanGreaterThanOrEqual(v) * idx.countGreaterThanOrEqual(v);
			assertEquals(totalSum, reconstructed, DELTA, "reconstructed total sum mismatch at v=" + v);
		}
	}

	// -------------------------------------------------------------------------
	// meanEqual / meanNotEqual
	// -------------------------------------------------------------------------

	@Test
	void meanEqualIsValueWhenPresent() {
		var idx = buildIdx(0, 1, 2, 3, 4);
		// each value appears exactly once; mean = the value itself
		for (long v = 0; v <= 4; v++) {
			assertEquals((double) v, idx.meanEqual(v), 0.0, "meanEqual v=" + v);
		}
	}

	@Test
	void meanEqualAbsentValueIsZero() {
		assertEquals(0.0, buildIdx(0, 1, 2, 3, 4).meanEqual(99), 0.0);
	}

	@Test
	void meanEqualWithDuplicates() {
		// three copies of 3; every matching row holds value 3, so mean = 3
		assertEquals(3.0, buildIdx(3, 3, 3, 1, 2).meanEqual(3), DELTA);
	}

	@Test
	void meanNotEqualBasic() {
		// {0,1,3,4} != 2; mean = (0+1+3+4)/4 = 2.0
		assertEquals(2.0, buildIdx(0, 1, 2, 3, 4).meanNotEqual(2), DELTA);
	}

	@Test
	void meanNotEqualAbsentValueMatchesOverallMean() {
		// excluded value not present → all rows match → meanNotEqual == overall mean
		var idx = buildIdx(0, 1, 2, 3, 4);
		assertEquals((0.0 + 1 + 2 + 3 + 4) / 5, idx.meanNotEqual(99), DELTA);
	}

	@Test
	void meanPartitionsIntoWeightedContributions() {
		// totalSum = meanEqual(v) * countEqual(v) + meanNotEqual(v) * countNotEqual(v)
		long[] data = {3, 1, 4, 1, 5, 9, 2, 6};
		var idx = buildIdx(data);
		double totalSum = 0;
		for (long v : data)
			totalSum += v;
		for (long v : new long[]{1, 3, 5, 9, 99}) {
			double reconstructed = idx.meanEqual(v) * idx.countEqual(v) + idx.meanNotEqual(v) * idx.countNotEqual(v);
			assertEquals(totalSum, reconstructed, DELTA, "partition failed at v=" + v);
		}
	}

	// -------------------------------------------------------------------------
	// meanBetween
	// -------------------------------------------------------------------------

	@Test
	void meanBetweenBasic() {
		// {1,2,3} in [1,4); mean = (1+2+3)/3 = 2.0
		assertEquals(2.0, buildIdx(0, 1, 2, 3, 4).meanBetween(1, 4), DELTA);
	}

	@Test
	void meanBetweenLowerZeroDelegatesToMeanLessThan() {
		// between(0, 3) delegates to lessThan(3): {0,1,2}, mean = 1.0
		assertEquals(1.0, buildIdx(0, 1, 2, 3, 4).meanBetween(0, 3), DELTA);
	}

	@Test
	void meanBetweenEmptyRangeIsZero() {
		var idx = buildIdx(0, 1, 2, 3, 4);
		assertEquals(0.0, idx.meanBetween(2, 2), 0.0);
		assertEquals(0.0, idx.meanBetween(3, 1), 0.0);
	}

	@Test
	void meanBetweenNonZeroBlockMin() {
		long[] data = {5, 6, 7, 8, 9};
		var idx = buildIdx(data);
		// [6,9) = {6,7,8}; mean = 7.0
		assertEquals(7.0, idx.meanBetween(6, 9), DELTA);
	}

	// -------------------------------------------------------------------------
	// meanIn
	// -------------------------------------------------------------------------

	@Test
	void meanInEmptyValuesArrayIsZero() {
		assertEquals(0.0, buildIdx(0, 1, 2, 3, 4).meanIn(), 0.0);
	}

	@Test
	void meanInSingleValueMatchesMeanEqual() {
		var idx = buildIdx(0, 1, 2, 3, 4);
		for (long v = 0; v <= 4; v++) {
			assertEquals(idx.meanEqual(v), idx.meanIn(v), DELTA, "meanIn vs meanEqual at v=" + v);
		}
	}

	@Test
	void meanInMultipleValues() {
		// in(1,3): matching values {1,3}; mean = 2.0
		assertEquals(2.0, buildIdx(0, 1, 2, 3, 4).meanIn(1, 3), DELTA);
	}

	@Test
	void meanInAllAbsent() {
		assertEquals(0.0, buildIdx(0, 1, 2, 3, 4).meanIn(10, 20, 30), 0.0);
	}

	@Test
	void meanInWithDuplicateIndexedValues() {
		// {3,3,3,1,2}; in(3,1) matches {3,3,3,1}; sum=10, count=4, mean=2.5
		assertEquals((9.0 + 1) / 4, buildIdx(3, 3, 3, 1, 2).meanIn(3, 1), DELTA);
	}

	@Test
	void meanInConsistentWithSumAndCount() {
		var idx = buildIdx(0, 1, 2, 3, 4);
		long[] query = {1, 3};
		assertMeanConsistency(idx.sumIn(query), idx.countIn(query), idx.meanIn(query), "meanIn(1,3)");
	}

	// -------------------------------------------------------------------------
	// Empty index: every mean method returns 0
	// -------------------------------------------------------------------------

	@Test
	void emptyIndexAllMeansZero() {
		var idx = SliceZ.build();
		assertEquals(0.0, idx.meanLessThan(5), 0.0);
		assertEquals(0.0, idx.meanLessThanOrEqual(5), 0.0);
		assertEquals(0.0, idx.meanGreaterThan(0), 0.0);
		assertEquals(0.0, idx.meanGreaterThanOrEqual(0), 0.0);
		assertEquals(0.0, idx.meanEqual(5), 0.0);
		assertEquals(0.0, idx.meanNotEqual(5), 0.0);
		assertEquals(0.0, idx.meanBetween(0, 10), 0.0);
		assertEquals(0.0, idx.meanIn(5), 0.0);
	}

	// -------------------------------------------------------------------------
	// Non-zero blockMin: blockMin contribution must be included exactly once
	// -------------------------------------------------------------------------

	@Test
	void meanWithNonZeroBlockMin() {
		long[] data = {100, 101, 102, 103, 104};
		var idx = buildIdx(data);
		assertEquals(refMean(data, v -> v < 103), idx.meanLessThan(103), DELTA);
		assertEquals(refMean(data, v -> v <= 102), idx.meanLessThanOrEqual(102), DELTA);
		assertEquals(refMean(data, v -> v > 101), idx.meanGreaterThan(101), DELTA);
		assertEquals(refMean(data, v -> v >= 102), idx.meanGreaterThanOrEqual(102), DELTA);
		assertEquals(refMean(data, v -> v == 101), idx.meanEqual(101), DELTA);
		assertEquals(refMean(data, v -> v != 101), idx.meanNotEqual(101), DELTA);
		assertEquals(refMean(data, v -> v >= 101 && v < 104), idx.meanBetween(101, 104), DELTA);
		assertEquals(refMean(data, v -> v == 100 || v == 103), idx.meanIn(100, 103), DELTA);
	}

	@Test
	void meanAllSameNonZeroValue() {
		// all values = 7 → blockMin = 7, all slices FULL; mean = 7 for all matching
		// sets
		long[] data = {7, 7, 7};
		var idx = buildIdx(data);
		assertEquals(7.0, idx.meanEqual(7), DELTA);
		assertEquals(0.0, idx.meanEqual(0), 0.0);
		assertEquals(7.0, idx.meanNotEqual(0), DELTA);
		assertEquals(7.0, idx.meanLessThanOrEqual(7), DELTA);
		assertEquals(0.0, idx.meanLessThan(7), 0.0);
		assertEquals(0.0, idx.meanGreaterThan(7), 0.0);
		assertEquals(7.0, idx.meanGreaterThanOrEqual(7), DELTA);
		assertEquals(7.0, idx.meanBetween(7, 8), DELTA);
		assertEquals(7.0, idx.meanIn(7), DELTA);
	}

	// -------------------------------------------------------------------------
	// bottomMean
	// -------------------------------------------------------------------------

	@Test
	void bottomMeanZeroIsZero() {
		assertEquals(0.0, SliceZ.build(3, 1, 4, 1, 5).bottomMean(0), 0.0);
	}

	@Test
	void bottomMeanNegativeKThrows() {
		var idx = SliceZ.build(1, 2, 3);
		assertThrows(IllegalArgumentException.class, () -> idx.bottomMean(-1));
	}

	@Test
	void bottomMeanBasic() {
		// sorted unsigned: [1,1,2,3,4,5,6,9]; bottom(3) = {1,1,2}; mean = 4/3
		assertEquals((1.0 + 1 + 2) / 3, SliceZ.build(3, 1, 4, 1, 5, 9, 2, 6).bottomMean(3), DELTA);
	}

	@Test
	void bottomMeanEqualsBottomSumDividedByK() {
		// when k <= totalRows, bottomMean(k) = bottomSum(k) / k
		long[] data = {3, 1, 4, 1, 5, 9, 2, 6};
		var idx = SliceZ.build(data);
		for (int k = 1; k <= data.length; k++) {
			assertEquals((double) idx.bottomSum(k) / k, idx.bottomMean(k), DELTA, "bottomMean consistency at k=" + k);
		}
	}

	@Test
	void bottomMeanKExceedsRowCountAveragesAll() {
		long[] data = {3, 1, 4};
		var idx = SliceZ.build(data);
		// k=100 but only 3 rows: mean of all three = 8/3
		assertEquals((3.0 + 1 + 4) / 3, idx.bottomMean(100), DELTA);
	}

	@Test
	void bottomMeanSingleElement() {
		assertEquals(42.0, SliceZ.build(42L).bottomMean(1), DELTA);
		assertEquals(42.0, SliceZ.build(42L).bottomMean(2), DELTA);
	}

	@Test
	void bottomMeanIsNonDecreasingInK() {
		// Each increment of k adds the next-smallest value to the set; since that
		// value >= mean of the k smallest values already seen, the mean is
		// non-decreasing.
		long[] data = {3, 1, 4, 1, 5, 9, 2, 6};
		var idx = SliceZ.build(data);
		double prev = 0;
		for (int k = 1; k <= data.length; k++) {
			double mean = idx.bottomMean(k);
			assertTrue(mean >= prev - DELTA, "bottomMean should be non-decreasing at k=" + k);
			prev = mean;
		}
	}

	@Test
	void bottomMeanMultiBlock() {
		// values 0..BLOCK_SIZE+9; bottom(5) = {0,1,2,3,4}; mean = 2.0
		var appender = SliceZ.appender();
		LongStream.range(0, BLOCK_SIZE + 10).forEach(appender::add);
		assertEquals(2.0, appender.build().bottomMean(5), DELTA);
	}

	// -------------------------------------------------------------------------
	// Multi-block
	// -------------------------------------------------------------------------

	@ParameterizedTest
	@ValueSource(ints = {0xFFFF, 0x10001, 100_000})
	void meanLessThanOrEqualMultiBlock(int size) {
		var appender = SliceZ.appender();
		LongStream.range(0, size).forEach(appender::add);
		var idx = appender.build();
		long upper = size / 2L;
		// values 0..upper inclusive; mean = upper/2
		assertEquals(upper / 2.0, idx.meanLessThanOrEqual(upper), DELTA);
	}

	@ParameterizedTest
	@ValueSource(ints = {0xFFFF, 0x10001, 100_000})
	void meanBetweenMultiBlock(int size) {
		var appender = SliceZ.appender();
		LongStream.range(0, size).forEach(appender::add);
		var idx = appender.build();
		long lo = BLOCK_SIZE / 2L;
		long hi = BLOCK_SIZE + BLOCK_SIZE / 2L;
		long actualHi = Math.min(size - 1L, hi - 1);
		double expected = actualHi < lo ? 0.0 : (lo + actualHi) / 2.0;
		assertEquals(expected, idx.meanBetween(lo, hi), DELTA);
	}

	// -------------------------------------------------------------------------
	// Cross-check: refMean vs. actual for a range of data shapes
	// -------------------------------------------------------------------------

	@ParameterizedTest
	@MethodSource("testCases")
	void meanCrossCheck(long[] data, long threshold) {
		var idx = buildIdx(data);
		assertEquals(refMean(data, v -> Long.compareUnsigned(v, threshold) < 0), idx.meanLessThan(threshold), DELTA,
				"meanLessThan");
		assertEquals(refMean(data, v -> Long.compareUnsigned(v, threshold) <= 0), idx.meanLessThanOrEqual(threshold),
				DELTA, "meanLessThanOrEqual");
		assertEquals(refMean(data, v -> Long.compareUnsigned(v, threshold) > 0), idx.meanGreaterThan(threshold), DELTA,
				"meanGreaterThan");
		assertEquals(refMean(data, v -> Long.compareUnsigned(v, threshold) >= 0), idx.meanGreaterThanOrEqual(threshold),
				DELTA, "meanGreaterThanOrEqual");
		assertEquals(refMean(data, v -> v == threshold), idx.meanEqual(threshold), DELTA, "meanEqual");
		assertEquals(refMean(data, v -> v != threshold), idx.meanNotEqual(threshold), DELTA, "meanNotEqual");
	}

	@ParameterizedTest
	@MethodSource("testCases")
	void meanInCrossCheck(long[] data, long threshold) {
		var idx = buildIdx(data);
		long[] query = {data[0], threshold, data[data.length - 1]};
		assertEquals(refMean(data, v -> {
			for (long q : query)
				if (v == q)
					return true;
			return false;
		}), idx.meanIn(query), DELTA, "meanIn");
	}

	static Stream<Arguments> testCases() {
		return Stream.of(Arguments.of(new long[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, 5L),
				Arguments.of(new long[]{3, 1, 4, 1, 5, 9, 2, 6}, 4L), Arguments.of(new long[]{0, 0, 0, 1, 2}, 0L),
				// non-zero blockMin
				Arguments.of(new long[]{100, 101, 102, 103, 104}, 102L),
				Arguments.of(new long[]{1000, 2000, 3000, 1500, 2500}, 2000L),
				// contiguous ranges
				Arguments.of(LongStream.range(0, 200).toArray(), 100L),
				Arguments.of(LongStream.range(500, 700).toArray(), 600L),
				// multi-block
				Arguments.of(LongStream.range(0, (long) BLOCK_SIZE + 100).toArray(), (long) (BLOCK_SIZE / 2)));
	}
}
