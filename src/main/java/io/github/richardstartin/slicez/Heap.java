package io.github.richardstartin.slicez;

import java.lang.reflect.Array;
import java.util.Comparator;

class Heap<T> {

	private final T[] values;
	private final Comparator<T> comparator;
	private int size;
	private int tailIndex;

	public Heap(Class<T> klass, Comparator<T> comparator, int k) {
		this.values = (T[]) Array.newInstance(klass, k);
		this.comparator = comparator;
	}

	public boolean isEmpty() {
		return size == 0;
	}

	public T tail() {
		return values[tailIndex];
	}

	public int size() {
		return size;
	}

	public boolean add(T value) {
		if (size < values.length) {
			values[size] = value;
			if (size == 0 || greaterThan(value, values[tailIndex])) {
				tailIndex = size;
			}
			siftUp(size);
			size++;
			return true;
		} else if (!lessThan(value, values[tailIndex])) {
			return false;
		} else {
			values[tailIndex] = value;
			siftUp(tailIndex);
			updateTail();
			return true;
		}
	}

	private void updateTail() {
		int idx = size >>> 1;
		for (int i = idx + 1; i < size; i++) {
			if (greaterThan(values[i], values[idx])) {
				idx = i;
			}
		}
		tailIndex = idx;
	}

	/**
	 * Returns the backing array to avoid copying. Usage may invalidate the heap,
	 * use cautiously afterwards.
	 * 
	 * @return
	 */
	T[] backingArray() {
		return values;
	}

	public T peek() {
		return values[0];
	}

	public T poll() {
		var value = values[0];
		var last = values[--size];
		if (size > 0) {
			values[0] = last;
			siftDown(0);
		}
		return value;
	}

	private void siftUp(int index) {
		var value = values[index];
		while (index > 0) {
			int parent = (index - 1) >>> 1;
			if (parent == tailIndex) {
				tailIndex = index;
			}
			if (values[parent] == value || greaterThan(value, values[parent])) {
				break;
			}
			values[index] = values[parent];
			index = parent;
		}
		values[index] = value;
	}

	private void siftDown(int index) {
		var value = values[index];
		int half = size >>> 1;

		while (index < half) {
			int left = (index << 1) + 1;
			int right = left + 1;
			int smallest = left;
			if (right < size && lessThan(values[right], values[left])) {
				smallest = right;
			}

			if (value == values[smallest] || lessThan(value, values[smallest])) {
				break;
			}

			values[index] = values[smallest];
			index = smallest;
		}

		values[index] = value;
	}

	protected boolean lessThan(T left, T right) {
		return comparator.compare(left, right) < 0;
	}

	protected boolean greaterThan(T left, T right) {
		return comparator.compare(left, right) > 0;
	}
}
