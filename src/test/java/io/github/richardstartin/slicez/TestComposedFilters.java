package io.github.richardstartin.slicez;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.ArrayList;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.function.LongPredicate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link ResultIterator}: the result of a query is itself a
 * {@link BlockIterator}, so it can be handed to another query as a filter.
 *
 * <p>
 * The canonical identity is that composing the two single-bound queries
 * reproduces {@code between}: {@code between(lower, upper)} keeps
 * {@code lower <= v < upper}, which is exactly
 * {@code lessThanOrEqual(upper - 1)} intersected with
 * {@code greaterThan(lower - 1)}.
 */
@Execution(ExecutionMode.CONCURRENT)
class TestComposedFilters {

	private static final int BLOCK = 1 << 16;

	private static int[] collect(PrimitiveIterator.OfInt it) {
		var out = new ArrayList<Integer>();
		while (it.hasNext()) {
			out.add(it.nextInt());
		}
		return out.stream().mapToInt(Integer::intValue).toArray();
	}

	/** The row ids whose value satisfies {@code cond}. */
	private static int[] rowsWhere(long[] values, LongPredicate cond) {
		var out = new ArrayList<Integer>();
		for (int r = 0; r < values.length; r++) {
			if (cond.test(values[r])) {
				out.add(r);
			}
		}
		return out.stream().mapToInt(Integer::intValue).toArray();
	}

	private static double sumOf(long[] values, int[] rows) {
		double sum = 0;
		for (int r : rows) {
			sum += values[r];
		}
		return sum;
	}

	// -------------------------------------------------------------------------
	// composing the two single-bound queries reproduces between()
	// -------------------------------------------------------------------------

	@Test
	void lessThanOrEqualFilteredByGreaterThanIsBetween() {
		long[] values = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
		var idx = SliceZ.build(values);
		// [3, 7) == v > 2 && v <= 6
		assertArrayEquals(new int[]{3, 4, 5, 6}, collect(idx.lessThanOrEqual(6, idx.greaterThan(2)).rowIds()));
		assertArrayEquals(collect(idx.between(3, 7).rowIds()),
				collect(idx.lessThanOrEqual(6, idx.greaterThan(2)).rowIds()));
	}

	@Test
	void greaterThanFilteredByLessThanOrEqualIsBetween() {
		// composition is symmetric: it does not matter which bound is the filter
		long[] values = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
		var idx = SliceZ.build(values);
		assertArrayEquals(collect(idx.between(3, 7).rowIds()),
				collect(idx.greaterThan(2, idx.lessThanOrEqual(6)).rowIds()));
	}

	@Test
	void composedBoundsMatchBetweenAcrossDistributions() {
		var random = new SplittableRandom(20260929L);
		for (int trial = 0; trial < 500; trial++) {
			int n = 1 + random.nextInt(150);
			long base = random.nextInt(100000);
			int spread = 1 + random.nextInt(40);
			// half the trials are heavily skewed so most slices are SPARSE/SPARSE_INVERTED
			boolean skewed = random.nextBoolean();
			long dominant = base + random.nextInt(spread);
			long[] values = new long[n];
			for (int i = 0; i < n; i++) {
				values[i] = (skewed && random.nextInt(20) != 0) ? dominant : base + random.nextInt(spread);
			}
			var idx = SliceZ.build(values);

			// thresholds biased to the edges: below min, inside, above max
			long lower = base - 2 + random.nextInt(spread + 4);
			long upper = base - 2 + random.nextInt(spread + 4);
			int[] expected = rowsWhere(values,
					v -> Long.compareUnsigned(v, lower) >= 0 && Long.compareUnsigned(v, upper) < 0);

			String message = "lower=" + lower + " upper=" + upper + " values=" + n;
			assertArrayEquals(expected, collect(idx.between(lower, upper).rowIds()), message);
			assertArrayEquals(expected, collect(idx.lessThanOrEqual(upper - 1, idx.greaterThan(lower - 1)).rowIds()),
					message);
			assertArrayEquals(expected, collect(idx.greaterThan(lower - 1, idx.lessThanOrEqual(upper - 1)).rowIds()),
					message);
			assertEquals(expected.length, idx.countLessThanOrEqual(upper - 1, idx.greaterThan(lower - 1)), message);
			assertEquals(sumOf(values, expected), idx.sumLessThanOrEqual(upper - 1, idx.greaterThan(lower - 1)), 1e-9,
					message);
			assertEquals(expected.length == 0 ? 0 : sumOf(values, expected) / expected.length,
					idx.meanLessThanOrEqual(upper - 1, idx.greaterThan(lower - 1)), 1e-9, message);
		}
	}

