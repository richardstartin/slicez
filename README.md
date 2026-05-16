# slicez

Z-layout bit-sliced index for evaluating point and range queries over unsorted numerical data.

## Usage

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

| Type | Condition                                     | Payload |
|---|-----------------------------------------------|---|
| `FULL` | Every row has the bit set                     | None (2-bit header tag only) |
| `SPARSE` | Fewer than 4096 rows have the bit set         | `count` (2 B) + sorted row positions (2 B each) |
| `SPARSE_INVERTED` | Fewer than 4096 rows have the bit **clear** | `count` (2 B) + sorted complement positions (2 B each) |
| `DENSE` | Otherwise                                     | 1,024-byte flat bitset (16 × 64-bit words) |

The block header is 24 bytes: two 64-bit words (`typesHigh`, `typesLow`) encoding the 2-bit type for each of the 64 slices, plus the 64-bit `blockMin`.

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

### Slice operations

Every accumulator operation is dispatched on slice type. Sparse operations iterate the stored position list directly. Dense operations work in 8-word strides and propagate `empty`/`full` sentinel flags so that subsequent operations can short-circuit without touching slice data. For example, once the accumulator is known to be empty, AND operations become no-ops; once it is full, union operations against FULL slices are no-ops.

---

## Benchmarks

Benchmarks compare SliceZ against [`RangeBitmap`](https://github.com/RoaringBitmap/RoaringBitmap) indexing **100 million** `long` values. The equality benchmark queries the median value; the range benchmark queries between the 50th and 51st percentile (a narrow 1-percentile window). All timings are average time in microseconds (lower is better).

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
| UNIFORM_1 | 6,748 | 17,695 | 2.6× |
| UNIFORM_2 | 7,431 | 12,866 | 1.7× |
| EXP_0_1 | 37,030 | 74,413 | 2.0× |
| DOUBLES | 6,870 | 17,740 | 2.6× |
| SAMPLED_PCS | 23,222 | 16,006 | 0.69× |

### Range query (between 50th–51st percentile)

| Distribution | SliceZ (µs) | RangeBitmap (µs) | SliceZ speedup |
|---|---|---|---|
| UNIFORM_1 | 41,150 | 51,060 | 1.2× |
| UNIFORM_2 | 23,240 | 59,341 | 2.6× |
| EXP_0_1 | 38,880 | 97,808 | 2.5× |
| DOUBLES | 39,486 | 48,260 | 1.2× |
| SAMPLED_PCS | 46,307 | 32,080 | 0.69× |

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

The `SAMPLED_PCS` distribution is the one case where SliceZ is slower. Profiler addresses have the top 17 bits fixed (→ FULL slices, no benefit to query cost) but the remaining 47 bits span varied code addresses, all falling into dense slices. RangeBitmap compresses this distribution 2× better than SliceZ, and its representation aligns better with the narrow inter-percentile query range.

---

Measured with JMH 1.37 on JDK 25 (OpenJDK 64-Bit Server VM 25+36-3489), Apple Silicon.
