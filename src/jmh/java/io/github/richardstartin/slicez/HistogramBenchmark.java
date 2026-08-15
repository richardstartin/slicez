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
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsPrepend = {"-Xmx6g"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class HistogramBenchmark {

	private static final int DECILES = 10;

	/**
	 * Returns the supremum (unsigned maximum) of each of the ten deciles of
	 * {@code values}, in ascending unsigned order, to be used as exclusive bucket
	 * upper bounds.
	 */
	private static long[] decileSuprema(long[] values) {
		long[] sorted = unsignedSorted(values);
		long[] bounds = new long[DECILES];
		for (int k = 0; k < DECILES; k++) {
			bounds[k] = sorted[(k + 1) * (sorted.length / DECILES) - 1];
		}
		return bounds;
	}

	/** Sorts a copy of {@code values} into ascending unsigned order. */
	private static long[] unsignedSorted(long[] values) {
		long[] sorted = values.clone();
		// flip the sign bit so a signed sort yields unsigned order, then flip back
		for (int i = 0; i < sorted.length; i++) {
			sorted[i] ^= Long.MIN_VALUE;
		}
		Arrays.sort(sorted);
		for (int i = 0; i < sorted.length; i++) {
			sorted[i] ^= Long.MIN_VALUE;
		}
		return sorted;
	}

	/**
	 * Returns the index of the bucket {@code value} falls into: the first index
	 * {@code i} with {@code value < bounds[i]} (unsigned), or {@code bounds.length}
	 * if {@code value} is at or above every bound.
	 */
	private static int bucket(long[] bounds, long value) {
		int lo = 0;
		int hi = bounds.length;
		while (lo < hi) {
			int mid = (lo + hi) >>> 1;
			if (Long.compareUnsigned(bounds[mid], value) <= 0) {
				lo = mid + 1;
			} else {
				hi = mid;
			}
		}
		return lo;
	}

	@State(Scope.Thread)
	@AuxCounters(AuxCounters.Type.EVENTS)
	public static class SliceZState {
		@Param({"100000000"})
		int size;
		@Param({"UNIFORM_1", "UNIFORM_2", "EXP_0_1", "DOUBLES", "SAMPLED_PCS"})
		DataGenerator data;

		SliceZ index;
		long min;
		long[] bounds;
		// min and decile bounds rounded down to a power-of-two multiple: after
		// anchoring, the thresholds agree on their low bits, activating the shared
		// low-prefix fold. Clamped to stay >= quantizedMin and ascending.
		long quantizedMin;
		long[] quantizedBounds;

		@Setup(Level.Trial)
		public void setup() {
			long[] values = data.generate(size);
			long[] sorted = unsignedSorted(values);
			min = sorted[0];
			bounds = new long[DECILES];
			quantizedBounds = new long[DECILES];
			long quantum = 1L << 12;
			quantizedMin = min - Long.remainderUnsigned(min, quantum);
			for (int k = 0; k < DECILES; k++) {
				bounds[k] = sorted[(k + 1) * (sorted.length / DECILES) - 1];
				long q = bounds[k] - Long.remainderUnsigned(bounds[k], quantum);
				quantizedBounds[k] = Long.compareUnsigned(q, quantizedMin) < 0 ? quantizedMin : q;
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
		@Param({"UNIFORM_1", "UNIFORM_2", "EXP_0_1", "DOUBLES", "SAMPLED_PCS"})
		DataGenerator data;

		long[] values;
		long min;
		long[] bounds;

		@Setup(Level.Trial)
		public void setup() {
			values = data.generate(size);
			long[] sorted = unsignedSorted(values);
			min = sorted[0];
			bounds = decileSuprema(values);
		}
	}

	@Benchmark
	public long[] histogram(SliceZState s) {
		return s.index.histogram(s.min, s.bounds);
	}

	@Benchmark
	public long[] histogramQuantized(SliceZState s) {
		return s.index.histogram(s.quantizedMin, s.quantizedBounds);
	}

	@Benchmark
	public long[] baselineScan(LongArrayState s) {
		long[] counts = new long[s.bounds.length];
		for (long v : s.values) {
			if (Long.compareUnsigned(v, s.min) < 0) {
				continue;
			}
			int b = bucket(s.bounds, v);
			if (b < counts.length) {
				counts[b]++;
			}
		}
		return counts;
	}
}
