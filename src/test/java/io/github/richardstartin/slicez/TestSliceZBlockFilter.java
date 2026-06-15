package io.github.richardstartin.slicez;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.ArrayList;
import java.util.PrimitiveIterator;
import java.util.Set;
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
