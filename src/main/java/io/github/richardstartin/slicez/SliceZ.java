package io.github.richardstartin.slicez;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.PrimitiveIterator;
import java.util.function.LongConsumer;
import java.util.function.LongToDoubleFunction;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

/**
 * A Z-layout bit-sliced index for evaluating point, range, and k-tail
 * (top/bottom) queries over unsorted numerical data.
 *
 * <p>
 * All values are treated as unsigned 64-bit integers and every comparison uses
 * unsigned order ({@link Long#compareUnsigned}). Floating-point data can be
 * indexed by first mapping each value to a sortable {@code long}. The index is
 * immutable once built; construct one with {@link #build(long...)} or via an
 * {@link Appender}.
 *
 * <p>
 * Query methods come in four flavours: those returning a
 * {@link PrimitiveIterator.OfInt} yield the matching row ids in ascending
 * order; the {@code count*} variants return only the number of matches; the
 * {@code sum*} variants return the sum of matching values as a {@code double};
 * and the {@code mean*} variants return their arithmetic mean.
 */
public class SliceZ {

	static final int SPARSE_INVERTED = 0;
	static final int SPARSE = 1;
	static final int DENSE = 2;
	static final int FULL = 3;
	private static final int BLOCK_COUNT = 4;
	private static final int SPARSE_SIZE = 5;
	private static final int NUM_COUNTS = SPARSE_SIZE + 1;
	private static final int COOKIE = 0xfeef1f0;
	private static final int BLOCK_HEADER_SIZE = Long.BYTES * 4; // typesHigh + typesLow + blockMin + blockMax
	static final int HEADER_SIZE = Integer.BYTES // cookie
			+ Integer.BYTES // count
			+ Long.BYTES // min
			+ Long.BYTES // max
			+ NUM_COUNTS * Integer.BYTES; // counts

	static final int BLOCK_SIZE = 0x10000;
	static final int BLOCK_WORDS = BLOCK_SIZE / Long.SIZE; // 16
	static final int SPARSE_THRESHOLD = BLOCK_SIZE / Short.SIZE - 1;

	/**
	 * Accumulates values in insertion order and produces an immutable
	 * {@link SliceZ}.
	 *
	 * <p>
	 * Each appended value is assigned the next row id, starting from {@code 0}.
	 * Values are treated as unsigned. Rows are encoded in blocks as they fill up;
	 * the index is finalized when the appender is built. An {@code Appender} is not
	 * thread-safe and should not be reused after building.
	 */
	public static final class Appender implements LongConsumer {

		private Appender() {
		}

		private final long[] values = new long[BLOCK_SIZE];
		private final int[] sliceCardinalities = new int[Long.SIZE];
		private ByteBuffer output = ByteBuffer.allocate(1 << 10).position(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
		private int rid;
		private long blockMin = -1L;
		private long blockMax = 0L;
		private long min = -1L;
		private long max = 0L;
		private final int[] counts = new int[NUM_COUNTS];

		/**
		 * Appends {@code value} at the next row id, in insertion order. The value is
		 * treated as an unsigned 64-bit integer.
		 *
		 * @param value
		 *            the value to index
		 */
		public void add(long value) {
			blockMin = unsignedMin(value, blockMin);
			blockMax = unsignedMax(value, blockMax);
			min = unsignedMin(value, min);
			max = unsignedMax(value, max);
			final int ridLow = rid & (BLOCK_SIZE - 1);
			values[ridLow] = value;
			if (ridLow == BLOCK_SIZE - 1) {
				flush(BLOCK_SIZE);
			}
			rid++;
		}

		void flush(int blockLimit) {
			// subtract delimiter to reduce the number of populated slices, figure out
			// required slice types. this also conflates empty and full slices, allowing for
			// more non-trivial storage types with a 2-bit/slice type header overhead
			for (int i = 0; i < blockLimit; i++) {
				values[i] = ~(values[i] - blockMin);
				long value = values[i];
				while (value != 0) {
					int index = Long.numberOfTrailingZeros(value);
					value &= (value - 1);
					sliceCardinalities[index]++;
				}
			}
			// compute space required and slice types
			long typesHigh = 0L;
			long typesLow = 0L;
			int sliceStorageSize = 0;
			for (int i = 0; i < sliceCardinalities.length; i++) {
				int sliceCardinality = sliceCardinalities[i];
				assert sliceCardinality != 0 : "empty slice encountered";
				if (sliceCardinality == blockLimit) {
					typesHigh |= ((FULL >>> 1) & 1L) << i;
					typesLow |= (FULL & 1L) << i;
					counts[FULL]++;
				} else if (sliceCardinality < SPARSE_THRESHOLD) {
					typesHigh |= ((SPARSE >>> 1) & 1L) << i;
					typesLow |= (SPARSE & 1L) << i;
					sliceStorageSize += (sliceCardinality + 1) * Character.BYTES;
					counts[SPARSE]++;
					counts[SPARSE_SIZE] += (1 + sliceCardinality) * Character.BYTES;
				} else if (sliceCardinality > blockLimit - SPARSE_THRESHOLD) {
					typesHigh |= ((SPARSE_INVERTED >>> 1) & 1L) << i;
					typesLow |= (SPARSE_INVERTED & 1L) << i;
					sliceStorageSize += (blockLimit - sliceCardinality + 1) * Character.BYTES;
					counts[SPARSE_INVERTED]++;
					counts[SPARSE_SIZE] += (blockLimit - sliceCardinality + 1) * Character.BYTES;
				} else {
					typesHigh |= ((DENSE >>> 1) & 1L) << i;
					typesLow |= (DENSE & 1L) << i;
					sliceStorageSize += BLOCK_WORDS * Long.BYTES;
					counts[DENSE]++;
				}
			}
			long fullSlices = typesHigh & typesLow;
			long storedSlicesMask = ~fullSlices;
			long denseSlicesMask = typesHigh & storedSlicesMask;
			long sparseSlicesMask = typesLow & storedSlicesMask;
			long sparseInvertedSlicesMask = ~typesLow & storedSlicesMask;
			ensureCapacity(BLOCK_HEADER_SIZE + sliceStorageSize);
			int headerPos = output.position();
			output.position(headerPos + BLOCK_HEADER_SIZE); // reserve space for the block header
			while (storedSlicesMask != 0) {
				int bit = Long.numberOfTrailingZeros(storedSlicesMask);
				storedSlicesMask &= (storedSlicesMask - 1);
				if ((sparseSlicesMask & (1L << bit)) != 0) {
					// for sparse slices, translate the bitset into a sorted array
					storeSparseValues((char) sliceCardinalities[bit], blockLimit, bit, false);
				} else if ((denseSlicesMask & (1L << bit)) != 0) {
					int position = output.position();
					for (int i = 0; i < blockLimit; i += Long.SIZE) {
						long toAdd = 0L;
						for (int j = 0; j < Math.min(Long.SIZE, blockLimit - i); j++) {
							toAdd |= ((values[i + j] >>> bit) & 1L) << j;
						}
						assert output.getLong(output.position()) == 0;
						output.putLong(toAdd);
					}
					output.position(position + BLOCK_WORDS * Long.BYTES);
				} else if ((sparseInvertedSlicesMask & (1L << bit)) != 0) {
					// translate the bitset into a sorted array similarly to sparse slices, just
					// invert the check
					storeSparseValues((char) (blockLimit - sliceCardinalities[bit]), blockLimit, bit, true);
				}
			}

			output.putLong(headerPos, typesHigh);
			headerPos += Long.BYTES;
			output.putLong(headerPos, typesLow);
			headerPos += Long.BYTES;
			output.putLong(headerPos, blockMin);
			headerPos += Long.BYTES;
			output.putLong(headerPos, blockMax);
			headerPos += Long.BYTES;

			reset();
			counts[BLOCK_COUNT]++;
		}

		private void storeSparseValues(char cardinality, int blockLimit, int bit, boolean invert) {
			output.putChar(cardinality);
			int added = 0;
			long mask = 1L << bit;
			long filter = invert ? 0L : mask;
			for (int i = 0; i < blockLimit; i++) {
				if ((values[i] & mask) == filter) {
					output.putChar((char) i);
					added++;
				}
			}
			assert added == cardinality;
		}

		private void reset() {
			Arrays.fill(sliceCardinalities, 0);
			blockMin = -1L;
			blockMax = 0L;
		}

		private void ensureCapacity(int needed) {
			if (output.remaining() < needed) {
				ByteBuffer grown = ByteBuffer.allocate(Math.max(output.capacity() * 2, output.position() + needed))
						.order(ByteOrder.LITTLE_ENDIAN);
				output.flip();
				grown.put(output);
				output = grown;
			}
		}

		/**
		 * Flushes any buffered rows and finalises the index.
		 *
		 * @return an immutable {@link SliceZ} over all values added so far
		 */
		public SliceZ build() {
			if ((rid & (BLOCK_SIZE - 1)) != 0) {
				flush(rid & (BLOCK_SIZE - 1));
			}
			writeHeader();
			output.flip();
			byte[] trimmed = new byte[output.limit()];
			output.get(trimmed);
			return new SliceZ(ByteBuffer.wrap(trimmed).order(ByteOrder.LITTLE_ENDIAN));
		}

		private void writeHeader() {
			int offset = 0;
			output.putInt(offset, COOKIE);
			offset += Integer.BYTES;
			output.putInt(offset, rid);
			offset += Integer.BYTES;
			output.putLong(offset, min);
			offset += Long.BYTES;
			output.putLong(offset, max);
			offset += Long.BYTES;
			for (int count : counts) {
				output.putInt(offset, count);
				offset += Integer.BYTES;
			}
		}

		private static long unsignedMin(long x, long y) {
			return Long.compareUnsigned(x, y) < 0 ? x : y;
		}

		private static long unsignedMax(long x, long y) {
			return Long.compareUnsigned(x, y) > 0 ? x : y;
		}

		/**
		 * Equivalent to {@link #add(long)}; lets an {@code Appender} be used wherever a
		 * {@link LongConsumer} is expected (for example as a {@link LongStream} sink).
		 *
		 * @param value
		 *            the value to index
		 */
		@Override
		public void accept(long value) {
			add(value);
		}
	}

	/**
	 * Creates a new empty {@link Appender}.
	 *
	 * @return a fresh appender
	 */
	public static Appender appender() {
		return new Appender();
	}

	private final long min;
	private final long max;
	private final int rowCount;
	private final ByteBuffer data;

	/**
	 * Builds an index over the given values, assigning row ids in argument order.
	 *
	 * @param values
	 *            the values to index, treated as unsigned
	 * @return an immutable index over {@code values}
	 */
	public static SliceZ build(long... values) {
		var appender = appender();
		for (long value : values) {
			appender.add(value);
		}
		return appender.build();
	}