	@Test
	void composedBoundsMatchBetweenAcrossBlocks() {
		var random = new SplittableRandom(4243L);
		for (int trial = 0; trial < 4; trial++) {
			int n = BLOCK + 1 + random.nextInt(2 * BLOCK); // spans 2-3 blocks, last partial
			long[] values = new long[n];
			for (int i = 0; i < n; i++) {
				values[i] = i + 1; // distinct and ascending: every block holds a distinct range
			}
			var idx = SliceZ.build(values);
			long lower = BLOCK / 2;
			long upper = BLOCK + BLOCK / 2;
			int[] expected = rowsWhere(values,
					v -> Long.compareUnsigned(v, lower) >= 0 && Long.compareUnsigned(v, upper) < 0);
			assertArrayEquals(expected, collect(idx.lessThanOrEqual(upper - 1, idx.greaterThan(lower - 1)).rowIds()));
			assertEquals(expected.length, idx.countLessThanOrEqual(upper - 1, idx.greaterThan(lower - 1)));
		}
	}

	// -------------------------------------------------------------------------
	// the other query families as filters, and chains of them
	// -------------------------------------------------------------------------

	@Test
	void equalityQueriesCompose() {
		long[] values = {1, 2, 3, 2, 1, 3, 2, 4};
		var idx = SliceZ.build(values);
		// rows holding 2 that are not 1 -> all the rows holding 2
		assertArrayEquals(new int[]{1, 3, 6}, collect(idx.equal(2, idx.notEqual(1)).rowIds()));
		// rows in {2, 3} that also hold 3
		assertArrayEquals(new int[]{2, 5}, collect(idx.equal(3, idx.in(2, 3)).rowIds()));
		// rows not holding 2, restricted to those holding at most 2
		assertArrayEquals(new int[]{0, 4}, collect(idx.notEqual(2, idx.lessThanOrEqual(2)).rowIds()));
	}

	@Test
	void filtersChainThreeDeep() {
		long[] values = new long[64];
		for (int i = 0; i < values.length; i++) {
			values[i] = i;
		}
		var idx = SliceZ.build(values);
		// v > 10, then v <= 40, then v != 25
		var result = idx.notEqual(25, idx.lessThanOrEqual(40, idx.greaterThan(10)));
		int[] expected = rowsWhere(values, v -> v > 10 && v <= 40 && v != 25);
		assertArrayEquals(expected, collect(result.rowIds()));
		assertEquals(expected.length, idx.countNotEqual(25, idx.lessThanOrEqual(40, idx.greaterThan(10))));
	}

	@Test
	void betweenAcceptsAComposedFilter() {
		long[] values = new long[200];
		for (int i = 0; i < values.length; i++) {
			values[i] = i % 50;
		}
		var idx = SliceZ.build(values);
		int[] expected = rowsWhere(values, v -> v >= 10 && v < 20 && v > 12);
		assertArrayEquals(expected, collect(idx.between(10, 20, idx.greaterThan(12)).rowIds()));
		assertEquals(expected.length, idx.countBetween(10, 20, idx.greaterThan(12)));
		assertEquals(sumOf(values, expected), idx.sumBetween(10, 20, idx.greaterThan(12)), 1e-9);
	}

	@Test
	void histogramAcceptsAComposedFilter() {
		long[] values = new long[500];
		for (int i = 0; i < values.length; i++) {
			values[i] = i % 100;
		}
		var idx = SliceZ.build(values);
		long[] bounds = {20, 40, 60, 80};
		// only rows holding a value > 30 survive the filter
		long[] expected = new long[bounds.length];
		for (long v : values) {
			if (v <= 30) {
				continue;
			}
			for (int i = 0; i < bounds.length; i++) {
				if (Long.compareUnsigned(v, bounds[i]) < 0) {
					expected[i]++;
					break;
				}
			}
		}
		assertArrayEquals(expected, idx.histogram(idx.greaterThan(30), 0, bounds));
	}

	// -------------------------------------------------------------------------
	// top / bottom results as filters
	// -------------------------------------------------------------------------

