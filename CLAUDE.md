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

Run a single test class: `./gradlew test --tests "io.github.richardstartin.MyTest"`

## Source Sets

| Source set | Path | Purpose |
|---|---|---|
| `main` | `src/main/java/` | Library code (no RoaringBitmap) |
| `test` | `src/test/java/` | JUnit 5 tests |
| `jmh` | `src/jmh/java/` | JMH benchmarks |

RoaringBitmap is available in `test` and `jmh` but not `main`.

## Toolchain

Groovy DSL (`build.gradle`) with Gradle 9.0.0 — Kotlin DSL was avoided because the Kotlin compiler bundled in earlier Gradle versions cannot parse Java 25 version strings.