	/**
	 * Maps a {@code SliceZ} to the underlying data without copying it, supporting
	 * memory-mapping etc.
	 *
	 * @param data
	 *            a valid serialized SliceZ
	 * @throws IllegalArgumentException
	 *             if the data is corrupt
	 * @return
	 */
	public static SliceZ map(ByteBuffer data) {
		return new SliceZ(data);
	}

	SliceZ(ByteBuffer data) {
		this.data = data;
		int cookie = data.getInt(0);
		if (cookie != COOKIE) {
			throw new IllegalArgumentException(
					"cookie should be " + Integer.toHexString(COOKIE) + " but found " + Integer.toHexString(cookie));
		}
		this.rowCount = data.getInt(4);
		this.min = data.getLong(8);
		this.max = data.getLong(16);
	}

	/**
	 * Serializes the data as a read-only view.
	 * 
	 * @return a readonly view of the data.
	 */
	public ByteBuffer serialize() {
		return data.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
	}

	/**
	 * @return the total number of {@code SPARSE_INVERTED} slices across all blocks,
	 *         each storing the sorted positions of its unset bits
	 */
	public int getSparseInvertedSliceCount() {
		return getCount(SPARSE_INVERTED);
	}

	/**
	 * @return the total number of {@code SPARSE} slices across all blocks, each
	 *         storing the sorted positions of its set bits
	 */
	public int getSparseSliceCount() {
		return getCount(SPARSE);
	}

	/**
	 * @return the total number of {@code DENSE} slices across all blocks, each
	 *         stored as a full bitset
	 */
	public int getDenseSliceCount() {
		return getCount(DENSE);
	}

	/**
	 * @return the total number of {@code FULL} slices across all blocks, which
	 *         carry no payload
	 */
	public int getFullSliceCount() {
		return getCount(FULL);
	}

	/**
	 * @return the number of blocks the index is partitioned into; each block holds
	 *         up to 65536 rows
	 */
	public int getBlockCount() {
		return getCount(BLOCK_COUNT);
	}

	/**
	 * Returns the index size relative to the uncompressed input, where the
	 * uncompressed size is taken as 8 bytes per row.
	 *
	 * @return the encoded size divided by {@code 8 * rowCount}; values below
	 *         {@code 1.0} indicate compression
	 */
	public double getCompressionRatio() {
		double uncompressed = 8d * rowCount;
		double overhead = getBlockCount() * BLOCK_HEADER_SIZE + HEADER_SIZE;
		double sparseStorage = getCount(SPARSE_SIZE);
		double denseStorage = BLOCK_WORDS * Long.BYTES * getDenseSliceCount();
		return (overhead + sparseStorage + denseStorage) / uncompressed;
	}

	private int getCount(int offset) {
		return data.getInt(24 + offset * Integer.BYTES);
	}

	/**
	 * Finds the rows whose value is strictly less than {@code value} (unsigned).
	 *
	 * @param value
	 *            the exclusive upper bound
	 * @return the matching row ids in ascending order
	 */
	public PrimitiveIterator.OfInt lessThan(long value) {
		return value == 0L ? IntStream.empty().iterator() : lessThanOrEqual(value - 1);
	}

	/**
	 * Counts the rows whose value is strictly less than {@code value} (unsigned).
	 *
	 * @param value
	 *            the exclusive upper bound
	 * @return the number of matching rows
	 */
	public int countLessThan(long value) {
		return value == 0L ? 0 : countLessThanOrEqual(value - 1);
	}

	/**
	 * Sums the rows whose value is strictly less than {@code value} (unsigned).
	 *
	 * @param value
	 *            the exclusive upper bound
	 * @return the sum of matching values
	 */
	public double sumLessThan(long value) {
		return value == 0L ? 0 : sumLessThanOrEqual(value - 1);
	}

	/**
	 * Computes the arithmetic mean of the rows whose value is strictly less than
	 * {@code value} (unsigned).
	 *
	 * @param value
	 *            the exclusive upper bound
	 * @return the mean of matching values, or {@code 0} if there are no matches
	 */
	public double meanLessThan(long value) {
		return value == 0L ? 0 : meanLessThanOrEqual(value - 1);
	}

	/**
	 * Finds the rows whose value is less than or equal to {@code value} (unsigned).
	 *
	 * @param value
	 *            the inclusive upper bound
	 * @return the matching row ids in ascending order
	 */
	public PrimitiveIterator.OfInt lessThanOrEqual(long value) {
		return lessThanOrEqual(value, new IterateAllBlocks(rowCount));
	}

	/**
	 * Finds the rows whose value is less than or equal to {@code value} (unsigned).
	 *
	 * @param value
	 *            the inclusive upper bound
	 * @param filter
	 *            filters the input values
	 * @return the matching row ids in ascending order
	 */
	public PrimitiveIterator.OfInt lessThanOrEqual(long value, BlockIterator filter) {
		if (Long.compareUnsigned(value, min) < 0) {
			return IntStream.empty().iterator();
		}
		if (Long.compareUnsigned(value, max) > 0 && filter.isTrivial()) {
			return IntStream.range(0, rowCount).iterator();
		}
		return new SingleBoundQuery(filter, value, true).iterator();
	}

	/**
	 * Counts the rows whose value is less than or equal to {@code value}
	 * (unsigned).
	 *
	 * @param value
	 *            the inclusive upper bound
	 * @return the number of matching rows
	 */
	public int countLessThanOrEqual(long value) {
		return countLessThanOrEqual(value, new IterateAllBlocks(rowCount));
	}

	/**
	 * Counts the rows whose value is less than or equal to {@code value}
	 * (unsigned).
	 *
	 * @param value
	 *            the inclusive upper bound
	 * @param filter
	 *            filters the input values
	 * @return the number of matching rows
	 */
	public int countLessThanOrEqual(long value, BlockIterator filter) {
		if (Long.compareUnsigned(value, min) < 0) {
			return 0;
		}
		if (Long.compareUnsigned(value, max) > 0 && filter.isTrivial()) {
			return rowCount;
		}
		return new SingleBoundQuery(filter, value, true).matchingCount();
	}

	/**
	 * Sums the rows whose value is less than or equal to {@code value} (unsigned).
	 *
	 * @param value
	 *            the inclusive upper bound
	 * @return the sum of matching values
	 */
	public double sumLessThanOrEqual(long value) {
		return sumLessThanOrEqual(value, new IterateAllBlocks(rowCount));
	}

	/**
	 * Sums the rows whose value is less than or equal to {@code value} (unsigned).
	 *
	 * @param value
	 *            the inclusive upper bound
	 * @param filter
	 *            filters the input values
	 * @return the sum of matching values
	 */
	public double sumLessThanOrEqual(long value, BlockIterator filter) {
		if (Long.compareUnsigned(value, min) < 0) {
			return 0;
		}
		return new SingleBoundQuery(filter, value, true).sum();
	}

	/**
	 * Computes the arithmetic mean of the rows whose value is less than or equal to
	 * {@code value} (unsigned).
	 *
	 * @param value
	 *            the inclusive upper bound
	 * @return the mean of matching values, or {@code 0} if there are no matches
	 */
	public double meanLessThanOrEqual(long value) {
		return meanLessThanOrEqual(value, new IterateAllBlocks(rowCount));
	}

	/**
	 * Computes the arithmetic mean of the rows whose value is less than or equal to
	 * {@code value} (unsigned).
	 *
	 * @param value
	 *            the inclusive upper bound
	 * @param filter
	 *            filters the input values
	 * @return the mean of matching values, or {@code 0} if there are no matches
	 */
	public double meanLessThanOrEqual(long value, BlockIterator filter) {
		if (Long.compareUnsigned(value, min) < 0) {
			return 0;
		}
		return new SingleBoundQuery(filter, value, true).mean();
	}

	/**
	 * Finds the rows whose value is strictly greater than {@code value} (unsigned).
	 *
	 * @param value
	 *            the exclusive lower bound
	 * @return the matching row ids in ascending order
	 */
	public PrimitiveIterator.OfInt greaterThan(long value) {
		return greaterThan(value, new IterateAllBlocks(rowCount));
	}

	/**
	 * Finds the rows whose value is strictly greater than {@code value} (unsigned).
	 *
	 * @param value
	 *            the exclusive lower bound
	 * @param filter
	 *            filters the input values
	 * @return the matching row ids in ascending order
	 */
	public PrimitiveIterator.OfInt greaterThan(long value, BlockIterator filter) {
		if (Long.compareUnsigned(value, min) < 0 && filter.isTrivial()) {
			return IntStream.range(0, rowCount).iterator();
		}
		if (Long.compareUnsigned(value, max) > 0) {
			return IntStream.empty().iterator();
		}
		return new SingleBoundQuery(filter, value, false).iterator();
	}

	/**
	 * Counts the rows whose value is strictly greater than {@code value}
	 * (unsigned).
	 *
	 * @param value
	 *            the exclusive lower bound
	 * @return the number of matching rows
	 */
	public int countGreaterThan(long value) {
		return countGreaterThan(value, new IterateAllBlocks(rowCount));
	}

	/**
	 * Counts the rows whose value is strictly greater than {@code value}
	 * (unsigned).
	 *
	 * @param value
	 *            the exclusive lower bound
	 * @param filter
	 *            filters the input values
	 * @return the number of matching rows
	 */
	public int countGreaterThan(long value, BlockIterator filter) {
		if (Long.compareUnsigned(value, min) < 0 && filter.isTrivial()) {
			return rowCount;
		}
		if (Long.compareUnsigned(value, max) > 0) {
			return 0;
		}
		return new SingleBoundQuery(filter, value, false).matchingCount();
	}

	/**
	 * Sums the rows whose value is strictly greater than {@code value} (unsigned).
	 *
	 * @param value
	 *            the exclusive lower bound
	 * @return the sum of matching values
	 */
	public double sumGreaterThan(long value) {
		return sumGreaterThan(value, new IterateAllBlocks(rowCount));
	}

	/**
	 * Sums the rows whose value is strictly greater than {@code value} (unsigned).
	 *
	 * @param value
	 *            the exclusive lower bound
	 * @param filter
	 *            filters the input values
	 * @return the sum of matching values
	 */
	public double sumGreaterThan(long value, BlockIterator filter) {
		if (Long.compareUnsigned(value, max) > 0) {
			return 0L;
		}
		return new SingleBoundQuery(filter, value, false).sum();
	}

	/**
	 * Computes the arithmetic mean of the rows whose value is strictly greater than
	 * {@code value} (unsigned).
	 *
	 * @param value
	 *            the exclusive lower bound
	 * @return the mean of matching values, or {@code 0} if there are no matches
	 */
	public double meanGreaterThan(long value) {
		return meanGreaterThan(value, new IterateAllBlocks(rowCount));
	}