	@Test
	void topAndBottomComposeAsFilters() {
		var random = new SplittableRandom(99L);
		long[] values = new long[1000];
		for (int i = 0; i < values.length; i++) {
			values[i] = random.nextInt(10_000);
		}
		var idx = SliceZ.build(values);
		for (int k : new int[]{1, 7, 64, 200}) {
			// which rows top-k selects is unspecified when values tie, so take the
			// selection itself as the reference and filter it by hand
			var selected = new TreeSet<Integer>();
			for (int rid : collect(idx.top(k).rowIds())) {
				selected.add(rid);
			}
			var expected = new ArrayList<Integer>();
			for (int rid : selected) {
				if (values[rid] > 5000) {
					expected.add(rid);
				}
			}
			int[] expectedRows = expected.stream().mapToInt(Integer::intValue).toArray();
			assertArrayEquals(expectedRows, collect(idx.greaterThan(5000, idx.top(k)).rowIds()), "top " + k);
			assertEquals(expectedRows.length, idx.countGreaterThan(5000, idx.top(k)), "count top " + k);
		}
		for (int k : new int[]{1, 7, 64, 200}) {
			var selected = new TreeSet<Integer>();
			for (int rid : collect(idx.bottom(k).rowIds())) {
				selected.add(rid);
			}
			var expected = new ArrayList<Integer>();
			for (int rid : selected) {
				if (values[rid] <= 5000) {
					expected.add(rid);
				}
			}
			int[] expectedRows = expected.stream().mapToInt(Integer::intValue).toArray();
			assertArrayEquals(expectedRows, collect(idx.lessThanOrEqual(5000, idx.bottom(k)).rowIds()), "bottom " + k);
			assertEquals(expectedRows.length, idx.countLessThanOrEqual(5000, idx.bottom(k)), "count bottom " + k);
		}
	}

	@Test
	void topAsAFilterSpansBlocks() {
		int n = 2 * BLOCK + 1234;
		long[] values = new long[n];
		for (int i = 0; i < n; i++) {
			values[i] = i;
		}
		var idx = SliceZ.build(values);
		// the largest 100 values are the last 100 rows, spread over the final block
		int[] top = collect(idx.top(100).rowIds());
		assertEquals(100, top.length);
		int[] expected = rowsWhere(values, v -> v >= n - 100 && v % 2 == 0);
		assertArrayEquals(expected, collect(idx.in(idx.top(100), evens(n - 100, n)).rowIds()));
	}

	private static long[] evens(int from, int to) {
		var out = new ArrayList<Long>();
		for (int v = from; v < to; v++) {
			if (v % 2 == 0) {
				out.add((long) v);
			}
		}
		return out.stream().mapToLong(Long::longValue).toArray();
	}

	// -------------------------------------------------------------------------
	// degenerate results: nothing matched, or everything matched
	// -------------------------------------------------------------------------

	@Test
	void anEmptyResultFiltersEverythingOut() {
		long[] values = {1, 2, 3, 4, 5};
		var idx = SliceZ.build(values);
		// no value exceeds max, so greaterThan yields the empty result
		var empty = idx.greaterThan(100);
		assertFalse(empty.hasNext());
		assertArrayEquals(new int[0], collect(idx.lessThanOrEqual(5, idx.greaterThan(100)).rowIds()));
		assertEquals(0, idx.countLessThanOrEqual(5, idx.greaterThan(100)));
		assertEquals(0, idx.countEqual(3, idx.greaterThan(100)));
		assertEquals(0d, idx.sumLessThanOrEqual(5, idx.greaterThan(100)), 1e-9);
	}

	@Test
	void anAllRowsResultFiltersNothingOut() {
		long[] values = {1, 2, 3, 4, 5};
		var idx = SliceZ.build(values);
		// every value exceeds 0, so greaterThan yields the all-rows result
		assertArrayEquals(new int[]{0, 1, 2, 3, 4}, collect(idx.greaterThan(0).rowIds()));
		assertArrayEquals(collect(idx.lessThanOrEqual(3).rowIds()),
				collect(idx.lessThanOrEqual(3, idx.greaterThan(0)).rowIds()));
		assertEquals(idx.countEqual(3), idx.countEqual(3, idx.greaterThan(0)));
	}

