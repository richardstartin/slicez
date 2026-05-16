package io.github.richardstartin.slicez;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.roaringbitmap.RoaringBitmap;

import java.util.HashMap;
import java.util.PrimitiveIterator;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsPrepend = {"-Xmx6g"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class InQueryBenchmark {

    @State(Scope.Benchmark)
    public static class BaseState {
        @Param({"100000000"})
        int size;
        @Param({"UNIFORM_2", "EXP_0_1", "SAMPLED_PCS"})
        DataGenerator data;
        @Param({"4", "100"})
        int inClauseSize;

        public long[] generateValues() {
            return data.generate(size);
        }

        public long[] sampleQueryValues(long[] values) {
            long[] queryValues = new long[inClauseSize];
            for (int i = 0; i < inClauseSize; i++) {
                queryValues[i] = values[i * (size / inClauseSize)];
            }
            return queryValues;
        }
    }

    @State(Scope.Thread)
    @AuxCounters(AuxCounters.Type.EVENTS)
    public static class InvertedIndexState extends BaseState {
        HashMap<Long, RoaringBitmap> index;
        long[] queryValues;

        @Setup(Level.Trial)
        public void setup() {
            long[] values = generateValues();
            queryValues = sampleQueryValues(values);
            index = new HashMap<>();
            for (int i = 0; i < values.length; i++) {
                index.computeIfAbsent(values[i], k -> new RoaringBitmap()).add(i);
            }
        }
    }

    @State(Scope.Thread)
    @AuxCounters(AuxCounters.Type.EVENTS)
    public static class SliceZState extends BaseState {
        SliceZ index;
        long[] queryValues;

        @Setup(Level.Trial)
        public void setup() {
            long[] values = generateValues();
            queryValues = sampleQueryValues(values);
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
    public void invertedIndexIn(InvertedIndexState s, Blackhole bh) {
        RoaringBitmap result = new RoaringBitmap();
        for (long value : s.queryValues) {
            RoaringBitmap bitmap = s.index.get(value);
            if (bitmap != null) {
                result.or(bitmap);
            }
        }
        var it = result.getIntIterator();
        while (it.hasNext()) bh.consume(it.next());
    }

    @Benchmark
    public void SliceZIn(SliceZState s, Blackhole bh) {
        PrimitiveIterator.OfInt it = s.index.in(s.queryValues);
        while (it.hasNext()) bh.consume(it.nextInt());
    }
}
