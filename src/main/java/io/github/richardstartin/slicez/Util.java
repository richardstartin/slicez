package io.github.richardstartin.slicez;

import java.nio.ByteBuffer;
import java.util.Arrays;

class Util {
	static int skipBlock(ByteBuffer data, int position, long typesHigh, long typesLow) {
		long storedSlices = ~(typesHigh & typesLow);
		while (storedSlices != 0) {
			int bit = Long.numberOfTrailingZeros(storedSlices);
			int type = ((int) ((typesHigh >>> bit) & 1) << 1) | (int) ((typesLow >>> bit) & 1);
			position = skipSlice(type, data, position);
			storedSlices &= (storedSlices - 1);
		}
		return position;
	}

	static int skipSlice(int type, ByteBuffer data, int position) {
		return switch (type) {
			case SliceZ.SPARSE_INVERTED, SliceZ.SPARSE -> skipSparse(data, position);
			case SliceZ.DENSE -> skipDense(position);
			case SliceZ.FULL -> position;// nothing stored
			default -> throw new IllegalStateException("invalid type: " + type);
		};
	}

	static int firstRelevantSlice(long threshold, long typesHigh, long typesLow) {
		long fullSlices = typesHigh & typesLow;
		long presentFullSlices = fullSlices & threshold;
		int skipFull = 64 - Long.numberOfLeadingZeros(presentFullSlices);
		// when this slice is reached, the lower bitmap will be empty or full,
		// obliterating any work done previously
		return Math.max(0, skipFull - 1);
	}

	static int sparseAnd(long[] bitmap, ByteBuffer buffer, int position, int max) {
		int count = buffer.getChar(position);
		position += Character.BYTES;
		int prev = 0;
		boolean emptied = true;
		for (int j = 0; j < count; j++) {
			int value = buffer.getChar(position);
			position += Character.BYTES;
			emptied &= (bitmap[value >>> 6] & (1L << value)) == 0L;
			clearBitmap(bitmap, prev, value);
			prev = value + 1;
		}
		clearBitmap(bitmap, prev, max);
		return emptied ? -position : position;
	}

	static int coveredSparseAnd(long[] bitmap, ByteBuffer buffer, int position) {
		// precondition: bitmap contains every bit in the sparse slice
		Arrays.fill(bitmap, 0L);
		int count = buffer.getChar(position);
		position += Character.BYTES;
		for (int j = 0; j < count; j++, position += Character.BYTES) {
			int value = buffer.getChar(position);
			bitmap[value >>> 6] |= (1L << value);
		}
		// postcondition: bitmap is neither empty nor full
		return position;
	}

	static int sparseAndNot(long[] bitmap, ByteBuffer buffer, int position, int range) {
		int count = buffer.getChar(position);
		position += Character.BYTES;
		int limit = position + count * Character.BYTES;
		boolean foundNonEmptyWord = false;
		for (int p = position; p + Character.BYTES < limit; p += Character.BYTES * 2) {
			int value1 = buffer.getChar(p);
			int value2 = buffer.getChar(p + Character.BYTES);
			int wi1 = value1 >>> 6;
			int wi2 = value2 >>> 6;
			if (wi1 == wi2) {
				bitmap[wi1] &= ~((1L << value1) | (1L << value2));
				foundNonEmptyWord |= bitmap[wi1] != 0L;
			} else {
				bitmap[wi1] &= ~(1L << value1);
				bitmap[wi2] &= ~(1L << value2);
				foundNonEmptyWord |= bitmap[wi1] != 0L;
				foundNonEmptyWord |= bitmap[wi2] != 0L;
			}
		}
		if ((count & 1) == 1) {
			int value = buffer.getChar(limit - Character.BYTES);
			bitmap[value >>> 6] &= ~(1L << value);
			foundNonEmptyWord |= bitmap[value >>> 6] != 0L;
		}
		position = limit;
		boolean emptied = !foundNonEmptyWord;
		for (int i = 0; i < range >>> 6 && emptied; i += 8) {
			emptied = 0L == (bitmap[i] | bitmap[i + 1] | bitmap[i + 2] | bitmap[i + 3] | bitmap[i + 4] | bitmap[i + 5]
					| bitmap[i + 6] | bitmap[i + 7]);
		}
		return emptied ? -position : position;
	}

