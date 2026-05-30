package io.github.richardstartin.slicez;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.roaringbitmap.RangeBitmap;
import org.roaringbitmap.RoaringBitmap;

import java.util.Arrays;
import java.util.PrimitiveIterator;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsPrepend = {"-Xmx6g"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class EqualityQueryBenchmark {

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
		long value;
		long serializedSize;

		@Setup(Level.Trial)
		public void setup() {
			long[] values = generateValues();
			value = values[values.length / 2];
			var appender = RangeBitmap.appender(-1L);
			Arrays.stream(values).forEach(appender::add);
			index = appender.build();
			serializedSize = appender.serializedSizeInBytes();
		}

		public double getCompressionRatio() {
			return serializedSize / (size * 8d);
		}
	}

	@State(Scope.Thread)
	@AuxCounters(AuxCounters.Type.EVENTS)
	public static class SliceZState extends BaseState {
		SliceZ index;
		long value;

		@Setup(Level.Trial)
		public void setup() {
			long[] values = generateValues();
			value = values[values.length / 2];
			SliceZ.Appender appender = SliceZ.appender();
			for (long value : values)
				appender.add(value);
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
	public void rangeBitmapEqual(RangeBitmapState s, Blackhole bh) {
		var it = s.index.eq(s.value).getIntIterator();
		while (it.hasNext())
			bh.consume(it.next());
	}

	@Benchmark
	public void SliceZEqual(SliceZState s, Blackhole bh) {
		PrimitiveIterator.OfInt it = s.index.equal(s.value);
		while (it.hasNext())
			bh.consume(it.nextInt());
	}
}