	/**
	 * Computes the arithmetic mean of the rows whose value is strictly greater than
	 * {@code value} (unsigned).
	 *
	 * @param value
	 *            the exclusive lower bound
	 * @param filter
	 *            filters the input values
	 * @return the mean of matching values, or {@code 0} if there are no matches
	 */
	public double meanGreaterThan(long value, BlockIterator filter) {
		if (Long.compareUnsigned(value, max) > 0) {
			return 0L;
		}
		return new SingleBoundQuery(filter, value, false).mean();
	}

	/**
	 * Finds the rows whose value is greater than or equal to {@code value}
	 * (unsigned).
	 *
	 * @param value
	 *            the inclusive lower bound
	 * @return the matching row ids in ascending order
	 */
	public PrimitiveIterator.OfInt greaterThanOrEqual(long value) {
		return value == 0L ? IntStream.range(0, rowCount).iterator() : greaterThan(value - 1);
	}

	/**
	 * Counts the rows whose value is greater than or equal to {@code value}
	 * (unsigned).
	 *
	 * @param value
	 *            the inclusive lower bound
	 * @return the number of matching rows
	 */
	public int countGreaterThanOrEqual(long value) {
		return value == 0L ? rowCount : countGreaterThan(value - 1);
	}

	/**
	 * Sums the rows whose value is greater than or equal to {@code value}
	 * (unsigned).
	 *
	 * @param value
	 *            the inclusive lower bound
	 * @return the sum of matching values
	 */
	public double sumGreaterThanOrEqual(long value) {
		return value == 0L ? sumLessThanOrEqual(-1L) : sumGreaterThan(value - 1);
	}

	/**
	 * Computes the arithmetic mean of the rows whose value is greater than or equal
	 * to {@code value} (unsigned).
	 *
	 * @param value
	 *            the inclusive lower bound
	 * @return the mean of matching values, or {@code 0} if there are no matches
	 */
	public double meanGreaterThanOrEqual(long value) {
		return value == 0L ? meanLessThanOrEqual(-1L) : meanGreaterThan(value - 1);
	}

	/**
	 * Finds the rows whose value equals {@code value}.
	 *
	 * @param value
	 *            the value to match
	 * @return the matching row ids in ascending order
	 */
	public PrimitiveIterator.OfInt equal(long value) {
		return equal(value, new IterateAllBlocks(rowCount));
	}

	/**
	 * Finds the rows whose value equals {@code value}.
	 *
	 * @param value
	 *            the value to match
	 * @param filter
	 *            filters the input values
	 * @return the matching row ids in ascending order
	 */
	public PrimitiveIterator.OfInt equal(long value, BlockIterator filter) {
		return new EqualityQuery(filter, value, false).iterator();
	}

	/**
	 * Finds the rows whose value does not equal {@code value}.
	 *
	 * @param value
	 *            the value to exclude
	 * @return the matching row ids in ascending order
	 */
	public PrimitiveIterator.OfInt notEqual(long value) {
		return notEqual(value, new IterateAllBlocks(rowCount));
	}

	/**
	 * Finds the rows whose value does not equal {@code value}.
	 *
	 * @param value
	 *            the value to exclude
	 * @param filter
	 *            filters the input values
	 * @return the matching row ids in ascending order
	 */
	public PrimitiveIterator.OfInt notEqual(long value, BlockIterator filter) {
		return new EqualityQuery(filter, value, true).iterator();
	}

	/**
	 * Finds the rows whose value equals any of the given {@code values} (set
	 * membership).
	 *
	 * @param values
	 *            the values to match; duplicates and ordering do not affect the
	 *            result
	 * @return the matching row ids in ascending order, empty if {@code values} is
	 *         empty
	 */
	public PrimitiveIterator.OfInt in(long... values) {
		return in(new IterateAllBlocks(rowCount), values);
	}

	/**
	 * Finds the rows whose value equals any of the given {@code values} (set
	 * membership).
	 *
	 * @param filter
	 *            filters the input values
	 * @param values
	 *            the values to match; duplicates and ordering do not affect the
	 *            result
	 * @return the matching row ids in ascending order, empty if {@code values} is
	 *         empty
	 */
	public PrimitiveIterator.OfInt in(BlockIterator filter, long... values) {
		if (values.length == 0) {
			return IntStream.empty().iterator();
		}
		if (values.length == 1) {
			return equal(values[0], filter);
		}
		return new InQuery(filter, values).iterator();
	}

	/**
	 * Counts the rows whose value equals any of the given {@code values} (set
	 * membership).
	 *
	 * @param values
	 *            the values to match; duplicates and ordering do not affect the
	 *            result
	 * @return the number of matching rows, or {@code 0} if {@code values} is empty
	 */
	public int countIn(long... values) {
		return countIn(new IterateAllBlocks(rowCount), values);
	}

	/**
	 * Counts the rows whose value equals any of the given {@code values} (set
	 * membership).
	 *
	 * @param filter
	 *            filters the input values
	 * @param values
	 *            the values to match; duplicates and ordering do not affect the
	 *            result
	 * @return the number of matching rows, or {@code 0} if {@code values} is empty
	 */
	public int countIn(BlockIterator filter, long... values) {
		if (values.length == 0) {
			return 0;
		}
		if (values.length == 1) {
			return countEqual(values[0], filter);
		}
		return new InQuery(filter, values).matchingCount();
	}

	/**
	 * Sums the values of rows whose value equals any of the given {@code values}
	 * (set membership).
	 *
	 * @param values
	 *            the values to match; duplicates and ordering do not affect the
	 *            result
	 * @return the sum of matching values, or {@code 0} if {@code values} is empty
	 */
	public double sumIn(long... values) {
		return sumIn(new IterateAllBlocks(rowCount), values);
	}

	/**
	 * Sums the values of rows whose value equals any of the given {@code values}
	 * (set membership).
	 *
	 * @param filter
	 *            filters the input values
	 * @param values
	 *            the values to match; duplicates and ordering do not affect the
	 *            result
	 * @return the sum of matching values, or {@code 0} if {@code values} is empty
	 */
	public double sumIn(BlockIterator filter, long... values) {
		if (values.length == 0) {
			return 0D;
		}
		if (values.length == 1) {
			return sumEqual(values[0]);
		}
		return new InQuery(filter, values).sum();
	}

	/**
	 * Computes the arithmetic mean of the values of rows whose value equals any of
	 * the given {@code values} (set membership).
	 *
	 * @param values
	 *            the values to match; duplicates and ordering do not affect the
	 *            result
	 * @return the mean of matching values, or {@code 0} if {@code values} is empty
	 *         or no rows match
	 */
	public double meanIn(long... values) {
		return meanIn(new IterateAllBlocks(rowCount), values);
	}

	/**
	 * Computes the arithmetic mean of the values of rows whose value equals any of
	 * the given {@code values} (set membership).
	 *
	 * @param filter
	 *            filters the input values
	 * @param values
	 *            the values to match; duplicates and ordering do not affect the
	 *            result
	 * @return the mean of matching values, or {@code 0} if {@code values} is empty
	 *         or no rows match
	 */
	public double meanIn(BlockIterator filter, long... values) {
		if (values.length == 0) {
			return 0D;
		}
		if (values.length == 1) {
			return meanEqual(values[0]);
		}
		return new InQuery(filter, values).mean();
	}

	/**
	 * Counts the rows whose value equals {@code value}.
	 *
	 * @param value
	 *            the value to match
	 * @return the number of matching rows
	 */
	public int countEqual(long value) {
		return countEqual(value, new IterateAllBlocks(rowCount));
	}

	/**
	 * Counts the rows whose value equals {@code value}.
	 *
	 * @param value
	 *            the value to match
	 * @param filter
	 *            filters the input values
	 * @return the number of matching rows
	 */
	public int countEqual(long value, BlockIterator filter) {
		return new EqualityQuery(filter, value, false).matchingCount();
	}

	/**
	 * Counts the rows whose value does not equal {@code value}.
	 *
	 * @param value
	 *            the value to exclude
	 * @return the number of matching rows
	 */
	public int countNotEqual(long value) {
		return countNotEqual(value, new IterateAllBlocks(rowCount));
	}

	/**
	 * Counts the rows whose value does not equal {@code value}.
	 *
	 * @param value
	 *            the value to exclude
	 * @param filter
	 *            filters the input values
	 * @return the number of matching rows
	 */
	public int countNotEqual(long value, BlockIterator filter) {
		return new EqualityQuery(filter, value, true).matchingCount();
	}

	/**
	 * Sums the values equal to {@code value}.
	 *
	 * @param value
	 *            the value to match
	 * @return the sum of matching rows
	 */
	public double sumEqual(long value) {
		return countEqual(value) * (double) value;
	}

	/**
	 * Computes the arithmetic mean of the values equal to {@code value}. Since
	 * every matching row holds exactly {@code value}, the result is {@code value}
	 * when at least one row matches, or {@code 0} otherwise.
	 *
	 * @param value
	 *            the value to match
	 * @return {@code (double) value} if any row matches, {@code 0} otherwise
	 */
	public double meanEqual(long value) {
		return countEqual(value) > 0 ? value : 0D;
	}

	/**
	 * Sums the values not equal to {@code value}.
	 *
	 * @param value
	 *            the value to exclude
	 * @return the sum of matching values
	 */
	public double sumNotEqual(long value) {
		return sumNotEqual(value, new IterateAllBlocks(rowCount));
	}

	/**
	 * Sums the values not equal to {@code value}.
	 *
	 * @param value
	 *            the value to exclude
	 * @param filter
	 *            filters the input values
	 * @return the sum of matching values
	 */
	public double sumNotEqual(long value, BlockIterator filter) {
		// fixme if we store the sum this can be computed faster by subtracting sumEqual
		// from the global sum
		return new EqualityQuery(filter, value, true).sum();
	}

	/**
	 * Computes the arithmetic mean of the values not equal to {@code value}.
	 *
	 * @param value
	 *            the value to exclude
	 * @return the mean of matching values, or {@code 0} if there are no matches
	 */
	public double meanNotEqual(long value) {
		return meanNotEqual(value, new IterateAllBlocks(rowCount));
	}

	/**
	 * Computes the arithmetic mean of the values not equal to {@code value}.
	 *
	 * @param value
	 *            the value to exclude
	 * @param filter
	 *            filters the input values
	 * @return the mean of matching values, or {@code 0} if there are no matches
	 */
	public double meanNotEqual(long value, BlockIterator filter) {
		return new EqualityQuery(filter, value, true).mean();
	}

	/**
	 * Finds the rows whose value lies in the half-open unsigned range
	 * {@code [lower, upper)} — that is, {@code lower <= v < upper} in unsigned
	 * order. The lower bound is inclusive and the upper bound is exclusive.
	 *
	 * @param lower
	 *            the inclusive lower bound
	 * @param upper
	 *            the exclusive upper bound
	 * @return the matching row ids in ascending order, empty if {@code upper} is
	 *         less than or equal to {@code lower} in unsigned order
	 */
	public PrimitiveIterator.OfInt between(long lower, long upper) {
		return between(lower, upper, new IterateAllBlocks(rowCount));
	}

