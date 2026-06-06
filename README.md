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

Every query method comes in two forms: an iterator that returns matching row indices, and a count method.

```java
// equality
PrimitiveIterator.OfInt it = index.equal(42L);
int count = index.countEqual(42L);

// range (exclusive upper bound)
it = index.lessThan(1000L);
count = index.countLessThan(1000L);

it = index.lessThanOrEqual(1000L);
count = index.countLessThanOrEqual(1000L);

it = index.greaterThan(42L);
count = index.countGreaterThan(42L);

it = index.greaterThanOrEqual(42L);
count = index.countGreaterThanOrEqual(42L);

// between(lower, upper) is exclusive on both ends: lower < value < upper
it = index.between(42L, 1000L);
count = index.countBetween(42L, 1000L);

// inequality and multi-value match
it = index.notEqual(42L);
count = index.countNotEqual(42L);
it = index.in(1L, 42L, 1000L);

// top-k / bottom-k row IDs (unsigned order; partial when fewer than k rows exist)
PrimitiveIterator.OfInt top = index.top(10);
PrimitiveIterator.OfInt bot = index.bottom(10);

// global extrema and row count
long min = index.min();
long max = index.max();
```

All comparisons use **unsigned** 64-bit order, so values are treated as unsigned longs regardless of sign.

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

SliceZ answers equality and range queries (`=`, `<`, `≤`, `>`, `≥`, `between`, `in`) over sequences of `long` values without sorting the data. It is a bit-sliced index in the tradition of O'Neil and Quass (1997). IEEE 754 doubles can be indexed by first mapping them to a total unsigned order with `FPOrdering.ordinalOf`.

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

**`between lower, upper`:** Decomposes into `NOT(value ≤ lower − 1) AND (value ≤ upper)`. Two accumulators are maintained in parallel (sharing slice reads where possible) and combined at the end as `~lower_buffer & upper_buffer` via `flipAnd`.

**`top k` / `bottom k`:** Two passes. The first walks block headers only (no payload reads) and keeps a *k*-block heap keyed on `blockMax` (for `top`) or `blockMin` (for `bottom`) — every candidate block is guaranteed to contain at least one row that could rank in the global top/bottom *k*. The second pass processes the candidates in best-first order with a *k*-row heap: each block evaluates the current threshold via `evaluateSingleBoundQueryBlock`, extracts matching rows, and tightens the threshold to the heap's *k*-th-best value so subsequent blocks can short-circuit on `blockMax`/`blockMin` alone. The threshold is treated as an inclusive lower (resp. upper) bound and translated to the strict `>` operator via `threshold − 1`, with a fill fallback when the threshold lands on the unsigned minimum.

The supporting `Heap` is a custom bounded min-heap with parallel `long[] keys` and `T[] values`. Comparisons run on the `long` keys via a `LongComparator` functional interface, and the `values[]` slot tracks its key through every sift, so callers reuse pre-allocated `Block` / `Row` instances rather than allocating per insertion.

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
