package io.github.richardstartin.slicez;

import java.lang.reflect.Array;
import java.util.function.Supplier;

class Heap<T> {

	@FunctionalInterface
	interface LongComparator {
		int compare(long left, long right);
	}

	private final T[] values;
	private final long[] keys;
	private final LongComparator comparator;
	private int size;
	private int tailIndex;

	@SuppressWarnings("unchecked")
	public Heap(Class<T> klass, Supplier<T> factory, LongComparator comparator, int k) {
		this.values = (T[]) Array.newInstance(klass, k);
		this.keys = new long[k];
		for (int i = 0; i < k; i++) {
			this.values[i] = factory.get();
		}
		this.comparator = comparator;
	}

	public boolean isEmpty() {
		return size == 0;
	}

	public T tail() {
		return values[tailIndex];
	}

	public long tailKey() {
		return keys[tailIndex];
	}

	public int size() {
		return size;
	}

	/**
	 * Insert {@code key} into the heap. If the heap is below capacity, or
	 * {@code key} ranks ahead of the current tail, the value at the key's eventual
	 * position is returned for the caller to populate. Otherwise {@code null} is
	 * returned and the heap is unchanged.
	 *
	 * <p>
	 * Whenever a key is moved (sift up / sift down / eviction) the parallel
	 * {@code values[]} slot is moved in lockstep, so the returned reference is
	 * always the value that will be associated with the inserted key for the rest
	 * of its time in the heap.
	 */
	public T add(long key) {
		if (size < values.length) {
			keys[size] = key;
			if (size == 0 || greaterThan(key, keys[tailIndex])) {
				tailIndex = size;
			}
			int finalIndex = siftUp(size);
			size++;
			return values[finalIndex];
		} else if (!lessThan(key, keys[tailIndex])) {
			return null;
		} else {
			keys[tailIndex] = key;
			int finalIndex = siftUp(tailIndex);
			updateTail();
			return values[finalIndex];
		}
	}

	private void updateTail() {
		int idx = size >>> 1;
		for (int i = idx + 1; i < size; i++) {
			if (greaterThan(keys[i], keys[idx])) {
				idx = i;
			}
		}
		tailIndex = idx;
	}

	/**
	 * Returns the backing array to avoid copying. Usage may invalidate the heap,
	 * use cautiously afterwards.
	 */
	T[] backingArray() {
		return values;
	}

    long[] backingKeys() {
        return keys;
    }

	public T peek() {
		return values[0];
	}

    public long pollKey() {
        T value = values[0];
        long key = keys[0];
        if (--size > 0) {
            values[0] = values[size];
            keys[0] = keys[size];
            // park the polled instance at the just-vacated slot so the next add() can reuse
            // it
            values[size] = value;
            keys[size] = key;
            siftDown(0);
        }
        return key;
    }

	public T poll() {
		T value = values[0];
		if (--size > 0) {
			values[0] = values[size];
			keys[0] = keys[size];
			// park the polled instance at the just-vacated slot so the next add() can reuse
			// it
			values[size] = value;
			siftDown(0);
		}
		return value;
	}

	private int siftUp(int index) {
		long key = keys[index];
		T value = values[index];
		while (index > 0) {
			int parent = (index - 1) >>> 1;
			if (parent == tailIndex) {
				tailIndex = index;
			}
			if (!lessThan(key, keys[parent])) {
				break;
			}
			keys[index] = keys[parent];
			values[index] = values[parent];
			index = parent;
		}
		keys[index] = key;
		values[index] = value;
		return index;
	}

	private void siftDown(int index) {
		long key = keys[index];
		T value = values[index];
		int half = size >>> 1;

		while (index < half) {
			int left = (index << 1) + 1;
			int right = left + 1;
			int smallest = left;
			if (right < size && lessThan(keys[right], keys[left])) {
				smallest = right;
			}

			if (!lessThan(keys[smallest], key)) {
				break;
			}

			keys[index] = keys[smallest];
			values[index] = values[smallest];
			index = smallest;
		}

		keys[index] = key;
		values[index] = value;
	}

	private boolean lessThan(long left, long right) {
		return comparator.compare(left, right) < 0;
	}

	private boolean greaterThan(long left, long right) {
		return comparator.compare(left, right) > 0;
	}
}