	@Test
	void allRowsResultIteratesEveryRowAcrossBlocks() {
		int n = 2 * BLOCK + 7;
		long[] values = new long[n];
		java.util.Arrays.fill(values, 5L);
		var idx = SliceZ.build(values);
		// min == max == 5, so greaterThan(4) takes the all-rows fast path
		int[] rows = collect(idx.greaterThan(4).rowIds());
		assertEquals(n, rows.length);
		for (int i = 0; i < n; i++) {
			assertEquals(i, rows[i]);
		}
		// and as a filter it must visit every block, including the partial last one
		assertEquals(n, idx.countGreaterThan(4, idx.greaterThan(4)));
		assertArrayEquals(rows, collect(idx.greaterThan(4, idx.greaterThan(4)).rowIds()));
	}

	// -------------------------------------------------------------------------
	// the ResultIterator contract itself
	// -------------------------------------------------------------------------

	@Test
	void resultsCanBeDrivenAsBlocks() {
		int n = 2 * BLOCK + 500;
		long[] values = new long[n];
		for (int i = 0; i < n; i++) {
			values[i] = i;
		}
		var idx = SliceZ.build(values);
		// v > 2 * BLOCK - 1 keeps the tail of block 1 plus all of block 2
		var result = idx.greaterThan(BLOCK + BLOCK / 2);
		var blocks = new ArrayList<Integer>();
		int counted = 0;
		while (result.hasNext()) {
			int block = result.nextBlock();
			blocks.add(block);
			counted += result.getBits().count(Math.min(n - (block << 16), BLOCK));
		}
		assertArrayEquals(new int[]{1, 2}, blocks.stream().mapToInt(Integer::intValue).toArray());
		assertEquals(idx.countGreaterThan(BLOCK + BLOCK / 2), counted);
	}

	@Test
	void exhaustedResultsThrow() {
		var idx = SliceZ.build(1, 2, 3);
		var byValue = idx.equal(2).rowIds();
		assertTrue(byValue.hasNext());
		assertEquals(1, byValue.nextInt());
		assertFalse(byValue.hasNext());
		assertThrows(NoSuchElementException.class, byValue::nextInt);

		var byBlock = idx.equal(2);
		assertEquals(0, byBlock.nextBlock());
		assertFalse(byBlock.hasNext());
		assertThrows(NoSuchElementException.class, byBlock::nextBlock);

		var emptyRows = ResultIterator.EMPTY.rowIds();
		assertFalse(emptyRows.hasNext());
		assertThrows(NoSuchElementException.class, emptyRows::nextInt);
		assertThrows(NoSuchElementException.class, ResultIterator.EMPTY::nextBlock);

		var allRows = idx.greaterThan(0); // the all-rows result
		assertEquals(0, allRows.nextBlock());
		assertFalse(allRows.hasNext());
		assertThrows(NoSuchElementException.class, allRows::nextBlock);

		var top = idx.top(2);
		assertEquals(0, top.nextBlock());
		assertFalse(top.hasNext());
		assertThrows(NoSuchElementException.class, top::nextBlock);
	}

	@Test
	void resultsSkipBlocksWithNoMatches() {
		// block 1 holds no matching row, so driving the result as blocks must jump
		// straight from block 0 to block 2
		int n = 2 * BLOCK + 100;
		long[] values = new long[n];
		for (int i = 0; i < n; i++) {
			// distinct per block: block 0 -> 0, block 1 -> 1, block 2 -> 2
			values[i] = i >>> 16;
		}
		var idx = SliceZ.build(values);
		var result = idx.notEqual(1);
		var blocks = new ArrayList<Integer>();
		while (result.hasNext()) {
			blocks.add(result.nextBlock());
		}
		assertArrayEquals(new int[]{0, 2}, blocks.stream().mapToInt(Integer::intValue).toArray());
		assertEquals(BLOCK + 100, idx.countNotEqual(1, idx.notEqual(1)));
	}

	@Test
	void composingAResultWithItselfIsIdempotent() {
		var random = new SplittableRandom(31337L);
		for (int trial = 0; trial < 200; trial++) {
			int n = 1 + random.nextInt(300);
			long[] values = new long[n];
			for (int i = 0; i < n; i++) {
				values[i] = random.nextInt(64);
			}
			var idx = SliceZ.build(values);
			long t = random.nextInt(64);
			int[] plain = collect(idx.lessThanOrEqual(t).rowIds());
			assertArrayEquals(plain, collect(idx.lessThanOrEqual(t, idx.lessThanOrEqual(t)).rowIds()),
					"threshold " + t);
			assertEquals(plain.length, idx.countLessThanOrEqual(t, idx.lessThanOrEqual(t)), "threshold " + t);
		}
	}
}