	/**
	 * Finds the rows whose value lies in the half-open unsigned range
	 * {@code [lower, upper)} — that is, {@code lower <= v < upper} in unsigned
	 * order. The lower bound is inclusive and the upper bound is exclusive.
	 *
	 * @param lower
	 *            the inclusive lower bound
	 * @param upper
	 *            the exclusive upper bound
	 * @param filter
	 *            filters the input values
	 * @return the matching row ids in ascending order, empty if {@code upper} is
	 *         less than or equal to {@code lower} in unsigned order
	 */
	public PrimitiveIterator.OfInt between(long lower, long upper, BlockIterator filter) {
		if (Long.compareUnsigned(upper, min) < 0 || Long.compareUnsigned(max, lower) < 0
				|| Long.compareUnsigned(upper, lower) <= 0) {
			return IntStream.empty().iterator();
		}
		if (Long.compareUnsigned(lower, min) < 0 && Long.compareUnsigned(max, upper) < 0) {
			return IntStream.range(0, rowCount).iterator();
		}
		if (lower == 0L) {
			return upper == 0L ? IntStream.empty().iterator() : lessThanOrEqual(upper - 1, filter);
		}
		return new BetweenQuery(filter, lower - 1, upper - 1).iterator();
	}

	/**
	 * Counts the rows whose value lies in the half-open unsigned range
	 * {@code [lower, upper)} — that is, {@code lower <= v < upper} in unsigned
	 * order. The lower bound is inclusive and the upper bound is exclusive.
	 *
	 * @param lower
	 *            the inclusive lower bound
	 * @param upper
	 *            the exclusive upper bound
	 * @return the number of matching rows
	 */
	public int countBetween(long lower, long upper) {
		return countBetween(lower, upper, new IterateAllBlocks(rowCount));
	}

	/**
	 * Counts the rows whose value lies in the half-open unsigned range
	 * {@code [lower, upper)} — that is, {@code lower <= v < upper} in unsigned
	 * order. The lower bound is inclusive and the upper bound is exclusive.
	 *
	 * @param lower
	 *            the inclusive lower bound
	 * @param upper
	 *            the exclusive upper bound
	 * @param filter
	 *            filters the input values
	 * @return the number of matching rows
	 */
	public int countBetween(long lower, long upper, BlockIterator filter) {
		if (lower == 0L) {
			return upper == 0L ? 0 : countLessThanOrEqual(upper - 1, filter);
		}
		if (Long.compareUnsigned(upper, lower) <= 0) {
			return 0;
		}
		return new BetweenQuery(filter, lower - 1, upper - 1).matchingCount();
	}

	/**
	 * Sums all values lying in the half-open unsigned range {@code [lower, upper)}
	 * — that is, {@code lower <= v < upper} in unsigned order. The lower bound is
	 * inclusive and the upper bound is exclusive.
	 *
	 * @param lower
	 *            the inclusive lower bound
	 * @param upper
	 *            the exclusive upper bound
	 * @return the sum of matching values
	 */
	public double sumBetween(long lower, long upper) {
		return sumBetween(lower, upper, new IterateAllBlocks(rowCount));
	}

	/**
	 * Sums all values lying in the half-open unsigned range {@code [lower, upper)}
	 * — that is, {@code lower <= v < upper} in unsigned order. The lower bound is
	 * inclusive and the upper bound is exclusive.
	 *
	 * @param lower
	 *            the inclusive lower bound
	 * @param upper
	 *            the exclusive upper bound
	 * @param filter
	 *            filters the input values
	 * @return the sum of matching values
	 */
	public double sumBetween(long lower, long upper, BlockIterator filter) {
		if (lower == 0L) {
			return upper == 0L ? 0 : sumLessThanOrEqual(upper - 1, filter);
		}
		if (Long.compareUnsigned(upper, lower) <= 0) {
			return 0;
		}
		return new BetweenQuery(filter, lower - 1, upper - 1).sum();
	}

	/**
	 * Computes the arithmetic mean of all values lying in the half-open unsigned
	 * range {@code [lower, upper)} — that is, {@code lower <= v < upper} in
	 * unsigned order. The lower bound is inclusive and the upper bound is
	 * exclusive.
	 *
	 * @param lower
	 *            the inclusive lower bound
	 * @param upper
	 *            the exclusive upper bound
	 * @return the mean of matching values, or {@code 0} if there are no matches
	 */
	public double meanBetween(long lower, long upper) {
		return meanBetween(lower, upper, new IterateAllBlocks(rowCount));
	}

	/**
	 * Computes the arithmetic mean of all values lying in the half-open unsigned
	 * range {@code [lower, upper)} — that is, {@code lower <= v < upper} in
	 * unsigned order. The lower bound is inclusive and the upper bound is
	 * exclusive.
	 *
	 * @param lower
	 *            the inclusive lower bound
	 * @param upper
	 *            the exclusive upper bound
	 * @param filter
	 *            filters the input values
	 * @return the mean of matching values, or {@code 0} if there are no matches
	 */
	public double meanBetween(long lower, long upper, BlockIterator filter) {
		if (lower == 0L) {
			return upper == 0L ? 0 : meanLessThanOrEqual(upper - 1, filter);
		}
		if (Long.compareUnsigned(upper, lower) <= 0) {
			return 0;
		}
		return new BetweenQuery(filter, lower - 1, upper - 1).mean();
	}

	/**
	 * Computes an unfiltered histogram over the subrange {@code [min, last bound)}
	 * of the data set. The {@code min} argument is the inclusive lower bound of the
	 * first bucket; each {@code upperBounds} argument is the <em>exclusive</em>
	 * upper bound of a bucket and the bounds must be given in ascending unsigned
	 * order. Bucket {@code i} counts the rows whose value {@code v} satisfies
	 * {@code lower(i) <= v < upperBounds[i]} (unsigned), where {@code lower(0)} is
	 * {@code min} and {@code lower(i)} is {@code upperBounds[i - 1]} otherwise.
	 * Rows below {@code min} or at or above the final bound fall outside every
	 * bucket and are not counted.
	 *
	 * <p>
	 * The returned array has the same length as {@code upperBounds}, so callers can
	 * zip the thresholds and counts together by index.
	 *
	 * @param min
	 *            the inclusive lower bound of the first bucket
	 * @param upperBounds
	 *            the exclusive upper bounds of each bucket, in ascending unsigned
	 *            order; every bound must be greater than or equal to {@code min}
	 * @return the per-bucket row counts, indexed in parallel with
	 *         {@code upperBounds}
	 * @throws IllegalArgumentException
	 *             if any bound is less than {@code min} in unsigned order
	 */
	public long[] histogram(long min, long... upperBounds) {
		return histogram(new IterateAllBlocks(rowCount), min, upperBounds);
	}

	/**
	 * Computes a filtered histogram over the subrange {@code [min, last bound)} of
	 * the data set. The {@code min} argument is the inclusive lower bound of the
	 * first bucket; each {@code upperBounds} argument is the <em>exclusive</em>
	 * upper bound of a bucket and the bounds must be given in ascending unsigned
	 * order. Bucket {@code i} counts the rows whose value {@code v} satisfies
	 * {@code lower(i) <= v < upperBounds[i]} (unsigned), where {@code lower(0)} is
	 * {@code min} and {@code lower(i)} is {@code upperBounds[i - 1]} otherwise.
	 * Rows below {@code min} or at or above the final bound fall outside every
	 * bucket and are not counted.
	 *
	 * <p>
	 * The returned array has the same length as {@code upperBounds}, so callers can
	 * zip the thresholds and counts together by index.
	 *
	 * @param blockIterator
	 *            the filter to apply prior to producing the histogram
	 * @param min
	 *            the inclusive lower bound of the first bucket
	 * @param upperBounds
	 *            the exclusive upper bounds of each bucket, in ascending unsigned
	 *            order; every bound must be greater than or equal to {@code min}
	 * @return the per-bucket row counts, indexed in parallel with
	 *         {@code upperBounds}
	 * @throws IllegalArgumentException
	 *             if any bound is less than {@code min} in unsigned order
	 */
	public long[] histogram(BlockIterator blockIterator, long min, long... upperBounds) {
		for (long bound : upperBounds) {
			if (Long.compareUnsigned(bound, min) < 0) {
				throw new IllegalArgumentException("histogram bound " + Long.toUnsignedString(bound) + " is below min "
						+ Long.toUnsignedString(min));
			}
		}
		if (upperBounds.length == 0) {
			return new long[0];
		}
		return new HistogramQuery(blockIterator).histogram(min, upperBounds);
	}

	/**
	 * @return the largest indexed value in unsigned order, or {@code 0} if the
	 *         index is empty
	 */
	public long max() {
		return max;
	}

	/**
	 * @return the smallest indexed value in unsigned order, or {@code -1} (unsigned
	 *         maximum) if the index is empty
	 */
	public long min() {
		return min;
	}

	/**
	 * Finds the row ids of the {@code k} smallest values in unsigned order. Fewer
	 * than {@code k} are returned only when the index has fewer rows. When several
	 * rows share the boundary value, which of them are returned is unspecified.
	 *
	 * @param k
	 *            the number of rows to select
	 * @return the row ids of the bottom-{@code k} values, in ascending row-id order
	 * @throws IllegalArgumentException
	 *             if {@code k} is negative
	 */
	public PrimitiveIterator.OfInt bottom(int k) {
		if (k < 0) {
			throw new IllegalArgumentException("bottom-k negative k: " + k);
		}
		if (k == 0) {
			return IntStream.empty().iterator();
		}
		return new KTailRowsIdsQuery(k, true);
	}

	/**
	 * Returns the {@code k} smallest values in unsigned order. This is consistent
	 * with {@link #bottom(int)}: it selects the same rows but yields their values
	 * rather than their row ids.
	 *
	 * @param k
	 *            the number of values to select
	 * @return the bottom-{@code k} values
	 * @throws IllegalArgumentException
	 *             if {@code k} is negative
	 */
	public PrimitiveIterator.OfLong bottomValues(int k) {
		if (k < 0) {
			throw new IllegalArgumentException("bottom-k negative k: " + k);
		}
		if (k == 0) {
			return LongStream.empty().iterator();
		}
		return new KTailValuesQuery(k, true);
	}

	/**
	 * Sums the {@code k} smallest values in unsigned order. The sum is computed
	 * with wrapping {@code long} arithmetic over the same values
	 * {@link #bottomValues(int)} would return.
	 *
	 * @param k
	 *            the number of values to sum
	 * @return the sum of the bottom-{@code k} values, or {@code 0} if {@code k} is
	 *         zero
	 * @throws IllegalArgumentException
	 *             if {@code k} is negative
	 */
	public long bottomSum(int k) {
		if (k < 0) {
			throw new IllegalArgumentException("top-k negative k: " + k);
		}
		if (k == 0) {
			return 0L;
		}
		return new KTailValuesQuery(k, true).sumLongs();
	}

