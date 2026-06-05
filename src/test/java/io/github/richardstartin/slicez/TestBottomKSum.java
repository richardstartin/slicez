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
import java.util.function.DoubleToLongFunction;
import java.util.function.LongToDoubleFunction;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static io.github.richardstartin.slicez.SliceZ.BLOCK_SIZE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SliceZ#bottomSum(int)}, {@link SliceZ#topSum(int)} and their
 * {@link LongToDoubleFunction}-encoded overloads.
 *
 * <p>
 * {@code bottomSum(k)} / {@code topSum(k)} must return the long sum of exactly
 * the same values that {@link SliceZ#bottomValues(int)} /
 * {@link SliceZ#topValues(int)} would yield. The encoded overloads must return
 * the sum of {@code encoding.applyAsDouble(value)} over those same values.
 * Since the values selected are a fixed multiset (tiebreaking among equal
 * boundary values does not affect the sum), the references below are
 * order-independent.
 */
@Execution(ExecutionMode.CONCURRENT)
class TestBottomKSum {

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

	/** Brute-force reference: sort all values by unsigned order, return first k. */
	private static long[] referenceBottomK(long[] data, int k) {
		long[] sorted = sortedUnsigned(data);
		return Arrays.copyOf(sorted, Math.min(k, sorted.length));
	}

	private static long[] referenceTopK(long[] data, int k) {
		long[] sorted = sortedUnsigned(data);
		return Arrays.copyOfRange(sorted, Math.max(sorted.length - k, 0), sorted.length);
	}

	private static long sumLongs(long[] values) {
		long sum = 0L;
		for (long v : values)
			sum += v;
		return sum;
	}

	private static double sumDoubles(long[] values, LongToDoubleFunction encoding) {
		double sum = 0D;
		for (long v : values)
			sum += encoding.applyAsDouble(v);
		return sum;
	}

	private static double sumMagnitude(long[] values, LongToDoubleFunction encoding) {
		double mag = 0D;
		for (long v : values)
			mag += Math.abs(encoding.applyAsDouble(v));
		return mag;
	}

	/**
	 * Tolerance for a naive sequential sum of {@code n} terms whose absolute values
	 * total {@code operandMagnitude}. The forward error of left-to-right summation
	 * is bounded by {@code (n-1) * u * Σ|termᵢ|} where {@code u} is the unit
	 * roundoff; because addition is non-associative, the implementation and the
	 * reference may accumulate the same multiset in different orders, so the
	 * tolerance must scale with the operand magnitudes (which can dwarf the result
	 * under cancellation) rather than the result. A safety factor and a small
	 * absolute floor are added.
	 */
	private static double tolerance(double operandMagnitude, int n) {
		double unitRoundoff = Math.ulp(1.0) / 2; // 2^-53
		return Math.max(1e-9, operandMagnitude * unitRoundoff * Math.max(1, n) * 8);
	}

	private static SliceZ build(long[] data) {
		var appender = SliceZ.appender();
		for (long v : data)
			appender.add(v);
		return appender.build();
	}

	private static void assertBottomSum(long[] data, int k) {
		var idx = build(data);
		long expected = sumLongs(referenceBottomK(data, k));
		assertEquals(expected, idx.bottomSum(k), "bottomSum mismatch for k=" + k);
		// consistency with bottomValues: same multiset of values
		assertEquals(sumLongs(collect(idx.bottomValues(k))), idx.bottomSum(k),
				"bottomSum inconsistent with bottomValues for k=" + k);
	}

	private static void assertTopSum(long[] data, int k) {
		var idx = build(data);
		long expected = sumLongs(referenceTopK(data, k));
		assertEquals(expected, idx.topSum(k), "topSum mismatch for k=" + k);
		assertEquals(sumLongs(collect(idx.topValues(k))), idx.topSum(k),
				"topSum inconsistent with topValues for k=" + k);
	}

	private static void assertBottomSum(long[] data, int k, LongToDoubleFunction encoding) {
		var idx = build(data);
		long[] ref = referenceBottomK(data, k);
		double expected = sumDoubles(ref, encoding);
		double tol = tolerance(sumMagnitude(ref, encoding), ref.length);
		assertEquals(expected, idx.bottomSum(k, encoding), tol, "bottomSum(encoding) mismatch for k=" + k);
		assertEquals(sumDoubles(collect(idx.bottomValues(k)), encoding), idx.bottomSum(k, encoding), tol,
				"bottomSum(encoding) inconsistent with bottomValues for k=" + k);
	}

	private static void assertTopSum(long[] data, int k, LongToDoubleFunction encoding) {
		var idx = build(data);
		long[] ref = referenceTopK(data, k);
		double expected = sumDoubles(ref, encoding);
		double tol = tolerance(sumMagnitude(ref, encoding), ref.length);
		assertEquals(expected, idx.topSum(k, encoding), tol, "topSum(encoding) mismatch for k=" + k);
		assertEquals(sumDoubles(collect(idx.topValues(k)), encoding), idx.topSum(k, encoding), tol,
				"topSum(encoding) inconsistent with topValues for k=" + k);
	}

	// A couple of arbitrary value->double encodings to exercise the encoded
	// overloads.
	private static final LongToDoubleFunction IDENTITY = v -> (double) v;
	private static final LongToDoubleFunction AFFINE = v -> v * 0.25 + 1.0;

	// -------------------------------------------------------------------------
	// Contract / edge cases
	// -------------------------------------------------------------------------

	@Test
	void bottomZeroIsZero() {
		var idx = build(new long[]{3, 1, 4, 1, 5});
		assertEquals(0L, idx.bottomSum(0));
		assertEquals(0D, idx.bottomSum(0, IDENTITY));
	}

	@Test
	void topZeroIsZero() {
		var idx = build(new long[]{3, 1, 4, 1, 5});
		assertEquals(0L, idx.topSum(0));
		assertEquals(0D, idx.topSum(0, IDENTITY));
	}

	@Test
	void bottomNegativeKThrows() {
		var idx = SliceZ.build(1, 2, 3);
		assertThrows(IllegalArgumentException.class, () -> idx.bottomSum(-1));
		assertThrows(IllegalArgumentException.class, () -> idx.bottomSum(-1, IDENTITY));
	}

	@Test
	void topNegativeKThrows() {
		var idx = SliceZ.build(1, 2, 3);
		assertThrows(IllegalArgumentException.class, () -> idx.topSum(-1));
		assertThrows(IllegalArgumentException.class, () -> idx.topSum(-1, IDENTITY));
	}

	@Test
	void bottomEmptyIndexIsZero() {
		var idx = SliceZ.build();
		assertEquals(0L, idx.bottomSum(0));
		assertEquals(0L, idx.bottomSum(1));
		assertEquals(0L, idx.bottomSum(1000));
		assertEquals(0D, idx.bottomSum(1000, IDENTITY));
	}

	@Test
	void topEmptyIndexIsZero() {
		var idx = SliceZ.build();
		assertEquals(0L, idx.topSum(0));
		assertEquals(0L, idx.topSum(1));
		assertEquals(0L, idx.topSum(1000));
		assertEquals(0D, idx.topSum(1000, IDENTITY));
	}

	@Test
	void bottomSingleElement() {
		assertBottomSum(new long[]{42L}, 1);
		assertBottomSum(new long[]{42L}, 2);
		assertBottomSum(new long[]{42L}, 1, AFFINE);
	}

	@Test
	void topSingleElement() {
		assertTopSum(new long[]{42L}, 1);
		assertTopSum(new long[]{42L}, 2);
		assertTopSum(new long[]{42L}, 1, AFFINE);
	}

	@Test
	void bottomKExceedsRowCountSumsAll() {
		long[] data = {3, 1, 4};
		assertBottomSum(data, 100);
		assertBottomSum(data, 100, AFFINE);
	}

	@Test
	void topKExceedsRowCountSumsAll() {
		long[] data = {3, 1, 4};
		assertTopSum(data, 100);
		assertTopSum(data, 100, AFFINE);
	}

	// -------------------------------------------------------------------------
	// Sum-specific value checks
	// -------------------------------------------------------------------------

	@Test
	void bottomSumKnownValues() {
		// sorted: [1, 1, 2, 3, 4, 5, 6, 9]; bottom(3) -> 1+1+2 = 4
		assertEquals(4L, build(new long[]{3, 1, 4, 1, 5, 9, 2, 6}).bottomSum(3));
	}

	@Test
	void topSumKnownValues() {
		// sorted: [1, 1, 2, 3, 4, 5, 6, 9]; top(3) -> 9+6+5 = 20
		assertEquals(20L, build(new long[]{3, 1, 4, 1, 5, 9, 2, 6}).topSum(3));
	}

	@Test
	void bottomSumPlusTopSumCoversTotalWhenKPartitions() {
		// For disjoint partition (no shared boundary rows), bottomSum(k)+topSum(n-k) ==
		// total.
		long[] data = LongStream.range(0, 100).toArray(); // all distinct
		var idx = build(data);
		long total = sumLongs(data);
		for (int k = 0; k <= data.length; k++) {
			assertEquals(total, idx.bottomSum(k) + idx.topSum(data.length - k), "partition mismatch at k=" + k);
		}
	}

	@Test
	void sumIsMonotoneAcrossK() {
		// bottomSum(k) for increasing k adds the next-smallest value each step.
		long[] data = {3, 1, 4, 1, 5, 9, 2, 6};
		var idx = build(data);
		long[] sorted = sortedUnsigned(data);
		long running = 0L;
		for (int k = 1; k <= data.length; k++) {
			running += sorted[k - 1];
			assertEquals(running, idx.bottomSum(k), "running bottom sum mismatch at k=" + k);
		}
		running = 0L;
		for (int k = 1; k <= data.length; k++) {
			running += sorted[sorted.length - k];
			assertEquals(running, idx.topSum(k), "running top sum mismatch at k=" + k);
		}
	}

	// -------------------------------------------------------------------------
	// Duplicates and ties
	// -------------------------------------------------------------------------

	@Test
	void bottomAllSameValue() {
		assertBottomSum(new long[]{7, 7, 7, 7, 7}, 3);
		assertBottomSum(new long[]{7, 7, 7, 7, 7}, 5);
		assertBottomSum(new long[]{7, 7, 7, 7, 7}, 10);
	}

	@Test
	void topAllSameValue() {
		assertTopSum(new long[]{7, 7, 7, 7, 7}, 3);
		assertTopSum(new long[]{7, 7, 7, 7, 7}, 5);
		assertTopSum(new long[]{7, 7, 7, 7, 7}, 10);
	}

	@Test
	void bottomDuplicatesAtBoundary() {
		assertBottomSum(new long[]{1, 2, 3, 3, 3, 4}, 4);
	}

	@Test
	void topDuplicatesAtBoundary() {
		assertTopSum(new long[]{1, 2, 3, 3, 3, 4}, 4);
	}

	// -------------------------------------------------------------------------
	// Unsigned semantics
	// -------------------------------------------------------------------------

	@Test
	void bottomUnsignedOrder() {
		// unsigned: 0 < Long.MAX_VALUE < Long.MIN_VALUE < -1L
		long[] data = {0L, Long.MAX_VALUE, Long.MIN_VALUE, -1L};
		for (int k = 1; k <= 4; k++)
			assertBottomSum(data, k);
	}

	@Test
	void topUnsignedOrder() {
		long[] data = {0L, Long.MAX_VALUE, Long.MIN_VALUE, -1L};
		for (int k = 1; k <= 4; k++)
			assertTopSum(data, k);
	}

	@Test
	void topSumWrapsLikeReferenceOnOverflow() {
		// top(2) selects the two unsigned-largest: -1L and -2L; their long sum wraps.
		long[] data = {0L, 1L, -2L, -1L};
		assertEquals((-1L) + (-2L), build(data).topSum(2));
		assertTopSum(data, 2);
	}

	// -------------------------------------------------------------------------
	// Encoded overloads
	// -------------------------------------------------------------------------

	@Test
	void bottomSumWithEncoding() {
		long[] data = {3, 1, 4, 1, 5, 9, 2, 6};
		for (int k = 1; k <= data.length; k++) {
			assertBottomSum(data, k, IDENTITY);
			assertBottomSum(data, k, AFFINE);
		}
	}

	@Test
	void topSumWithEncoding() {
		long[] data = {3, 1, 4, 1, 5, 9, 2, 6};
		for (int k = 1; k <= data.length; k++) {
			assertTopSum(data, k, IDENTITY);
			assertTopSum(data, k, AFFINE);
		}
	}

	/**
	 * Realistic use case: doubles encoded to order-preserving sortable longs, then
	 * the sum of the decoded top/bottom-k doubles is recovered via the encoding
	 * overload.
	 */
	@Test
	void sumOfDecodedDoubles() {
		double[] doubles = {-1000.5, -3.25, -0.0, 0.0, 1.5, 2.75, 42.0, 1234.5, 9999.875};
		long[] encoded = Arrays.stream(doubles).mapToLong(DOUBLE_ENCODER::applyAsLong).toArray();
		var idx = build(encoded);
		double[] sortedDoubles = Arrays.stream(doubles).sorted().toArray();
		for (int k = 1; k <= doubles.length; k++) {
			double expectedBottom = 0D, expectedTop = 0D, magBottom = 0D, magTop = 0D;
			for (int i = 0; i < k; i++) {
				expectedBottom += sortedDoubles[i];
				expectedTop += sortedDoubles[sortedDoubles.length - 1 - i];
				magBottom += Math.abs(sortedDoubles[i]);
				magTop += Math.abs(sortedDoubles[sortedDoubles.length - 1 - i]);
			}
			assertEquals(expectedBottom, idx.bottomSum(k, DOUBLE_DECODER), tolerance(magBottom, k),
					"decoded bottom sum mismatch at k=" + k);
			assertEquals(expectedTop, idx.topSum(k, DOUBLE_DECODER), tolerance(magTop, k),
					"decoded top sum mismatch at k=" + k);
		}
	}

	// -------------------------------------------------------------------------
	// Multi-block
	// -------------------------------------------------------------------------

	@Test
	void bottomMultiBlock() {
		assertBottomSum(LongStream.range(0, BLOCK_SIZE + 10).toArray(), 5);
		assertBottomSum(LongStream.range(0, BLOCK_SIZE + 10).toArray(), BLOCK_SIZE + 5);
	}

	@Test
	void topMultiBlock() {
		assertTopSum(LongStream.range(0, BLOCK_SIZE + 10).toArray(), 5);
		assertTopSum(LongStream.range(0, BLOCK_SIZE + 10).toArray(), BLOCK_SIZE + 5);
	}

	@Test
	void bottomMultiBlockMinInLaterBlock() {
		long[] data = new long[BLOCK_SIZE + 1];
		for (int i = 0; i < BLOCK_SIZE; i++)
			data[i] = 101 + i;
		data[BLOCK_SIZE] = 1L;
		assertBottomSum(data, 3);
	}

	@Test
	void topMultiBlockMaxInLaterBlock() {
		long[] data = new long[BLOCK_SIZE + 1];
		for (int i = 0; i < BLOCK_SIZE; i++)
			data[i] = i + 1;
		data[BLOCK_SIZE] = BLOCK_SIZE + 1000L;
		assertTopSum(data, 3);
	}

	@Test
	void bottomMultiBlockAllSameValueFullSlices() {
		long[] data = new long[BLOCK_SIZE + 1];
		Arrays.fill(data, 42L);
		assertBottomSum(data, BLOCK_SIZE);
		assertBottomSum(data, BLOCK_SIZE + 1);
	}

	@Test
	void topMultiBlockAllSameValueFullSlices() {
		long[] data = new long[BLOCK_SIZE + 1];
		Arrays.fill(data, 42L);
		assertTopSum(data, BLOCK_SIZE);
		assertTopSum(data, BLOCK_SIZE + 1);
	}

	@ParameterizedTest
	@ValueSource(ints = {0xFFFF, 0x10001, 100_000})
	void bottomMultiBlockKEdges(int size) {
		long[] data = LongStream.range(0, size).toArray();
		assertBottomSum(data, 1);
		assertBottomSum(data, size);
	}

	@ParameterizedTest
	@ValueSource(ints = {0xFFFF, 0x10001, 100_000})
	void topMultiBlockKEdges(int size) {
		long[] data = LongStream.range(0, size).toArray();
		assertTopSum(data, 1);
		assertTopSum(data, size);
	}

	// -------------------------------------------------------------------------
	// Cross-check against brute-force reference
	// -------------------------------------------------------------------------

	@ParameterizedTest
	@MethodSource("distributions")
	void bottomCrossCheck(long[] data, int k) {
		assertBottomSum(data, k);
		assertBottomSum(data, k, AFFINE);
	}

	@ParameterizedTest
	@MethodSource("distributions")
	void topCrossCheck(long[] data, int k) {
		assertTopSum(data, k);
		assertTopSum(data, k, AFFINE);
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

	// -------------------------------------------------------------------------
	// Order-preserving double <-> sortable long codec (mirrors TestSliceZ)
	// -------------------------------------------------------------------------

	private static final DoubleToLongFunction DOUBLE_ENCODER = value -> {
		if (value == Double.NEGATIVE_INFINITY)
			return 0;
		if (value == Double.POSITIVE_INFINITY || Double.isNaN(value))
			return 0xFFFFFFFFFFFFFFFFL;
		long bits = Double.doubleToLongBits(value);
		if ((bits & Long.MIN_VALUE) == Long.MIN_VALUE) {
			bits = bits == Long.MIN_VALUE ? Long.MIN_VALUE : ~bits;
		} else {
			bits ^= Long.MIN_VALUE;
		}
		return bits;
	};

	private static final LongToDoubleFunction DOUBLE_DECODER = encoded -> {
		if ((encoded & Long.MIN_VALUE) == Long.MIN_VALUE) {
			return Double.longBitsToDouble(encoded ^ Long.MIN_VALUE);
		}
		return Double.longBitsToDouble(~encoded);
	};
}
