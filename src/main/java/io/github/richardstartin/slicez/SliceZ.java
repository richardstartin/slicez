package io.github.richardstartin.slicez;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.PrimitiveIterator;
import java.util.function.LongConsumer;
import java.util.stream.IntStream;

public class SliceZ {

    private static final int SPARSE_INVERTED = 0;
    private static final int SPARSE = 1;
    private static final int DENSE = 2;
    private static final int FULL = 3;
    private static final int BLOCK_COUNT = 4;
    private static final int SPARSE_SIZE = 5;
    private static final int NUM_COUNTS = SPARSE_SIZE + 1;
    private static final int COOKIE = 0xfeef1f0;
    private static final int BLOCK_HEADER_SIZE = Long.BYTES * 3; // typesHigh + typesLow + blockMin
    static final int HEADER_SIZE = Integer.BYTES // cookie
            + Integer.BYTES // count
            + Long.BYTES // min
            + Long.BYTES // max
            + NUM_COUNTS * Integer.BYTES; // counts

    static final int BLOCK_SIZE = 0x10000;
    private static final int BLOCK_WORDS = BLOCK_SIZE / Long.SIZE; // 16
    private static final int SPARSE_THRESHOLD = BLOCK_SIZE / Short.SIZE - 1;

    public static final class Appender implements LongConsumer {

        private final long[] values = new long[BLOCK_SIZE];
        private final int[] sliceCardinalities = new int[Long.SIZE];
        private ByteBuffer output = ByteBuffer.allocate(1 << 10).position(HEADER_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN);
        private int rid;
        private long blockMin = -1L;
        private long min = -1L;
        private long max = 0L;
        private final int[] counts = new int[NUM_COUNTS];

        public void add(long value) {
            blockMin = unsignedMin(value, blockMin);
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
            // subtract blockMin to reduce the number of populated slices, figure out required slice types
            // this also conflates empty and full slices, allowing for more non-trivial storage types with
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
                    sliceStorageSize += (sliceCardinality + 1) * Character.BYTES;
                    counts[SPARSE_INVERTED]++;
                    counts[SPARSE_SIZE] += (1 + sliceCardinality) * Character.BYTES;
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
                    // translate the bitset into a sorted array similarly to sparse slices, just invert the check
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

            reset();
            counts[BLOCK_COUNT]++;
        }


        private void reset() {
            Arrays.fill(sliceCardinalities, 0);
            blockMin = -1L;
        }