	/**
	 * Computes the arithmetic mean of the {@code k} smallest values in unsigned
	 * order. This is consistent with {@link #bottomValues(int)}: it averages the
	 * same values that method would yield.
	 *
	 * @param k
	 *            the number of values to average
	 * @return the mean of the bottom-{@code k} values, or {@code 0} if {@code k} is
	 *         zero
	 * @throws IllegalArgumentException
	 *             if {@code k} is negative
	 */
	public double bottomMean(int k) {
		if (k < 0) {
			throw new IllegalArgumentException("top-k negative k: " + k);
		}
		if (k == 0) {
			return 0L;
		}
		return new KTailValuesQuery(k, true).mean();
	}

	/**
	 * Sums {@code encoding} applied to each of the {@code k} smallest values in
	 * unsigned order. Useful when values are sortable {@code long} encodings of
	 * another domain (for example doubles), letting the decoded magnitudes be
	 * summed.
	 *
	 * @param k
	 *            the number of values to sum
	 * @param encoding
	 *            maps each selected value to the {@code double} that is summed
	 * @return the sum of {@code encoding} over the bottom-{@code k} values, or
	 *         {@code 0} if {@code k} is zero
	 * @throws IllegalArgumentException
	 *             if {@code k} is negative
	 */
	public double bottomSum(int k, LongToDoubleFunction encoding) {
		if (k < 0) {
			throw new IllegalArgumentException("bottom-k negative k: " + k);
		}
		if (k == 0) {
			return 0L;
		}
		return new KTailValuesQuery(k, true).sumDoubles(encoding);
	}

	/**
	 * Finds the row ids of the {@code k} largest values in unsigned order. Fewer
	 * than {@code k} are returned only when the index has fewer rows. When several
	 * rows share the boundary value, which of them are returned is unspecified.
	 *
	 * @param k
	 *            the number of rows to select
	 * @return the row ids of the top-{@code k} values, in ascending row-id order
	 * @throws IllegalArgumentException
	 *             if {@code k} is negative
	 */
	public PrimitiveIterator.OfInt top(int k) {
		if (k < 0) {
			throw new IllegalArgumentException("top-k negative k: " + k);
		}
		if (k == 0) {
			return IntStream.empty().iterator();
		}
		return new KTailRowsIdsQuery(k, false);
	}

	/**
	 * Returns the {@code k} largest values in unsigned order. This is consistent
	 * with {@link #top(int)}: it selects the same rows but yields their values
	 * rather than their row ids.
	 *
	 * @param k
	 *            the number of values to select
	 * @return the top-{@code k} values
	 * @throws IllegalArgumentException
	 *             if {@code k} is negative
	 */
	public PrimitiveIterator.OfLong topValues(int k) {
		if (k < 0) {
			throw new IllegalArgumentException("top-k negative k: " + k);
		}
		if (k == 0) {
			return LongStream.empty().iterator();
		}
		return new KTailValuesQuery(k, false);
	}

	/**
	 * Sums the {@code k} largest values in unsigned order. The sum is computed with
	 * wrapping {@code long} arithmetic over the same values {@link #topValues(int)}
	 * would return.
	 *
	 * @param k
	 *            the number of values to sum
	 * @return the sum of the top-{@code k} values, or {@code 0} if {@code k} is
	 *         zero
	 * @throws IllegalArgumentException
	 *             if {@code k} is negative
	 */
	public long topSum(int k) {
		if (k < 0) {
			throw new IllegalArgumentException("top-k negative k: " + k);
		}
		if (k == 0) {
			return 0L;
		}
		return new KTailValuesQuery(k, false).sumLongs();
	}

	/**
	 * Sums {@code encoding} applied to each of the {@code k} largest values in
	 * unsigned order. Useful when values are sortable {@code long} encodings of
	 * another domain (for example doubles), letting the decoded magnitudes be
	 * summed.
	 *
	 * @param k
	 *            the number of values to sum
	 * @param encoding
	 *            maps each selected value to the {@code double} that is summed
	 * @return the sum of {@code encoding} over the top-{@code k} values, or
	 *         {@code 0} if {@code k} is zero
	 * @throws IllegalArgumentException
	 *             if {@code k} is negative
	 */
	public double topSum(int k, LongToDoubleFunction encoding) {
		if (k < 0) {
			throw new IllegalArgumentException("top-k negative k: " + k);
		}
		if (k == 0) {
			return 0L;
		}
		return new KTailValuesQuery(k, false).sumDoubles(encoding);
	}

	protected class KTailQuery {

		static final Heap.LongComparator UNSIGNED_NATURAL = Long::compareUnsigned;
		static final Heap.LongComparator UNSIGNED_REVERSE = (a, b) -> Long.compareUnsigned(b, a);

		protected static final class Block {
			long delimiter;
			long min;
			int offset;
			int base;
		}

		protected static final class Row {
			int rid;
		}

		protected Heap<Block> findCandidateBlocks(int k, boolean bottom) {
			var blockHeap = new Heap<>(Block.class, Block::new, bottom ? UNSIGNED_NATURAL : UNSIGNED_REVERSE, k);
			int position = HEADER_SIZE;
			int base = 0;
			while (base < rowCount) {
				int blockOffset = position;
				long typesHigh = data.getLong(position);
				position += Long.BYTES;
				long typesLow = data.getLong(position);
				position += Long.BYTES;
				long blockMin = data.getLong(position);
				position += Long.BYTES;
				long blockMax = data.getLong(position);
				position += Long.BYTES;
				position = Util.skipBlock(data, position, typesHigh, typesLow);
				long delimiter = bottom ? blockMin : blockMax;
				Block block = blockHeap.add(delimiter);
				if (block != null) {
					block.delimiter = delimiter;
					block.min = blockMin;
					block.offset = blockOffset;
					block.base = base;
				}
				base += BLOCK_SIZE;
			}
			return blockHeap;
		}

		protected Heap<Row> findRows(Heap<Block> blockHeap, int k, boolean bottom, Bits buffer) {
			return bottom ? findBottomRows(blockHeap, k, buffer) : findTopRows(blockHeap, k, buffer);
		}

		protected Heap<Row> findTopRows(Heap<Block> blockHeap, int k, Bits buffer) {
			var resultHeap = new Heap<>(Row.class, Row::new, UNSIGNED_REVERSE, k);
			int blockCount = blockHeap.size();
			if (blockCount != 0) {
				Block[] blocks = blockHeap.backingArray();
				Arrays.sort(blocks, 0, blockCount, (a, b) -> Long.compareUnsigned(b.delimiter, a.delimiter));
				long threshold = blockCount < k ? 0L : blocks[blockCount - 1].delimiter;
				for (int i = 0; i < blockCount; i++) {
					var block = blocks[i];
					if (resultHeap.size() == k && Long.compareUnsigned(block.delimiter, threshold) <= 0) {
						break;
					}
					int range = Math.min(rowCount - block.base, BLOCK_SIZE);
					if (threshold == 0L) {
						buffer.fill(range);
					} else {
						evaluateSingleBoundQueryBlock(data, block.offset, range, buffer, threshold - 1, false);
					}
					extractValuesToHeap(data, block, buffer, resultHeap, range);
					if (resultHeap.size() == k) {
						long bound = resultHeap.tailKey();
						if (Long.compareUnsigned(bound, threshold) > 0) {
							threshold = bound;
						}
					}
				}
			}
			return resultHeap;
		}

		protected Heap<Row> findBottomRows(Heap<Block> blockHeap, int k, Bits buffer) {
			var resultHeap = new Heap<>(Row.class, Row::new, UNSIGNED_NATURAL, k);
			int blockCount = blockHeap.size();
			Block[] blocks = blockHeap.backingArray();
			Arrays.sort(blocks, 0, blockCount, (a, b) -> Long.compareUnsigned(a.delimiter, b.delimiter));
			// fixme review this logic
			long threshold = blockCount == 1 || blockCount < k ? -1L : blocks[blockCount - 1].delimiter;
			for (int i = 0; i < blockCount; i++) {
				var block = blocks[i];
				if (resultHeap.size() == k) {
					long bound = resultHeap.tailKey();
					if (Long.compareUnsigned(bound, threshold) < 0)
						threshold = bound;
					if (Long.compareUnsigned(block.delimiter, threshold) >= 0)
						break;
				}
				int range = Math.min(rowCount - block.base, BLOCK_SIZE);
				evaluateSingleBoundQueryBlock(data, block.offset, range, buffer, threshold, true);
				extractValuesToHeap(data, block, buffer, resultHeap, range);
			}
			return resultHeap;
		}

		protected void extractValuesToHeap(ByteBuffer data, Block block, Bits filter, Heap<Row> heap, int range) {
			int position = block.offset;
			long typesHigh = data.getLong(position);
			position += Long.BYTES;
			long typesLow = data.getLong(position);
			position += Long.BYTES;
			long blockMin = data.getLong(position);
			position += Long.BYTES;
			long blockMax = data.getLong(position);
			position += Long.BYTES;
			// fixme allocate these at a higher scope for reuse across blocks
			int size = filter.count(range);
			long[] values = new long[size];
			// rowIndex is null when the value range is contiguous,
			// which avoids needing to create the index at all, and avoids binary searches
			// when the number of rows are at their densest
			int[] rowIndex = null;
			if (!filter.isFull()) {
				rowIndex = new int[size];
				for (int i = 0, r = 0; i < filter.bits.length && r < rowIndex.length; i++) {
					long word = filter.bits[i];
					while (word != 0 && r < rowIndex.length) {
						rowIndex[r++] = i * Long.SIZE + Long.numberOfTrailingZeros(word);
						word &= (word - 1);
					}
				}
			}
			long fullSlices = typesHigh & typesLow;
			long storedSlices = ~fullSlices;
			while (storedSlices != 0) {
				int slice = Long.numberOfTrailingZeros(storedSlices);
				storedSlices &= (storedSlices - 1);
				int type = ((int) ((typesHigh >>> slice) & 1) << 1) | (int) ((typesLow >>> slice) & 1);
				long bit = 1L << slice;
				switch (type) {
					case SPARSE -> {
						int count = data.getChar(position);
						position += Character.BYTES;
						for (int i = 0; i < count; i++) {
							int row = data.getChar(position);
							position += Character.BYTES;
							if (filter.contains(row)) {
								// fixme - this is probably going to be a bottleneck
								values[rowIndex == null ? row : Arrays.binarySearch(rowIndex, row)] |= bit;
							}
						}
					}
					case SPARSE_INVERTED -> {
						int count = data.getChar(position);
						position += Character.BYTES;
						int prev = 0;
						for (int i = 0; i < count; i++) {
							int next = data.getChar(position);
							position += Character.BYTES;
							for (int row = prev; row < next; row++) {
								if (filter.contains(row)) {
									// fixme - this is probably going to be a bottleneck
									values[rowIndex == null ? row : Arrays.binarySearch(rowIndex, row)] |= bit;
								}
							}
							prev = next + 1;
						}
						for (int row = prev; row < range; row++) {
							if (filter.contains(row)) {
								// fixme - this is probably going to be a bottleneck
								values[rowIndex == null ? row : Arrays.binarySearch(rowIndex, row)] |= bit;
							}
						}
					}
					case DENSE -> {
						for (int i = 0; i < BLOCK_WORDS; i++) {
							long filterWord = filter.bits[i];
							long storedWord = data.getLong(position);
							position += Long.BYTES;
							long rows = filterWord & storedWord;
							while (rows != 0) {
								int row = i * Long.SIZE + Long.numberOfTrailingZeros(rows);
								// fixme probable bottleneck
								values[rowIndex == null ? row : Arrays.binarySearch(rowIndex, row)] |= bit;
								rows &= (rows - 1);
							}
						}
					}
				}
			}
			for (int i = 0; i < size; i++) {
				Row row = heap.add(blockMin + ~(fullSlices | values[i]));
				if (row != null) {
					row.rid = block.base + (rowIndex == null ? i : rowIndex[i]);
				}
			}
		}
	}

