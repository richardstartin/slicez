package io.github.richardstartin.slicez;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.PrimitiveIterator;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.function.LongPredicate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exercises the {@link BlockIterator}-filtered query overloads, using
 * {@link BlockedBitmap#blockIterator()}, {@link BlockedBitmap#and} and
 * {@link BlockedBitmap#or} as the source of the filter.
 *
 * <p>
 * A filtered query must keep a row only when the query condition holds for its
 * value <em>and</em> the row id is present in the filter. For example, over the
 * data {@code {1, 2, 2}} with a filter containing only row {@code 1},
 * {@code countEqual(2, filter)} is {@code 1}: row 2 also holds value 2 but is
 * excluded by the filter.
 */
@Execution(ExecutionMode.CONCURRENT)
class TestSliceZBlockFilter {

	private static final int BLOCK = 1 << 16;

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

	private static int[] collect(PrimitiveIterator.OfInt it) {
		var out = new ArrayList<Integer>();
		while (it.hasNext()) {
			out.add(it.nextInt());
		}
		return out.stream().mapToInt(Integer::intValue).toArray();
	}

	/**
	 * The row ids whose value satisfies {@code cond} and which are present in
	 * {@code allowed}.
	 */
	private static int[] rowsWhere(long[] values, LongPredicate cond, TreeSet<Integer> allowed) {
		var out = new ArrayList<Integer>();
		for (int r = 0; r < values.length; r++) {
			if (allowed.contains(r) && cond.test(values[r])) {
				out.add(r);
			}
		}
		return out.stream().mapToInt(Integer::intValue).toArray();
	}

	/**
	 * Asserts the four query flavours (matching ids, count, sum, mean) all agree
	 * with the rows the condition keeps once constrained to {@code allowed}.
	 */
	private static void assertQuery(long[] values, TreeSet<Integer> allowed, LongPredicate cond,
			PrimitiveIterator.OfInt ids, int count, double sum, double mean) {
		int[] rows = rowsWhere(values, cond, allowed);
		double expectedSum = 0;
		for (int r : rows) {
			expectedSum += values[r];
		}
		double expectedMean = rows.length == 0 ? 0 : expectedSum / rows.length;
		assertArrayEquals(rows, collect(ids), "matching row ids");
		assertEquals(rows.length, count, "count");
		assertEquals(expectedSum, sum, 1e-9, "sum");
		assertEquals(expectedMean, mean, 1e-9, "mean");
	}

	// -------------------------------------------------------------------------
	// blockIterator() as the filter
	// -------------------------------------------------------------------------

	@Test
	void specExample() {
		// data {1, 2, 2}, filter {1}: only row 1 (value 2) survives
		var idx = SliceZ.build(1, 2, 2);
		assertEquals(1, idx.countEqual(2, bitmap(1).blockIterator()));
		assertArrayEquals(new int[]{1}, collect(idx.equal(2, bitmap(1).blockIterator())));
	}

	@Test
	void equalWithBlockIteratorFilter() {
		long[] values = {1, 2, 2, 3, 2};
		var idx = SliceZ.build(values);
		var filter = bitmap(1, 3, 4);
		var allowed = rowSet(1, 3, 4);
		int[] rows = rowsWhere(values, v -> v == 2, allowed);
		assertArrayEquals(rows, collect(idx.equal(2, filter.blockIterator())));
		assertEquals(rows.length, idx.countEqual(2, filter.blockIterator()));
	}

	@Test
	void notEqualWithBlockIteratorFilter() {
		long[] values = {1, 2, 2, 3, 2};
		var idx = SliceZ.build(values);
		var filter = bitmap(0, 2, 3);
		var allowed = rowSet(0, 2, 3);
		assertQuery(values, allowed, v -> v != 2, idx.notEqual(2, filter.blockIterator()),
				idx.countNotEqual(2, filter.blockIterator()), idx.sumNotEqual(2, filter.blockIterator()),
				idx.meanNotEqual(2, filter.blockIterator()));
	}

	@Test
	void lessThanOrEqualWithBlockIteratorFilter() {
		long[] values = {0, 1, 2, 3, 4, 5, 6, 7};
		var idx = SliceZ.build(values);
		var filter = bitmap(1, 2, 5, 7);
		var allowed = rowSet(1, 2, 5, 7);
		assertQuery(values, allowed, v -> Long.compareUnsigned(v, 4) <= 0,
				idx.lessThanOrEqual(4, filter.blockIterator()), idx.countLessThanOrEqual(4, filter.blockIterator()),
				idx.sumLessThanOrEqual(4, filter.blockIterator()), idx.meanLessThanOrEqual(4, filter.blockIterator()));
	}

	@Test
	void greaterThanWithBlockIteratorFilter() {
		long[] values = {0, 1, 2, 3, 4, 5, 6, 7};
		var idx = SliceZ.build(values);
		var filter = bitmap(1, 2, 5, 7);
		var allowed = rowSet(1, 2, 5, 7);
		assertQuery(values, allowed, v -> Long.compareUnsigned(v, 4) > 0, idx.greaterThan(4, filter.blockIterator()),
				idx.countGreaterThan(4, filter.blockIterator()), idx.sumGreaterThan(4, filter.blockIterator()),
				idx.meanGreaterThan(4, filter.blockIterator()));
	}

	@Test
	void betweenWithBlockIteratorFilter() {
		long[] values = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
		var idx = SliceZ.build(values);
		var filter = bitmap(1, 3, 4, 6, 8);
		var allowed = rowSet(1, 3, 4, 6, 8);
		assertQuery(values, allowed, v -> Long.compareUnsigned(v, 3) >= 0 && Long.compareUnsigned(v, 7) < 0,
				idx.between(3, 7, filter.blockIterator()), idx.countBetween(3, 7, filter.blockIterator()),
				idx.sumBetween(3, 7, filter.blockIterator()), idx.meanBetween(3, 7, filter.blockIterator()));
	}

	@Test
	void betweenCoveringFullRangeRespectsFilter() {
		// min = 10, max = 50; the range [5, 60) covers every value, which triggers the
		// "all rows" fast path in between(lower, upper, filter). That path must still
		// honour the filter (as countBetween/sumBetween do): only rows 1 and 3 qualify.
		long[] values = {10, 20, 30, 40, 50};
		var idx = SliceZ.build(values);
		var filter = bitmap(1, 3);
		var allowed = rowSet(1, 3);
		assertQuery(values, allowed, v -> Long.compareUnsigned(v, 5) >= 0 && Long.compareUnsigned(v, 60) < 0,
				idx.between(5, 60, filter.blockIterator()), idx.countBetween(5, 60, filter.blockIterator()),
				idx.sumBetween(5, 60, filter.blockIterator()), idx.meanBetween(5, 60, filter.blockIterator()));
	}

	@Test
	void sumInSingleValueRespectsFilter() {
		// value 5 is in rows 0..3; the filter keeps rows 0 and 2, so the filtered sum
		// is 10. countIn's single-value shortcut is filtered (returns 2), but sumIn's
		// shortcut delegates to the *unfiltered* sumEqual and returns 20 (5 * 4).
		long[] values = {5, 5, 5, 5};
		var idx = SliceZ.build(values);
		var filter = bitmap(0, 2);
		assertEquals(2, idx.countIn(filter.blockIterator(), 5L), "count (control)");
		assertEquals(10.0, idx.sumIn(filter.blockIterator(), 5L), 1e-9, "sum must honour the filter");
	}

	@Test
	void sumEqualHandlesLargeUnsignedValue() {
		// Long.MIN_VALUE is 2^63 in unsigned terms (~9.22e18). Two rows hold it, so the
		// unsigned sum is 2^64. sumEqual uses (double) value, which reads the sign bit
		// as negative and returns -2^64 instead.
		long v = Long.MIN_VALUE;
		var idx = SliceZ.build(v, v);
		assertEquals(2, idx.countEqual(v), "count (control)");
		assertEquals(Math.scalb(1.0, 64), idx.sumEqual(v), 1.0, "sumEqual must treat the value as unsigned");
	}

	@Test
	void filteredQueriesCrossCheck() {
		var random = new SplittableRandom(20260817L);
		for (int trial = 0; trial < 500; trial++) {
			int n = 1 + random.nextInt(150);
			long base = random.nextInt(100000);
			int spread = 1 + random.nextInt(40);
			long[] values = new long[n];
			// half the trials use a heavily skewed distribution (one dominant value) so
			// most bit-slices are near-constant, producing SPARSE / SPARSE_INVERTED slices
			boolean skewed = random.nextBoolean();
			long dominant = base + random.nextInt(spread);
			for (int i = 0; i < n; i++) {
				values[i] = (skewed && random.nextInt(20) != 0) ? dominant : base + random.nextInt(spread);
			}
			var idx = SliceZ.build(values);

			var allowed = new TreeSet<Integer>();
			switch (random.nextInt(4)) {
				case 0 -> { // trivial: every row
					for (int i = 0; i < n; i++) {
						allowed.add(i);
					}
				}
				case 1 -> { // random subset
					for (int i = 0; i < n; i++) {
						if (random.nextBoolean()) {
							allowed.add(i);
						}
					}
				}
				case 2 -> allowed.add(random.nextInt(n)); // singleton
				default -> {
					/* empty */ }
			}
			var filter = bitmap(allowed.stream().mapToInt(Integer::intValue).toArray());

			// thresholds biased to the edges: below min, inside, above max
			long t = base - 2 + random.nextInt(spread + 4);
			long lo = base - 2 + random.nextInt(spread + 4);
			long hi = base - 2 + random.nextInt(spread + 4);
			long eq = base + random.nextInt(spread);

			assertQuery(values, allowed, v -> Long.compareUnsigned(v, t) > 0,
					idx.greaterThan(t, filter.blockIterator()), idx.countGreaterThan(t, filter.blockIterator()),
					idx.sumGreaterThan(t, filter.blockIterator()), idx.meanGreaterThan(t, filter.blockIterator()));
			assertQuery(values, allowed, v -> Long.compareUnsigned(v, t) <= 0,
					idx.lessThanOrEqual(t, filter.blockIterator()), idx.countLessThanOrEqual(t, filter.blockIterator()),
					idx.sumLessThanOrEqual(t, filter.blockIterator()),
					idx.meanLessThanOrEqual(t, filter.blockIterator()));
			assertQuery(values, allowed, v -> v != eq, idx.notEqual(eq, filter.blockIterator()),
					idx.countNotEqual(eq, filter.blockIterator()), idx.sumNotEqual(eq, filter.blockIterator()),
					idx.meanNotEqual(eq, filter.blockIterator()));
			assertQuery(values, allowed, v -> Long.compareUnsigned(v, lo) >= 0 && Long.compareUnsigned(v, hi) < 0,
					idx.between(lo, hi, filter.blockIterator()), idx.countBetween(lo, hi, filter.blockIterator()),
					idx.sumBetween(lo, hi, filter.blockIterator()), idx.meanBetween(lo, hi, filter.blockIterator()));

			// equal (no filtered sum/mean overloads, so check ids and count only)
			int[] eqIds = rowsWhere(values, v -> v == eq, allowed);
			assertArrayEquals(eqIds, collect(idx.equal(eq, filter.blockIterator())), "equal ids");
			assertEquals(eqIds.length, idx.countEqual(eq, filter.blockIterator()), "equal count");

			// unfiltered greaterThanOrEqual / lessThan (their value==0 and value-1
			// delegations are the shortcut style that has had bugs); force 0 sometimes
			var allRows = new TreeSet<Integer>();
			for (int i = 0; i < n; i++) {
				allRows.add(i);
			}
			long gte = random.nextInt(3) == 0 ? 0 : t;
			assertQuery(values, allRows, v -> Long.compareUnsigned(v, gte) >= 0, idx.greaterThanOrEqual(gte),
					idx.countGreaterThanOrEqual(gte), idx.sumGreaterThanOrEqual(gte), idx.meanGreaterThanOrEqual(gte));

			// unfiltered fast-path families (IterateAllBlocks is trivial, so these hit the
			// isTrivial() all-rows / rowCount shortcuts at the edge thresholds)
			assertQuery(values, allRows, v -> Long.compareUnsigned(v, t) <= 0, idx.lessThanOrEqual(t),
					idx.countLessThanOrEqual(t), idx.sumLessThanOrEqual(t), idx.meanLessThanOrEqual(t));
			assertQuery(values, allRows, v -> Long.compareUnsigned(v, t) > 0, idx.greaterThan(t),
					idx.countGreaterThan(t), idx.sumGreaterThan(t), idx.meanGreaterThan(t));
			assertQuery(values, allRows, v -> Long.compareUnsigned(v, lo) >= 0 && Long.compareUnsigned(v, hi) < 0,
					idx.between(lo, hi), idx.countBetween(lo, hi), idx.sumBetween(lo, hi), idx.meanBetween(lo, hi));
			long ltv = random.nextInt(3) == 0 ? 0 : t;
			assertQuery(values, allRows, v -> Long.compareUnsigned(v, ltv) < 0, idx.lessThan(ltv),
					idx.countLessThan(ltv), idx.sumLessThan(ltv), idx.meanLessThan(ltv));

			// in with a mix of present and absent values (exercises InQuery)
			int k = 2 + random.nextInt(3);
			long[] inVals = new long[k];
			var inSet = new TreeSet<Long>();
			for (int j = 0; j < k; j++) {
				inVals[j] = base - 1 + random.nextInt(spread + 2);
				inSet.add(inVals[j]);
			}
			assertQuery(values, allowed, inSet::contains, idx.in(filter.blockIterator(), inVals),
					idx.countIn(filter.blockIterator(), inVals), idx.sumIn(filter.blockIterator(), inVals),
					idx.meanIn(filter.blockIterator(), inVals));
		}
	}

	@Test
	void filteredQueriesCrossCheckMultiBlock() {
		var random = new SplittableRandom(4242L);
		for (int trial = 0; trial < 6; trial++) {
			int n = BLOCK + 1 + random.nextInt(2 * BLOCK); // spans 2-3 blocks, last partial
			long[] values = new long[n];
			for (int i = 0; i < n; i++) {
				values[i] = i + 1; // distinct, ascending: each block holds a distinct value range
			}
			var idx = SliceZ.build(values);
			var allowed = new TreeSet<Integer>();
			for (int i = 0; i < n; i++) {
				if (random.nextInt(4) == 0) {
					allowed.add(i);
				}
			}
			var filter = bitmap(allowed.stream().mapToInt(Integer::intValue).toArray());

			// values spread across blocks plus one out of range, so some blocks are skipped
			long[] inVals = {5, BLOCK + 10, 2L * BLOCK + 3, n + 100L};
			var inSet = new TreeSet<Long>();
			for (long x : inVals) {
				inSet.add(x);
			}
			assertQuery(values, allowed, inSet::contains, idx.in(filter.blockIterator(), inVals),
					idx.countIn(filter.blockIterator(), inVals), idx.sumIn(filter.blockIterator(), inVals),
					idx.meanIn(filter.blockIterator(), inVals));

			long lo = BLOCK / 2;
			long hi = BLOCK + BLOCK / 2;
			assertQuery(values, allowed, v -> Long.compareUnsigned(v, lo) >= 0 && Long.compareUnsigned(v, hi) < 0,
					idx.between(lo, hi, filter.blockIterator()), idx.countBetween(lo, hi, filter.blockIterator()),
					idx.sumBetween(lo, hi, filter.blockIterator()), idx.meanBetween(lo, hi, filter.blockIterator()));

			long eq = BLOCK + 5;
			int[] eqIds = rowsWhere(values, v -> v == eq, allowed);
			assertArrayEquals(eqIds, collect(idx.equal(eq, filter.blockIterator())), "equal ids");
			assertEquals(eqIds.length, idx.countEqual(eq, filter.blockIterator()), "equal count");
		}
	}

	@Test
	void serializeMapRoundTrip() {
		var random = new SplittableRandom(7L);
		long[] values = new long[BLOCK + 1000];
		for (int i = 0; i < values.length; i++) {
			values[i] = random.nextLong(1_000_000);
		}
		var idx = SliceZ.build(values);
		var mapped = SliceZ.map(idx.serialize());
		for (long t : new long[]{0, 1, 5, 12345, 500_000, 999_999, 1_000_000}) {
			assertArrayEquals(collect(idx.lessThanOrEqual(t)), collect(mapped.lessThanOrEqual(t)),
					"lessThanOrEqual " + t);
			assertEquals(idx.countGreaterThan(t), mapped.countGreaterThan(t), "countGreaterThan " + t);
			assertEquals(idx.sumLessThanOrEqual(t), mapped.sumLessThanOrEqual(t), 1e-6, "sumLessThanOrEqual " + t);
		}
	}

	@Test
	void filteredCountCrossCheckUnsigned() {
		var random = new SplittableRandom(555L);
		for (int trial = 0; trial < 300; trial++) {
			int n = 1 + random.nextInt(150);
			long[] values = new long[n];
			for (int i = 0; i < n; i++) {
				values[i] = random.nextLong(); // full 64-bit range: large unsigned values
			}
			var idx = SliceZ.build(values);
			var allowed = new TreeSet<Integer>();
			for (int i = 0; i < n; i++) {
				if (random.nextBoolean()) {
					allowed.add(i);
				}
			}
			var filter = bitmap(allowed.stream().mapToInt(Integer::intValue).toArray());
			long t = random.nextInt(2) == 0 ? values[random.nextInt(n)] : random.nextLong();
			long lo = random.nextLong();
			long hi = random.nextLong();

			countIds(values, allowed, v -> Long.compareUnsigned(v, t) > 0, idx.greaterThan(t, filter.blockIterator()),
					idx.countGreaterThan(t, filter.blockIterator()));
			countIds(values, allowed, v -> Long.compareUnsigned(v, t) <= 0,
					idx.lessThanOrEqual(t, filter.blockIterator()),
					idx.countLessThanOrEqual(t, filter.blockIterator()));
			countIds(values, allowed, v -> v == t, idx.equal(t, filter.blockIterator()),
					idx.countEqual(t, filter.blockIterator()));
			countIds(values, allowed, v -> v != t, idx.notEqual(t, filter.blockIterator()),
					idx.countNotEqual(t, filter.blockIterator()));
			countIds(values, allowed, v -> Long.compareUnsigned(v, lo) >= 0 && Long.compareUnsigned(v, hi) < 0,
					idx.between(lo, hi, filter.blockIterator()), idx.countBetween(lo, hi, filter.blockIterator()));
		}
	}

	private static void countIds(long[] values, TreeSet<Integer> allowed, LongPredicate cond,
			PrimitiveIterator.OfInt ids, int count) {
		int[] rows = rowsWhere(values, cond, allowed);
		assertArrayEquals(rows, collect(ids), "matching row ids");
		assertEquals(rows.length, count, "count");
	}

	@Test
	void histogramCrossCheck() {
		var random = new SplittableRandom(9001L);
		for (int trial = 0; trial < 300; trial++) {
			int n = 1 + random.nextInt(200);
			long[] data = new long[n];
			long dom = random.nextInt(1000);
			boolean skew = random.nextBoolean();
			for (int i = 0; i < n; i++) {
				data[i] = skew && random.nextInt(10) != 0 ? dom : random.nextInt(1000);
			}
			var idx = SliceZ.build(data);
			long min = random.nextInt(1000);
			int nb = 1 + random.nextInt(6);
			long[] bounds = new long[nb];
			for (int i = 0; i < nb; i++) {
				bounds[i] = min + random.nextInt(1000); // every bound >= min
			}
			Arrays.sort(bounds); // ascending (may contain duplicates -> zero-width buckets)

			long[] expected = new long[nb];
			for (long v : data) {
				if (Long.compareUnsigned(v, min) < 0) {
					continue;
				}
				for (int i = 0; i < nb; i++) {
					if (Long.compareUnsigned(v, bounds[i]) < 0) {
						expected[i]++;
						break;
					}
				}
			}
			assertArrayEquals(expected, idx.histogram(min, bounds),
					() -> "min=" + min + " bounds=" + Arrays.toString(bounds));
		}
	}

	@Test
	void mapThroughBytesRoundTrip() {
		// the natural persist/restore cycle: serialize -> raw bytes -> wrap -> map.
		// A freshly wrapped ByteBuffer defaults to BIG_ENDIAN, and map() reads the
		// header without forcing the little-endian order serialize() produced, so the
		// round trip must still reproduce the index.
		var idx = SliceZ.build(3, 1, 4, 1, 5, 9, 2, 6);
		ByteBuffer serialized = idx.serialize();
		byte[] bytes = new byte[serialized.capacity()];
		serialized.duplicate().get(bytes);
		var mapped = SliceZ.map(ByteBuffer.wrap(bytes));
		assertArrayEquals(collect(idx.lessThanOrEqual(4)), collect(mapped.lessThanOrEqual(4)),
				"map() must round-trip serialized bytes regardless of the buffer's byte order");
	}

	@Test
	void inWithBlockIteratorFilter() {
		long[] values = {1, 2, 3, 2, 1, 3, 2};
		var idx = SliceZ.build(values);
		var filter = bitmap(0, 1, 3, 5, 6);
		var allowed = rowSet(0, 1, 3, 5, 6);
		Set<Long> match = Set.of(2L, 3L);
		int[] rows = rowsWhere(values, match::contains, allowed);
		double sum = 0;
		for (int r : rows) {
			sum += values[r];
		}
		assertArrayEquals(rows, collect(idx.in(filter.blockIterator(), 2, 3)));
		assertEquals(rows.length, idx.countIn(filter.blockIterator(), 2, 3));
		assertEquals(sum, idx.sumIn(filter.blockIterator(), 2, 3), 1e-9);
		assertEquals(rows.length == 0 ? 0 : sum / rows.length, idx.meanIn(filter.blockIterator(), 2, 3), 1e-9);
	}

	@Test
	void fullFilterMatchesUnfiltered() {
		// a filter that contains every row must not change the result
		long[] values = {1, 2, 2, 3, 2};
		var idx = SliceZ.build(values);
		var all = bitmap(0, 1, 2, 3, 4);
		assertArrayEquals(collect(idx.equal(2)), collect(idx.equal(2, all.blockIterator())));
		assertEquals(idx.countEqual(2), idx.countEqual(2, all.blockIterator()));
	}

	// -------------------------------------------------------------------------
	// and() / or() as the filter
	// -------------------------------------------------------------------------

	@Test
	void equalWithAndFilter() {
		long[] values = {5, 5, 5, 5, 5, 5, 5};
		var idx = SliceZ.build(values);
		var a = bitmap(1, 2, 3, 4);
		var b = bitmap(3, 4, 5, 6);
		var allowed = rowSet(3, 4); // intersection
		int[] rows = rowsWhere(values, v -> v == 5, allowed);
		assertArrayEquals(rows, collect(idx.equal(5, a.and(b))));
		assertEquals(rows.length, idx.countEqual(5, a.and(b)));
	}

	@Test
	void betweenWithAndFilter() {
		long[] values = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
		var idx = SliceZ.build(values);
		var a = bitmap(1, 2, 3, 4, 5, 6);
		var b = bitmap(3, 4, 5, 6, 7, 8);
		var allowed = rowSet(3, 4, 5, 6); // intersection
		assertQuery(values, allowed, v -> Long.compareUnsigned(v, 4) >= 0 && Long.compareUnsigned(v, 8) < 0,
				idx.between(4, 8, a.and(b)), idx.countBetween(4, 8, a.and(b)), idx.sumBetween(4, 8, a.and(b)),
				idx.meanBetween(4, 8, a.and(b)));
	}

	@Test
	void equalWithOrFilter() {
		long[] values = {5, 5, 5, 5, 5, 5, 5};
		var idx = SliceZ.build(values);
		var a = bitmap(0, 1, 2);
		var b = bitmap(4, 5);
		var allowed = rowSet(0, 1, 2, 4, 5); // union
		int[] rows = rowsWhere(values, v -> v == 5, allowed);
		assertArrayEquals(rows, collect(idx.equal(5, a.or(b))));
		assertEquals(rows.length, idx.countEqual(5, a.or(b)));
	}

	@Test
	void lessThanOrEqualWithOrFilter() {
		long[] values = {0, 1, 2, 3, 4, 5, 6};
		var idx = SliceZ.build(values);
		var a = bitmap(0, 1, 2);
		var b = bitmap(4, 5);
		var allowed = rowSet(0, 1, 2, 4, 5); // union
		assertQuery(values, allowed, v -> Long.compareUnsigned(v, 3) <= 0, idx.lessThanOrEqual(3, a.or(b)),
				idx.countLessThanOrEqual(3, a.or(b)), idx.sumLessThanOrEqual(3, a.or(b)),
				idx.meanLessThanOrEqual(3, a.or(b)));
	}

	// -------------------------------------------------------------------------
	// Multiple blocks: the filter both skips a whole block and masks rows within
	// the blocks it keeps.
	// -------------------------------------------------------------------------

	@Test
	void multiBlockBlockAndBitLevel() {
		int n = 200_000; // blocks 0, 1 and 2 (the last partial)
		long[] values = new long[n];
		for (int r = 0; r < n; r++) {
			values[r] = r % 10;
		}
		var idx = SliceZ.build(values);
		// rows in block 0 and block 2; block 1 (rows [BLOCK, 2*BLOCK)) is excluded
		// entirely
		var filter = bitmap(3, 13, 100, 2 * BLOCK + 931, 2 * BLOCK + 18931);
		var allowed = rowSet(3, 13, 100, 2 * BLOCK + 931, 2 * BLOCK + 18931);
		int[] rows = rowsWhere(values, v -> v == 3, allowed);
		assertArrayEquals(rows, collect(idx.equal(3, filter.blockIterator())));
		assertEquals(rows.length, idx.countEqual(3, filter.blockIterator()));
	}

	// -------------------------------------------------------------------------
	// A full (65536-row) block whose rows all match makes the query buffer "full".
	// Intersecting that with a partial filter must keep only the filtered rows.
	// -------------------------------------------------------------------------

	@Test
	void fullBlockMatchingEveryRowRespectsFilter() {
		long[] values = new long[BLOCK]; // exactly one full block
		java.util.Arrays.fill(values, 1L);
		var idx = SliceZ.build(values);
		var filter = bitmap(0, 5, 100);
		// every row satisfies notEqual(0), so only the three filtered rows survive
		assertArrayEquals(new int[]{0, 5, 100}, collect(idx.notEqual(0, filter.blockIterator())));
		assertEquals(3, idx.countNotEqual(0, filter.blockIterator()));
	}

	// -------------------------------------------------------------------------
	// When the threshold falls outside [min, max] every row matches the
	// condition, but the filter must still constrain which rows are returned.
	// -------------------------------------------------------------------------

	@Test
	void thresholdOutsideValueRangeStillRespectsFilter() {
		// all values equal 5, so min == max == 5
		var idx = SliceZ.build(5, 5, 5);
		var filter = bitmap(1); // only row 1 passes the filter

		// threshold above max: lessThanOrEqual matches every row, but only row 1 is
		// kept
		assertEquals(1, idx.countLessThanOrEqual(6, filter.blockIterator()));
		assertArrayEquals(new int[]{1}, collect(idx.lessThanOrEqual(6, filter.blockIterator())));

		// threshold below min: greaterThan matches every row, but only row 1 is kept
		assertEquals(1, idx.countGreaterThan(4, filter.blockIterator()));
		assertArrayEquals(new int[]{1}, collect(idx.greaterThan(4, filter.blockIterator())));
	}
}
