package io.github.richardstartin.slicez;

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
import org.roaringbitmap.IntIterator;
import org.roaringbitmap.buffer.ImmutableRoaringBitmap;
import org.roaringbitmap.buffer.MutableRoaringBitmap;

import java.nio.ByteBuffer;
import java.util.PrimitiveIterator;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * Compares full iteration over a {@link CompressedBitmap} against an
 * {@link ImmutableRoaringBitmap} for row id sets drawn from a range of
 * exponential distributions over {@code [0, 100M)}. A smaller {@code mean}
 * concentrates the row ids near the origin (dense low blocks, long absent
 * tails); a larger {@code mean} spreads them across the whole range.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsPrepend = {"-Xmx6g"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class IteratorBenchmark {

	private static final int RANGE = 100_000_000;
	private static final int SAMPLES = 10_000_000;
	private static final long SEED = 0;

	@State(Scope.Benchmark)
	public static class BaseState {
		@Param({"100000", "1000000", "10000000", "50000000"})
		double mean;

		/**
		 * Draws {@link #SAMPLES} exponentially distributed positions in
		 * {@code [0, RANGE)} and returns the distinct positions in ascending order,
		 * ready to be appended as row ids.
		 */
		int[] generateRowIds() {
			long[] present = new long[(RANGE >>> 6) + 1];
			var random = new SplittableRandom(SEED);
			for (int i = 0; i < SAMPLES; i++) {
				long x = (long) (-mean * Math.log(1.0 - random.nextDouble()));
				if (x >= RANGE) {
					x = RANGE - 1;
				}
				present[(int) (x >>> 6)] |= 1L << x;
			}
			int cardinality = 0;
			for (long word : present) {
				cardinality += Long.bitCount(word);
			}
			int[] rids = new int[cardinality];
			int n = 0;
			for (int i = 0; i < present.length; i++) {
				long word = present[i];
				while (word != 0) {
					int bit = Long.numberOfTrailingZeros(word);
					word &= (word - 1);
					rids[n++] = (i << 6) + bit;
				}
			}
			return rids;
		}
	}

	@State(Scope.Benchmark)
	public static class CompressedBitmapState extends BaseState {
		CompressedBitmap bitmap;

		@Setup(Level.Trial)
		public void setup() {
			var appender = CompressedBitmap.appender();
			for (int rid : generateRowIds()) {
				appender.add(rid);
			}
			bitmap = appender.build();
		}
	}

	@State(Scope.Benchmark)
	public static class RoaringState extends BaseState {
		ImmutableRoaringBitmap bitmap;

		@Setup(Level.Trial)
		public void setup() {
			var mutable = new MutableRoaringBitmap();
			for (int rid : generateRowIds()) {
				mutable.add(rid);
			}
			mutable.runOptimize();
			// materialise the genuinely immutable, buffer-backed form
			ByteBuffer buffer = ByteBuffer.allocate(mutable.serializedSizeInBytes());
			mutable.serialize(buffer);
			buffer.flip();
			bitmap = new ImmutableRoaringBitmap(buffer);
		}
	}

	@Benchmark
	public void compressedBitmap(CompressedBitmapState state, Blackhole bh) {
		PrimitiveIterator.OfInt it = state.bitmap.iterator();
		while (it.hasNext()) {
			bh.consume(it.nextInt());
		}
	}

	@Benchmark
	public void immutableRoaringBitmap(RoaringState state, Blackhole bh) {
		IntIterator it = state.bitmap.getIntIterator();
		while (it.hasNext()) {
			bh.consume(it.next());
		}
	}
}
