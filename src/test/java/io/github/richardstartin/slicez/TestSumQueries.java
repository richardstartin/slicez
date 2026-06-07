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
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for the {@code sum*} family of methods on {@link SliceZ}.
 *
 * <p>
 * The reference for every assertion is {@link #refSum}: sum {@code (double) v}
 * over all indexed values matching the predicate, using unsigned comparisons
 * where applicable. Data sets with a non-zero {@code blockMin} are deliberately
 * included to exercise the per-block offset arithmetic.
 */
@Execution(ExecutionMode.CONCURRENT)
class TestSumQueries {

	/** Brute-force reference: cast each matching value to double and accumulate. */
	private static double refSum(long[] data, LongPredicate pred) {
		double sum = 0.0;
		for (long v : data)
			if (pred.test(v))
				sum += v;
		return sum;
	}

	private static SliceZ buildIdx(long... data) {
		var appender = SliceZ.appender();
		for (long v : data)
			appender.add(v);
		return appender.build();
	}

	private static final double DELTA = 1e-9;

	// -------------------------------------------------------------------------
	// sumLessThan / sumLessThanOrEqual
	// -------------------------------------------------------------------------

	@Test
	void sumLessThanZeroIsZero() {
		assertEquals(0.0, buildIdx(1, 2, 3).sumLessThan(0), DELTA);
	}

	@Test
	void sumLessThanBasic() {
		assertEquals(0.0 + 1 + 2, buildIdx(0, 1, 2, 3, 4).sumLessThan(3), DELTA);
	}

	@Test
	void sumLessThanOrEqualBasic() {
		assertEquals(0.0 + 1 + 2 + 3, buildIdx(0, 1, 2, 3, 4).sumLessThanOrEqual(3), DELTA);
	}

	@Test
	void sumLessThanNoneMatch() {
		assertEquals(0.0, buildIdx(5, 6, 7).sumLessThan(5), DELTA);
	}

	@Test
	void sumLessThanAllMatch() {
		assertEquals(0.0 + 1 + 2 + 3 + 4, buildIdx(0, 1, 2, 3, 4).sumLessThan(100), DELTA);
	}

	// -------------------------------------------------------------------------
	// sumGreaterThan / sumGreaterThanOrEqual
	// -------------------------------------------------------------------------

	@Test
	void sumGreaterThanBasic() {
		assertEquals(3.0 + 4, buildIdx(0, 1, 2, 3, 4).sumGreaterThan(2), DELTA);
	}

	@Test
	void sumGreaterThanOrEqualBasic() {
		assertEquals(2.0 + 3 + 4, buildIdx(0, 1, 2, 3, 4).sumGreaterThanOrEqual(2), DELTA);
	}

	@Test
	void sumGreaterThanNoneMatch() {
		assertEquals(0.0, buildIdx(0, 1, 2).sumGreaterThan(2), DELTA);
	}

	@Test
	void sumGreaterThanOrEqualZeroReturnsTotal() {
		assertEquals(3.0 + 1 + 4 + 1 + 5, buildIdx(3, 1, 4, 1, 5).sumGreaterThanOrEqual(0), DELTA);
	}

	// -------------------------------------------------------------------------
	// lt + gte = total; lte + gt = total
	// -------------------------------------------------------------------------

	@Test
	void sumComplementsAreConsistent() {
		long[] data = {0, 1, 2, 3, 4};
		var idx = buildIdx(data);
		double total = refSum(data, v -> true);
		for (long v = 0; v <= 5; v++) {
			assertEquals(total, idx.sumLessThan(v) + idx.sumGreaterThanOrEqual(v), DELTA,
					"lt + gte mismatch at v=" + v);
			assertEquals(total, idx.sumLessThanOrEqual(v) + idx.sumGreaterThan(v), DELTA,
					"lte + gt mismatch at v=" + v);
		}
	}

	// -------------------------------------------------------------------------
	// sumEqual / sumNotEqual
	// -------------------------------------------------------------------------

	@Test
	void sumEqualBasic() {
		assertEquals(2.0, buildIdx(0, 1, 2, 3, 4).sumEqual(2), DELTA);
	}

	@Test
	void sumEqualAbsentValue() {
		assertEquals(0.0, buildIdx(0, 1, 2, 3, 4).sumEqual(99), DELTA);
	}

	@Test
	void sumEqualWithDuplicates() {
		assertEquals(9.0, buildIdx(3, 3, 3, 1, 2).sumEqual(3), DELTA);
	}

	@Test
	void sumEqualAllSameNonZeroBlockMin() {
		// All values equal blockMin: all slices FULL, stored-slices loop never
		// executes.
		// blockMin contribution (3 * 7 = 21) must still be included.
		assertEquals(21.0, buildIdx(7, 7, 7).sumEqual(7), DELTA);
	}

	@Test
	void sumNotEqualBasic() {
		assertEquals(0.0 + 1 + 3 + 4, buildIdx(0, 1, 2, 3, 4).sumNotEqual(2), DELTA);
	}

	@Test
	void sumNotEqualAbsentValue() {
		assertEquals(0.0 + 1 + 2 + 3 + 4, buildIdx(0, 1, 2, 3, 4).sumNotEqual(99), DELTA);
	}

	@Test
	void sumNotEqualAllSameNonZeroBlockMin() {
		// notEqual(0) on {7,7,7}: all rows match; blockMin=7, all slices FULL.
		assertEquals(21.0, buildIdx(7, 7, 7).sumNotEqual(0), DELTA);
	}

	@Test
	void sumEqualPlusSumNotEqualEqualsTotal() {
		long[] data = {3, 1, 4, 1, 5, 9, 2, 6};
		var idx = buildIdx(data);
		double total = refSum(data, v -> true);
		for (long v : new long[]{1, 3, 5, 9, 99}) {
			assertEquals(total, idx.sumEqual(v) + idx.sumNotEqual(v), DELTA, "partition failed at v=" + v);
		}
	}

	// -------------------------------------------------------------------------
	// sumBetween
	// -------------------------------------------------------------------------

	@Test
	void sumBetweenBasic() {
		// between(1, 4) = 1 <= v < 4
		assertEquals(1.0 + 2 + 3, buildIdx(0, 1, 2, 3, 4).sumBetween(1, 4), DELTA);
	}

	@Test
	void sumBetweenLowerZeroDelegatesToLessThan() {
		// between(0, 3) behaves like lessThan(3)
		assertEquals(0.0 + 1 + 2, buildIdx(0, 1, 2, 3, 4).sumBetween(0, 3), DELTA);
	}

	@Test
	void sumBetweenEmptyRange() {
		var idx = buildIdx(0, 1, 2, 3, 4);
		assertEquals(0.0, idx.sumBetween(2, 2), DELTA);
		assertEquals(0.0, idx.sumBetween(3, 1), DELTA);
	}

	@Test
	void sumBetweenNonZeroBlockMin() {
		// blockMin=5; validates that blockMin is included exactly once
		long[] data = {5, 6, 7, 8, 9};
		var idx = buildIdx(data);
		assertEquals(6.0 + 7 + 8, idx.sumBetween(6, 9), DELTA);
		assertEquals(5.0 + 6 + 7 + 8 + 9, idx.sumBetween(5, 10), DELTA);
	}

	@Test
	void sumBetweenUpperZeroIsEmpty() {
		// between(lower, 0) means lower <= v < 0, which is always empty in unsigned
		// order. sumBetween only special-cases lower == 0; when upper == 0 it computes
		// BetweenQuery(lower - 1, upper - 1) where upper - 1 underflows to -1L
		// (unsigned
		// max), turning the query into "everything >= lower".
		long[] data = {0, 1, 2, 3, 4, 5, 6, 7};
		var idx = buildIdx(data);
		assertEquals(refSum(data, v -> Long.compareUnsigned(v, 5) >= 0 && Long.compareUnsigned(v, 0) < 0),
				idx.sumBetween(5, 0), DELTA,
				"sumBetween with upper == 0 should be empty, not the sum of all v >= lower");
	}

	@Test
	void sumBlockMinBit63UnsignedOverflow() {
		// Four identical values of 2^63: blockMin = 2^63 and every slice is FULL, so
		// the whole sum comes from `(double) blockMin * matchingCount` (line 1261).
		// blockMin has bit 63 set, so the signed (double) cast reads it as
		// Long.MIN_VALUE (negative) instead of its true unsigned magnitude 2^63,
		// yielding a large negative sum instead of a positive one.
		long v = 1L << 63; // 0x8000_0000_0000_0000, unsigned 2^63
		long[] data = {v, v, v, v};
		var idx = buildIdx(data);
		// 2^63 is exactly representable as a double; unsigned expected = 4 * 2^63
		double expected = 4 * 0x1p63;
		assertEquals(expected, idx.sumGreaterThanOrEqual(0), 1.0,
				"(double) blockMin treated bit-63 value as signed/negative");
	}

	// -------------------------------------------------------------------------
	// sumIn / countIn
	// -------------------------------------------------------------------------

	@Test
	void sumInEmpty() {
		var idx = buildIdx(0, 1, 2, 3, 4);
		assertEquals(0.0, idx.sumIn(), DELTA);
		assertEquals(0, idx.countIn());
	}

	@Test
	void sumInSingleValue() {
		var idx = buildIdx(0, 1, 2, 3, 4);
		assertEquals(2.0, idx.sumIn(2), DELTA);
		assertEquals(1, idx.countIn(2));
		assertEquals(0.0, idx.sumIn(99), DELTA);
		assertEquals(0, idx.countIn(99));
	}

	@Test
	void sumInMultipleValues() {
		var idx = buildIdx(0, 1, 2, 3, 4);
		assertEquals(1.0 + 3, idx.sumIn(1, 3), DELTA);
		assertEquals(2, idx.countIn(1, 3));
	}

	@Test
	void sumInAllAbsent() {
		var idx = buildIdx(0, 1, 2, 3, 4);
		assertEquals(0.0, idx.sumIn(10, 20, 30), DELTA);
		assertEquals(0, idx.countIn(10, 20, 30));
	}

	@Test
	void sumInDuplicateIndexedValues() {
		long[] data = {3, 3, 3, 1, 2};
		var idx = buildIdx(data);
		// in(3, 1): three 3s (sum=9) plus one 1 (sum=1) = 10
		assertEquals(10.0, idx.sumIn(3, 1), DELTA);
		assertEquals(4, idx.countIn(3, 1));
	}

	@Test
	void sumInDuplicateQueryValues() {
		var idx = buildIdx(0, 1, 2, 3, 4);
		// querying same value twice must not double-count
		assertEquals(idx.sumEqual(2), idx.sumIn(2, 2), DELTA);
		assertEquals(idx.countEqual(2), idx.countIn(2, 2));
	}

	@Test
	void sumInConsistentWithSumEqual() {
		var idx = buildIdx(0, 1, 2, 3, 4);
		for (long v = 0; v <= 4; v++) {
			assertEquals(idx.sumEqual(v), idx.sumIn(v), DELTA, "sumIn vs sumEqual at v=" + v);
			assertEquals(idx.countEqual(v), idx.countIn(v), "countIn vs countEqual at v=" + v);
		}
	}

	@Test
	void sumInOrderIndependence() {
		var idx = buildIdx(0, 1, 2, 3, 4);
		assertEquals(idx.sumIn(1, 3), idx.sumIn(3, 1), DELTA);
		assertEquals(idx.countIn(1, 3), idx.countIn(3, 1));
		assertEquals(idx.sumIn(0, 2, 4), idx.sumIn(4, 2, 0), DELTA);
	}

	@Test
	void sumInEmptyIndex() {
		var idx = SliceZ.build();
		assertEquals(0.0, idx.sumIn(1, 2, 3), DELTA);
		assertEquals(0, idx.countIn(1, 2, 3));
	}

	// -------------------------------------------------------------------------
	// Empty index: every sum method must return 0
	// -------------------------------------------------------------------------

	@Test
	void emptyIndexAllSumsZero() {
		var idx = SliceZ.build();
		assertEquals(0.0, idx.sumLessThan(5), DELTA);
		assertEquals(0.0, idx.sumLessThanOrEqual(5), DELTA);
		assertEquals(0.0, idx.sumGreaterThan(0), DELTA);
		assertEquals(0.0, idx.sumGreaterThanOrEqual(0), DELTA);
		assertEquals(0.0, idx.sumEqual(5), DELTA);
		assertEquals(0.0, idx.sumNotEqual(5), DELTA);
		assertEquals(0.0, idx.sumBetween(0, 10), DELTA);
		assertEquals(0.0, idx.sumIn(5), DELTA);
	}

	// -------------------------------------------------------------------------
	// Non-zero blockMin: blockMin contribution must be added exactly once per block
	// -------------------------------------------------------------------------

	@Test
	void sumWithNonZeroBlockMin() {
		long[] data = {100, 101, 102, 103, 104};
		var idx = buildIdx(data);
		assertEquals(refSum(data, v -> v < 103), idx.sumLessThan(103), DELTA);
		assertEquals(refSum(data, v -> v <= 102), idx.sumLessThanOrEqual(102), DELTA);
		assertEquals(refSum(data, v -> v > 101), idx.sumGreaterThan(101), DELTA);
		assertEquals(refSum(data, v -> v >= 102), idx.sumGreaterThanOrEqual(102), DELTA);
		assertEquals(refSum(data, v -> v == 101), idx.sumEqual(101), DELTA);
		assertEquals(refSum(data, v -> v != 101), idx.sumNotEqual(101), DELTA);
		assertEquals(refSum(data, v -> v >= 101 && v < 104), idx.sumBetween(101, 104), DELTA);
		assertEquals(refSum(data, v -> v == 100 || v == 103), idx.sumIn(100, 103), DELTA);
	}

	@Test
	void sumAllSameNonZeroValue() {
		// All values = 7, so blockMin = 7 and all slices are FULL.
		// The stored-slices loop never runs; blockMin * count must still be returned.
		long[] data = {7, 7, 7};
		var idx = buildIdx(data);
		assertEquals(21.0, idx.sumEqual(7), DELTA);
		assertEquals(0.0, idx.sumEqual(0), DELTA);
		assertEquals(21.0, idx.sumNotEqual(0), DELTA);
		assertEquals(21.0, idx.sumLessThanOrEqual(7), DELTA);
		assertEquals(0.0, idx.sumLessThan(7), DELTA);
		assertEquals(0.0, idx.sumGreaterThan(7), DELTA);
		assertEquals(21.0, idx.sumGreaterThanOrEqual(7), DELTA);
		assertEquals(21.0, idx.sumBetween(7, 8), DELTA);
		assertEquals(21.0, idx.sumIn(7), DELTA);
	}

	// -------------------------------------------------------------------------
	// High bit positions: per-slice contribution must not overflow long
	// -------------------------------------------------------------------------

	@Test
	void sumHighBitSliceDoesNotOverflow() {
		// Three rows whose relative value is 2^62 -> the bit-62 slice has
		// cardinality 3. sum() computes `cardinality * (1L << bit)` in long
		// arithmetic, so 3 * 2^62 overflows long and wraps negative before being
		// added to the double accumulator. The leading 0 keeps bit 62 a stored
		// (non-FULL) slice and forces blockMin = 0.
		long hi = 1L << 62; // 4611686018427387904, positive as a signed long
		long[] data = {0, hi, hi, hi};
		var idx = buildIdx(data);
		// matches the three hi rows; expected = 3 * 2^62 = 1.3835058e19
		assertEquals(refSum(data, v -> v > 0), idx.sumGreaterThan(0), 1.0,
				"high-bit slice contribution overflowed long before widening to double");
	}

	@Test
	void sumBlockMinDoesNotOverflow() {
		// 16 identical large values: blockMin = 2^60 and every slice is FULL, so
		// the entire sum comes from the `blockMin * matchingCount` term in
		// BaseQuery.sum(). That term is computed in long arithmetic:
		// 2^60 * 16 = 2^64 wraps to exactly 0L, so sum() returns 0.0 instead of
		// 16 * 2^60. (sumEqual is unaffected: it shortcuts to countEqual * value.)
		long v = 1L << 60; // 1152921504606846976, positive as a signed long
		long[] data = new long[16];
		java.util.Arrays.fill(data, v);
		var idx = buildIdx(data);
		// sumGreaterThanOrEqual(0) matches every row; expected = 16 * 2^60 =
		// 1.8446744e19
		assertEquals(refSum(data, x -> true), idx.sumGreaterThanOrEqual(0), 1.0,
				"blockMin * matchingCount overflowed long before widening to double");
	}

	// -------------------------------------------------------------------------
	// Multi-block
	// -------------------------------------------------------------------------

	@ParameterizedTest
	@ValueSource(ints = {0xFFFF, 0x10001, 100_000})
	void sumLessThanOrEqualMultiBlock(int size) {
		var appender = SliceZ.appender();
		LongStream.range(0, size).forEach(appender::add);
		var idx = appender.build();
		long upper = size / 2L;
		// sum 0..upper inclusive = upper*(upper+1)/2
		assertEquals((double) upper * (upper + 1) / 2, idx.sumLessThanOrEqual(upper), DELTA);
	}

	@ParameterizedTest
	@ValueSource(ints = {0xFFFF, 0x10001, 100_000})
	void sumBetweenMultiBlock(int size) {
		var appender = SliceZ.appender();
		LongStream.range(0, size).forEach(appender::add);
		var idx = appender.build();
		long lo = BLOCK_SIZE / 2L;
		long hi = BLOCK_SIZE + BLOCK_SIZE / 2L;
		// clamp to actual data range; sum of lo..actualHi
		long actualHi = Math.min(size - 1L, hi - 1);
		double expected = actualHi < lo ? 0.0 : (double) (actualHi - lo + 1) * (lo + actualHi) / 2;
		assertEquals(expected, idx.sumBetween(lo, hi), DELTA);
	}

	// -------------------------------------------------------------------------
	// Cross-check: refSum vs. actual for a range of data shapes
	// -------------------------------------------------------------------------

	@ParameterizedTest
	@MethodSource("testCases")
	void sumCrossCheck(long[] data, long threshold) {
		var idx = buildIdx(data);
		assertEquals(refSum(data, v -> Long.compareUnsigned(v, threshold) < 0), idx.sumLessThan(threshold), DELTA,
				"sumLessThan");
		assertEquals(refSum(data, v -> Long.compareUnsigned(v, threshold) <= 0), idx.sumLessThanOrEqual(threshold),
				DELTA, "sumLessThanOrEqual");
		assertEquals(refSum(data, v -> Long.compareUnsigned(v, threshold) > 0), idx.sumGreaterThan(threshold), DELTA,
				"sumGreaterThan");
		assertEquals(refSum(data, v -> Long.compareUnsigned(v, threshold) >= 0), idx.sumGreaterThanOrEqual(threshold),
				DELTA, "sumGreaterThanOrEqual");
		assertEquals(refSum(data, v -> v == threshold), idx.sumEqual(threshold), DELTA, "sumEqual");
		assertEquals(refSum(data, v -> v != threshold), idx.sumNotEqual(threshold), DELTA, "sumNotEqual");
	}

	@ParameterizedTest
	@MethodSource("testCases")
	void sumInCrossCheck(long[] data, long threshold) {
		var idx = buildIdx(data);
		long[] query = {data[0], threshold, data[data.length - 1]};
		double expected = refSum(data, v -> {
			for (long q : query)
				if (v == q)
					return true;
			return false;
		});
		int expectedCount = 0;
		for (long v : data) {
			for (long q : query) {
				if (v == q) {
					expectedCount++;
					break;
				}
			}
		}
		assertEquals(expected, idx.sumIn(query), DELTA);
		assertEquals(expectedCount, idx.countIn(query));
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
