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

import java.util.PrimitiveIterator;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsPrepend = {"-Xmx6g"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class BottomKBenchmark {

	@State(Scope.Thread)
	@AuxCounters(AuxCounters.Type.EVENTS)
	public static class SliceZState {
		@Param({"100000000"})
		int size;
		@Param({"UNIFORM_1", "UNIFORM_2", "EXP_0_1", "DOUBLES", "SAMPLED_PCS"})
		DataGenerator data;
		@Param({"10", "100", "1000"})
		int k;

		SliceZ index;

		@Setup(Level.Trial)
		public void setup() {
			long[] values = data.generate(size);
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
	public void sliceZBottomK(SliceZState s, Blackhole bh) {
		PrimitiveIterator.OfInt it = s.index.bottom(s.k);
		while (it.hasNext())
			bh.consume(it.nextInt());
	}
}