	protected class KTailRowsIdsQuery extends KTailQuery implements PrimitiveIterator.OfInt {

		private final Row[] rows;
		private final int resultCount;

		private int it;

		private KTailRowsIdsQuery(int k, boolean bottom) {
			super();
			var blockHeap = findCandidateBlocks(k, bottom);
			var resultsHeap = findRows(blockHeap, k, bottom, new Bits());
			this.resultCount = resultsHeap.size();
			this.rows = prepareResults(resultsHeap);
		}

		private Row[] prepareResults(Heap<Row> resultsHeap) {
			var rows = resultsHeap.backingArray();
			Arrays.sort(rows, 0, resultsHeap.size(), (a, b) -> Integer.compare(a.rid, b.rid));
			return rows;
		}

		@Override
		public int nextInt() {
			return rows[it++].rid;
		}

		@Override
		public boolean hasNext() {
			return it < resultCount;
		}
	}

	protected class KTailValuesQuery extends KTailQuery implements PrimitiveIterator.OfLong {

		private final Heap<?> values;
		private final int size;

		private KTailValuesQuery(int k, boolean bottom) {
			super();
			var blockHeap = findCandidateBlocks(k, bottom);
			this.values = findRows(blockHeap, k, bottom, new Bits());
			this.size = values.size();
		}

		@Override
		public long nextLong() {
			return values.pollKey();
		}

		@Override
		public boolean hasNext() {
			return !values.isEmpty();
		}

		long sumLongs() {
			long[] keys = values.backingKeys();
			long sum = 0L;
			for (int i = 0; i < size; i++) {
				sum += keys[i];
			}
			return sum;
		}

		double mean() {
			long[] keys = values.backingKeys();
			double sum = 0D;
			for (int i = 0; i < size; i++) {
				sum += keys[i];
			}
			return sum / size;
		}

		double sumDoubles(LongToDoubleFunction encoding) {
			long[] keys = values.backingKeys();
			double sum = 0D;
			for (int i = 0; i < size; i++) {
				sum += encoding.applyAsDouble(keys[i]);
			}
			return sum;
		}
	}

	private static class OutputIterator implements PrimitiveIterator.OfInt {
		private final BaseQuery query;
		private final char[] output;
		private final int rowCount;
		private int prefix;
		private int base;
		private int it;
		private int outputLimit;
		// index of the physical block currently sitting at the query's read position
		private int block;

		private OutputIterator(BaseQuery query, int rowCount, char[] output) {
			this.query = query;
			this.output = output;
			this.rowCount = rowCount;
		}

		@Override
		public int nextInt() {
			return prefix + output[it++];
		}

		@Override
		public boolean hasNext() {
			if (it == outputLimit) {
				while (base < rowCount && !nextBatch());
			}
			return base < rowCount || it < outputLimit;
		}

		private boolean nextBatch() {
			BlockIterator blocks = query.blockIterator;
			if (!blocks.hasNext()) {
				// no further blocks are of interest: stop the driving loop in hasNext
				base = rowCount;
				return false;
			}
			// the iterator names the next block to evaluate; skip the stored data of any
			// blocks it passed over so the query's read position lands on that block
			int target = blocks.nextBlock();
			while (block < target) {
				query.skipBlock();
				block++;
			}
			prefix = block * BLOCK_SIZE;
			base = prefix;
			query.evaluateBlock();
			query.buffer.and(blocks.getBits());
			int range = Math.min(rowCount - base, output.length);
			outputLimit = query.extractBits(output, range);
			base += range;
			block++;
			it = 0;
			return outputLimit > 0;
		}
	}

	protected abstract class BaseQuery {
		protected final BlockIterator blockIterator;
		protected final Bits buffer = new Bits();
		protected int position = HEADER_SIZE;
		protected int base = 0;

		protected BaseQuery(BlockIterator blockIterator) {
			this.blockIterator = blockIterator;
		}

		public PrimitiveIterator.OfInt iterator() {
			return new OutputIterator(this, rowCount, new char[buffer.capacity()]);
		}

		int extractBits(char[] output, int range) {
			return buffer.extractBits(range, output);
		}

		int matchingCount() {
			int matchingCount = 0;
			int block = 0;
			while (blockIterator.hasNext()) {
				int target = blockIterator.nextBlock();
				// skip the stored data of any blocks the iterator passed over
				while (block < target) {
					skipBlock();
					block++;
				}
				base = block * BLOCK_SIZE;
				int limit = range();
				evaluateBlock();
				buffer.and(blockIterator.getBits());
				matchingCount += buffer.count(limit);
				block++;
			}
			return matchingCount;
		}

		double mean() {
			return aggregate(true);
		}

		double sum() {
			return aggregate(false);
		}

		double aggregate(boolean normalise) {
			long count = 0;
			double sum = 0D;
			int block = 0;
			while (blockIterator.hasNext()) {
				int target = blockIterator.nextBlock();
				// skip the stored data of any blocks the iterator passed over
				while (block < target) {
					skipBlock();
					block++;
				}
				base = block * BLOCK_SIZE;
				int snapshot = position;
				int limit = range();
				evaluateBlock();
				buffer.and(blockIterator.getBits());
				int matchingCount = buffer.count(limit);
				if (matchingCount > 0) {
					count += matchingCount;
					position = snapshot;
					long typesHigh = data.getLong(position);
					position += Long.BYTES;
					long typesLow = data.getLong(position);
					position += Long.BYTES;
					long blockMin = data.getLong(position);
					position += Long.BYTES;
					position += Long.BYTES;
					long fullSlices = typesHigh & typesLow;
					long storedSlices = ~fullSlices;
					while (storedSlices != 0L) {
						int bit = Long.numberOfTrailingZeros(storedSlices);
						storedSlices &= (storedSlices - 1);
						int type = ((int) ((typesHigh >>> bit) & 1) << 1) | (int) ((typesLow >>> bit) & 1);
						int cardinality = 0;
						switch (type) {
							case SPARSE_INVERTED -> {
								cardinality = buffer.sparseAndCardinality(position, data);
								position += (data.getChar(position) + 1) * Character.BYTES;
							}
							case SPARSE -> {
								cardinality = buffer.sparseAndNotCardinality(position, data, limit);
								position += (data.getChar(position) + 1) * Character.BYTES;
							}
							case DENSE -> {
								cardinality = buffer.denseAndNotCardinality(position, data, limit);
								position += BLOCK_WORDS * Long.BYTES;
							}
						}
						sum += cardinality * Math.scalb(1D, bit);
					}
					// all values are stored relative to blockMin
					sum += Util.unsignedToDouble(blockMin) * matchingCount;
				}
				block++;
			}
			return normalise && count != 0 ? sum / count : sum;
		}

		protected abstract void evaluateBlock();

		protected void skipBlock(long typesHigh, long typesLow) {
			position = Util.skipBlock(data, position, typesHigh, typesLow);
		}

		/**
		 * Advances the read position past the whole block at the current position
		 * (header and slice payloads) without evaluating it.
		 */
		protected void skipBlock() {
			long typesHigh = data.getLong(position);
			long typesLow = data.getLong(position + Long.BYTES);
			position += BLOCK_HEADER_SIZE;
			skipBlock(typesHigh, typesLow);
		}

		protected final int range() {
			return Math.min(rowCount - base, buffer.capacity());
		}

		protected void skipSlice(int type) {
			switch (type) {
				case SPARSE_INVERTED, SPARSE -> {
					int cnt = data.getChar(position);
					position += Character.BYTES + cnt * Character.BYTES;
				}
				case DENSE -> position += Long.BYTES * BLOCK_WORDS;
				default -> {
				} // FULL has no payload
			}
		}
	}

	private final class SingleBoundQuery extends BaseQuery {

		private final boolean upper;
		private final long threshold;

		private SingleBoundQuery(BlockIterator blockIterator, long threshold, boolean upper) {
			super(blockIterator);
			this.threshold = threshold;
			this.upper = upper;
		}

		private SingleBoundQuery(long threshold, boolean upper) {
			this(new IterateAllBlocks(rowCount), threshold, upper);
		}

		@Override
		protected void evaluateBlock() {
			int range = range();
			position = evaluateSingleBoundQueryBlock(data, position, range, buffer, threshold, upper);
		}
	}

	private final class HistogramQuery extends BaseQuery {

		private final int tileCount;

		private HistogramQuery(BlockIterator blockIterator) {
			this(blockIterator, 16);
		}

		private HistogramQuery(BlockIterator blockIterator, int tileCount) {
			super(blockIterator);
			this.tileCount = tileCount;
		}

