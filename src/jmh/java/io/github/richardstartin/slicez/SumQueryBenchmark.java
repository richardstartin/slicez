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

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsPrepend = {"-Xmx6g"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class SumQueryBenchmark {

	private static final int IN_CLAUSE_SIZE = 100;

	private static long[] percentileBounds(long[] values) {
		long[] sorted = values.clone();
		Arrays.sort(sorted);
		return new long[]{sorted[50 * (values.length / 100)], sorted[51 * (values.length / 100)]};
	}

	@State(Scope.Thread)
	@AuxCounters(AuxCounters.Type.EVENTS)
	public static class SliceZState {
		@Param({"100000000"})
		int size;
		@Param({"UNIFORM_1", "UNIFORM_2", "EXP_0_1", "SAMPLED_PCS"})
		DataGenerator data;

		SliceZ index;
		long value;
		long lower, upper;
		long[] inValues;

		@Setup(Level.Trial)
		public void setup() {
			long[] values = data.generate(size);
			value = values[values.length / 2];
			long[] bounds = percentileBounds(values);
			lower = bounds[0];
			upper = bounds[1];
			inValues = new long[IN_CLAUSE_SIZE];
			for (int i = 0; i < IN_CLAUSE_SIZE; i++) {
				inValues[i] = values[i * (size / IN_CLAUSE_SIZE)];
			}
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

	@State(Scope.Thread)
	public static class LongArrayState {
		@Param({"100000000"})
		int size;
		@Param({"UNIFORM_1", "UNIFORM_2", "EXP_0_1", "SAMPLED_PCS"})
		DataGenerator data;

		long[] values;
		long value;
		long lower, upper;
		// in-clause values mapped into signed order (xor with the sign bit) and sorted,
		// so unsigned membership can be tested with a signed binary search
		long[] sortedInXor;

		@Setup(Level.Trial)
		public void setup() {
			values = data.generate(size);
			value = values[values.length / 2];
			long[] bounds = percentileBounds(values);
			lower = bounds[0];
			upper = bounds[1];
			sortedInXor = new long[IN_CLAUSE_SIZE];
			for (int i = 0; i < IN_CLAUSE_SIZE; i++) {
				sortedInXor[i] = values[i * (size / IN_CLAUSE_SIZE)] ^ Long.MIN_VALUE;
			}
			Arrays.sort(sortedInXor);
		}
	}

	@Benchmark
	public double baselineSumLessThan(LongArrayState s) {
		double sum = 0D;
		for (long v : s.values) {
			if (Long.compareUnsigned(v, s.value) < 0)
				sum += Util.unsignedToDouble(v);
		}
		return sum;
	}

	@Benchmark
	public double baselineSumGreaterThan(LongArrayState s) {
		double sum = 0D;
		for (long v : s.values) {
			if (Long.compareUnsigned(v, s.value) > 0)
				sum += Util.unsignedToDouble(v);
		}
		return sum;
	}

	@Benchmark
	public double baselineSumBetween(LongArrayState s) {
		double sum = 0D;
		for (long v : s.values) {
			if (Long.compareUnsigned(v, s.lower) >= 0 && Long.compareUnsigned(v, s.upper) < 0)
				sum += Util.unsignedToDouble(v);
		}
		return sum;
	}

	@Benchmark
	public double baselineSumIn(LongArrayState s) {
		double sum = 0D;
		for (long v : s.values) {
			if (Arrays.binarySearch(s.sortedInXor, v ^ Long.MIN_VALUE) >= 0)
				sum += Util.unsignedToDouble(v);
		}
		return sum;
	}

	@Benchmark
	public double baselineSumEqual(LongArrayState s) {
		double sum = 0D;
		for (long v : s.values) {
			if (v == s.value)
				sum += Util.unsignedToDouble(v);
		}
		return sum;
	}

	@Benchmark
	public double baselineSumNotEqual(LongArrayState s) {
		double sum = 0D;
		for (long v : s.values) {
			if (v != s.value)
				sum += Util.unsignedToDouble(v);
		}
		return sum;
	}

	@Benchmark
	public double sumLessThan(SliceZState s) {
		return s.index.sumLessThan(s.value);
	}

	@Benchmark
	public double sumGreaterThan(SliceZState s) {
		return s.index.sumGreaterThan(s.value);
	}

	@Benchmark
	public double sumBetween(SliceZState s) {
		return s.index.sumBetween(s.lower, s.upper);
	}

	@Benchmark
	public double sumIn(SliceZState s) {
		return s.index.sumIn(s.inValues);
	}

	@Benchmark
	public double sumEqual(SliceZState s) {
		return s.index.sumEqual(s.value);
	}

	@Benchmark
	public double sumNotEqual(SliceZState s) {
		return s.index.sumNotEqual(s.value);
	}
}
