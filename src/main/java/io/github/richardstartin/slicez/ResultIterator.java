package io.github.richardstartin.slicez;

import java.util.NoSuchElementException;
import java.util.PrimitiveIterator;

/**
 * The result of a query, which can be consumed in either of two ways:
 *
 * <ul>
 * <li>as a {@link BlockIterator}, so the result can be handed to another query
 * as a filter, making queries composable. For example
 * {@code index.lessThanOrEqual(y, index.greaterThan(x))} keeps the rows
 * satisfying both bounds;</li>
 * <li>as a {@link PrimitiveIterator.OfInt} obtained from {@link #rowIds()},
 * draining the matching row ids in ascending order.</li>
 * </ul>
 *
 * <p>
 * Materialising row ids needs a buffer which filtering does not, so it is
 * allocated by {@link #rowIds()} rather than up front: a result used only as a
 * filter never pays for one.
 *
 * <p>
 * A {@code ResultIterator} is single-pass and must be consumed in one mode or
 * the other. The iterator returned by {@link #rowIds()} is a view over the same
 * cursor the block methods advance, rather than an independent traversal, so
 * mixing the two will skip results. {@link #hasNext()} reports whether any
 * results remain, whichever mode is in use.
 *
 * <p>
 * As with every {@link BlockIterator}, the bits returned by {@link #getBits()}
 * describe the block most recently returned by {@link #nextBlock()} and are
 * invalidated by the next call to {@link #hasNext()} or {@link #nextBlock()}.
 */
public interface ResultIterator extends BlockIterator {

	/**
	 * Views this result as an iterator over the matching row ids, in ascending
	 * order. Allocates the buffer the row ids are materialised into, so call it
	 * once and drain the iterator it returns.
	 *
	 * @return the matching row ids in ascending order
	 */
	PrimitiveIterator.OfInt rowIds();

	/**
	 * A result containing no rows at all.
	 */
	ResultIterator EMPTY = new ResultIterator() {

		@Override
		public Bits getBits() {
			return Bits.EMPTY;
		}

		@Override
		public int nextBlock() {
			throw new NoSuchElementException();
		}

		@Override
		public boolean hasNext() {
			return false;
		}

		@Override
		public PrimitiveIterator.OfInt rowIds() {
			return new PrimitiveIterator.OfInt() {

				@Override
				public boolean hasNext() {
					return false;
				}

				@Override
				public int nextInt() {
					throw new NoSuchElementException();
				}
			};
		}
	};
}
