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
import org.roaringbitmap.buffer.ImmutableRoaringBitmap;
import org.roaringbitmap.buffer.MutableRoaringBitmap;

import java.nio.ByteBuffer;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * Compares the boolean operations (intersection and union) of two
 * {@link CompressedBitmap}s against the same operations on two
 * {@link ImmutableRoaringBitmap}s. The two operands are drawn from the same
 * exponential distribution (the {@code mean} parameter, varied across a range)
 * but with different seeds, so they are distinct bitmaps with a non-trivial
 * overlap over {@code [0, 100M)}.
 *
 * <p>
 * The {@link CompressedBitmap} side consumes its block iterator, materialising
 * each result block into a reused bitset; the Roaring side computes the
 * operation with
 * {@link ImmutableRoaringBitmap#and}/{@link ImmutableRoaringBitmap#or} and
 * consumes the resulting bitmap rather than iterating individual bits.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsPrepend = {"-Xmx6g"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class BooleanOperationsBenchmark {

	private static final int RANGE = 100_000_000;
	private static final int SAMPLES = 10_000_000;

	@State(Scope.Benchmark)
	public static class BaseState {
		@Param({"100000", "1000000", "10000000", "50000000"})
		double mean;

		/**
		 * Draws {@link #SAMPLES} exponentially distributed positions in
		 * {@code [0, RANGE)} using {@code seed} and returns the distinct positions in
		 * ascending order.
		 */
		int[] generateRowIds(long seed) {
			long[] present = new long[(RANGE >>> 6) + 1];
			var random = new SplittableRandom(seed);
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
		CompressedBitmap x;
		CompressedBitmap y;

		@Setup(Level.Trial)
		public void setup() {
			x = build(generateRowIds(0));
			y = build(generateRowIds(1));
		}

		private static CompressedBitmap build(int[] rids) {
			var appender = CompressedBitmap.appender();
			for (int rid : rids) {
				appender.add(rid);
			}
			return appender.build();
		}
	}

	@State(Scope.Benchmark)
	public static class RoaringState extends BaseState {
		ImmutableRoaringBitmap x;
		ImmutableRoaringBitmap y;

		@Setup(Level.Trial)
		public void setup() {
			x = build(generateRowIds(0));
			y = build(generateRowIds(1));
		}

		private static ImmutableRoaringBitmap build(int[] rids) {
			var mutable = new MutableRoaringBitmap();
			for (int rid : rids) {
				mutable.add(rid);
			}
			mutable.runOptimize();
			ByteBuffer buffer = ByteBuffer.allocate(mutable.serializedSizeInBytes());
			mutable.serialize(buffer);
			buffer.flip();
			return new ImmutableRoaringBitmap(buffer);
		}
	}

	@Benchmark
	public void compressedBitmapAnd(CompressedBitmapState state, Blackhole bh) {
		var it = state.x.and(state.y);
		var bits = it.newBits();
		while (it.hasNext()) {
			bh.consume(it.nextBlock(bits));
			bh.consume(bits);
		}
	}

	@Benchmark
	public void compressedBitmapOr(CompressedBitmapState state, Blackhole bh) {
		var it = state.x.or(state.y);
		var bits = it.newBits();
		while (it.hasNext()) {
			bh.consume(it.nextBlock(bits));
			bh.consume(bits);
		}
	}

	@Benchmark
	public void immutableRoaringBitmapAnd(RoaringState state, Blackhole bh) {
		bh.consume(ImmutableRoaringBitmap.and(state.x, state.y));
	}

	@Benchmark
	public void immutableRoaringBitmapOr(RoaringState state, Blackhole bh) {
		bh.consume(ImmutableRoaringBitmap.or(state.x, state.y));
	}
}