	static int sparseOr(long[] bitmap, ByteBuffer buffer, int position) {
		int count = buffer.getChar(position);
		position += Character.BYTES;
		int limit = position + count * Character.BYTES;
		for (int p = position; p + Character.BYTES < limit; p += Character.BYTES * 2) {
			int v1 = buffer.getChar(p);
			int v2 = buffer.getChar(p + Character.BYTES);
			int wi1 = v1 >>> 6;
			int wi2 = v2 >>> 6;
			if (wi1 == wi2) {
				bitmap[wi1] |= (1L << v1) | (1L << v2);
			} else {
				bitmap[wi1] |= (1L << v1);
				bitmap[wi2] |= (1L << v2);
			}
		}
		if ((count & 1) == 1) {
			int value = buffer.getChar(limit - Character.BYTES);
			bitmap[value >>> 6] |= 1L << value;
		}
		position = limit;
		boolean filled = true;
		for (int i = 0; i < bitmap.length && filled; i += 8) {
			filled = -1L == (bitmap[i] & bitmap[i + 1] & bitmap[i + 2] & bitmap[i + 3] & bitmap[i + 4] & bitmap[i + 5]
					& bitmap[i + 6] & bitmap[i + 7]);
		}
		return filled ? -position : position;
	}

	static int sparseOrNot(long[] bitmap, ByteBuffer buffer, int position, int max) {
		int count = buffer.getChar(position);
		position += Character.BYTES;
		int prev = 0;
		boolean filled = true;
		for (int j = 0; j < count; j++) {
			int value = buffer.getChar(position);
			position += Character.BYTES;
			filled &= (bitmap[value >>> 6] & (1L << value)) != 0;
			fillBitmap(bitmap, prev, value);
			prev = value + 1;
		}
		fillBitmap(bitmap, prev, max);
		return filled ? -position : position;
	}

	static int coveredSparseOrNot(long[] bitmap, ByteBuffer buffer, int position, int max) {
		// precondition: bitmap is empty
		int count = buffer.getChar(position);
		position += Character.BYTES;
		fillBitmap(bitmap, 0, max);
		for (int j = 0; j < count; j++, position += Character.BYTES) {
			int value = buffer.getChar(position);
			bitmap[value >>> 6] ^= (1L << value);
		}
		return position;
	}

	static int skipSparse(ByteBuffer buffer, int position) {
		int count = buffer.getChar(position);
		return position + Character.BYTES * (1 + count);
	}

	static int skipDense(int position) {
		return position + SliceZ.BLOCK_WORDS * Long.BYTES;
	}

	static void flipBitmap(long[] bitmap, int min, int max) {
		if (min == max) {
			return;
		}
		int first = min >>> 6;
		int last = (max - 1) >>> 6;
		bitmap[first] ^= ~(-1L << min);
		for (int i = first; i < last; i++) {
			bitmap[i] = ~bitmap[i];
		}
		bitmap[last] ^= -1L >>> -max;
	}

	static void fillBitmap(long[] bitmap, int min, int max) {
		if (min == max) {
			return;
		}
		int first = min >>> 6;
		int last = (max - 1) >>> 6;
		if (first == last) {
			bitmap[first] |= (-1L << min) & (-1L >>> -max);
			return;
		}
		bitmap[first] |= -1L << min;
		for (int i = first + 1; i < last; i++) {
			bitmap[i] = -1L;
		}
		bitmap[last] |= -1L >>> -max;
	}

	static void clearBitmap(long[] bitmap, int min, int max) {
		if (min == max) {
			return;
		}
		int first = min >>> 6;
		int last = (max - 1) >>> 6;

		if (first == last) {
			bitmap[first] &= ~((-1L << min) & (-1L >>> -max));
			return;
		}
		bitmap[first] &= ~(-1L << min);
		for (int i = first + 1; i < last; i++) {
			bitmap[i] = 0;
		}
		bitmap[last] &= ~(-1L >>> -max);
	}
}