		/**
		 * Computes a histogram over the rows accepted by this query's block iterator,
		 * restricted to the subrange {@code [min, last bound)}. Each entry of
		 * {@code upperBounds} is the exclusive upper bound of a bucket and the bounds
		 * must be supplied in ascending unsigned order. Within every block the
		 * histogram is evaluated as a sequence of {@code countLessThan} operations: the
		 * baseline is {@code countLessThan(min)} (subtracted from every count), the
		 * running count for each successive bound is the number of matching rows below
		 * it, and the previous bound's count is subtracted so that bucket {@code i}
		 * accumulates the rows in {@code [lower(i), upperBounds[i])}, where
		 * {@code lower(0)} is {@code min}. Rows below {@code min} or at or above the
		 * final bound contribute to no bucket.
		 *
		 * @param min
		 *            the inclusive lower bound of the first bucket
		 * @param upperBounds
		 *            the exclusive bucket upper bounds, ascending in unsigned order
		 * @return per-bucket counts, indexed in parallel with {@code upperBounds}
		 */
		long[] histogram(long min, long... upperBounds) {
			int nBounds = upperBounds.length + 1;
			long[] bounds = new long[nBounds];
			bounds[0] = min;
			System.arraycopy(upperBounds, 0, bounds, 1, upperBounds.length);

			int b = Math.min(tileCount, nBounds);
			Bits[] acc = new Bits[b];
			acc[0] = buffer;
			for (int i = 1; i < b; i++) {
				acc[i] = new Bits();
			}
			Bits slice = new Bits();
			long[] blockLt = new long[nBounds];
			long[] counts = new long[upperBounds.length];
			long[] anchored = new long[b];
			int[] start = new int[b];

			int block = 0;
			while (blockIterator.hasNext()) {
				int target = blockIterator.nextBlock();
				while (block < target) {
					skipBlock();
					block++;
				}
				base = block * BLOCK_SIZE;
				int range = range();

				long typesHigh = data.getLong(position);
				long typesLow = data.getLong(position + Long.BYTES);
				long blockMin = data.getLong(position + 2 * Long.BYTES);
				long blockMax = data.getLong(position + 3 * Long.BYTES);
				int sliceStart = position + BLOCK_HEADER_SIZE;
				Bits filter = blockIterator.getBits();

				for (int lo = 0; lo < nBounds; lo += b) {
					// re-seek to the first slice: each tile group replays the block's
					// slices from the start, and position is left past them afterwards
					position = sliceStart;
					int n = Math.min(b, nBounds - lo);
					for (int i = 0; i < n; i++) {
						start[i] = seedThreshold(bounds[lo + i], acc[i], blockMin, blockMax, typesHigh, typesLow,
								anchored, i);
					}
					// Shared low-prefix fold: when no present FULL slice forces a later
					// start (every active bound begins at slice 0) and all active
					// thresholds agree on a run of low bits, those low slices produce the
					// same partial result for every bound. Evaluate them once into the
					// first active accumulator and broadcast, so the per-bound fan-out only
					// begins at the first differing bit.
					int firstActive = -1;
					int activeCount = 0;
					long andAll = -1L;
					long orAll = 0L;
					boolean allZeroStart = true;
					for (int i = 0; i < n; i++) {
						if (start[i] == Long.SIZE) {
							continue;
						}
						activeCount++;
						allZeroStart &= start[i] == 0;
						if (firstActive < 0) {
							firstActive = i;
						}
						andAll &= anchored[i];
						orAll |= anchored[i];
					}
					int startSlice = 0;
					if (allZeroStart && activeCount >= 2) {
						// bits where the active thresholds all agree; the shared low run is
						// the contiguous agreement from bit 0 up to the first divergence
						long agree = andAll | ~orAll;
						int shared = Math.min(Long.numberOfTrailingZeros(~agree), Long.SIZE - 1);
						if (shared >= 2) {
							long fth = typesHigh, ftl = typesLow;
							for (int s = 0; s < shared; s++) {
								int type = ((int) (fth & 1) << 1) | (int) (ftl & 1);
								fth >>>= 1;
								ftl >>>= 1;
								position = combineOne(type, position, acc[firstActive], range, s,
										anchored[firstActive]);
							}
							for (int i = 0; i < n; i++) {
								if (i != firstActive && start[i] != Long.SIZE) {
									acc[i].copyFrom(acc[firstActive]);
								}
							}
							startSlice = shared;
						}
					}

					long th = typesHigh >>> startSlice, tl = typesLow >>> startSlice;
					for (int s = startSlice; s < Long.SIZE; s++) {
						int type = ((int) (th & 1) << 1) | (int) (tl & 1);
						th >>>= 1;
						tl >>>= 1;
						// count the accumulators that still need this slice, tracking the
						// sole consumer so a single-consumer slice can stream directly
						int needCount = 0;
						int only = -1;
						for (int i = 0; i < n; i++) {
							if (s >= start[i]) {
								needCount++;
								only = i;
							}
						}
						if (needCount == 0) {
							position = Util.skipSlice(type, data, position);
							continue;
						}

						switch (type) {
							case FULL -> {
								for (int i = 0; i < n; i++) {
									if (s >= start[i] && (s == 0 || ((anchored[i] >>> s) & 1L) == 1L)) {
										acc[i].fillFull();
									}
								}
							}
							case DENSE -> {
								for (int i = 0; i < n; i++) {
									if (s >= start[i]) {
										long bit = (anchored[i] >>> s) & 1L;
										if (s == 0 && bit == 1L) {
											acc[i].fillFull();
										} else if (bit == 1L || s == 0) {
											acc[i].denseOr(position, data);
										} else {
											acc[i].denseAnd(position, data);
										}
									}
								}
								position += BLOCK_WORDS * Long.BYTES;
							}
							default -> {
								if (needCount == 1) {
									position = combineSparse(type, position, acc[only], range, s, anchored[only]);
								} else {
									position = decodeSparseSlice(type, position, slice, range);
									for (int i = 0; i < n; i++) {
										if (s >= start[i])
											combineSlice(acc[i], slice, s, anchored[i]);
									}
								}
							}
						}
					}

					for (int i = 0; i < n; i++) {
						acc[i].and(filter);
						blockLt[lo + i] = acc[i].count(range);
					}
				}
				for (int j = 0; j < upperBounds.length; j++) {
					counts[j] += blockLt[j + 1] - blockLt[j];
				}
				block++;
			}
			return counts;
		}

		private void combineSlice(Bits acc, Bits slice, int s, long anchored) {
			long bit = (anchored >>> s) & 1L;
			if (s == 0 && bit == 1L)
				acc.fillFull();
			else if (bit == 1L || s == 0)
				acc.or(slice);
			else
				acc.and(slice);
		}

		private int decodeSparseSlice(int type, int position, Bits slice, int range) {
			slice.reset();
			return type == SPARSE ? slice.sparseOr(position, data) : slice.sparseOrNot(position, data, range);
		}

		private int seedThreshold(long bound, Bits acc, long blockMin, long blockMax, long typesHigh, long typesLow,
				long[] anchored, int i) {
			acc.reset();
			if (bound == 0L)
				return Long.SIZE; // nothing < 0
			long threshold = bound - 1;
			if (Long.compareUnsigned(threshold, blockMin) < 0)
				return Long.SIZE; // all rows ≥ bound
			if (Long.compareUnsigned(threshold, blockMax) > 0) { // all rows < bound
				acc.fillFull();
				return Long.SIZE;
			}
			anchored[i] = threshold - blockMin;
			return Util.firstRelevantSlice(anchored[i], typesHigh, typesLow);
		}

		/**
		 * Combines a single sparse (or sparse-inverted) slice straight into
		 * {@code target}, mirroring the streaming evaluation used when a slice has one
		 * consumer. At slice {@code s == 0} the accumulator is initialised (filled when
		 * the value bit is set, otherwise the slice becomes the accumulator); for
		 * higher slices a set bit unions the slice and an unset bit intersects it. The
		 * read position is advanced past the payload and returned.
		 */
		private int combineSparse(int type, int position, Bits target, int range, int s, long anchored) {
			long bit = (anchored >>> s) & 1L;
			if (s == 0) {
				if (bit == 1L) {
					target.fillFull();
					return Util.skipSlice(type, data, position);
				}
				return type == SPARSE ? target.sparseOr(position, data) : target.sparseOrNot(position, data, range);
			}
			if (bit == 1L) {
				return type == SPARSE ? target.sparseOr(position, data) : target.sparseOrNot(position, data, range);
			}
			return type == SPARSE
					? target.sparseAnd(position, data, range)
					: target.sparseAndNot(position, data, range);
		}

		/**
		 * Combines a single slice of any type into {@code target}, following the same
		 * per-slice semantics as the main fan-out loop but for one accumulator. Used by
		 * the shared low-prefix fold to evaluate the common low slices once. Advances
		 * the read position past the payload and returns it.
		 */
		private int combineOne(int type, int position, Bits target, int range, int s, long anchored) {
			switch (type) {
				case FULL -> {
					if (s == 0 || ((anchored >>> s) & 1L) == 1L) {
						target.fillFull();
					}
					return position;
				}
				case DENSE -> {
					long bit = (anchored >>> s) & 1L;
					if (s == 0) {
						if (bit == 1L) {
							target.fillFull();
						} else {
							target.denseOr(position, data); // empty target: a copy
						}
					} else if (bit == 1L) {
						target.denseOr(position, data);
					} else {
						target.denseAnd(position, data);
					}
					return position + BLOCK_WORDS * Long.BYTES;
				}
				default -> {
					return combineSparse(type, position, target, range, s, anchored);
				}
			}
		}

		@Override
		protected void evaluateBlock() {
			// histogram() evaluates each bound per block directly via countLessThan, so
			// the single-threshold evaluateBlock contract is never exercised
			throw new UnsupportedOperationException("histogram evaluates bounds per block");
		}
	}

	private final class EqualityQuery extends BaseQuery {

		private final boolean negate;
		private final long threshold;

		private EqualityQuery(BlockIterator blockIterator, long threshold, boolean negate) {
			super(blockIterator);
			this.threshold = threshold;
			this.negate = negate;
		}

		private EqualityQuery(long threshold, boolean negate) {
			this(new IterateAllBlocks(rowCount), threshold, negate);
		}

		@Override
		protected void evaluateBlock() {
			// for each i
			// - if the bit i is not set in the value, intersect the container with the bits
			// - if the bit i is set in value, remove the container from the bits
			int range = range();
			long typesHigh = data.getLong(position);
			position += Long.BYTES;
			long typesLow = data.getLong(position);
			position += Long.BYTES;
			long blockMin = data.getLong(position);
			position += Long.BYTES;
			long blockMax = data.getLong(position);
			position += Long.BYTES;
			if (Long.compareUnsigned(threshold, blockMin) < 0 || Long.compareUnsigned(threshold, blockMax) > 0) {
				skipBlock(typesHigh, typesLow);
				if (negate) {
					buffer.fill(range);
				} else {
					buffer.clear(range);
				}
				return;
			}
			long anchoredThreshold = threshold - blockMin;
			int snapshot = position;
			// FIXME this is a horrible error prone contract and only exists for the sake of
			// reuse and lack of pass by reference
			position = evaluateBlockForEquality(position, typesHigh, typesLow, anchoredThreshold, data, negate, buffer,
					range);
			// check if the block was skipped
			if (position < 0) {
				position = snapshot;
				// we can skip the entire block
				// this could be calculated from the type masks if sparse slices were removed
				skipBlock(typesHigh, typesLow);
				if (negate) {
					buffer.fill(range);
				} else {
					buffer.clear(range);
				}
			}
		}
	}

	private final class InQuery extends BaseQuery {

		private final Bits temp = new Bits();
		private final long[] values;

		InQuery(BlockIterator blockIterator, long... values) {
			super((blockIterator));
			this.values = values;
		}

		InQuery(long... values) {
			this(new IterateAllBlocks(rowCount), values);
		}

