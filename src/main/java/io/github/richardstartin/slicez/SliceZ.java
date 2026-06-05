package io.github.richardstartin.slicez;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.PrimitiveIterator;
import java.util.function.DoubleToLongFunction;
import java.util.function.LongConsumer;
import java.util.function.LongToDoubleFunction;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

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
	private static final int SPARSE_THRESHOLD = BLOCK_SIZE / Short.SIZE - 1;

	public static final class Appender implements LongConsumer {

		private final long[] values = new long[BLOCK_SIZE];
		private final int[] sliceCardinalities = new int[Long.SIZE];
		private ByteBuffer output = ByteBuffer.allocate(1 << 10).position(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
		private int rid;
		private long blockMin = -1L;
		private long blockMax = 0L;
		private long min = -1L;
		private long max = 0L;
		private final int[] counts = new int[NUM_COUNTS];

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

		public void flush(int blockLimit) {
			// subtract delimiter to reduce the number of populated slices, figure out
			// required slice types
			// this also conflates empty and full slices, allowing for more non-trivial
			// storage types with
			// a 2-bit/slice type header overhead
			for (int i = 0; i < blockLimit; i++) {
				// FIXME needs to be unsigned - is this correct?
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
					output.putChar((char) sliceCardinalities[bit]);
					for (int i = 0; i < blockLimit; i++) {
						if (((values[i] >>> bit) & 1L) == 1L) {
							output.putChar((char) i);
						}
					}
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
					// fixme - this code could be mostly shared
					output.putChar((char) (blockLimit - sliceCardinalities[bit]));
					int added = 0;
					for (int i = 0; i < blockLimit; i++) {
						if (((values[i] >>> bit) & 1L) == 0L) {
							output.putChar((char) i);
							added++;
						}
					}
					assert !(added < blockLimit - sliceCardinalities[bit]) : "underflow";
					assert !(added > blockLimit - sliceCardinalities[bit]) : "overflow";
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

		SliceZ build() {
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

		@Override
		public void accept(long value) {
			add(value);
		}
	}

	public static Appender appender() {
		return new Appender();
	}

	private final long min;
	private final long max;
	private final int rowCount;
	private final ByteBuffer data;

	public static SliceZ build(long... values) {
		var appender = appender();
		for (long value : values) {
			appender.add(value);
		}
		return appender.build();
	}

	public SliceZ(ByteBuffer data) {
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

	public int getSparseInvertedSliceCount() {
		return getCount(SPARSE_INVERTED);
	}

	public int getSparseSliceCount() {
		return getCount(SPARSE);
	}

	public int getDenseSliceCount() {
		return getCount(DENSE);
	}

	public int getFullSliceCount() {
		return getCount(FULL);
	}

	public int getBlockCount() {
		return getCount(BLOCK_COUNT);
	}

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

	public PrimitiveIterator.OfInt lessThan(long value) {
		return value == 0L ? IntStream.empty().iterator() : lessThanOrEqual(value - 1);
	}

	public int countLessThan(long value) {
		return value == 0L ? 0 : countLessThanOrEqual(value - 1);
	}

	public PrimitiveIterator.OfInt lessThanOrEqual(long value) {
		if (Long.compareUnsigned(value, min) < 0) {
			return IntStream.empty().iterator();
		}
		if (Long.compareUnsigned(value, max) > 0) {
			return IntStream.range(0, rowCount).iterator();
		}
		return new SingleBoundQuery(value, true);
	}

	public int countLessThanOrEqual(long value) {
		if (Long.compareUnsigned(value, min) < 0) {
			return 0;
		}
		if (Long.compareUnsigned(value, max) > 0) {
			return rowCount;
		}
		return new SingleBoundQuery(value, true).matchingCount();
	}

	public PrimitiveIterator.OfInt greaterThan(long value) {
		if (Long.compareUnsigned(value, min) < 0) {
			return IntStream.range(0, rowCount).iterator();
		}
		if (Long.compareUnsigned(value, max) > 0) {
			return IntStream.empty().iterator();
		}
		return new SingleBoundQuery(value, false);
	}

	public int countGreaterThan(long value) {
		if (Long.compareUnsigned(value, min) < 0) {
			return rowCount;
		}
		if (Long.compareUnsigned(value, max) > 0) {
			return 0;
		}
		return new SingleBoundQuery(value, false).matchingCount();
	}

	public PrimitiveIterator.OfInt greaterThanOrEqual(long value) {
		return value == 0L ? IntStream.range(0, rowCount).iterator() : greaterThan(value - 1);
	}

	public int countGreaterThanOrEqual(long value) {
		return value == 0L ? rowCount : countGreaterThan(value - 1);
	}

	public PrimitiveIterator.OfInt equal(long value) {
		return new EqualityQuery(value, false);
	}

	public PrimitiveIterator.OfInt notEqual(long value) {
		return new EqualityQuery(value, true);
	}

	public PrimitiveIterator.OfInt in(long... values) {
		if (values.length == 0) {
			return IntStream.empty().iterator();
		}
		if (values.length == 1) {
			return equal(values[0]);
		}
		return new InQuery(values);
	}

	public int countEqual(long value) {
		return new EqualityQuery(value, false).matchingCount();
	}

	public int countNotEqual(long value) {
		return new EqualityQuery(value, true).matchingCount();
	}

	public PrimitiveIterator.OfInt between(long lower, long upper) {
		if (Long.compareUnsigned(upper, min) < 0 || Long.compareUnsigned(max, lower) < 0) {
			return IntStream.empty().iterator();
		}
		if (Long.compareUnsigned(lower, min) < 0 && Long.compareUnsigned(max, upper) < 0) {
			return IntStream.range(0, rowCount).iterator();
		}
		if (lower == 0L) {
			return lessThan(upper);
		}
		return new BetweenQuery(lower - 1, upper - 1);
	}

	public int countBetween(long lower, long upper) {
		if (lower == 0L) {
			return countLessThan(upper);
		}
		return new BetweenQuery(lower - 1, upper - 1).matchingCount();
	}

	public long max() {
		return max;
	}

	public long min() {
		return min;
	}

	public PrimitiveIterator.OfInt bottom(int k) {
		if (k < 0) {
			throw new IllegalArgumentException("bottom-k negative k: " + k);
		}
		if (k == 0) {
			return IntStream.empty().iterator();
		}
		return new KTailRowsIdsQuery(k, true);
	}

	public PrimitiveIterator.OfLong bottomValues(int k) {
		if (k < 0) {
			throw new IllegalArgumentException("bottom-k negative k: " + k);
		}
		if (k == 0) {
			return LongStream.empty().iterator();
		}
		return new KTailValuesQuery(k, true);
	}

	public long bottomSum(int k) {
		if (k < 0) {
			throw new IllegalArgumentException("top-k negative k: " + k);
		}
		if (k == 0) {
			return 0L;
		}
		return new KTailValuesQuery(k, true).sumLongs();
	}

	public double bottomSum(int k, LongToDoubleFunction encoding) {
		if (k < 0) {
			throw new IllegalArgumentException("bottom-k negative k: " + k);
		}
		if (k == 0) {
			return 0L;
		}
		return new KTailValuesQuery(k, true).sumDoubles(encoding);
	}

	public PrimitiveIterator.OfInt top(int k) {
		if (k < 0) {
			throw new IllegalArgumentException("top-k negative k: " + k);
		}
		if (k == 0) {
			return IntStream.empty().iterator();
		}
		return new KTailRowsIdsQuery(k, false);
	}

	public PrimitiveIterator.OfLong topValues(int k) {
		if (k < 0) {
			throw new IllegalArgumentException("top-k negative k: " + k);
		}
		if (k == 0) {
			return LongStream.empty().iterator();
		}
		return new KTailValuesQuery(k, false);
	}

	public long topSum(int k) {
		if (k < 0) {
			throw new IllegalArgumentException("top-k negative k: " + k);
		}
		if (k == 0) {
			return 0L;
		}
		return new KTailValuesQuery(k, false).sumLongs();
	}

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

		public long sumLongs() {
			long[] keys = values.backingKeys();
			long sum = 0L;
			for (int i = 0; i < size; i++) {
				sum += keys[i];
			}
			return sum;
		}

		public double sumDoubles(LongToDoubleFunction encoding) {
			long[] keys = values.backingKeys();
			double sum = 0D;
			for (int i = 0; i < size; i++) {
				sum += encoding.applyAsDouble(keys[i]);
			}
			return sum;
		}
	}

	protected abstract class BaseQuery implements PrimitiveIterator.OfInt {
		protected final Bits buffer = new Bits();
		protected int position = HEADER_SIZE;
		protected final int[] output = new int[buffer.capacity()];
		protected int outputPosition = 0;
		protected int outputLimit = 0;
		protected int base = 0;

		@Override
		public int nextInt() {
			return output[outputPosition++];
		}

		@Override
		public boolean hasNext() {
			if (outputPosition == outputLimit) {
				while (base < rowCount && !nextBatch());
			}
			return base < rowCount || outputPosition < outputLimit;
		}

		public int matchingCount() {
			int matchingCount = 0;
			while (base < rowCount) {
				int limit = range();
				evaluateBlock();
				base += BLOCK_SIZE;
				matchingCount += buffer.count(limit);
			}
			return matchingCount;
		}

		protected abstract void evaluateBlock();

		protected boolean nextBatch() {
			evaluateBlock();
			return extractBits();
		}

		protected void skipBlock(long typesHigh, long typesLow) {
			position = Util.skipBlock(data, position, typesHigh, typesLow);
		}

		protected final int range() {
			return Math.min(rowCount - base, buffer.capacity());
		}

		protected boolean extractBits() {
			outputPosition = 0;
			long packed = buffer.extractBits(base, range(), output);
			base = (int) (packed >>> 32);
			outputLimit = (int) (packed & 0xFFFF_FFFFL);
			return outputLimit > 0;
		}
	}

	private final class SingleBoundQuery extends BaseQuery {

		private final boolean upper;
		private final long threshold;

		private SingleBoundQuery(long threshold, boolean upper) {
			this.threshold = threshold;
			this.upper = upper;
		}

		@Override
		protected void evaluateBlock() {
			int range = range();
			position = evaluateSingleBoundQueryBlock(data, position, range, buffer, threshold, upper);
		}
	}

	private final class EqualityQuery extends BaseQuery {

		private final boolean negate;
		private final long threshold;

		public EqualityQuery(long threshold, boolean negate) {
			this.threshold = threshold;
			this.negate = negate;
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

		InQuery(long... values) {
			this.values = values;
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

		private BetweenQuery(long lower, long upper) {
			this.lower = lower;
			this.upper = upper;
		}

		private void skipSlice(int type) {
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

}
