package io.github.richardstartin.slicez;

import java.util.Arrays;
import java.util.SplittableRandom;

public enum DataGenerator {

	UNIFORM_1 {
		@Override
		public long[] generate(int count) {
			var random = new SplittableRandom(0);
			long[] values = new long[count];
			for (int i = 0; i < values.length; i++) {
				values[i] = random.nextLong();
			}
			return values;
		}
	},
	UNIFORM_2 {
		@Override
		public long[] generate(int count) {
			var random = new SplittableRandom(0);
			long[] values = new long[count];
			for (int i = 0; i < values.length; i++) {
				values[i] = random.nextLong(0, 100_000) * 10_000;
			}
			return values;
		}
	},
	EXP_0_1 {
		@Override
		public long[] generate(int count) {
			var random = new SplittableRandom(0);
			long[] values = new long[count];
			for (int i = 0; i < values.length; i++) {
				values[i] = (long) Math.ceil(-Math.log(random.nextDouble()) / 0.1);
			}
			return values;
		}
	},
	DOUBLES {
		@Override
		public long[] generate(int count) {
			var random = new SplittableRandom(0);
			long[] values = new long[count];
			for (int i = 0; i < values.length; i++) {
				values[i] = FPOrdering.ordinalOf(random.nextDouble());
			}
			return values;
		}
	},
	/**
	 * Simulates PC samples from a profiler (e.g. perf/SIGPROF).
	 * <p>
	 * 256 synthetic "functions" are laid out at a fixed user-space base address
	 * (0x00007f...), so the top 17 bits of every value are zero. In SliceZ's ~value
	 * encoding those bits are always 1 → FULL slices with no payload. Function
	 * sizes and sample weights both follow a Zipfian distribution so a handful of
	 * hot functions receive most of the samples.
	 */
	SAMPLED_PCS {
		@Override
		public long[] generate(int count) {
			var random = new SplittableRandom(0);
			// Typical JIT code region base on Linux x86-64: upper 17 bits = 0
			long base = 0x00007f1234560000L;
			int numFunctions = 256;
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
			for (int k = 0; k < numFunctions; k++)
				cumWeight[k] /= totalWeight;

			long[] values = new long[count];
			for (int i = 0; i < count; i++) {
				// Inverse-CDF sample: pick function proportional to 1/(k+1)
				double r = random.nextDouble();
				int k = Arrays.binarySearch(cumWeight, r);
				if (k < 0)
					k = -k - 1;
				if (k >= numFunctions)
					k = numFunctions - 1;
				values[i] = funcBase[k] + random.nextLong(funcSize[k]);
			}
			return values;
		}
	};

	abstract long[] generate(int count);
}
