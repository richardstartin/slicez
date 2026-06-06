package io.github.richardstartin.slicez;

import java.nio.ByteBuffer;
import java.util.Arrays;

class Bits {

	final long[] bits;
	private boolean empty = true;
	private boolean full;

	public Bits() {
		this(SliceZ.BLOCK_WORDS);
	}

	public Bits(int numWords) {
		this.bits = new long[numWords];
	}

	boolean isFull() {
		return full;
	}

	boolean isEmpty() {
		return empty;
	}

	public void reset() {
		if (!empty) {
			Arrays.fill(bits, 0L);
		}
		empty = true;
		full = false;
	}

	public int capacity() {
		return bits.length * Long.SIZE;
	}

	public boolean contains(int index) {
		return (bits[index >>> 6] & (1L << index)) != 0;
	}

	public void clear(int limit) {
		Util.clearBitmap(bits, 0, limit);
		full = false;
		if (limit >= capacity()) {
			empty = true;
		}
	}

	public void fill(int limit) {
		Util.fillBitmap(bits, 0, limit);
		empty = false;
		if (limit >= capacity()) {
			full = true;
		}
	}

	public int denseOr(int position, ByteBuffer data) {
		if (!full) {
			if (empty) {
				// data.slice(position, bits.length * Long.BYTES).asLongBuffer().get(bits);
				// we know by the fact the data is represented by a bitset that it is not full
				for (int i = 0; i < bits.length; i++) {
					bits[i] = data.getLong(position + i * Long.BYTES);
				}
			} else {
				for (int i = 0; i < bits.length; i++) {
					bits[i] |= data.getLong(position + i * Long.BYTES);
				}
				boolean filled = true;
				for (int i = 0; i < bits.length && filled; i += 8) {
					filled = -1L == (bits[i] & bits[i + 1] & bits[i + 2] & bits[i + 3] & bits[i + 4] & bits[i + 5]
							& bits[i + 6] & bits[i + 7]);
				}
				full = filled;
			}
		}
		empty = false;
		return position + SliceZ.BLOCK_WORDS * Long.BYTES;
	}

	public int denseAnd(int position, ByteBuffer data) {
		if (!empty) {
			if (full) {
				// data cannot be empty
				for (int i = 0; i < bits.length; i++) {
					bits[i] = data.getLong(position + i * Long.BYTES);
				}
			} else {
				for (int i = 0; i < bits.length; i++) {
					bits[i] &= data.getLong(position + i * Long.BYTES);
				}
				boolean emptied = true;
				for (int i = 0; i < bits.length && emptied; i += 8) {
					emptied = (0L == (bits[i] | bits[i + 1] | bits[i + 2] | bits[i + 3] | bits[i + 4] | bits[i + 5]
							| bits[i + 6] | bits[i + 7]));
				}
				empty = emptied;
			}
		}
		full = false;
		return position + SliceZ.BLOCK_WORDS * Long.BYTES;
	}

	public int denseAndNot(int position, ByteBuffer data) {
		if (!empty) {
			if (full) {
				// data stored as bitset must not be empty or full
				// so cannot result in empty bits
				for (int i = 0; i < bits.length; i++) {
					bits[i] = ~data.getLong(position + i * Long.BYTES);
				}
			} else {
				for (int i = 0; i < bits.length; i++) {
					bits[i] &= ~data.getLong(position + i * Long.BYTES);
				}
				boolean emptied = true;
				for (int i = 0; i < bits.length && emptied; i += 8) {
					emptied = 0L == (bits[i] | bits[i + 1] | bits[i + 2] | bits[i + 3] | bits[i + 4] | bits[i + 5]
							| bits[i + 6] | bits[i + 7]);
				}
				empty = emptied;
			}
		}
		full = false;
		return position + SliceZ.BLOCK_WORDS * Long.BYTES;
	}

	public int denseAndNotCardinality(int position, ByteBuffer data) {
		return denseAndNotCardinality(position, data, capacity());
	}

	public int denseAndNotCardinality(int position, ByteBuffer data, int limit) {
		int cardinality = 0;
		int wordLimit = (limit + 63) >>> 6;
		for (int i = 0; i < wordLimit; i++) {
			cardinality += Long.bitCount(bits[i] & ~data.getLong(position + i * Long.BYTES));
		}
		return cardinality;
	}

	public int sparseAndCardinality(int position, ByteBuffer data) {
		int cardinality = 0;
		int p = position;
		int count = data.getChar(p);
		p += Character.BYTES;
		for (int i = 0; i < count; i++) {
			int next = data.getChar(p);
			p += Character.BYTES;
			long word = bits[next >>> 6];
			cardinality += Long.bitCount(word & (1L << next));
		}
		return cardinality;
	}

	public int sparseAndNotCardinality(int position, ByteBuffer data, int limit) {
		int cardinality = 0;
		int p = position;
		int count = data.getChar(p);
		p += Character.BYTES;
		int prev = 0;
		for (int i = 0; i < count; i++) {
			int next = data.getChar(p);
			p += Character.BYTES;
			cardinality += cardinalityInRange(prev, next);
			prev = next + 1;
		}
		cardinality += cardinalityInRange(prev, limit);
		return cardinality;
	}

