# SISMD 2026 — Histogram Equalizer

> Parallel and sequential implementations of **luminosity-histogram
> equalization** in Java 17, built for the *Sistemas Multinúcleo e
> Distribuídos* (SISMD) master-level course at ISEP.

Five interchangeable `ImageProcessor` strategies are benchmarked across
four garbage collectors and analysed in a single executive report.

- **Repository:** <https://github.com/Af-Oliveira/SISMD2026>
- **Executive report:** [`report/REPORT.md`](report/REPORT.md)
- **GC analysis:** [`gc-tuning/README.md`](gc-tuning/README.md)

---

## Table of Contents

1. [Overview](#1-overview)
2. [Prerequisites](#2-prerequisites)
3. [Build](#3-build)
4. [Benchmarks](#4-benchmarks)
5. [GC Tuning](#5-gc-tuning)
6. [Project Structure](#6-project-structure)
7. [Authors](#7-authors)

---

## 1. Overview

The project implements the classic 3-stage histogram-equalization
pipeline:

1. **Compute luminosity histogram** — one bin per grey level (0–255),
   using the Rec. 601 weighting (`0.299·R + 0.587·G + 0.114·B`).
2. **Compute cumulative histogram** — prefix sum over the 256 bins.
3. **Rewrite pixels** — map every pixel through the normalized
   cumulative distribution function to spread intensities across the
   full dynamic range.

Five strategies implement the `ImageProcessor` interface and produce
**pixel-identical output**:

| Implementation | Class | Strategy |
|---|---|---|
| Sequential | `SequentialHistogramEqualizer` | Single-threaded baseline (reference implementation). |
| Manual threads | `ManualThreadHistogramEqualizer` | Raw `new Thread(...)`, `start()`, `join()`. Thread-local partial histograms merged sequentially after `join()` — avoids CAS contention. |
| Thread pool | `ThreadPoolHistogramEqualizer` | Fixed-size `ExecutorService` (`Executors.newFixedThreadPool`). `Callable` tasks return partial histograms; pool is always shut down in a `finally` block. |
| Fork/Join | `ForkJoinHistogramEqualizer` | `ForkJoinPool.commonPool()` with `RecursiveTask<int[]>` (histogram) and `RecursiveAction` (pixel rewrite), recursively split until a configurable row-threshold. |
| CompletableFuture | `CompletableFutureHistogramEqualizer` | Async pipeline: `supplyAsync` → pairwise `thenCombine` merges → `thenApply` (cumulative) → `thenCompose` → `runAsync` slice writes → `allOf`. |


---

## 2. Prerequisites

| Tool | Version | Purpose |
|---|---|---|
| JDK  | **17+** (tested on OpenJDK 26) | Build & run |
| Maven | **3.8+** | Build, test, exec |
| Python | 3.9+ *(optional)* | Chart regeneration (`report/charts/generate_charts.py`) |


---

## 3. Build

### Compile

```bash
mvn -q compile
```

### Run the test suite

```bash
mvn test
```

The suite covers:
- Per-implementation unit tests (`*HistogramEqualizerTest`).
- Cross-implementation correctness (`HistogramEqualizationCorrectnessTest`)
  — every concurrent implementation is asserted pixel-equal to the
  sequential baseline across small/medium/large/gradient images.
- Test utilities (`TestImageFactoryTest`, `UtilsTest`).
- Benchmark harness + GC report (`BenchmarkRunnerTest`, `GcComparisonReportTest`).


## 4. Benchmarks

### 4.1 What `BenchmarkRunner` does

Sweeps the Cartesian product of:

- **5 implementations** (Sequential, ManualThread, ThreadPool, ForkJoin, CompletableFuture)
- **3 image sizes** — `small_640x480`, `medium_1280x720`, `large_1920x1080`
- **Thread/partition counts** — `{1, 2, 4, 8, 16, N_cores}`

For each configuration it runs **3 warm-up + 10 measured** iterations
and records:

- Execution time (avg / min / max) via `System.nanoTime()`
- Heap usage before/after via `MemoryMXBean`
- GC count & time delta summed across all `GarbageCollectorMXBean`s

Output: `results_time.csv`, `results_memory.csv`, `results_gc.csv`.

### 4.2 Run the benchmark

```bash
mvn -q compile

# Writes to ./results by default
java -Xms2g -Xmx2g -cp target/classes pt.isep.sismd.histogram.BenchmarkRunner

# Or pass a custom output directory
java -Xms2g -Xmx2g -cp target/classes pt.isep.sismd.histogram.BenchmarkRunner results/g1
```

### 4.3 Run with specific GC flags

The harness does **not** select a GC itself — set it at JVM launch:

```bash
java -XX:+UseSerialGC   -Xms2g -Xmx2g -cp target/classes pt.isep.sismd.histogram.BenchmarkRunner results/serial
java -XX:+UseParallelGC -Xms2g -Xmx2g -cp target/classes pt.isep.sismd.histogram.BenchmarkRunner results/parallel
java -XX:+UseG1GC       -Xms2g -Xmx2g -cp target/classes pt.isep.sismd.histogram.BenchmarkRunner results/g1
java -XX:+UseZGC        -Xms2g -Xmx2g -cp target/classes pt.isep.sismd.histogram.BenchmarkRunner results/zgc
```

Add `-Xlog:gc*:file=<path>` to capture a GC log alongside the CSVs.

### 4.4 Regenerate charts from the CSVs

```powershell
# 1. Compile the Java sources.
mvn -q compile

# 2. Run all four GC sweeps + auto-aggregate the cross-GC summary.
gc-tuning\run_all.ps1            # ~45-60 s on a 24-core machine
```

```bash
python -m pip install matplotlib pandas
python report/charts/generate_charts.py
```

Charts are written to `report/charts/*.png` and embedded in

---

## 5. GC Tuning

Helper scripts for every GC live in [`gc-tuning/`](gc-tuning/), in both
`bash` (`.sh`) and PowerShell (`.ps1`) flavours:

```bash
# Linux / macOS
./gc-tuning/run_serial_gc.sh
./gc-tuning/run_parallel_gc.sh
./gc-tuning/run_g1_gc.sh
./gc-tuning/run_zgc.sh
./gc-tuning/run_all.sh        # one-shot sweep of all four
```

```powershell
# Windows
.\gc-tuning\run_serial_gc.ps1
.\gc-tuning\run_parallel_gc.ps1
.\gc-tuning\run_g1_gc.ps1
.\gc-tuning\run_zgc.ps1
.\gc-tuning\run_all.ps1
```

Each script:

1. Launches `BenchmarkRunner` with the appropriate `-XX:+Use*GC` flag and a fixed `-Xms2g -Xmx2g` heap.
2. Captures `-Xlog:gc*` output to `gc-tuning/logs/<gc>.log`.
3. Writes benchmark CSVs into `results/<gc>/`.

---

## 6. Project Structure

```
SISMD2026/
├── pom.xml                              Maven configuration (JDK 17, JUnit 5)
├── README.md                            You are here
│
├── src/main/java/pt/isep/sismd/histogram/
│   ├── ApplyFilters.java                Interactive entry point
│   ├── ImageProcessor.java              Strategy interface
│   ├── HistogramUtils.java              Shared luminosity helpers
│   ├── Utils.java                       Image I/O (load / write / copy)
│   │
│   ├── SequentialHistogramEqualizer.java          Baseline
│   ├── ManualThreadHistogramEqualizer.java        §2 raw Threads
│   ├── ThreadPoolHistogramEqualizer.java          §3 ExecutorService
│   ├── ForkJoinHistogramEqualizer.java            §4 Fork/Join
│   ├── CompletableFutureHistogramEqualizer.java   §5 CompletableFuture
│   │
│   ├── BenchmarkRunner.java             Metrics harness → CSVs
│   └── GcComparisonReport.java          Cross-GC Markdown aggregator
│
├── src/test/java/pt/isep/sismd/histogram/
│   ├── TestImageFactory.java            Reproducible test images
│   ├── ImageAssertions.java             Pixel-equality assertion
│   ├── HistogramEqualizationCorrectnessTest.java  Cross-impl golden test
│   └── *Test.java                       Per-class unit tests
│
├── gc-tuning/                           GC launch scripts + analysis
│   ├── README.md                        GC methodology + verdict
│   ├── comparison.md                    Cross-GC summary table
│   ├── run_{serial,parallel,g1,zgc}.{sh,ps1}
│   └── run_all.{sh,ps1}
│
├── report/
│   ├── REPORT.md                        Executive analysis report
│   └── charts/
│       ├── generate_charts.py           Chart generator (matplotlib)
│       └── *.png                        Generated figures
│
└── results/                             Raw benchmark CSVs per GC
    ├── serial/   {results_time,results_memory,results_gc}.csv
    ├── parallel/ {results_time,results_memory,results_gc}.csv
    ├── g1/       {results_time,results_memory,results_gc}.csv
    └── zgc/      {results_time,results_memory,results_gc}.csv
```

---

## 7. Authors

- **Afonso Oliveira** — ISEP / SISMD 2025–26

See the project board for per-issue assignments:
<https://github.com/users/Af-Oliveira/projects/6/views/1>.

---