		@Override
		protected void evaluateBlock() {
			int range = range();
			long typesHigh = data.getLong(position);
			position += Long.BYTES;
			long typesLow = data.getLong(position);
			position += Long.BYTES;
			long blockMin = data.getLong(position);
			position += Long.BYTES;
			long blockMax = data.getLong(position);
			position += Long.BYTES;
			int blockStart = position;
			buffer.reset();
			for (long value : values) {
				position = blockStart;
				if (Long.compareUnsigned(value, blockMin) < 0) {
					skipBlock(typesHigh, typesLow);
					temp.clear(range);
				} else {
					temp.reset();
					long anchoredValue = value - blockMin;
					position = evaluateBlockForEquality(position, typesHigh, typesLow, anchoredValue, data, false, temp,
							range);
				}
				buffer.or(temp);
			}
			if (position < 0) {
				position = blockStart;
			}
			if (position == blockStart) {
				skipBlock(typesHigh, typesLow);
			}
		}
	}

	private final class BetweenQuery extends BaseQuery {

		private final Bits buffer2 = new Bits();
		private final long lower;
		private final long upper;

		private BetweenQuery(BlockIterator blockIterator, long lower, long upper) {
			super(blockIterator);
			this.lower = lower;
			this.upper = upper;
		}

		private BetweenQuery(long lower, long upper) {
			this(new IterateAllBlocks(rowCount), lower, upper);
		}

		private void applySlice(Bits bitmap, long threshold, int type, int i, int range) {
			if (i == 0)
				firstSlice(bitmap, threshold, type, range);
			else
				nextSlice(bitmap, threshold, type, i, range);
		}

		private void firstSlice(Bits bitmap, long threshold, int type, int range) {
			if ((threshold & 1) == 1) {
				bitmap.fill(range);
				switch (type) {
					case SPARSE_INVERTED, SPARSE -> {
						int cnt = data.getChar(position);
						position += Character.BYTES + cnt * Character.BYTES;
					}
					case DENSE -> position += Long.BYTES * BLOCK_WORDS;
					case FULL -> {
					} // nothing stored
				}
			} else {
				switch (type) {
					case SPARSE_INVERTED -> position = bitmap.sparseOrNot(position, data, range);
					case SPARSE -> position = bitmap.sparseOr(position, data);
					case DENSE -> position = bitmap.denseOr(position, data);
					case FULL -> bitmap.fill(range);
				}
			}
		}

		private void nextSlice(Bits bitmap, long threshold, int type, int bit, int range) {
			if ((threshold & (1L << bit)) != 0) {
				// do union
				switch (type) {
					case SPARSE_INVERTED -> position = bitmap.sparseOrNot(position, data, range);
					case SPARSE -> position = bitmap.sparseOr(position, data);
					case DENSE -> position = bitmap.denseOr(position, data);
					case FULL -> bitmap.fill(range);
				}
			} else {
				switch (type) {
					case SPARSE_INVERTED -> position = bitmap.sparseAndNot(position, data, range);
					case SPARSE -> position = bitmap.sparseAnd(position, data, range);
					case DENSE -> position = bitmap.denseAnd(position, data);
					case FULL -> {
					} // nothing to do
				}
			}
		}

		@Override
		protected void evaluateBlock() {
			int range = range();
			long typesHigh = data.getLong(position);
			position += Long.BYTES;
			long typesLow = data.getLong(position);
			position += Long.BYTES;
			long blockMin = data.getLong(position);
			position += Long.BYTES;
			long blockMax = data.getLong(position);
			position += Long.BYTES;
			boolean trivialUpperBound = Long.compareUnsigned(upper, blockMin) < 0;
			boolean trivialLowerBound = Long.compareUnsigned(lower, blockMin) < 0;
			if (trivialUpperBound) {
				skipBlock(typesHigh, typesLow);
				buffer.clear(range);
				return;
			}
			long anchoredLower = lower - blockMin;
			long anchoredUpper = upper - blockMin;
			int lowerStart = trivialLowerBound
					? Long.SIZE
					: Util.firstRelevantSlice(anchoredLower, typesHigh, typesLow);
			if (trivialLowerBound) {
				buffer.clear(range);
			} else {
				buffer.reset();
			}
			buffer2.reset();
			int upperStart = Util.firstRelevantSlice(anchoredUpper, typesHigh, typesLow);
			for (int i = 0; i < Long.SIZE; i++) {
				int type = ((int) (typesHigh & 1) << 1) | (int) (typesLow & 1);
				typesHigh >>>= 1;
				typesLow >>>= 1;
				boolean doLower = i >= lowerStart;
				boolean doUpper = i >= upperStart;
				if (doLower && doUpper) {
					int restore = position;
					applySlice(buffer, anchoredLower, type, i, range);
					position = restore;
					applySlice(buffer2, anchoredUpper, type, i, range);
				} else if (doLower) {
					int restore = position;
					applySlice(buffer, anchoredLower, type, i, range);
					position = restore;
					skipSlice(type);
				} else if (doUpper) {
					applySlice(buffer2, anchoredUpper, type, i, range);
				} else {
					skipSlice(type);
				}
			}
			buffer.flipAnd(buffer2);
		}
	}

	private static int evaluateSingleBoundQueryBlock(ByteBuffer data, int position, int range, Bits buffer,
			long threshold, boolean upper) {
		long typesHigh = data.getLong(position);
		position += Long.BYTES;
		long typesLow = data.getLong(position);
		position += Long.BYTES;
		long blockMin = data.getLong(position);
		position += Long.BYTES;
		long blockMax = data.getLong(position);
		position += Long.BYTES;
		if (Long.compareUnsigned(threshold, blockMin) < 0) {
			position = Util.skipBlock(data, position, typesHigh, typesLow);
			if (upper) {
				buffer.clear(range);
			} else {
				buffer.fill(range);
			}
			return position;
		}
		if (Long.compareUnsigned(threshold, blockMax) > 0) {
			position = Util.skipBlock(data, position, typesHigh, typesLow);
			if (upper) {
				buffer.fill(range);
			} else {
				buffer.clear(range);
			}
			return position;
		}
		long anchoredThreshold = threshold - blockMin;
		int firstRelevantSlice = Util.firstRelevantSlice(anchoredThreshold, typesHigh, typesLow);
		if (firstRelevantSlice > 0) {
			if ((typesHigh & typesLow & (1L << firstRelevantSlice)) != 0) {
				buffer.fill(range);
			} else {
				buffer.clear(range);
			}
		}
		int type = ((int) (typesHigh & 1) << 1) | (int) (typesLow & 1);
		typesHigh >>>= 1;
		typesLow >>>= 1;
		// first slice is special - if the threshold includes it just fill the buffer
		if (firstRelevantSlice == 0) {
			if ((anchoredThreshold & 1) == 1) {
				buffer.fill(range);
				position = Util.skipSlice(type, data, position);
			} else {
				buffer.reset();
				switch (type) {
					case SPARSE_INVERTED -> position = buffer.sparseOrNot(position, data, range);
					case SPARSE -> position = buffer.sparseOr(position, data);
					case DENSE -> position = buffer.denseOr(position, data);
					case FULL -> buffer.fill(range);
				}
			}
		} else {
			position = Util.skipSlice(type, data, position);
		}
		// process the other slices
		int slice = 1;
		for (; slice <= firstRelevantSlice; slice++) {
			type = ((int) (typesHigh & 1) << 1) | (int) (typesLow & 1);
			position = Util.skipSlice(type, data, position);
			typesHigh >>>= 1;
			typesLow >>>= 1;
		}
		for (; slice < Long.SIZE; slice++) {
			type = ((int) (typesHigh & 1) << 1) | (int) (typesLow & 1);
			if ((anchoredThreshold & (1L << slice)) != 0) {
				// do union
				switch (type) {
					case SPARSE_INVERTED -> position = buffer.sparseOrNot(position, data, range);
					case SPARSE -> position = buffer.sparseOr(position, data);
					case DENSE -> position = buffer.denseOr(position, data);
					case FULL -> buffer.fill(range);
				}
			} else {
				switch (type) {
					case SPARSE_INVERTED -> position = buffer.sparseAndNot(position, data, range);
					case SPARSE -> position = buffer.sparseAnd(position, data, range);
					case DENSE -> position = buffer.denseAnd(position, data);
					case FULL -> {
					} // nothing to do
				}
			}
			typesHigh >>>= 1;
			typesLow >>>= 1;
		}
		if (!upper) {
			buffer.flip(range);
		}
		return position;
	}

	private static int evaluateBlockForEquality(int position, long typesHigh, long typesLow, long anchoredThreshold,
			ByteBuffer data, boolean negate, Bits buffer, int range) {
		// bitset becomes empty whenever bit i is present and slice i is full, or bit i
		// is absent and slice i is empty
		// if the bitset does become empty, it can't be populated again, so the
		// evaluation can be skipped for the entire block
		long fullSlices = typesHigh & typesLow;
		if ((fullSlices & anchoredThreshold) == 0) {
			long storedSlices = ~(typesHigh & typesLow);
			buffer.fill(range);
			while (storedSlices != 0) {
				int slice = Long.numberOfTrailingZeros(storedSlices);
				storedSlices &= (storedSlices - 1);
				int type = ((int) ((typesHigh >>> slice) & 1) << 1) | (int) ((typesLow >>> slice) & 1);
				if (((anchoredThreshold >>> slice) & 1) == 1) {
					// bit present, need to remove the container values from bits (bits &= ~mask)
					switch (type) {
						case SPARSE_INVERTED -> position = buffer.sparseAnd(position, data, range);
						case SPARSE -> position = buffer.sparseAndNot(position, data, range);
						case DENSE -> position = buffer.denseAndNot(position, data);
						case FULL -> {
							assert false : "entire block should have been skipped for full block";
							buffer.clear(range);
						}
					}
				} else {
					// bit not present, need to intersect the container bits (bits &= mask)
					switch (type) {
						case SPARSE_INVERTED -> position = buffer.sparseAndNot(position, data, range);
						case SPARSE -> position = buffer.sparseAnd(position, data, range);
						case DENSE -> position = buffer.denseAnd(position, data);
						case FULL -> {
						} // nothing to do
					}
				}
			}
			if (negate) {
				buffer.flip(range);
			}
			return position;
		}
		return -1;
	}

	private static final class IterateAllBlocks implements BlockIterator {

		private final int maxBlock;
		private final Bits bits = Bits.FULL;
		private int blockIndex = 0;

		public IterateAllBlocks(int maxRows) {
			// round up: a partial final block still has to be visited
			this.maxBlock = (maxRows + BLOCK_SIZE - 1) >>> Integer.bitCount(BLOCK_SIZE - 1);
		}

		@Override
		public Bits getBits() {
			return bits;
		}

		@Override
		public int nextBlock() {
			return blockIndex++;
		}

		@Override
		public boolean hasNext() {
			return blockIndex < maxBlock;
		}

		@Override
		public boolean isTrivial() {
			return true;
		}
	}
}
