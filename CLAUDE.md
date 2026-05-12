# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

`slicez` is a Z-layout bit-sliced index for evaluating point and range queries over unsorted numerical data. It is a Java project (Apache 2.0 license).

## Build and Test

```bash
./gradlew build          # compile + test
./gradlew test           # run tests
./gradlew jmh            # run benchmarks
```

Run a single test class: `./gradlew test --tests "io.github.richardstartin.slicez.TestSliceZ"`

## Source Sets

| Source set | Path | Purpose |
|---|---|---|
| `main` | `src/main/java/` | Library code (no RoaringBitmap) |
| `test` | `src/test/java/` | JUnit 5 tests |
| `jmh` | `src/jmh/java/` | JMH benchmarks |

RoaringBitmap is available in `test` and `jmh` but not `main`.

## Toolchain

Groovy DSL (`build.gradle`) with Gradle 9.0.0 — Kotlin DSL was avoided because the Kotlin compiler bundled in earlier Gradle versions cannot parse Java 25 version strings.

## Architecture

The entire implementation is a single class: `SliceZ` in `src/main/java/io/github/richardstartin/slicez/SliceZ.java`.

### Binary format

The index is stored as a little-endian `ByteBuffer`. Layout:

```
[Global header: 44 bytes]
  cookie (4B) | rowCount (4B) | min (8B) | max (8B) | counts[6] (24B)

[Block 0 header: 24 bytes]
  typesHigh (8B) | typesLow (8B) | blockMin (8B)
[Block 0 slices: variable]
  ...slice payloads for non-FULL slices...

[Block 1 header + slices]
...
```

Each block holds up to `BLOCK_SIZE = 65536` rows. The `typesHigh` and `typesLow` longs together encode a 2-bit type for each of the 64 bit-slices.

### Value transformation

During construction, values are stored relative to the block minimum, then bitwise-complemented:

```java
values[i] = ~(values[i] - blockMin);
```

This conflates all-zero slices with all-one slices (both become `FULL`), reducing the number of slices that need storage. All query code applies the inverse transformation when evaluating against a threshold.

### Slice types (2-bit encoding via `typesHigh`/`typesLow`)

| Type | Bits (high,low) | Condition | Stored payload |
|---|---|---|---|
| `FULL` | 1,1 | all bits in block set | nothing |
| `DENSE` | 1,0 | neither sparse nor sparse-inverted | 1024 × 8B bitset (8192 bytes) |
| `SPARSE` | 0,1 | cardinality < `SPARSE_THRESHOLD` (≈2047) | `count` (char) + sorted `char[]` of set positions |
| `SPARSE_INVERTED` | 0,0 | complement cardinality < `SPARSE_THRESHOLD` | `count` (char) + sorted `char[]` of unset positions |

### Query evaluation

All queries extend `BaseQuery implements PrimitiveIterator.OfInt`. They iterate block by block, calling `evaluateBlock()` which fills a `Bits` working buffer, then `extractBits()` materializes matching row IDs into an `int[]` output batch.

The `Bits` helper class wraps a `long[BLOCK_WORDS]` bitmap and tracks `empty`/`full` flags to short-circuit bitwise operations. It provides `denseOr`, `denseAnd`, `denseAndNot`, `sparseOr`, `sparseAnd`, `sparseAndNot`, `sparseOrNot` operations that advance the `ByteBuffer` read position as a side effect.

Three concrete query types:

- **`SingleBoundQuery`** (`lessThanOrEqual` / `greaterThan`): iterates slices from LSB to MSB; for each bit position in the anchored threshold, performs OR (bit set) or AND (bit not set) against the current buffer, then flips the buffer for `greaterThan`.
- **`EqualityQuery`** (`equal`): starts full, then for each slice performs AND (bit absent in value) or AND-NOT (bit present in value).
- **`BetweenQuery`** (`between`): maintains two `Bits` buffers — one for the lower bound, one for the upper bound — processes both in a single pass, then combines with `flipAnd` (upper AND NOT lower).

The `firstRelevantSlice` optimization skips slices below the highest `FULL` slice that would dominate the result, allowing the loop to start later.

### Unsigned semantics

All comparisons use unsigned 64-bit order (`Long.compareUnsigned`). The public API accepts plain `long` values treated as unsigned. Floating-point values can be indexed by mapping to a sortable `long` (see `DOUBLE_ENCODER` in `TestSliceZ`).

### Known issues

There is an open `FIXME` in `Appender.flush()` regarding whether the complement transformation is correct for unsigned semantics (line ~61).

### Benchmarks

JMH benchmarks in `src/jmh/` compare `SliceZ` against RoaringBitmap's `RangeBitmap` on 100M-element datasets across five data distributions (`UNIFORM_1`, `UNIFORM_2`, `EXP_0_1`, `DOUBLES`, `SAMPLED_PCS`). The `DataGenerator` enum defines those distributions. `RangeBitmapState` exposes a `getCompressionRatio()` aux counter for direct size comparison.
