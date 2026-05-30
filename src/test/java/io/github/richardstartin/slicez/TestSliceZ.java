package io.github.richardstartin.slicez;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.roaringbitmap.RangeBitmap;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.PrimitiveIterator;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleToLongFunction;
import java.util.function.IntToLongFunction;
import java.util.function.LongSupplier;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static io.github.richardstartin.slicez.SliceZ.BLOCK_SIZE;
import static io.github.richardstartin.slicez.SliceZ.build;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Execution(ExecutionMode.CONCURRENT)
class TestSliceZ {

    private static int[] collect(PrimitiveIterator.OfInt it) {
        int[] buf = new int[4096];
        int n = 0;
        while (it.hasNext()) {
            if (n == buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
            buf[n++] = it.nextInt();
        }
        return Arrays.copyOf(buf, n);
    }

    /**
     * Returns consecutive row indices [from, to).
     */
    private static int[] range(int from, int to) {
        return IntStream.range(from, to).toArray();
    }

    private static int[] union(int[] a, int[] b) {
        int[] out = new int[a.length + b.length];
        int i = 0, j = 0, k = 0;
        while (i < a.length && j < b.length) {
            if (a[i] < b[j]) out[k++] = a[i++];
            else if (a[i] > b[j]) out[k++] = b[j++];
            else {
                out[k++] = a[i++];
                j++;
            }
        }
        while (i < a.length) out[k++] = a[i++];
        while (j < b.length) out[k++] = b[j++];
        return Arrays.copyOf(out, k);
    }

    private static int[] intersect(int[] a, int[] b) {
        int[] out = new int[Math.min(a.length, b.length)];
        int i = 0, j = 0, k = 0;
        while (i < a.length && j < b.length) {
            if (a[i] < b[j]) i++;
            else if (a[i] > b[j]) j++;
            else {
                out[k++] = a[i++];
                j++;
            }
        }
        return Arrays.copyOf(out, k);
    }

    // -------------------------------------------------------------------------
    // Stats
    // -------------------------------------------------------------------------
    @Test
    public void testCompressionRatio() {
        assertEquals(0.00012d, testCounts(i -> 0L, 1, 64, 0, 0, 0), 1e-3);
        assertEquals(0.00012d, testCounts(i -> 1L, 1, 64, 0, 0, 0), 1e-3);
        assertEquals(0.00012d, testCounts(i -> Long.MIN_VALUE, 1, 64, 0, 0, 0), 1e-3);
        assertEquals(0.00012d, testCounts(i -> Long.MAX_VALUE, 1, 64, 0, 0, 0), 1e-3);
        assertEquals(0.00012d, testCounts(i -> -1L, 1, 64, 0, 0, 0), 1e-3);
        assertEquals(0.00012d, testCounts(i -> 0xFFFFFFFFL, 1, 64, 0, 0, 0), 1e-3);
        assertEquals(1.00012d, testCounts(i -> ThreadLocalRandom.current().nextLong(), 1, 0, 0, 64, 0), 1e-3);
        assertEquals(0.25d, testCounts(i -> i, 1, 48, 0, 16, 0), 1e-3);
        assertEquals(0.25d, testCounts(i -> -i, 1, 0, 0, 16, 48), 1e-3);
        assertEquals(0.25d, testCounts(i -> -i & 0xFFFFFFFFL, 1, 32, 0, 16, 16), 1e-3);
        assertEquals(0.016d, testCounts(i -> i & 1, 1, 63, 0, 1, 0), 1e-3);
        assertEquals(0.031d, testCounts(i -> i & 3, 1, 62, 0, 2, 0), 1e-3);
        assertEquals(0.047d, testCounts(i -> i & 7, 1, 61, 0, 3, 0), 1e-3);
        assertEquals(0.062d, testCounts(i -> i & 15, 1, 60, 0, 4, 0), 1e-3);
    }

    private static double testCounts(IntToLongFunction generator, int blocks, int expectedFull, int expectedSparseInverted, int expectedDense, int expectedSparse) {
        var appender = SliceZ.appender();
        IntStream.range(0, blocks * BLOCK_SIZE).mapToLong(generator).forEach(appender);
        var sut = appender.build();
        assertEquals(expectedFull, sut.getFullSliceCount());
        assertEquals(expectedSparseInverted, sut.getSparseInvertedSliceCount());
        assertEquals(expectedSparse, sut.getSparseSliceCount());
        assertEquals(expectedDense, sut.getDenseSliceCount());
        return sut.getCompressionRatio();
    }

    // -------------------------------------------------------------------------
    // min() / max()
    // -------------------------------------------------------------------------

    @Test
    void minMaxBasic() {
        var idx = build(3, 1, 4, 1, 5, 9, 2, 6);
        assertEquals(1L, idx.min());
        assertEquals(9L, idx.max());
    }

    @Test
    void minMaxSingleElement() {
        var idx = build(42L);
        assertEquals(42L, idx.min());
        assertEquals(42L, idx.max());
    }

    @Test
    void minMaxAllSame() {
        var idx = build(7, 7, 7);
        assertEquals(7L, idx.min());
        assertEquals(7L, idx.max());
    }

    @Test
    void minMaxUnsignedOrdering() {
        // unsigned order: 0 < Long.MAX_VALUE < Long.MIN_VALUE < -1L
        var idx = build(0L, Long.MAX_VALUE, Long.MIN_VALUE, -1L);
        assertEquals(0L, idx.min());
        assertEquals(-1L, idx.max());
    }

    @Test
    void minMaxUnsignedExtremes() {
        var idx = build(0L, -1L);
        assertEquals(0L, idx.min());
        assertEquals(-1L, idx.max());
    }

    @ParameterizedTest
    @ValueSource(ints = {0xFFFF, 0x10001, 100_000, 0x110001})
    void minMaxMultiBlock(int size) {
        SliceZ.Appender appender = SliceZ.appender();
        LongStream.range(0, size).forEach(appender::add);
        SliceZ idx = appender.build();
        assertEquals(0L, idx.min());
        assertEquals(size - 1L, idx.max());
    }

    @Test
    void minMaxConsistentWithQueryBounds() {
        var idx = build(3, 1, 4, 1, 5, 9, 2, 6);
        int n = 8;
        assertEquals(0, idx.countLessThan(idx.min()));
        assertEquals(0, idx.countGreaterThan(idx.max()));
        assertEquals(n, idx.countGreaterThanOrEqual(idx.min()));
        assertEquals(n, idx.countLessThanOrEqual(idx.max()));
    }

    @Test
    void minMaxEmptyIndexSentinels() {
        // empty index exposes the internal unsigned sentinels: min=-1L (unsigned max),
        // max=0L — callers can detect empty by checking Long.compareUnsigned(min, max) > 0
        var idx = build();
        assertEquals(-1L, idx.min());
        assertEquals(0L, idx.max());
        assertTrue(Long.compareUnsigned(idx.min(), idx.max()) > 0);
    }

    // -------------------------------------------------------------------------
    // Basic operations
    // -------------------------------------------------------------------------

    @Test
    public void testRejectCorruptData() {
        assertThrows(IllegalArgumentException.class, () -> new SliceZ(ByteBuffer.allocate(100)));
    }

    @Test
    void lessThan() {
        var idx = build(0, 1, 2, 3, 4);
        assertArrayEquals(new int[]{0, 1, 2}, collect(idx.lessThan(3)));
        assertEquals(3, idx.countLessThan(3));
    }

    @Test
    void lessThanOrEqual() {
        var idx = build(0, 1, 2, 3, 4);
        assertArrayEquals(new int[]{0, 1, 2, 3}, collect(idx.lessThanOrEqual(3)));
        assertEquals(4, idx.countLessThanOrEqual(3));
    }

    @Test
    void equal() {
        var idx = build(0, 1, 2, 3, 4);
        assertArrayEquals(new int[]{2}, collect(idx.equal(2)));
        assertEquals(1, idx.countEqual(3));
    }

    @Test
    void notEqual() {
        var idx = build(0, 1, 2, 3, 4);
        assertArrayEquals(new int[]{0, 1, 3, 4}, collect(idx.notEqual(2)));
        assertEquals(4, idx.countNotEqual(2));
    }

    @Test
    void notEqualAbsentValue() {
        var idx = build(0, 1, 2, 3, 4);
        assertArrayEquals(range(0, 5), collect(idx.notEqual(99)));
        assertEquals(5, idx.countNotEqual(99));
    }

    @Test
    void notEqualIsComplementOfEqual() {
        var idx = build(0, 1, 2, 3, 4);
        for (long v = 0; v < 5; v++) {
            int[] eq    = collect(idx.equal(v));
            int[] neq   = collect(idx.notEqual(v));
            assertArrayEquals(range(0, 5), union(eq, neq), "union at v=" + v);
            assertArrayEquals(new int[]{},  intersect(eq, neq), "intersection at v=" + v);
            assertEquals(5, idx.countEqual(v) + idx.countNotEqual(v));
        }
    }

    @Test
    void notEqualAllSameValues() {
        var idx = build(7, 7, 7);
        assertArrayEquals(new int[]{}, collect(idx.notEqual(7)));
        assertEquals(0, idx.countNotEqual(7));
        assertArrayEquals(range(0, 3), collect(idx.notEqual(0)));
        assertEquals(3, idx.countNotEqual(0));
    }

    @Test
    void notEqualSingleElement() {
        var idx = build(42L);
        assertArrayEquals(new int[]{},  collect(idx.notEqual(42L)));
        assertEquals(0, idx.countNotEqual(42L));
        assertArrayEquals(new int[]{0}, collect(idx.notEqual(99L)));
        assertEquals(1, idx.countNotEqual(99L));
    }

    @Test
    void notEqualEmptyIndex() {
        var idx = build();
        assertArrayEquals(new int[]{}, collect(idx.notEqual(0L)));
        assertEquals(0, idx.countNotEqual(0L));
    }

    @Test
    void notEqualDuplicates() {
        var idx = build(3, 3, 3, 1, 2);
        assertArrayEquals(new int[]{3, 4}, collect(idx.notEqual(3L)));
        assertEquals(2, idx.countNotEqual(3L));
        assertArrayEquals(new int[]{0, 1, 2, 4}, collect(idx.notEqual(1L)));
        assertEquals(4, idx.countNotEqual(1L));
    }

    @Test
    void notEqualUnsignedExtremes() {
        var idx = build(0L, Long.MIN_VALUE, -1L);
        assertArrayEquals(new int[]{1, 2}, collect(idx.notEqual(0L)));
        assertEquals(2, idx.countNotEqual(0L));
        assertArrayEquals(new int[]{0, 2}, collect(idx.notEqual(Long.MIN_VALUE)));
        assertEquals(2, idx.countNotEqual(Long.MIN_VALUE));
        assertArrayEquals(new int[]{0, 1}, collect(idx.notEqual(-1L)));
        assertEquals(2, idx.countNotEqual(-1L));
    }

    @ParameterizedTest
    @ValueSource(ints = {0xFFFF, 0x10001, 100_000, 0x110001})
    void notEqualMultiBlock(int size) {
        SliceZ.Appender appender = SliceZ.appender();
        LongStream.range(0, size).forEach(appender::add);
        SliceZ idx = appender.build();
        for (long v : new long[]{0, 1, size / 2, size - 1}) {
            int[] eq  = collect(idx.equal(v));
            int[] neq = collect(idx.notEqual(v));
            assertArrayEquals(range(0, size), union(eq, neq), "union at v=" + v);
            assertArrayEquals(new int[]{},     intersect(eq, neq), "intersection at v=" + v);
            assertEquals(size, idx.countEqual(v) + idx.countNotEqual(v));
        }
    }

    @Test
    void greaterThan() {
        var idx = build(0, 1, 2, 3, 4);
        assertArrayEquals(new int[]{3, 4}, collect(idx.greaterThan(2)));
        assertEquals(2, idx.countGreaterThan(2));
    }

    @Test
    void greaterThanOrEqual() {
        var idx = build(0, 1, 2, 3, 4);
        assertArrayEquals(new int[]{2, 3, 4}, collect(idx.greaterThanOrEqual(2)));
        assertEquals(3, idx.countGreaterThanOrEqual(2));
    }

    @Test
    void between() {
        var idx = build(0, 1, 2, 3, 4);
        assertArrayEquals(new int[]{1, 2, 3}, collect(idx.between(1, 4)));
        assertEquals(3, idx.countBetween(1, 4));
    }

    // -------------------------------------------------------------------------
    // Unsigned semantics at the 32-bit boundary
    // -------------------------------------------------------------------------

    private static final long MAX_U32 = 0xFFFFFFFFL;
    private static final long MID_U32 = 0x80000000L;
    private static final long SIGNED_MAX = Integer.MAX_VALUE;

    private static final long[] U32_BOUNDARY = {0L, 1L, SIGNED_MAX, MID_U32, MAX_U32};

    @Test
    void unsignedLt_belowMidpoint() {
        var idx = build(U32_BOUNDARY);
        assertArrayEquals(new int[]{0, 1, 2}, collect(idx.lessThan(MID_U32)));
        assertEquals(3, idx.countLessThan(MID_U32));
    }

    @Test
    void unsignedGt_aboveSignedMax() {
        var idx = build(U32_BOUNDARY);
        assertArrayEquals(new int[]{3, 4}, collect(idx.greaterThan(SIGNED_MAX)));
        assertEquals(2, idx.countGreaterThan(SIGNED_MAX));
    }

    @Test
    void unsignedEqual_maxU32() {
        var idx = build(U32_BOUNDARY);
        assertArrayEquals(new int[]{4}, collect(idx.equal(MAX_U32)));
        assertEquals(1, idx.countEqual(MAX_U32));
    }

    @Test
    void unsignedBetween_straddlingSignedBoundary() {
        var idx = build(U32_BOUNDARY);
        assertArrayEquals(new int[]{2, 3}, collect(idx.between(SIGNED_MAX, MAX_U32)));
        assertEquals(2, idx.countBetween(SIGNED_MAX, MAX_U32));
    }

    @Test
    void unsignedGte_zero_returnsAll() {
        var idx = build(U32_BOUNDARY);
        assertArrayEquals(new int[]{0, 1, 2, 3, 4}, collect(idx.greaterThanOrEqual(0L)));
        assertEquals(5, idx.countGreaterThanOrEqual(0L));
    }

    @Test
    void unsignedLte_maxU32_returnsAll() {
        var idx = build(U32_BOUNDARY);
        assertArrayEquals(new int[]{0, 1, 2, 3, 4}, collect(idx.lessThanOrEqual(MAX_U32)));
        assertEquals(5, idx.countLessThanOrEqual(MAX_U32));
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Test
    void ltZeroIsEmpty() {
        var idx = build(0, 1, 2);
        assertArrayEquals(new int[]{}, collect(idx.lessThan(0L)));
        assertEquals(0, idx.countLessThan(0L));
    }

    @Test
    void gtMaxU32IsEmpty() {
        var idx = build(0, 1, MAX_U32);
        assertArrayEquals(new int[]{}, collect(idx.greaterThan(MAX_U32)));
        assertEquals(0, idx.countGreaterThan(MAX_U32));
    }

    @Test
    void emptyIndex() {
        var idx = build();
        assertArrayEquals(new int[]{}, collect(idx.lessThan(5L)));
        assertEquals(0, idx.countLessThan(5L));
        assertArrayEquals(new int[]{}, collect(idx.greaterThan(5L)));
        assertEquals(0, idx.countGreaterThan(5L));
        assertArrayEquals(new int[]{}, collect(idx.equal(5L)));
        assertEquals(0, idx.countEqual(5L));
        assertArrayEquals(new int[]{}, collect(idx.between(0L, 10L)));
        assertEquals(0, idx.countBetween(0L, 10L));
    }

    @Test
    void duplicateValues() {
        var idx = build(3, 3, 3, 1, 2);
        assertArrayEquals(new int[]{0, 1, 2}, collect(idx.equal(3L)));
        assertEquals(3, idx.countEqual(3L));
        assertArrayEquals(new int[]{3, 4}, collect(idx.lessThan(3L)));
        assertEquals(2, idx.countLessThan(3L));
        assertArrayEquals(new int[]{}, collect(idx.greaterThan(3L)));
        assertEquals(0, idx.countGreaterThan(3L));
    }

    @Test
    void betweenEmptyWhenLowerEqualsUpper() {
        var idx = build(0, 1, 2, 3, 4);
        assertArrayEquals(new int[]{}, collect(idx.between(2L, 2L)));
        assertEquals(0, idx.countBetween(2L, 2L));
    }

    @Test
    void betweenEmptyWhenLowerExceedsUpper() {
        var idx = build(0, 1, 2, 3, 4);
        assertArrayEquals(new int[]{}, collect(idx.between(3L, 1L)));
        assertEquals(0, idx.countBetween(3L, 1L));
    }

    @Test
    void singleElement() {
        var idx = build(42L);
        assertArrayEquals(new int[]{0}, collect(idx.equal(42L)));
        assertEquals(1, idx.countEqual(42L));
        assertArrayEquals(new int[]{}, collect(idx.lessThan(42L)));
        assertEquals(0, idx.countLessThan(42L));
        assertArrayEquals(new int[]{}, collect(idx.greaterThan(42L)));
        assertEquals(0, idx.countGreaterThan(42L));
        assertArrayEquals(new int[]{0}, collect(idx.between(42L, 43L)));
        assertEquals(1, idx.countBetween(42L, 43L));
        assertArrayEquals(new int[]{}, collect(idx.between(41L, 42L)));
        assertEquals(0, idx.countBetween(41L, 42L));
    }

    @Test
    void allZeroValues() {
        var idx = build(0, 0, 0);
        assertArrayEquals(new int[]{0, 1, 2}, collect(idx.equal(0L)));
        assertEquals(3, idx.countEqual(0L));
        assertArrayEquals(new int[]{}, collect(idx.lessThan(0L)));
        assertEquals(0, idx.countLessThan(0L));
        assertArrayEquals(new int[]{0, 1, 2}, collect(idx.lessThanOrEqual(0L)));
        assertEquals(3, idx.countLessThanOrEqual(0L));
    }

    // -------------------------------------------------------------------------
    // Cross-check against RangeBitmapLongIndex over multiple blocks
    // -------------------------------------------------------------------------

    @Test
    void crossCheckMultipleBlocks() {
        long[] values = new long[10000];
        for (int i = 0; i < values.length; i++) values[i] = i * 7L;
        var rba = RangeBitmap.appender(-1L);
        Arrays.stream(values).forEach(rba::add);
        var ref = rba.build();
        var idx = build(values);
        long lower = 500L, upper = 3000L;
        assertArrayEquals(ref.between(lower, upper).toArray(), collect(idx.between(lower, upper)));
        assertArrayEquals(ref.lt(1000L).toArray(), collect(idx.lessThan(1000L)));
        assertArrayEquals(ref.gt(5000L).toArray(), collect(idx.greaterThan(5000L)));
        assertArrayEquals(ref.eq(777L).toArray(), collect(idx.equal(777L)));
    }

    // =========================================================================
    // Adapted from RangeBitmapTest
    // =========================================================================

    @ParameterizedTest
    @ValueSource(ints = {0, 0xFFFF, 0xFFFF1, 0x10001, 100_000, 0x110001, 0x110001, 1_000_000})
    void testInsertContiguousValues(int size) {
        SliceZ.Appender appender = SliceZ.appender();
        LongStream.range(0, size).forEach(appender::add);
        SliceZ idx = appender.build();
        assertArrayEquals(range(0, size), collect(idx.lessThanOrEqual(size)));
        for (long upper = 1; upper < size; upper *= 10) {
            assertArrayEquals(range(0, (int) upper + 1), collect(idx.lessThanOrEqual(upper)));
            assertEquals((int) upper + 1, idx.countLessThanOrEqual(upper));
            assertArrayEquals(range(0, (int) upper), collect(idx.lessThan(upper)));
            assertEquals(upper, idx.countLessThan(upper));
            assertArrayEquals(new int[]{(int) upper}, collect(idx.equal(upper)));
            assertEquals(1, idx.countEqual(upper));
        }
        for (long lower = 1; lower < size; lower *= 10) {
            assertArrayEquals(range((int) lower, size), collect(idx.greaterThanOrEqual(lower)));
            assertEquals(size - lower, idx.countGreaterThanOrEqual(lower));
            assertArrayEquals(range((int) lower + 1, size), collect(idx.greaterThan(lower)));
            assertEquals(size - lower - 1, idx.countGreaterThan(lower));
            assertArrayEquals(new int[]{(int) lower}, collect(idx.equal(lower)));
            assertEquals(1, idx.countEqual(lower));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 0xFFFF, 0xFFFF1, 0x10001, 100_000, 0x110001, 1_000_000})
    void testInsertReversedContiguousValues(int size) {
        // row i has value (size - i), so values are size, size-1, ..., 1
        SliceZ.Appender appender = SliceZ.appender();
        LongStream.range(0, size).map(i -> size - i).forEach(appender::add);
        SliceZ idx = appender.build();
        for (long upper = 1; upper < size; upper *= 10) {
            // value <= upper at rows where size-i <= upper, i.e. i >= size-upper
            assertArrayEquals(range(size - (int) upper, size), collect(idx.lessThanOrEqual(upper)), upper + "," + size);
            assertEquals((int) upper, idx.countLessThanOrEqual(upper));
            assertArrayEquals(range(size - (int) upper + 1, size), collect(idx.lessThan(upper)));
            assertEquals((int) upper - 1, idx.countLessThan(upper));
        }
        for (long lower = 1; lower < size; lower *= 10) {
            // value >= lower at rows where size-i >= lower, i.e. i <= size-lower
            assertArrayEquals(range(0, size - (int) lower + 1), collect(idx.greaterThanOrEqual(lower)));
            assertEquals(size - (int) lower + 1, idx.countGreaterThanOrEqual(lower));
            assertArrayEquals(range(0, size - (int) lower), collect(idx.greaterThan(lower)), size + "/" + lower);
            assertEquals(size - (int) lower, idx.countGreaterThan(lower));
        }
    }

    @Test
    public void testSparseOrNot() {
        long[] bitmap = new long[]{-1L, 0, 0xF0F0F0F0F0F0F0F0L, 0x0F0F0F0F0F0F0F0FL};
        char[] values = new char[]{(char) 32, (char) 96, (char) 160, (char) 204, (char) 216, (char) 218};
        long[] asBitmap = new long[bitmap.length];
        ByteBuffer buffer = ByteBuffer.allocate(Character.BYTES * (values.length + 1));
        buffer.putChar((char) values.length);
        for (char value : values) {
            buffer.putChar(value);
            asBitmap[value >>> 6] |= (1L << value);
        }
        long[] expected = Arrays.copyOf(bitmap, bitmap.length);
        for (int i = 0; i < bitmap.length; i++) {
            expected[i] |= ~asBitmap[i];
        }
        int position = Util.sparseOrNot(bitmap, buffer, 0, bitmap.length * Long.SIZE);
        assertEquals(buffer.limit(), position);
        assertArrayEquals(expected, bitmap);
    }

    @Test
    void testInsertContiguousValuesAboveRange() {
        SliceZ.Appender appender = SliceZ.appender();
        LongStream.range(0, 1_000_000).forEach(appender::add);
        SliceZ idx = appender.build();
        int[] all = range(0, 1_000_000);
        assertArrayEquals(all, collect(idx.lessThanOrEqual(999_999)));
        assertEquals(all.length, idx.countLessThanOrEqual(999_999));
        assertArrayEquals(all, collect(idx.lessThanOrEqual(1_000_000)));
        assertEquals(all.length, idx.countLessThanOrEqual(1_000_000));
        assertArrayEquals(all, collect(idx.lessThan(1_000_000)));
        assertEquals(all.length, idx.countLessThan(1_000_000));
        assertArrayEquals(all, collect(idx.lessThanOrEqual(1_000_000_000)));
        assertEquals(all.length, idx.countLessThanOrEqual(1_000_000_000));
        assertArrayEquals(all, collect(idx.lessThan(1_000_000_000)));
        assertEquals(all.length, idx.countLessThan(1_000_000_000));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 0xFFFF, 0x10001, 100_000, 0x110001, 1_000_000})
    void monotonicLTECardinality(int size) {
        SliceZ.Appender appender = SliceZ.appender();
        LongStream.range(0, size).forEach(appender::add);
        SliceZ idx = appender.build();
        int prev = 0;
        for (int i = size - 2; i <= size + 2; i++) {
            int count = collect(idx.lessThanOrEqual(i)).length;
            assertTrue(count >= prev);
            assertEquals(count, idx.countLessThanOrEqual(i));
            prev = count;
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 0xFFFF, 0x10001, 100_000, 0x110001, 1_000_000})
    void monotonicGTCardinality(int size) {
        SliceZ.Appender appender = SliceZ.appender();
        LongStream.range(0, size).forEach(appender::add);
        SliceZ idx = appender.build();
        int prev = size;
        for (int i = size - 2; i <= size + 2; i++) {
            int count = collect(idx.greaterThan(i)).length;
            assertTrue(count <= prev);
            assertEquals(count, idx.countGreaterThan(i));
            prev = count;
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 0xFFFF, 0x10000, 0x10001, 100_000, 0x110001, 1_000_000})
    void unionOfComplementsMatchesAll(int size) {
        SliceZ.Appender appender = SliceZ.appender();
        LongStream.range(0, size).forEach(appender::add);
        SliceZ idx = appender.build();
        int[] all = range(0, size);
        for (int i = size - 2; i <= size + 2; i++) {
            assertArrayEquals(all, union(collect(idx.greaterThanOrEqual(i)), collect(idx.lessThan(i))));
        }
    }

    @ParameterizedTest
    @MethodSource("distributions")
    void testDoubleEndedRangeMatchesIntersection(LongSupplier dist) {
        long maxValue = 10_000_000;
        var appender = SliceZ.appender();
        LongStream.range(0, 0x10000).forEach(i -> {
            long value = Math.min(dist.getAsLong(), maxValue);
            appender.add(value);
        });
        SliceZ sut = appender.build();
        for (int i = 7; i < 8; i++) {
            long min = (long) Math.pow(10, i - 1);
            long max = (long) Math.pow(10, i);
            int[] expected = intersect(collect(sut.lessThanOrEqual(max)), collect(sut.greaterThanOrEqual(min)));
            assertArrayEquals(expected, collect(sut.between(min, max + 1)), min + "," + max);
        }
    }

    @Test
    void testExtremeValues() {
        // unsigned ordering: 0 < Long.MIN_VALUE (0x8000...0) < -1L (0xFFFF...F)
        SliceZ idx = build(0L, Long.MIN_VALUE, -1L);
        assertArrayEquals(new int[]{}, collect(idx.greaterThan(-1L)));
        assertEquals(0, idx.countGreaterThan(-1L));
        assertArrayEquals(new int[]{2}, collect(idx.greaterThanOrEqual(-1L)));
        assertEquals(1, idx.countGreaterThanOrEqual(-1L));
        assertArrayEquals(new int[]{0, 1, 2}, collect(idx.lessThanOrEqual(-1L)));
        assertEquals(3, idx.countLessThanOrEqual(-1L));
        assertArrayEquals(new int[]{0, 1}, collect(idx.lessThanOrEqual(-2L)));
        assertEquals(2, idx.countLessThanOrEqual(-2L));
        assertArrayEquals(new int[]{0, 1}, collect(idx.lessThan(-1L)));
        assertEquals(2, idx.countLessThan(-1L));
        assertArrayEquals(new int[]{0, 1}, collect(idx.lessThanOrEqual(Long.MIN_VALUE)));
        assertEquals(2, idx.countLessThanOrEqual(Long.MIN_VALUE));
        assertArrayEquals(new int[]{0}, collect(idx.lessThan(Long.MIN_VALUE)));
        assertEquals(1, idx.countLessThan(Long.MIN_VALUE));
        assertArrayEquals(new int[]{2}, collect(idx.greaterThan(Long.MIN_VALUE)));
        assertEquals(1, idx.countGreaterThan(Long.MIN_VALUE));
        assertArrayEquals(new int[]{1, 2}, collect(idx.greaterThanOrEqual(Long.MIN_VALUE)));
        assertEquals(2, idx.countGreaterThanOrEqual(Long.MIN_VALUE));
        assertArrayEquals(new int[]{0}, collect(idx.lessThanOrEqual(0)));
        assertEquals(1, idx.countLessThanOrEqual(0));
        assertArrayEquals(new int[]{}, collect(idx.lessThan(0)));
        assertEquals(0, idx.countLessThan(0));
        assertArrayEquals(new int[]{0, 1, 2}, collect(idx.greaterThanOrEqual(0)));
        assertEquals(3, idx.countGreaterThanOrEqual(0));
        assertArrayEquals(new int[]{1, 2}, collect(idx.greaterThan(0)));
        assertEquals(2, idx.countGreaterThan(0));
        assertArrayEquals(new int[]{}, collect(idx.equal(2L)));
        assertEquals(0, idx.countEqual(2L));
        assertArrayEquals(new int[]{0}, collect(idx.equal(0L)));
        assertEquals(1, idx.countEqual(0L));
        assertArrayEquals(new int[]{1}, collect(idx.equal(Long.MIN_VALUE)));
        assertEquals(1, idx.countEqual(Long.MIN_VALUE));
        assertArrayEquals(new int[]{2}, collect(idx.equal(-1L)));
        assertEquals(1, idx.countEqual(-1L));
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 7, 8, 15, 16, 31, 32, 63, 64})
    void extremelySmallBitmapTest(long value) {
        SliceZ idx = build(value);
        assertEquals(1, collect(idx.greaterThanOrEqual(value)).length);
        assertEquals(1, idx.countGreaterThanOrEqual(value));
        assertEquals(1, collect(idx.lessThanOrEqual(value)).length);
        assertEquals(1, idx.countLessThanOrEqual(value));
        assertEquals(1, collect(idx.between(value, value + 1)).length);
        assertEquals(1, idx.countBetween(value, value + 1));
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 7, 8, 15, 16, 31, 32, 63, 64})
    void testModulo65536(long value) {
        int count = 65537;
        SliceZ.Appender appender = SliceZ.appender();
        for (int i = 0; i < count; i++) appender.add(value);
        SliceZ idx = appender.build();
        assertEquals(count, collect(idx.greaterThanOrEqual(value)).length);
        assertEquals(count, idx.countGreaterThanOrEqual(value));
        assertEquals(count, collect(idx.lessThanOrEqual(value)).length);
        assertEquals(count, idx.countLessThanOrEqual(value));
        assertEquals(count, collect(idx.between(value, value + 1)).length);
        assertEquals(count, idx.countBetween(value, value + 1));
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 10, 100, 1000})
    void testEq(int max) {
        SliceZ.Appender appender = SliceZ.appender();
        int total = 100_000;
        for (int i = 0; i < total; i++) appender.add(i % max);
        SliceZ idx = appender.build();
        for (int offset = 0; offset < max; offset++) {
            // rows with value == offset: {offset, offset+max, offset+2*max, ...}
            int count = (total - offset + max - 1) / max;
            int[] expected = new int[count];
            for (int k = 0; k < count; k++) expected[k] = offset + k * max;
            assertArrayEquals(expected, collect(idx.equal(offset)), max + ", " + offset);
        }
    }

    @Test
    void testEq2() {
        int max = 2;
        SliceZ.Appender appender = SliceZ.appender();
        int total = 256 - 1;
        for (int i = 0; i < total; i++) {
            appender.add(i % max);
        }
        SliceZ idx = appender.build();
        int offset = 0;
        int count = (total - offset + max - 1) / max;
        int[] expected = new int[count];
        for (int k = 0; k < count; k++) {
            expected[k] = offset + k * max;
        }
        assertArrayEquals(expected, collect(idx.equal(offset)), max + ", " + offset);
    }

    private static final DoubleToLongFunction DOUBLE_ENCODER = value -> {
        if (value == Double.NEGATIVE_INFINITY) return 0;
        if (value == Double.POSITIVE_INFINITY || Double.isNaN(value)) return 0xFFFFFFFFFFFFFFFFL;
        long bits = Double.doubleToLongBits(value);
        if ((bits & Long.MIN_VALUE) == Long.MIN_VALUE) {
            bits = bits == Long.MIN_VALUE ? Long.MIN_VALUE : ~bits;
        } else {
            bits ^= Long.MIN_VALUE;
        }
        return bits;
    };

    @Test
    void testIndexDoubleValues() {
        double[] doubles = IntStream.range(0, 200)
                .mapToDouble(i -> Math.pow(-1, i) * Math.pow(10, i)).toArray();
        SliceZ.Appender appender = SliceZ.appender();
        Arrays.stream(doubles).mapToLong(DOUBLE_ENCODER).forEach(appender::add);
        SliceZ idx = appender.build();
        for (int v = 0; v < doubles.length; v++) {
            long threshold = DOUBLE_ENCODER.applyAsLong(doubles[v]);
            final int fv = v;
            int[] expected = IntStream.range(0, doubles.length)
                    .filter(j -> doubles[j] <= doubles[fv]).toArray();
            assertArrayEquals(expected, collect(idx.lessThanOrEqual(threshold)));
        }
    }

    @Test
    void testBetweenDoubleValues() {
        double[] doubles = IntStream.range(0, 200)
                .mapToDouble(i -> Math.pow(-1, i) * Math.pow(10, i)).toArray();
        SliceZ.Appender appender = SliceZ.appender();
        Arrays.stream(doubles).mapToLong(DOUBLE_ENCODER).forEach(appender::add);
        SliceZ idx = appender.build();
        for (int v = 0; v < doubles.length; v++) {
            long min = DOUBLE_ENCODER.applyAsLong(doubles[v] / 2);
            long max = DOUBLE_ENCODER.applyAsLong(doubles[v]);
            final int fv = v;
            int[] expected = IntStream.range(0, doubles.length)
                    .filter(j -> doubles[j] <= doubles[fv] && doubles[j] >= doubles[fv] / 2)
                    .toArray();
            assertArrayEquals(expected, collect(idx.between(min, max + 1)));
        }
    }

    @ParameterizedTest
    @MethodSource("distributions")
    void testConstructRelativeToMinValue(LongSupplier dist) {
        int[] values = IntStream.range(0, 1_000_000).map(i -> (int) dist.getAsLong()).toArray();
        int min = IntStream.of(values).min().orElse(0);
        int max = IntStream.of(values).max().orElse(Integer.MAX_VALUE) - min;
        SliceZ.Appender appender = SliceZ.appender();
        IntStream.of(values).map(i -> i - min).forEach(appender::add);
        SliceZ idx = appender.build();
        assertEquals(values.length, collect(idx.lessThanOrEqual(max)).length);
        assertEquals(values.length, collect(idx.greaterThanOrEqual(0)).length);
    }

    @ParameterizedTest
    @MethodSource("distributions")
    void testCountersAndCompressionRatio(LongSupplier dist) {
        int[] values = IntStream.range(0, 1_000_000).map(i -> (int) dist.getAsLong()).toArray();
        SliceZ.Appender appender = SliceZ.appender();
        IntStream.of(values).forEach(appender::add);
        SliceZ idx = appender.build();
        assertTrue(idx.getCompressionRatio() <= 1d);
        assertTrue(idx.getBlockCount() > 0);
        assertEquals(idx.getBlockCount() * Long.SIZE, idx.getSparseSliceCount() + idx.getDenseSliceCount() + idx.getSparseInvertedSliceCount() + idx.getFullSliceCount());
    }


    // -------------------------------------------------------------------------
    // in()
    // -------------------------------------------------------------------------

    @Test
    void inEmptyValuesArray() {
        var idx = build(0, 1, 2, 3, 4);
        assertArrayEquals(new int[]{}, collect(idx.in()));
    }

    @Test
    void inSingleValueMatchesSingleEqual() {
        var idx = build(0, 1, 2, 3, 4);
        assertArrayEquals(collect(idx.equal(2)), collect(idx.in(2)));
        assertArrayEquals(collect(idx.equal(99)), collect(idx.in(99)));
    }

    @Test
    void inTwoValuesBothPresent() {
        var idx = build(0, 1, 2, 3, 4);
        int[] expected = union(collect(idx.equal(1)), collect(idx.equal(3)));
        assertArrayEquals(expected, collect(idx.in(1, 3)));
    }

    @Test
    void inTwoValuesOneAbsent() {
        var idx = build(0, 1, 2, 3, 4);
        int[] expected = collect(idx.equal(2));
        assertArrayEquals(expected, collect(idx.in(2, 99)));
        assertArrayEquals(expected, collect(idx.in(99, 2)));
    }

    @Test
    void inAllValuesAbsent() {
        var idx = build(0, 1, 2, 3, 4);
        assertArrayEquals(new int[]{}, collect(idx.in(10, 20, 30)));
    }

    @Test
    void inOrderIndependence() {
        var idx = build(0, 1, 2, 3, 4);
        assertArrayEquals(collect(idx.in(1, 3)), collect(idx.in(3, 1)));
        assertArrayEquals(collect(idx.in(0, 2, 4)), collect(idx.in(4, 2, 0)));
    }

    @Test
    void inDuplicateQueryValues() {
        var idx = build(0, 1, 2, 3, 4);
        assertArrayEquals(collect(idx.equal(2)), collect(idx.in(2, 2)));
    }

    @Test
    void inWithDuplicateIndexedValues() {
        var idx = build(3, 3, 3, 1, 2);
        int[] expected = union(collect(idx.equal(3)), collect(idx.equal(1)));
        assertArrayEquals(expected, collect(idx.in(3, 1)));
    }

    @Test
    void inUnsignedExtremes() {
        var idx = build(0L, Long.MIN_VALUE, -1L);
        // unsigned order: 0 < Long.MIN_VALUE < -1L
        assertArrayEquals(new int[]{0, 1}, collect(idx.in(0L, Long.MIN_VALUE)));
        assertArrayEquals(new int[]{0, 2}, collect(idx.in(0L, -1L)));
        assertArrayEquals(new int[]{1, 2}, collect(idx.in(Long.MIN_VALUE, -1L)));
        assertArrayEquals(new int[]{0, 1, 2}, collect(idx.in(0L, Long.MIN_VALUE, -1L)));
    }

    @Test
    void inEmptyIndex() {
        var idx = build();
        assertArrayEquals(new int[]{}, collect(idx.in(0L, 1L)));
    }

    @ParameterizedTest
    @ValueSource(ints = {0xFFFF, 0x10001, 100_000, 0x110001})
    void inMultiBlockCrossCheck(int size) {
        SliceZ.Appender appender = SliceZ.appender();
        LongStream.range(0, size).forEach(appender::add);
        SliceZ idx = appender.build();
        long v1 = 42L;
        long v2 = (long) (size / 2);
        long v3 = size - 1L;
        int[] expected = union(union(collect(idx.equal(v1)), collect(idx.equal(v2))), collect(idx.equal(v3)));
        assertArrayEquals(expected, collect(idx.in(v1, v2, v3)));
    }

    @ParameterizedTest
    @ValueSource(ints = {0x10001, 100_000})
    void inMultiBlockOrderIndependence(int size) {
        SliceZ.Appender appender = SliceZ.appender();
        LongStream.range(0, size).forEach(appender::add);
        SliceZ idx = appender.build();
        long v1 = 42L;                   // block 0
        long v2 = (long) BLOCK_SIZE + 1; // block 1
        int[] expected = union(collect(idx.equal(v1)), collect(idx.equal(v2)));
        assertArrayEquals(expected, collect(idx.in(v1, v2)), "in(block0, block1)");
        assertArrayEquals(expected, collect(idx.in(v2, v1)), "in(block1, block0)");
    }

    @ParameterizedTest
    @MethodSource("distributions")
    void inCrossCheckAgainstUnionOfEquals(LongSupplier dist) {
        int total = 10_000;
        long[] data = LongStream.range(0, total).map(i -> dist.getAsLong()).toArray();
        var appender = SliceZ.appender();
        for (long v : data) appender.add(v);
        SliceZ idx = appender.build();
        long[] query = {data[0], data[total / 4], data[total / 2], data[total - 1]};
        int[] expected = Arrays.stream(query)
                .mapToObj(q -> collect(idx.equal(q)))
                .reduce(new int[0], TestSliceZ::union);
        assertArrayEquals(expected, collect(idx.in(query)));
    }

    public static Stream<Arguments> distributions() {
        return Stream.of(
                Distribution.NORMAL.of(42, 1_000, 100),
                Distribution.NORMAL.of(42, 10_000, 10),
                Distribution.NORMAL.of(42, 1_000_000, 1000),
                Distribution.UNIFORM.of(42, 0, 1_000_000),
                Distribution.UNIFORM.of(42, 500_000, 10_000_000),
                Distribution.EXP.of(42, 0.0001),
                Distribution.EXP.of(42, 0.9999),
                Distribution.POINT.of(0, 0),
                Distribution.POINT.of(0, 1),
                Distribution.POINT.of(0, Long.MAX_VALUE),
                Distribution.SAMPLED_PCS.of(0, 256)
        ).map(Arguments::of);
    }

    enum Distribution {
        UNIFORM {
            @Override
            LongSupplier of(long seed, double... params) {
                long min = (long) params[0];
                long max = (long) params[1];
                SplittableRandom random = new SplittableRandom(seed);
                return () -> random.nextLong(min, max);
            }
        },
        NORMAL {
            @Override
            LongSupplier of(long seed, double... params) {
                double mean = params[0];
                double stddev = params[1];
                Random random = new Random(seed);
                return () -> (long) (stddev * random.nextGaussian() + mean);
            }
        },
        EXP {
            @Override
            LongSupplier of(long seed, double... params) {
                double rate = params[0];
                SplittableRandom random = new SplittableRandom(seed);
                return () -> (long) -(Math.log(random.nextDouble()) / rate);
            }
        },
        POINT {
            @Override
            LongSupplier of(long seed, double... params) {
                return () -> (long) params[0];
            }
        },
        SAMPLED_PCS {
            @Override
            LongSupplier of(long seed, double... params) {
                var random = new SplittableRandom(seed);
                // Typical JIT code region base on Linux x86-64: upper 17 bits = 0
                long base = 0x00007f1234560000L;
                int numFunctions = (int) params[0];
                long[] funcBase = new long[numFunctions];
                long[] funcSize = new long[numFunctions];
                double[] cumWeight = new double[numFunctions];

                // Lay out functions with Zipfian sizes: function k has size 64 << (k % 11)
                // giving a mix of 64B .. 64KB ranges; Zipfian weight 1/(k+1) for sampling.
                long offset = 0;
                double totalWeight = 0;
                for (int k = 0; k < numFunctions; k++) {
                    funcSize[k] = 64L << (k % 11);
                    funcBase[k] = base + offset;
                    offset += funcSize[k] + 64; // 64-byte alignment gap
                    totalWeight += 1.0 / (k + 1);
                    cumWeight[k] = totalWeight;
                }
                for (int k = 0; k < numFunctions; k++) {
                    cumWeight[k] /= totalWeight;
                }

                return () -> {
                    // Inverse-CDF sample: pick function proportional to 1/(k+1)
                    double r = random.nextDouble();
                    int k = Arrays.binarySearch(cumWeight, r);
                    if (k < 0) k = -k - 1;
                    if (k >= numFunctions) k = numFunctions - 1;
                    return funcBase[k] + random.nextLong(funcSize[k]);
                };
            }
        };

        abstract LongSupplier of(long seed, double... params);
    }

    // -------------------------------------------------------------------------
    // BetweenQuery / flipAnd regression
    // -------------------------------------------------------------------------

    @Test
    void countBetweenLowerZeroExcludesUpperBound() {
        // between(lower, upper) is exclusive-upper: lower ≤ v < upper.
        // between(0, upper) delegates to lessThan(upper) = v < upper.
        // countBetween(0, upper) delegates to countLessThanOrEqual(upper) = count(v ≤ upper).
        // When upper is in the index, countBetween overcounts by the number of rows == upper.
        var idx = build(0, 1, 2, 3, 4);
        assertArrayEquals(range(0, 4), collect(idx.between(0, 4)));  // v < 4: {0,1,2,3}
        assertEquals(4, idx.countBetween(0, 4));  // fails: returns 5
    }

    @Test
    void countBetweenLowerZeroDuplicateBoundary() {
        // Three copies of value=3 at the exclusive upper bound.
        var idx = build(0, 1, 2, 3, 3, 3);
        assertArrayEquals(range(0, 3), collect(idx.between(0, 3)));  // v < 3: {0,1,2}
        assertEquals(3, idx.countBetween(0, 3));  // fails: returns 6
    }

    @Test
    void countBetweenLowerZeroMultiBlock() {
        // Span two blocks so between(0, BLOCK_SIZE) = v in {0..BLOCK_SIZE-1} = BLOCK_SIZE rows.
        var appender = SliceZ.appender();
        for (int i = 0; i <= BLOCK_SIZE; i++) appender.add(i);
        var idx = appender.build();
        assertEquals(BLOCK_SIZE, collect(idx.between(0, BLOCK_SIZE)).length);
        assertEquals(BLOCK_SIZE, idx.countBetween(0, BLOCK_SIZE));  // fails: returns BLOCK_SIZE+1
    }

    @Test
    void betweenFlipAndBug() {
        // Block 0: BLOCK_SIZE identical values (5).  Every sv = ~0 = all-ones, so every
        // bit-slice is FULL.  Block 1: one value (7), also all-FULL (single-row block).
        //
        // Global max = 7 >= lower = 6, so the early-exit guard in between() does not fire.
        //
        // For block 0 in BetweenQuery(5, 7) [= between(6, 8) after the -1 adjustment]:
        //   anchoredLower = (lower-1) - blockMin = 5 - 5 = 0.
        //   Bit 0 of anchoredLower is 0 and slice 0 is FULL, so firstSlice calls fill()
        //   -> buffer.full = true.  All subsequent AND-FULL steps are no-ops.
        //   flipAnd short-circuits on !full, leaving the result as full (all BLOCK_SIZE
        //   rows) rather than computing NOT(full) AND buffer2 = empty.
        //
        // Expected: {BLOCK_SIZE}  (only the value-7 row from block 1).
        // Actual:   {0 .. BLOCK_SIZE}  (all block-0 rows spuriously included).
        var appender = SliceZ.appender();
        for (int i = 0; i < SliceZ.BLOCK_SIZE; i++) appender.add(5);
        appender.add(7);
        var idx = appender.build();
        assertArrayEquals(new int[]{SliceZ.BLOCK_SIZE}, collect(idx.between(6, 8)));
    }

    @Test
    void betweenBuffer2StaleFullAcrossBlocks() {
        // buffer2 (upper-bound accumulator) is never reset between blocks in BetweenQuery.
        // When block 0 fills buffer2.full=true and block 1's firstRelevantSlice(anchoredUpper)
        // returns 0 with a DENSE slice whose first operation is denseOr, the denseOr
        // short-circuits on buffer2.full=true and skips the real data — leaving buffer2 erroneously
        // full for the entire block, causing all block-1 rows to be returned even those where
        // v >= upper.
        //
        // Setup:
        //   Block 0: BLOCK_SIZE rows of value=7.  All sv=~(7-7)=~0=0xffff..., all slices FULL.
        //            anchoredUpper = 7-7 = 0; firstSlice fills buffer2 → buffer2.full=true.
        //   Block 1: BLOCK_SIZE/2 rows of value=5, then BLOCK_SIZE/2 rows of value=8.
        //            blockMin=5. sv(5)=~0=full, sv(8)=~3=0xffff..fc. Slices 0,1 are DENSE.
        //            anchoredUpper = 7-5 = 2 = 0b10; firstRelevantSlice=0 (no all-FULL prefix).
        //            bit 0 of anchoredUpper=0, slice 0 is DENSE → firstSlice calls denseOr.
        //            denseOr is a no-op when buffer2.full=true, so buffer2 stays spuriously full.
        //            All BLOCK_SIZE block-1 rows are returned; the BLOCK_SIZE/2 rows with v=8
        //            (outside [3,8)) are false positives.
        //
        // between(3, 8) = 3 ≤ v < 8. Matching values: 7 (block 0) and 5 (first half of block 1).
        // Expected: BLOCK_SIZE + BLOCK_SIZE/2 matches.
        // Actual (buggy): 2 * BLOCK_SIZE matches.
        var appender = SliceZ.appender();
        for (int i = 0; i < BLOCK_SIZE; i++) appender.add(7);
        for (int i = 0; i < BLOCK_SIZE / 2; i++) appender.add(5);
        for (int i = 0; i < BLOCK_SIZE / 2; i++) appender.add(8);
        var idx = appender.build();
        int[] result = collect(idx.between(3, 8));
        assertEquals(BLOCK_SIZE + BLOCK_SIZE / 2, result.length);
    }

    @Test
    void betweenBufferStaleFullAcrossBlocks() {
        // buffer (lower-bound accumulator) is never reset between blocks in BetweenQuery
        // when !trivialLowerBound. When block 0's flipAnd leaves buffer.full=true, block 1
        // with lowerStart=0 calls firstSlice which dispatches to denseOr. denseOr
        // short-circuits on buffer.full=true (the `if (!full)` guard), so the real block-1
        // data is never loaded and the stale full state persists. Then flipAnd(buffer2)
        // sees buffer.full=true → else branch → clears buffer → zero matches from block 1.
        //
        // Setup:
        //   Block 0: BLOCK_SIZE rows of value=5. All-FULL slices. blockMin=5.
        //            trivialLowerBound=true (lower=2 < 5) → buffer.clear → empty.
        //            buffer2 fills to full (anchoredUpper=8-5=3 hits FULL slices).
        //            flipAnd: empty AND full → buffer.full=true.
        //   Block 1: BLOCK_SIZE/2 rows of value=0, BLOCK_SIZE/2 of value=3. blockMin=0.
        //            trivialLowerBound=false (lower=2 >= 0). lowerStart=0 (anchoredLower=2,
        //            but bit-1 slice is DENSE not FULL). buffer.full=true (stale).
        //            firstSlice(DENSE, bit0=0) → denseOr → no-op → buffer stays full.
        //            flipAnd clears buffer → 0 matches instead of BLOCK_SIZE/2 for v=3.
        //
        // between(3, 9) = 3 ≤ v < 9. v=5 (block 0) and v=3 (block 1 second half) match.
        // Expected: BLOCK_SIZE + BLOCK_SIZE/2 matches.
        // Actual (buggy): BLOCK_SIZE + 0 matches.
        var appender = SliceZ.appender();
        for (int i = 0; i < BLOCK_SIZE; i++) appender.add(5);
        for (int i = 0; i < BLOCK_SIZE / 2; i++) appender.add(0);
        for (int i = 0; i < BLOCK_SIZE / 2; i++) appender.add(3);
        var idx = appender.build();
        int[] result = collect(idx.between(3, 9));
        assertEquals(BLOCK_SIZE + BLOCK_SIZE / 2, result.length);
    }

    @Test
    void betweenExcludesMaxUnsignedUpperBound() {
        // between(lower, upper) is exclusive-upper: lower ≤ v < upper.
        // When upper == -1L (max unsigned value), the code short-circuits to
        // greaterThanOrEqual(lower), which INCLUDES rows where v == -1L because
        // v >= lower is satisfied. But -1L is not < -1L, so those rows must be excluded.
        //
        // countBetween does not take this shortcut: for lower != 0 it builds
        // BetweenQuery(lower-1, upper-1) = BetweenQuery(lower-1, -2L), which correctly
        // excludes v == -1L. The iterator and count are therefore inconsistent.
        //
        // build(5, -1L): row 0 = v=5, row 1 = v=-1L (0xFFFF...FFFF, max unsigned).
        // between(5, -1L) = 5 ≤ v < -1L: only v=5 qualifies.
        // greaterThanOrEqual(5) returns both rows since -1L >= 5 unsigned.
        var idx = build(5L, -1L);
        assertArrayEquals(new int[]{0}, collect(idx.between(5L, -1L)));
        assertEquals(1, idx.countBetween(5L, -1L));
    }

    @Test
    void equalityFalsePositiveWhenFullSliceAtBitZeroHasSetThresholdBit() {
        // firstRelevantSlice returns max(0, skipFull-1). When the highest FULL slice with
        // a set threshold bit is at position 0, skipFull=1 and the function returns 0.
        // evaluateBlockForEquality treats emptySlice==0 as "no optimization, process block",
        // but this conflates two cases: (a) no FULL slice has a set threshold bit, and
        // (b) the highest such FULL slice is at bit 0. Case (b) means equality is impossible
        // for the entire block (sv bit 0 is always 1, but the threshold needs sv bit 0 = 0).
        // Since FULL slices are excluded from storedSlices, the necessary AND-NOT never runs.
        //
        // Values [0, 2]: sv(0)=~0=all-ones, sv(2)=~2=...FFFD.
        // Slice 0: both sv[0]=1 → FULL. Slice 1: sv(0)[1]=1, sv(2)[1]=0 → SPARSE {row 0}.
        // equal(1): anchoredThreshold=1=0b01. Bit 0=1, slice 0=FULL.
        // firstRelevantSlice(1)=max(0,1-1)=0 → block processed, not skipped.
        // storedSlices={bit 1}. buffer.fill. AND with SPARSE → buffer={row 0 (v=0)}.
        // Result: {row 0} — false positive; no value in the index equals 1.
        var idx = build(0, 2);
        assertArrayEquals(new int[0], collect(idx.equal(1)));
        assertEquals(0, idx.countEqual(1));
    }
}