	private int cardinalityInRange(int from, int to) {
		if (from >= to)
			return 0;
		int firstWord = from >>> 6;
		int lastWord = to >>> 6;
		if (firstWord == lastWord) {
			return Long.bitCount(bits[firstWord] & -1L << from & -1L >>> -to);
		} else {
			int cardinality = Long.bitCount(bits[firstWord] & -1L << from);
			for (int i = firstWord + 1; i < lastWord; i++) {
				cardinality += Long.bitCount(bits[i]);
			}
			if ((to & 63) != 0) {
				cardinality += Long.bitCount(bits[lastWord] & -1L >>> -to);
			}
			return cardinality;
		}
	}

	public int sparseOrNot(int position, ByteBuffer data, int range) {
		if (!full) {
			int advancedTo = empty
					? Util.coveredSparseOrNot(bits, data, position, range)
					: Util.sparseOrNot(bits, data, position, range);
			full = advancedTo < 0;
			empty = false;
			return Math.abs(advancedTo);
		} else {
			return Util.skipSparse(data, position);
		}
	}

	public int sparseOr(int position, ByteBuffer data) {
		if (!full) {
			int advancedTo = Util.sparseOr(bits, data, position);
			full = advancedTo < 0;
			empty = false;
			return Math.abs(advancedTo);
		} else {
			return Util.skipSparse(data, position);
		}
	}

	public int sparseAnd(int position, ByteBuffer data, int range) {
		if (!empty) {
			int advancedTo = full
					? Util.coveredSparseAnd(bits, data, position)
					: Util.sparseAnd(bits, data, position, range);
			empty = advancedTo <= 0;
			full = false;
			return Math.abs(advancedTo);
		} else {
			full = false;
			return Util.skipSparse(data, position);
		}
	}

	public int sparseAndNot(int position, ByteBuffer data, int range) {
		if (!empty) {
			int advancedTo = Util.sparseAndNot(bits, data, position, range);
			empty = advancedTo <= 0;
			full = false;
			return Math.abs(advancedTo);
		}
		full = false;
		return Util.skipSparse(data, position);
	}

	public void flip(int range) {
		if (empty) {
			empty = false;
			full = true;
			Util.fillBitmap(bits, 0, range);
		} else if (full) {
			full = false;
			empty = true;
			Util.clearBitmap(bits, 0, range);
		} else {
			Util.flipBitmap(bits, 0, range);
		}
	}

	public void flipAnd(Bits other) {
		if (!full && !other.empty) {
			if (empty) {
				System.arraycopy(other.bits, 0, bits, 0, bits.length);
				empty = false;
				full = other.full;
			} else {
				for (int i = 0; i < bits.length; i++) {
					bits[i] = ~bits[i] & other.bits[i];
				}
			}
		} else {
			Arrays.fill(bits, 0L);
			full = false;
			empty = true;
		}
	}

	public void or(Bits other) {
		if (!full && !other.empty) {
			if (other.full) {
				Arrays.fill(bits, -1L);
				full = true;
				empty = false;
			} else if (empty) {
				System.arraycopy(other.bits, 0, bits, 0, bits.length);
				empty = false;
			} else {
				for (int i = 0; i < bits.length; i++) {
					bits[i] |= other.bits[i];
				}
				empty = false;
			}
		}
	}

	public long extractBits(int base, int range, int[] output) {
		int outputLimit = 0;
		if (!empty) {
			if (full) {
				outputLimit = range;
				for (int i = 0; i < outputLimit; i++) {
					output[i] = base + i;
				}
			} else {
				int lastWordIndex = range >>> 6;
				int b = base;
				for (int i = 0; i < lastWordIndex; i++, b += Long.SIZE) {
					long word = bits[i];
					// try to avoid data dependent bit extraction for common case
					if (word == -1L) {
						for (int j = 0; j < Long.SIZE; j++) {
							output[outputLimit + j] = b + j;
						}
						outputLimit += Long.SIZE;
					} else {
						while (word != 0) {
							output[outputLimit++] = b + Long.numberOfTrailingZeros(word);
							word &= (word - 1);
						}
					}
				}
				if (lastWordIndex != bits.length) {
					// (1L << range) - 1 already does the right thing for both partial
					// last words and multiples of 64 (Java shift wraps the count, so
					// (1L << 64) - 1 = 0, i.e. no extra bits to consider).
					long mask = (1L << range) - 1;
					long word = bits[lastWordIndex] & mask;
					while (word != 0) {
						output[outputLimit++] = b + Long.numberOfTrailingZeros(word);
						word &= (word - 1);
					}
				}
			}
		}
		return outputLimit | ((long) (base + SliceZ.BLOCK_SIZE) << 32);
	}

	public int count(int limit) {
		if (empty) {
			return 0;
		}
		if (full) {
			return limit;
		}
		int count = 0;
		int lastWordIndex = (limit >>> 6);
		for (int i = 0; i < lastWordIndex; i++) {
			count += Long.bitCount(bits[i]);
		}
		if (lastWordIndex != bits.length) {
			long mask = (1L << limit) - 1;
			count += Long.bitCount(bits[lastWordIndex] & mask);
		}
		return count;
	}
}
