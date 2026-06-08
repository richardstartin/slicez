# slicez

Z-layout bit-sliced index for evaluating point and range queries over unsorted numerical data.

## Usage

### Installation

`slicez` is published to Maven Central as `io.github.richardstartin:slicez`.

[![Maven Central](https://img.shields.io/maven-central/v/io.github.richardstartin/slicez)](https://central.sonatype.com/artifact/io.github.richardstartin/slicez)

Gradle (`build.gradle`):

```groovy
dependencies {
    implementation 'io.github.richardstartin:slicez:0.2.0'
}
```

Maven (`pom.xml`):

```xml
<dependency>
    <groupId>io.github.richardstartin</groupId>
    <artifactId>slicez</artifactId>
    <version>0.2.0</version>
</dependency>
```

### Building an index

For a known array of values:

```java
SliceZ index = SliceZ.build(1L, 42L, 1000L, 65536L, Long.MAX_VALUE);
```

For streaming ingestion (e.g. reading from a file or result set), use the `Appender`:

```java
SliceZ.Appender appender = SliceZ.appender();
for (long value : values) {
    appender.add(value);
}
// appender also implements LongConsumer, so:
// LongStream.of(values).forEach(appender);
SliceZ index = appender.build();
```

### Querying

Each predicate is available in four forms: an iterator over matching row indices (`…`), a count (`count…`), a sum of the matching values (`sum…`), and an arithmetic mean (`mean…`).

```java
// equality
PrimitiveIterator.OfInt it = index.equal(42L);
int    count = index.countEqual(42L);
double sum   = index.sumEqual(42L);
double mean  = index.meanEqual(42L);    // == 42.0 when any row matches, else 0.0

// range — lessThan / lessThanOrEqual / greaterThan / greaterThanOrEqual,
// each with a matching count…, sum…, and mean… method
it    = index.lessThan(1000L);
count = index.countLessThan(1000L);
sum   = index.sumLessThan(1000L);
mean  = index.meanLessThan(1000L);      // == sum / count when count > 0, else 0.0

it    = index.greaterThanOrEqual(42L);
count = index.countGreaterThanOrEqual(42L);
sum   = index.sumGreaterThanOrEqual(42L);
mean  = index.meanGreaterThanOrEqual(42L);

// between(lower, upper) is half-open: lower <= value < upper
// (inclusive lower bound, exclusive upper bound)
it    = index.between(42L, 1000L);
count = index.countBetween(42L, 1000L);
sum   = index.sumBetween(42L, 1000L);
mean  = index.meanBetween(42L, 1000L);

// inequality and multi-value match
it    = index.notEqual(42L);
count = index.countNotEqual(42L);
sum   = index.sumNotEqual(42L);
mean  = index.meanNotEqual(42L);

it    = index.in(1L, 42L, 1000L);
count = index.countIn(1L, 42L, 1000L);
sum   = index.sumIn(1L, 42L, 1000L);
mean  = index.meanIn(1L, 42L, 1000L);

// top-k / bottom-k by unsigned order; partial when fewer than k rows exist
PrimitiveIterator.OfInt  topIds   = index.top(10);          // row ids
PrimitiveIterator.OfInt  botIds   = index.bottom(10);
PrimitiveIterator.OfLong topVals  = index.topValues(10);    // the values themselves
PrimitiveIterator.OfLong botVals  = index.bottomValues(10);
long   topSum  = index.topSum(10);                          // sum of the top-10 values
long   botSum  = index.bottomSum(10);
double botMean = index.bottomMean(10);                      // mean of the bottom-10 values
// decode each selected value before summing (e.g. when longs encode doubles)
double topMagnitude = index.topSum(10, ord -> decode(ord));

// global extrema
long min = index.min();
long max = index.max();
```

All comparisons use **unsigned** 64-bit order, so values are treated as unsigned longs regardless of sign.

The `sum…` methods return a `double` and accumulate in floating point, so they do not overflow on large inputs or wide blocks. Each matching value contributes through the signed `(double)` conversion of its components, so totals are exact for the full unsigned range below `2^63`. The `topSum`/`bottomSum` methods that return `long` use wrapping two's-complement arithmetic, consistent with summing the `topValues`/`bottomValues` directly; the `LongToDoubleFunction` overloads decode each selected value first and accumulate in `double`.

### Floating-point values

`double` values can be indexed by mapping them to a total unsigned order:

```java
// Map doubles to longs that sort in the same order (NaN/±Inf handled)
long ordinal(double v) {
    if (v == Double.NEGATIVE_INFINITY) return 0L;
    if (v == Double.POSITIVE_INFINITY || Double.isNaN(v)) return 0xFFFFFFFFFFFFFFFFL;
    long bits = Double.doubleToLongBits(v);
    return (bits & Long.MIN_VALUE) == Long.MIN_VALUE
            ? (bits == Long.MIN_VALUE ? Long.MIN_VALUE : ~bits)
            : bits ^ Long.MIN_VALUE;
}

SliceZ.Appender appender = SliceZ.appender();
for (double v : doubles) appender.add(ordinal(v));
SliceZ index = appender.build();

// Query by mapping the threshold the same way
double threshold = 3.14;
PrimitiveIterator.OfInt it = index.lessThanOrEqual(ordinal(threshold));
```

---

## Implementation

### Overview

SliceZ answers equality and range queries (`=`, `≠`, `<`, `≤`, `>`, `≥`, `between`, `in`) over sequences of `long` values without sorting the data. Every predicate can return matching row ids, a count, a `double` sum, or a `double` arithmetic mean of the matching values, and the index also supports `top`/`bottom`-*k* selection (as row ids, values, a summed total, or a mean). It is a bit-sliced index in the tradition of O'Neil and Quass (1997). IEEE 754 doubles can be indexed by first mapping them to a total unsigned order with `FPOrdering.ordinalOf`.

Block-level introspection is exposed through `getBlockCount`, `getFullSliceCount`, `getDenseSliceCount`, `getSparseSliceCount`, `getSparseInvertedSliceCount`, and `getCompressionRatio` — the slice-type breakdown tables below are produced from these counters.

### Data layout

Data is partitioned into **blocks** of 65,536 rows. Within each block, each 64-bit value is decomposed across 64 **slices** — one per bit position. Slice `i` is the set of rows for which bit `i` is set in the stored value.

Before bit-slicing, every value in the block is transformed:

```
stored = ~(value − blockMin)
```

where `blockMin` is the unsigned minimum of the block. The subtraction shifts the range down to start at zero; the bitwise NOT then inverts the ordering so that `blockMin` maps to `0xFFFF…FFFF` and values larger than `blockMin` map to smaller stored values. 
The practical effect is that all bit positions above the effective value range become all-ones in the stored representation, enabling them to be stored as **FULL slices** with zero payload.

Each slice is assigned one of four storage types based on its cardinality within the block:

| Type | Condition                                     | Payload                                                |
|---|-----------------------------------------------|--------------------------------------------------------|
| `FULL` | Every row has the bit set                     | None (2-bit header tag only)                           |
| `SPARSE` | Fewer than 4096 rows have the bit set         | `count` (2 B) + sorted row positions (2 B each)        |
| `SPARSE_INVERTED` | Fewer than 4096 rows have the bit **clear** | `count` (2 B) + sorted complement positions (2 B each) |
| `DENSE` | Otherwise                                     | 8-KiB flat bitset (1024 × 64-bit words)                |

The block header is 32 bytes: two 64-bit words (`typesHigh`, `typesLow`) encoding the 2-bit type for each of the 64 slices, plus the 64-bit `blockMin` and `blockMax`. The min/max pair lets every bound query fast-exit a block whose value range doesn't overlap the threshold — without touching any slice payload.

### Query evaluation

Each query maintains a `Bits` accumulator — a 8192-byte bitset with one bit per row in the block — and iterates its set bits to produce row indices.

**`≤ threshold` (and by inversion `> threshold`):** Anchors the threshold: `anchoredThreshold = threshold − blockMin`. Then traverses slices from bit 0 (LSB) to bit 63 (MSB):
- Threshold bit = 1 → union the slice into the accumulator (`buffer |= slice`)
- Threshold bit = 0 → intersect the slice into the accumulator (`buffer &= slice`)

Due to the `~(v − blockMin)` encoding this correctly evaluates `value ≤ threshold` in the original unsigned space.

**`firstRelevantSlice` optimization:** If a threshold bit is 1 and the slice for that bit is FULL, the union fills the accumulator unconditionally, destroying all prior work. The highest such bit is `firstRelevantSlice`; the query initialises the accumulator at that point and skips all lower slices, saving both iteration and slice decoding.

**`= value`:** Starts with a full accumulator and narrows it slice by slice over all non-FULL slices:
- Value bit = 1 → `buffer &= ~slice` (clear rows where the bit is absent)
- Value bit = 0 → `buffer &= slice` (clear rows where the bit is present)

If a slice would make the result empty, the rest of the block is skipped entirely.

**`between lower, upper`** (half-open `[lower, upper)`)**:** Decomposes into `(value ≤ upper − 1) AND NOT(value ≤ lower − 1)`, i.e. `lower ≤ value < upper`. Two accumulators are maintained in parallel (sharing slice reads where possible) and combined at the end as `~lower_buffer & upper_buffer` via `flipAnd`. The `count`/`sum` forms guard empty ranges (`upper ≤ lower`, including `upper == 0`, which would otherwise underflow to the unsigned maximum) and return zero before any block is touched.

**`top k` / `bottom k`:** Two passes. The first walks block headers only (no payload reads) and keeps a *k*-block heap keyed on `blockMax` (for `top`) or `blockMin` (for `bottom`) — every candidate block is guaranteed to contain at least one row that could rank in the global top/bottom *k*. The second pass processes the candidates in best-first order with a *k*-row heap: each block evaluates the current threshold via `evaluateSingleBoundQueryBlock`, extracts matching rows, and tightens the threshold to the heap's *k*-th-best value so subsequent blocks can short-circuit on `blockMax`/`blockMin` alone. The threshold is treated as an inclusive lower (resp. upper) bound and translated to the strict `>` operator via `threshold − 1`, with a fill fallback when the threshold lands on the unsigned minimum.

The supporting `Heap` is a custom bounded min-heap with parallel `long[] keys` and `T[] values`. Comparisons run on the `long` keys via a `LongComparator` functional interface, and the `values[]` slot tracks its key through every sift, so callers reuse pre-allocated `Block` / `Row` instances rather than allocating per insertion.

`topValues` / `bottomValues` reuse the same two-pass selection but the k-row heap is keyed on the selected *values*, so draining the heap yields the values directly rather than their row ids. `topSum` / `bottomSum` go one step further and sum the heap's keys without materialising them: the `long`-returning forms use wrapping two's-complement addition (so they agree with summing `topValues`/`bottomValues` element by element), while the `LongToDoubleFunction` overloads decode each key before accumulating in `double`. `bottomMean(k)` divides the same sum by the number of values actually found (at most `k`), returning `0.0` when the index is empty.

### Aggregation: `count`, `sum`, and `mean`

`count…` evaluates the predicate into the `Bits` accumulator and population-counts the matching rows in each block, summed across blocks.

`sum…` reuses the same predicate evaluation but, rather than materialising row ids, reconstructs the matching total straight from the slices. For each block it first fills the accumulator with the matching rows, then walks the stored (non-FULL) slices: for the slice at bit position `b` it counts how many *matching* rows have that bit set in the original value — `cardinality` — using the type-specific `…AndCardinality` / `…AndNotCardinality` operations, and adds `cardinality × 2^b`. FULL slices carry a stored bit that is set for every row, which under the `~(v − blockMin)` transform corresponds to a *zero* bit in `v − blockMin`; they therefore contribute nothing and are skipped. Finally `blockMin × matchingCount` restores the per-block offset that was subtracted at build time.

`mean…` is `sum… / count…` for non-empty match sets and returns `0.0` when no rows match. It shares the same single-pass slice walk as `sum`, accumulating both the running total and the row count simultaneously. `meanEqual(v)` short-circuits to `(double) v` (or `0.0` when absent) without touching slices, and `bottomMean(k)` averages the `k`-smallest values selected by the two-pass heap algorithm.

Three details matter for correctness:

- **Accumulation is in `double`.** Both the per-slice term (`cardinality × 2^b`) and the block-offset term (`blockMin × matchingCount`) are widened to `double` *before* multiplying, so they cannot overflow `long` even when `b` is large or a block has up to 65,536 matching rows.
- **`sumEqual` / `meanEqual` short-circuit** to `countEqual(value) × value` (resp. `value`) instead of walking slices, since every matching row contributes exactly `value`.
- **`mean…` returns `0.0` on empty match sets** rather than `NaN`, so callers need not guard against division by zero.

### Slice operations

Every accumulator operation is dispatched on slice type. Sparse operations iterate the stored position list directly. Dense operations work in 8-word strides and propagate `empty`/`full` sentinel flags so that subsequent operations can short-circuit without touching slice data. For example, once the accumulator is known to be empty, AND operations become no-ops; once it is full, union operations against FULL slices are no-ops.

---

## Benchmarks

Benchmarks compare SliceZ against [`RangeBitmap`](https://github.com/RoaringBitmap/RoaringBitmap) indexing **100 million** `long` values. The equality benchmark queries the median value; the range benchmark queries between the 50th and 51st percentile (a narrow 1-percentile window). Top/bottom-k is compared against a brute-force heap scan of all values. All timings are average time in microseconds (lower is better).

### Distributions

| ID | Description |
|---|---|
| `UNIFORM_1` | Uniform random 64-bit longs |
| `UNIFORM_2` | Uniform random integers in [0, 100,000) scaled by 10,000 |
| `EXP_0_1` | Exponential distribution λ = 0.1 (mean ≈ 10, ~8 bits of range) |
| `DOUBLES` | Uniform random doubles in [0, 1) mapped to total unsigned order |
| `SAMPLED_PCS` | Synthetic profiler samples: 256 Zipf-distributed functions at a fixed userspace base address (top 17 bits fixed) |

### Equality query

| Distribution | SliceZ (µs) | RangeBitmap (µs) | SliceZ speedup |
|---|---|---|---|
| UNIFORM_1 | 6,621 | 16,738 | 2.5× |
| UNIFORM_2 | 7,359 | 8,856 | 1.2× |
| EXP_0_1 | 36,480 | 70,143 | 1.9× |
| DOUBLES | 6,245 | 15,433 | 2.5× |
| SAMPLED_PCS | 23,035 | 13,162 | 0.57× |

### Range query (between 50th–51st percentile)

| Distribution | SliceZ (µs) | RangeBitmap (µs) | SliceZ speedup |
|---|---|---|---|
| UNIFORM_1 | 37,050 | 42,351 | 1.1× |
| UNIFORM_2 | 21,700 | 38,904 | 1.8× |
| EXP_0_1 | 34,616 | 80,275 | 2.3× |
| DOUBLES | 38,164 | 40,333 | 1.1× |
| SAMPLED_PCS | 46,130 | 30,674 | 0.66× |

### Top-k and bottom-k

The baseline is a brute-force heap scan over all 100 million values (~158–162 ms on all distributions, independent of k). SliceZ uses block-level `blockMin`/`blockMax` pruning to skip most blocks.

**bottom-k (µs)**

| Distribution | k=10 | k=100 | k=1,000 | Heap scan |
|---|---|---|---|---|
| UNIFORM_1 | 720 | 6,108 | 45,577 | 161,446 |
| UNIFORM_2 | 200 | 1,923 | 18,979 | 160,809 |
| EXP_0_1 | 1,636 | 1,642 | 1,792 | 158,492 |
| DOUBLES | 755 | 11,567 | 48,958 | 160,990 |
| SAMPLED_PCS | 237 | 237 | 1,253 | 159,222 |

**top-k (µs)**

| Distribution | k=10 | k=100 | k=1,000 | Heap scan |
|---|---|---|---|---|
| UNIFORM_1 | 717 | 6,087 | 45,691 | 161,446 |
| UNIFORM_2 | 338 | 2,958 | 18,495 | 160,809 |
| EXP_0_1 | 1,584 | 14,535 | 103,823 | 158,492 |
| DOUBLES | 741 | 5,945 | 45,353 | 160,990 |
| SAMPLED_PCS | 1,834 | 15,273 | 110,505 | 159,222 |

For small k (10–100) the speedup over heap scan is typically 86–791×. At k=1,000 it narrows to 3–127× on most distributions. Two patterns are worth noting:

- **EXP_0_1 bottom-k is nearly k-invariant** (1.6–1.8 ms for k=10, 100, 1,000). Exponential values are heavily concentrated at small magnitudes, so almost every block is pruned on its `blockMin` alone, and the threshold tightens in the very first block. Top-k on the same distribution degrades sharply at k=1,000 (104 ms) because locating the 1,000 largest values of a heavy-tailed distribution requires visiting many more blocks.

- **SAMPLED_PCS bottom-k at k=10 and k=100 are identical** (237 µs). All values share the top 17 bits, so `blockMin` varies only in the lower 47 bits; the first block immediately yields a tight global bound that prunes almost everything. Top-k is much more expensive at k≥100 (15–111 ms) because many blocks have similar `blockMax` values and cannot be pruned.

### Index size relative to raw data

| Distribution | SliceZ | RangeBitmap |
|---|---|---|
| UNIFORM_1 | 1.00× | 1.00× |
| UNIFORM_2 | 0.41× | 0.41× |
| EXP_0_1 | 0.61× | 0.10× |
| DOUBLES | 0.90× | 0.86× |
| SAMPLED_PCS | 0.76× | 0.36× |

### SliceZ slice type breakdown (totals across all blocks per dataset)

| Distribution | Dense | Full | Sparse | Sparse-Inverted |
|---|---|---|---|---|
| UNIFORM_1 | 97,664 | 0 | 0 | 0 |
| UNIFORM_2 | 39,676 | 57,988 | 0 | 0 |
| EXP_0_1 | 7,630 | 86,730 | 0 | 3,304 |
| DOUBLES | 85,531 | 11,249 | 743 | 141 |
| SAMPLED_PCS | 30,648 | 64,092 | 0 | 2,924 |

`UNIFORM_1` has no FULL slices because uniformly random 64-bit data populates all bit positions. `EXP_0_1` is dominated by FULL slices: with values concentrated in roughly 8 bits, the remaining 56 high-order bits are all-zero in the original data and become all-ones after `~(v − blockMin)`, yielding FULL slices with no payload. `UNIFORM_2` similarly has ~38 FULL slices per block from the high-order zero bits and from the trailing zero bits introduced by the ×10,000 factor.

The `SAMPLED_PCS` distribution is the one case where SliceZ is slower than RangeBitmap for equality and range queries. Profiler addresses have the top 17 bits fixed (→ FULL slices, no benefit to query cost) but the remaining 47 bits span varied code addresses, all falling into dense slices. RangeBitmap compresses this distribution ~2× better than SliceZ, and its representation aligns better with the narrow inter-percentile query range. Top/bottom-k show the same asymmetry: bottom-k benefits from the fixed high bits (strong block pruning), while top-k must scan most blocks.

---

Measured with JMH 1.37 on JDK 25 (OpenJDK 64-Bit Server VM 25+36-3489), Apple Silicon.
