package io.github.richardstartin.slicez;

import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.roaringbitmap.RangeBitmap;

import java.util.Arrays;
import java.util.PrimitiveIterator;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsPrepend = {"-Xmx6g"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class RangeQueryBenchmark {

    private static long[] percentileBounds(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return new long[]{sorted[50 * (values.length / 100)], sorted[51 * (values.length / 100)]};
    }

    @State(Scope.Benchmark)
    public static class BaseState {
        @Param({"100000000"})
        int size;
        @Param({"UNIFORM_1", "UNIFORM_2", "EXP_0_1", "DOUBLES", "SAMPLED_PCS"})
        DataGenerator data;

        public long[] generateValues() {
            return data.generate(size);
        }
    }

    @State(Scope.Thread)
    @AuxCounters(AuxCounters.Type.EVENTS)
    public static class RangeBitmapState extends BaseState {
        RangeBitmap index;
        long lower, upper;
        private long serializedSize;

        @Setup(Level.Trial)
        public void setup() {
            long[] values = generateValues();
            long[] bounds = percentileBounds(values);
            lower = bounds[0];
            upper = bounds[1];
            var rba = RangeBitmap.appender(-1L);
            Arrays.stream(values).forEach(rba::add);
            index = rba.build();
            serializedSize = rba.serializedSizeInBytes();
        }

        public double getCompressionRatio() {
            return serializedSize / (size * 8d);
        }
    }

    @State(Scope.Thread)
    @AuxCounters(AuxCounters.Type.EVENTS)
    public static class SliceZState extends BaseState {
        SliceZ index;
        long lower, upper;

        @Setup(Level.Trial)
        public void setup() {
            long[] values = generateValues();
            long[] bounds = percentileBounds(values);
            lower = bounds[0];
            upper = bounds[1];
            SliceZ.Appender appender = SliceZ.appender();
            for (long value : values) appender.add(value);
            index = appender.build();
        }

        public int getSparseInvertedCount() {
            return index.getSparseInvertedSliceCount();
        }

        public int getSparseCount() {
            return index.getSparseSliceCount();
        }

        public int getDenseCount() {
            return index.getDenseSliceCount();
        }

        public int getFullCount() {
            return index.getFullSliceCount();
        }

        public double getCompressionRatio() {
            return index.getCompressionRatio();
        }
    }

    @Benchmark
    public void rangeBitmapBetween(RangeBitmapState s, Blackhole bh) {
        var it = s.index.between(s.lower, s.upper).getIntIterator();
        while (it.hasNext()) bh.consume(it.next());
    }

    @Benchmark
    public void SliceZBetween(SliceZState s, Blackhole bh) {
        PrimitiveIterator.OfInt it = s.index.between(s.lower, s.upper);
        while (it.hasNext()) bh.consume(it.nextInt());
    }
}
