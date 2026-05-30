package io.github.richardstartin.slicez;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class TestHeap {

	record Key(long value, int id) {
	}

	// -------------------------------------------------------------------------
	// Heap.Min<Key>
	// -------------------------------------------------------------------------

	@Test
	void minHeapEmptyOnConstruction() {
		var h = new Heap<>(Key.class, (l, r) -> Long.compareUnsigned(l.value, r.value), 4);
		assertTrue(h.isEmpty());
		assertEquals(0, h.size());
	}

	@Test
	void minHeapAddReturnsTrueWhenBelowCapacity() {
		var h = new Heap<>(Key.class, (l, r) -> Long.compareUnsigned(l.value, r.value), 3);
		assertTrue(h.add(new Key(10, 0)));
		assertTrue(h.add(new Key(5, 1)));
		assertTrue(h.add(new Key(20, 2)));
		assertEquals(3, h.size());
		assertFalse(h.isEmpty());
	}

	@Test
	void minHeapAddAcceptsSmallerValuesAndRejectsHigherValues() {
		var h = new Heap<>(Key.class, (l, r) -> Long.compareUnsigned(l.value, r.value), 2);
		h.add(new Key(10, 0));
		h.add(new Key(5, 1));
		assertEquals(10, h.tail().value);
		assertTrue(h.add(new Key(1, 2)), "full min heap should accept smaller value");
		assertEquals(5, h.tail().value, "tail should be kept up to date");
		assertFalse(h.add(new Key(99, 3)), "full min heap should reject higher value");
		assertEquals(2, h.size());
	}

	@Test
	void minHeapPeekReturnsMinium() {
		var h = new Heap<>(Key.class, (l, r) -> Long.compareUnsigned(l.value, r.value), 4);
		h.add(new Key(30, 2));
		h.add(new Key(10, 0));
		h.add(new Key(20, 1));
		h.add(new Key(5, 3));
		var out = h.peek();
		assertEquals(5L, out.value);
		assertEquals(3, out.id);
		assertEquals(4, h.size()); // peek does not remove
	}

	@Test
	void minHeapPollReturnsMinium() {
		var h = new Heap<>(Key.class, (l, r) -> Long.compareUnsigned(l.value, r.value), 3);
		h.add(new Key(30, 2));
		h.add(new Key(10, 0));
		h.add(new Key(20, 1));
		var out = h.poll();
		assertEquals(10L, out.value);
		assertEquals(0, out.id);
		assertEquals(2, h.size());
	}

	@Test
	void minHeapPollEmptiesInAscendingUnsignedOrder() {
		var h = new Heap<>(Key.class, (l, r) -> Long.compareUnsigned(l.value, r.value), 5);
		long[] unsorted = {30, 5, 20, 1, 15};
		for (int i = 0; i < unsorted.length; i++)
			h.add(new Key(unsorted[i], i));
		long prev = 0;
		while (!h.isEmpty()) {
			var out = h.poll();
			assertTrue(Long.compareUnsigned(prev, out.value) <= 0,
					"expected ascending unsigned order, got " + prev + " then " + out.value);
			prev = out.value;
		}
	}

	@Test
	void minHeapUnsignedOrdering() {
		// unsigned: 0 < Long.MAX_VALUE < Long.MIN_VALUE < -1L
		var h = new Heap<>(Key.class, (l, r) -> Long.compareUnsigned(l.value, r.value), 4);
		h.add(new Key(-1L, 3));
		h.add(new Key(Long.MIN_VALUE, 2));
		h.add(new Key(Long.MAX_VALUE, 1));
		h.add(new Key(0L, 0));

		var out = h.poll();
		assertEquals(0L, out.value);
		out = h.poll();
		assertEquals(Long.MAX_VALUE, out.value);
		out = h.poll();
		assertEquals(Long.MIN_VALUE, out.value);
		out = h.poll();
		assertEquals(-1L, out.value);
	}

	@Test
	void minHeapPollOverwritesFlyweight() {
		var h = new Heap<>(Key.class, (l, r) -> Long.compareUnsigned(l.value, r.value), 2);
		h.add(new Key(10, 0));
		h.add(new Key(5, 1));
		var out = h.poll();
		long first = out.value;
		out = h.poll();
		long second = out.value;
		assertTrue(Long.compareUnsigned(first, second) <= 0);
	}

	@Test
	void minHeapSizeDecreasesOnPoll() {
		var h = new Heap<>(Key.class, (l, r) -> Long.compareUnsigned(l.value, r.value), 3);
		h.add(new Key(1, 0));
		h.add(new Key(2, 1));
		h.add(new Key(3, 2));
		assertEquals(3, h.size());
		var out = h.poll();
		assertEquals(2, h.size());
		out = h.poll();
		assertEquals(1, h.size());
		out = h.poll();
		assertEquals(0, h.size());
		assertTrue(h.isEmpty());
	}

	@Test
	void minHeapTailReflectsLargestAddedValue() {
		// greatest() should return the largest value that has been added,
		// so callers can decide whether to attempt an add.
		var h = new Heap<>(Key.class, (l, r) -> Long.compareUnsigned(l.value, r.value), 4);
		h.add(new Key(10, 0));
		h.add(new Key(5, 1));
		h.add(new Key(20, 2));
		assertEquals(20L, h.tail().value);
	}

	@Test
	void minHeapTailUpdatesAsLargerValuesAdded() {
		var h = new Heap<>(Key.class, (l, r) -> Long.compareUnsigned(l.value, r.value), 4);
		h.add(new Key(3, 0));
		assertEquals(3L, h.tail().value);
		h.add(new Key(7, 1));
		assertEquals(7L, h.tail().value);
		h.add(new Key(2, 2));
		assertEquals(7L, h.tail().value); // stays at 7
	}

	@Test
	void minHeapDuplicateValues() {
		var h = new Heap<>(Key.class, (l, r) -> Long.compareUnsigned(l.value, r.value), 4);
		h.add(new Key(5, 0));
		h.add(new Key(5, 1));
		h.add(new Key(5, 2));
		h.add(new Key(5, 3));
		for (int i = 0; i < 4; i++) {
			var out = h.poll();
			assertEquals(5L, out.value);
		}
		assertTrue(h.isEmpty());
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 2, 3, 10})
	void minHeapCapacityOne(int k) {
		var h = new Heap<>(Key.class, (l, r) -> Long.compareUnsigned(l.value, r.value), k);
		for (int i = 1; i <= k; i++)
			assertTrue(h.add(new Key(i, i)));
		assertEquals(k, h.size());
		assertFalse(h.add(new Key(k, 99)));
		assertTrue(h.add(new Key(0, 100)));
	}

	// -------------------------------------------------------------------------
	// LongIntHeap.Max
	// -------------------------------------------------------------------------

	@Test
	void maxHeapEmptyOnConstruction() {
		var h = new Heap<>(Key.class, (l, r) -> Long.compareUnsigned(r.value, l.value), 4);
		assertTrue(h.isEmpty());
		assertEquals(0, h.size());
	}

	@Test
	void maxHeapAddReturnsTrueWhenBelowCapacity() {
		var h = new Heap<>(Key.class, (l, r) -> Long.compareUnsigned(r.value, l.value), 3);
		assertTrue(h.add(new Key(10, 0)));
		assertTrue(h.add(new Key(5, 1)));
		assertTrue(h.add(new Key(20, 2)));
		assertEquals(3, h.size());
		assertFalse(h.isEmpty());
	}

	@Test
	void maxHeapAddAcceptsLargerValuesAndRejectsSmallerValues() {
		var h = new Heap<>(Key.class, (l, r) -> Long.compareUnsigned(r.value, l.value), 2);
		h.add(new Key(5, 0));
		h.add(new Key(10, 1));
		assertEquals(5, h.tail().value);
		assertTrue(h.add(new Key(20, 2)), "full max heap should accept larger value");
		assertEquals(10, h.tail().value, "tail should be kept up to date");
		assertFalse(h.add(new Key(1, 3)), "full max heap should reject smaller value");
		assertEquals(2, h.size());
	}

	@Test
	void maxHeapPeekReturnsMaximum() {
		var h = new Heap<>(Key.class, (l, r) -> Long.compareUnsigned(r.value, l.value), 4);
		h.add(new Key(30, 2));
		h.add(new Key(10, 0));
		h.add(new Key(20, 1));
		h.add(new Key(5, 3));
		var out = h.peek();
		assertEquals(30L, out.value);
		assertEquals(2, out.id);
		assertEquals(4, h.size()); // peek does not remove
	}

	@Test
	void maxHeapPollReturnsMaximum() {
		var h = new Heap<>(Key.class, (l, r) -> Long.compareUnsigned(r.value, l.value), 3);
		h.add(new Key(30, 2));
		h.add(new Key(10, 0));
		h.add(new Key(20, 1));
		var out = h.poll();
		assertEquals(30L, out.value);
		assertEquals(2, out.id);
		assertEquals(2, h.size());
	}

	@Test
	void maxHeapPollEmptiesInDescendingUnsignedOrder() {
		var h = new Heap<>(Key.class, (l, r) -> Long.compareUnsigned(r.value, l.value), 5);
		long[] unsorted = {30, 5, 20, 1, 15};
		for (int i = 0; i < unsorted.length; i++)
			h.add(new Key(unsorted[i], i));
		long prev = -1L; // unsigned max as starting sentinel
		while (!h.isEmpty()) {
			var out = h.poll();
			assertTrue(Long.compareUnsigned(prev, out.value) >= 0, "expected descending unsigned order, got "
					+ Long.toUnsignedString(prev) + " then " + Long.toUnsignedString(out.value));
			prev = out.value;
		}
	}

	@Test
	void maxHeapUnsignedOrdering() {
		// unsigned: 0 < Long.MAX_VALUE < Long.MIN_VALUE < -1L, so max heap polls -1L
		// first
		var h = new Heap<>(Key.class, (l, r) -> Long.compareUnsigned(r.value, l.value), 4);
		h.add(new Key(0L, 0));
		h.add(new Key(Long.MAX_VALUE, 1));
		h.add(new Key(Long.MIN_VALUE, 2));
		h.add(new Key(-1L, 3));

		var out = h.poll();
		assertEquals(-1L, out.value);
		out = h.poll();
		assertEquals(Long.MIN_VALUE, out.value);
		out = h.poll();
		assertEquals(Long.MAX_VALUE, out.value);
		out = h.poll();
		assertEquals(0L, out.value);
	}

	@Test
	void maxHeapPollOverwritesFlyweight() {
		var h = new Heap<>(Key.class, (l, r) -> Long.compareUnsigned(r.value, l.value), 2);
		h.add(new Key(10, 0));
		h.add(new Key(5, 1));
		var out = h.poll();
		long first = out.value;
		out = h.poll();
		long second = out.value;
		assertTrue(Long.compareUnsigned(first, second) >= 0);
	}

	@Test
	void maxHeapSizeDecreasesOnPoll() {
		var h = new Heap<>(Key.class, (l, r) -> Long.compareUnsigned(r.value, l.value), 3);
		h.add(new Key(1, 0));
		h.add(new Key(2, 1));
		h.add(new Key(3, 2));
		assertEquals(3, h.size());
		var out = h.poll();
		assertEquals(2, h.size());
		out = h.poll();
		assertEquals(1, h.size());
		out = h.poll();
		assertEquals(0, h.size());
		assertTrue(h.isEmpty());
	}

	@Test
	void maxHeapTailReflectsSmallestAddedValue() {
		var h = new Heap<>(Key.class, (l, r) -> Long.compareUnsigned(r.value, l.value), 4);
		h.add(new Key(10, 0));
		h.add(new Key(5, 1));
		h.add(new Key(20, 2));
		assertEquals(5L, h.tail().value);
	}

	@Test
	void maxHeapTailUpdatesAsSmallerValuesAdded() {
		var h = new Heap<>(Key.class, (l, r) -> Long.compareUnsigned(r.value, l.value), 4);
		h.add(new Key(10, 0));
		assertEquals(10L, h.tail().value);
		h.add(new Key(5, 1));
		assertEquals(5L, h.tail().value);
		h.add(new Key(20, 2));
		assertEquals(5L, h.tail().value); // stays at 5
	}

	@Test
	void maxHeapDuplicateValues() {
		var h = new Heap<>(Key.class, (l, r) -> Long.compareUnsigned(r.value, l.value), 4);
		h.add(new Key(5, 0));
		h.add(new Key(5, 1));
		h.add(new Key(5, 2));
		h.add(new Key(5, 3));
		for (int i = 0; i < 4; i++) {
			var out = h.poll();
			assertEquals(5L, out.value);
		}
		assertTrue(h.isEmpty());
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 2, 3, 10})
	void maxHeapCapacityK(int k) {
		var h = new Heap<>(Key.class, (l, r) -> Long.compareUnsigned(r.value, l.value), k);
		for (int i = k; i >= 1; i--)
			assertTrue(h.add(new Key(i, i)));
		assertEquals(k, h.size());
		assertFalse(h.add(new Key(1, 99)));
		assertTrue(h.add(new Key(k + 1, 100)));
	}

	// -------------------------------------------------------------------------
	// Bugs: tail tracking after eviction
	//
	// When add() falls into the eviction branch (size == cap, value < tail),
	// it overwrites values[tailIndex] and calls siftUp(tailIndex). siftUp only
	// updates tailIndex when its in-loop "parent == tailIndex" check fires, but
	// it starts AT tailIndex and walks toward the root, so parent is always
	// less than tailIndex and the check never fires. tailIndex is left pointing
	// at the just-overwritten slot, whose value is no longer the heap's max,
	// and there is no post-eviction scan to repair it.
	// -------------------------------------------------------------------------

	@Test
	void minHeapTailStaleAfterEviction() {
		var h = new Heap<>(Key.class, (l, r) -> Long.compareUnsigned(l.value, r.value), 5);
		// Fill in ascending order; resulting structure is already heap-ordered
		// and tailIndex tracks slot 4 (the 5).
		for (long i = 1; i <= 5; i++)
			h.add(new Key(i, (int) i));
		assertEquals(5L, h.tail().value);

		// Evict the 5 by inserting 4. The slot we overwrite happens to receive
		// the new max (another 4 was already at slot 3), so tail looks fine.
		assertTrue(h.add(new Key(4, 10)));
		assertEquals(4L, h.tail().value);

		// Evict the 4 at slot 4 by inserting 3. The OTHER 4 still lives at
		// slot 3 — the true max is still 4 — but tailIndex was never re-scanned
		// and still points at slot 4, which now holds the freshly-written 3.
		assertTrue(h.add(new Key(3, 11)));
		assertEquals(4L, h.tail().value,
				"true max is 4 (still in heap at slot 3); tail() returns the stale slot value");
	}

	@Test
	void minHeapRejectsValueLessThanActualMaxAfterStaleEviction() {
		// Same setup as above. The stale tail (3) causes the heap to reject a
		// value (3) that is strictly less than the heap's true max (4) and
		// therefore should have displaced it.
		var h = new Heap<>(Key.class, (l, r) -> Long.compareUnsigned(l.value, r.value), 5);
		for (long i = 1; i <= 5; i++)
			h.add(new Key(i, (int) i));
		h.add(new Key(4, 10));
		h.add(new Key(3, 11));
		// 3 < true max (4) so it should be accepted, evicting the 4 at slot 3.
		assertTrue(h.add(new Key(3, 12)), "3 is less than the heap's true max (4) and should be accepted");
	}

	@Test
	void minHeapBottomKContentsWrongAfterStaleTail() {
		// Cumulative inputs: {1,2,3,4,5,4,3,3}. The 5 unsigned-smallest values
		// are {1,2,3,3,3}. With the stale-tail bug the last add(3) is rejected,
		// so the heap polls out as {1,2,3,3,4}.
		var h = new Heap<>(Key.class, (l, r) -> Long.compareUnsigned(l.value, r.value), 5);
		for (long i = 1; i <= 5; i++)
			h.add(new Key(i, (int) i));
		h.add(new Key(4, 10));
		h.add(new Key(3, 11));
		h.add(new Key(3, 12));

		long[] polled = new long[h.size()];
		for (int i = 0; !h.isEmpty(); i++)
			polled[i] = h.poll().value;
		assertArrayEquals(new long[]{1, 2, 3, 3, 3}, polled);
	}

	@Test
	void maxHeapTailStaleAfterEviction() {
		// Symmetric to the min-heap case. Max-heap with cap 5, comparator
		// inverted so the heap retains the 5 largest values and tail() is the
		// current minimum-in-heap (K-th largest).
		var h = new Heap<>(Key.class, (l, r) -> Long.compareUnsigned(r.value, l.value), 5);
		for (long i = 5; i >= 1; i--)
			h.add(new Key(i, (int) i));
		assertEquals(1L, h.tail().value);

		// Insert 2 — evicts the 1; slot now coincidentally holds the new min.
		assertTrue(h.add(new Key(2, 10)));
		assertEquals(2L, h.tail().value);

		// Insert 3 — evicts the 2 at the slot tailIndex points at, but a 2 is
		// still in the heap at slot 3, so the true min is still 2.
		assertTrue(h.add(new Key(3, 11)));
		assertEquals(2L, h.tail().value,
				"true min is 2 (still in heap at slot 3); tail() returns the stale slot value");
	}
}