        private void ensureCapacity(int needed) {
            if (output.remaining() < needed) {
                ByteBuffer grown = ByteBuffer.allocate(
                                Math.max(output.capacity() * 2, output.position() + needed))
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
            throw new IllegalArgumentException("cookie should be " + Integer.toHexString(COOKIE) + " but found " + Integer.toHexString(cookie));
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

    private static class Bits {

        private final long[] bits;
        private boolean empty = true;
        private boolean full;

        public Bits() {
            this(BLOCK_WORDS);
        }

        public Bits(int numWords) {
            this.bits = new long[numWords];
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

        public void clear(int limit) {
            if (!empty) {
                clearBitmap(bits, 0, limit);
                empty = true;
            }
            full = false;
        }

        public void fill(int limit) {
            if (!full) {
                fillBitmap(bits, 0, limit);
                full = true;
            }
            empty = false;
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
                        filled = -1L == (bits[i] & bits[i + 1] & bits[i + 2] & bits[i + 3] & bits[i + 4] & bits[i + 5] & bits[i + 6] & bits[i + 7]);
                    }
                    full = filled;
                }
            }
            empty = false;
            return position + BLOCK_WORDS * Long.BYTES;
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
                        emptied = (0L == (bits[i] | bits[i + 1] | bits[i + 2] | bits[i + 3] | bits[i + 4] | bits[i + 5] | bits[i + 6] | bits[i + 7]));
                    }
                    empty = emptied;
                }
            }
            full = false;
            return position + BLOCK_WORDS * Long.BYTES;
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
                        emptied = 0L == (bits[i] | bits[i + 1] | bits[i + 2] | bits[i + 3] | bits[i + 4] | bits[i + 5] | bits[i + 6] | bits[i + 7]);
                    }
                    empty = emptied;
                }
            }
            full = false;
            return position + BLOCK_WORDS * Long.BYTES;
        }

        public int sparseOrNot(int position, ByteBuffer data, int range) {
            if (!full) {
                int advancedTo = empty
                        ? SliceZ.coveredSparseOrNot(bits, data, position, range)
                        : SliceZ.sparseOrNot(bits, data, position, range);
                full = advancedTo < 0;
                empty = false;
                return Math.abs(advancedTo);
            } else {
                return SliceZ.skipSparse(data, position);
            }
        }

        public int sparseOr(int position, ByteBuffer data) {
            if (!full) {
                int advancedTo = SliceZ.sparseOr(bits, data, position);
                full = advancedTo < 0;
                empty = false;
                return Math.abs(advancedTo);
            } else {
                return SliceZ.skipSparse(data, position);
            }
        }

        public int sparseAnd(int position, ByteBuffer data, int range) {
            if (!empty) {
                int advancedTo = full
                        ? SliceZ.coveredSparseAnd(bits, data, position)
                        : SliceZ.sparseAnd(bits, data, position, range);
                empty = advancedTo <= 0;
                full = false;
                return Math.abs(advancedTo);
            } else {
                full = false;
                return SliceZ.skipSparse(data, position);
            }
        }

        public int sparseAndNot(int position, ByteBuffer data, int range) {
            if (!empty) {
                int advancedTo = SliceZ.sparseAndNot(bits, data, position, range);
                empty = advancedTo <= 0;
                full = false;
                return Math.abs(advancedTo);
            }
            full = false;
            return SliceZ.skipSparse(data, position);
        }

        public void flip(int range) {
            if (empty) {
                empty = false;
                full = true;
                fillBitmap(bits, 0, range);
            } else if (full) {
                full = false;
                empty = true;
                clearBitmap(bits, 0, range);
            } else {
                flipBitmap(bits, 0, range);
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
                        long mask = (range & 63) == 0 ? 0xFFFFFFFFFFFFFFFFL : (1L << range) - 1;
                        long word = bits[lastWordIndex] & mask;
                        while (word != 0) {
                            output[outputLimit++] = b + Long.numberOfTrailingZeros(word);
                            word &= (word - 1);
                        }
                    }
                }
            }
            return outputLimit | ((long) (base + BLOCK_SIZE) << 32);
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
                long mask = (limit & 63) == limit ? 0xFFFFFFFFFFFFFFFFL : (1L << limit) - 1;
                count += Long.bitCount(bits[lastWordIndex] & mask);
            }
            return count;
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
            long storedSlices = ~(typesHigh & typesLow);
            while (storedSlices != 0) {
                int bit = Long.numberOfTrailingZeros(storedSlices);
                int type = ((int) ((typesHigh >>> bit) & 1) << 1) | (int) ((typesLow >>> bit) & 1);
                position = skipSlice(type, data, position);
                storedSlices &= (storedSlices - 1);
            }
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
            long typesHigh = data.getLong(position);
            position += Long.BYTES;
            long typesLow = data.getLong(position);
            position += Long.BYTES;
            long blockMin = data.getLong(position);
            position += Long.BYTES;
            if (Long.compareUnsigned(threshold, blockMin) < 0) {
                skipBlock(typesHigh, typesLow);
                if (upper) {
                    buffer.clear(range);
                } else {
                    buffer.fill(range);
                }
                return;
            }
            long anchoredThreshold = threshold - blockMin;
            int firstRelevantSlice = firstRelevantSlice(anchoredThreshold, typesHigh, typesLow);
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
                    position = skipSlice(type, data, position);
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
                position = skipSlice(type, data, position);
            }
            // process the other slices
            int slice = 1;
            for (; slice <= firstRelevantSlice; slice++) {
                type = ((int) (typesHigh & 1) << 1) | (int) (typesLow & 1);
                position = skipSlice(type, data, position);
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
            //  - if the bit i is not set in the value, intersect the container with the bits
            //  - if the bit i is set in value, remove the container from the bits
            int range = range();
            long typesHigh = data.getLong(position);
            position += Long.BYTES;
            long typesLow = data.getLong(position);
            position += Long.BYTES;
            long blockMin = data.getLong(position);
            position += Long.BYTES;
            if (Long.compareUnsigned(threshold, blockMin) < 0) {
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
            // FIXME this is a horrible error prone contract and only exists for the sake of reuse and lack of pass by reference
            position = evaluateBlockForEquality(position, typesHigh, typesLow, anchoredThreshold, data, negate, buffer, range);
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
                    position = evaluateBlockForEquality(position, typesHigh, typesLow, anchoredValue, data, false, temp, range);
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
                } //  FULL has no payload
            }
        }

        private void applySlice(Bits bitmap, long threshold, int type, int i, int range) {
            if (i == 0) firstSlice(bitmap, threshold, type, range);
            else nextSlice(bitmap, threshold, type, i, range);
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
            boolean trivialUpperBound = Long.compareUnsigned(upper, blockMin) < 0;
            boolean trivialLowerBound = Long.compareUnsigned(lower, blockMin) < 0;
            if (trivialUpperBound) {
                skipBlock(typesHigh, typesLow);
                buffer.clear(range);
                return;
            }
            long anchoredLower = lower - blockMin;
            long anchoredUpper = upper - blockMin;
            int lowerStart = trivialLowerBound ? Long.SIZE : firstRelevantSlice(anchoredLower, typesHigh, typesLow);
            if (trivialLowerBound) {
                buffer.clear(range);
            } else {
                buffer.reset();
            }
            buffer2.reset();
            int upperStart = firstRelevantSlice(anchoredUpper, typesHigh, typesLow);
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

    private static int evaluateBlockForEquality(int position,
                                                long typesHigh,
                                                long typesLow,
                                                long anchoredThreshold,
                                                ByteBuffer data,
                                                boolean negate,
                                                Bits buffer,
                                                int range) {
        // bitset becomes empty whenever bit i is present and slice i is full, or bit i is absent and slice i is empty
        // if the bitset does become empty, it can't be populated again, so the evaluation can be skipped for the entire block
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
                            assert false: "entire block should have been skipped for full block";
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


    private static int skipSlice(int type, ByteBuffer data, int position) {
        return switch (type) {
            case SPARSE_INVERTED, SPARSE -> skipSparse(data, position);
            case DENSE -> skipDense(position);
            case FULL -> position;// nothing stored
            default -> throw new IllegalStateException("invalid type: " + type);
        };
    }

    private static int firstRelevantSlice(long threshold, long typesHigh, long typesLow) {
        long fullSlices = typesHigh & typesLow;
        long presentFullSlices = fullSlices & threshold;
        int skipFull = 64 - Long.numberOfLeadingZeros(presentFullSlices);
        // when this slice is reached, the lower bitmap will be empty or full, obliterating any work done previously
        return Math.max(0, skipFull - 1);
    }

    private static int sparseAnd(long[] bitmap, ByteBuffer buffer, int position, int max) {
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

    private static int coveredSparseAnd(long[] bitmap, ByteBuffer buffer, int position) {
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

    private static int sparseAndNot(long[] bitmap, ByteBuffer buffer, int position, int range) {
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
            emptied = 0L == (bitmap[i] | bitmap[i + 1] | bitmap[i + 2] | bitmap[i + 3] | bitmap[i + 4] | bitmap[i + 5] | bitmap[i + 6] | bitmap[i + 7]);
        }
        return emptied ? -position : position;
    }

    private static int sparseOr(long[] bitmap, ByteBuffer buffer, int position) {
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
            filled = -1L == (bitmap[i] & bitmap[i + 1] & bitmap[i + 2] & bitmap[i + 3] & bitmap[i + 4] & bitmap[i + 5] & bitmap[i + 6] & bitmap[i + 7]);
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

    private static int coveredSparseOrNot(long[] bitmap, ByteBuffer buffer, int position, int max) {
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

    private static int skipSparse(ByteBuffer buffer, int position) {
        int count = buffer.getChar(position);
        return position + Character.BYTES * (1 + count);
    }

    private static int skipDense(int position) {
        return position + BLOCK_WORDS * Long.BYTES;
    }

    private static void flipBitmap(long[] bitmap, int min, int max) {
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

    private static void fillBitmap(long[] bitmap, int min, int max) {
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

