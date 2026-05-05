package pt.isep.sismd.histogram;

import java.awt.Color;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * mvn -q compile
 * 
 * java -XX:+UseSerialGC   -Xmx2g -cp target\classes pt.isep.sismd.histogram.BenchmarkRunner results\serial
 * java -XX:+UseParallelGC -Xmx2g -cp target\classes pt.isep.sismd.histogram.BenchmarkRunner results\parallel
 * java -XX:+UseG1GC       -Xmx2g -cp target\classes pt.isep.sismd.histogram.BenchmarkRunner results\g1
 * java -XX:+UseZGC        -Xmx2g -cp target\classes pt.isep.sismd.histogram.BenchmarkRunner results\zgc
 * 
 * Benchmarking harness for every {@link ImageProcessor} implementation
 * (Issue #8 — PDF "Generation of Results").
 *
 * <p>Sweeps the cartesian product of:
 * <ul>
 *   <li>5 implementations (sequential + 4 concurrent)</li>
 *   <li>3 image sizes (small, medium, large)</li>
 *   <li>thread/partition counts: {1, 2, 4, 8, 16, N_cores}</li>
 * </ul>
 *
 * <p>For each configuration it runs {@value #WARMUP_ITERATIONS} warm-up
 * iterations (to let the JIT compile and the heap stabilize) followed by
 * {@value #MEASURED_ITERATIONS} measured iterations, recording:
 * <ul>
 *   <li>Execution time — {@code System.nanoTime()} delta (avg/min/max).</li>
 *   <li>Heap used before/after — {@link MemoryMXBean#getHeapMemoryUsage()}.</li>
 *   <li>GC count &amp; time delta — sum across all
 *       {@link GarbageCollectorMXBean}s.</li>
 * </ul>
 *
 * <p>Results are written to {@code results/results_time.csv},
 * {@code results/results_memory.csv} and {@code results/results_gc.csv}.
 *
 * <p>The active GC is whichever the JVM was started with — the harness
 * does <em>not</em> select one. That makes Issue #9 a one-liner: re-run
 * with {@code -XX:+UseG1GC}, {@code -XX:+UseZGC}, etc.
 *
 * <h2>Memory note</h2>
 * Each {@code Color[][]} of W×H pixels allocates W·H {@code Color} objects
 * (~48 bytes each). For {@code 1920×1080} that is roughly 100 MB; for the
 * large preset (default {@code 1920×1080}) plan on at least
 * {@code -Xmx2g}.
 */
public class BenchmarkRunner {

    public static final int WARMUP_ITERATIONS   = 3;
    public static final int MEASURED_ITERATIONS = 10;

    /** Where the CSV files are written. */
    public static final Path DEFAULT_RESULTS_DIR = Paths.get("results");

    private static final String[] IMPLEMENTATIONS = {
            "Sequential",
            "ManualThread",
            "ThreadPool",
            "ForkJoin",
            "CompletableFuture"
    };

    /** Benchmark image-size presets. */
    public static final ImageSize[] DEFAULT_SIZES = {
            new ImageSize("small_640x480",   640,  480),
            new ImageSize("medium_1280x720", 1280, 720),
            new ImageSize("large_1920x1080", 1920, 1080)
    };

    private BenchmarkRunner() {
        // Utility class — invoked via main.
    }

    // ── Entry point ────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        Path resultsDir = DEFAULT_RESULTS_DIR;
        if (args.length > 0) resultsDir = Paths.get(args[0]);
        Files.createDirectories(resultsDir);

        printJvmInfo();

        int cores = Runtime.getRuntime().availableProcessors();
        int[] threadSweep = uniqueSorted(new int[]{1, 2, 4, 8, 16, cores});
        System.out.println("Thread/partition sweep: " + Arrays.toString(threadSweep));
        System.out.println("Iterations: " + WARMUP_ITERATIONS
                + " warm-up + " + MEASURED_ITERATIONS + " measured");
        System.out.println();

        List<BenchmarkResult> results = new ArrayList<>();
        for (ImageSize size : DEFAULT_SIZES) {
            System.out.println("── " + size.label() + " ──");
            Color[][] image = randomImage(size.width(), size.height(), 42L);

            for (String impl : IMPLEMENTATIONS) {
                int[] sweep = "Sequential".equals(impl) ? new int[]{1} : threadSweep;
                for (int t : sweep) {
                    ImageProcessor processor = buildProcessor(impl, t, size.height());
                    BenchmarkResult r = runBenchmark(processor, image, MEASURED_ITERATIONS,
                            impl, size.label(), t);
                    results.add(r);
                    System.out.printf(Locale.ROOT,
                            "  %-18s t=%-3d  avg=%8.2f ms  min=%8.2f ms  gc=%d (%d ms)%n",
                            impl, t,
                            r.avgExecutionTimeNanos() / 1e6,
                            r.minTimeNanos()          / 1e6,
                            r.gcCount(), r.gcTimeMs());
                }
            }
            // Drop the reference so the next size can reclaim the heap.
            //noinspection UnusedAssignment
            image = null;
            System.gc();
        }

        exportTimeCSV(results,   resultsDir.resolve("results_time.csv"));
        exportMemoryCSV(results, resultsDir.resolve("results_memory.csv"));
        exportGCCSV(results,     resultsDir.resolve("results_gc.csv"));
        System.out.println();
        System.out.println("Wrote " + results.size() + " rows to " + resultsDir.toAbsolutePath());
    }

    // ── Public API (matches the SPRINT_PLAN stub) ──────────────────

    /**
     * Runs warm-up + measured iterations of {@code processor} against
     * {@code image} and returns the aggregated metrics.
     */
    public static BenchmarkResult runBenchmark(ImageProcessor processor,
                                                Color[][] image,
                                                int iterations) {
        return runBenchmark(processor, image, iterations,
                processor.getClass().getSimpleName(),
                image.length + "x" + image[0].length,
                1);
    }

    /** Variant that lets the caller pin the metadata columns. */
    public static BenchmarkResult runBenchmark(ImageProcessor processor,
                                                Color[][] image,
                                                int iterations,
                                                String implementationName,
                                                String imageSizeLabel,
                                                int threadCount) {
        if (iterations <= 0) {
            throw new IllegalArgumentException("iterations must be > 0 (got " + iterations + ")");
        }
        // Warm-up
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            processor.process(Utils.copyImage(image));
        }
        // Force a GC so the heap-before reading is more representative.
        System.gc();

        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        long heapBefore = memBean.getHeapMemoryUsage().getUsed();
        GCMetrics gcBefore = collectGCMetrics();

        long[] timings = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            Color[][] copy = Utils.copyImage(image);
            long t0 = System.nanoTime();
            processor.process(copy);
            long t1 = System.nanoTime();
            timings[i] = t1 - t0;
        }
        long heapAfter = memBean.getHeapMemoryUsage().getUsed();
        GCMetrics gcAfter = collectGCMetrics();

        long sum = 0L;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (long t : timings) {
            sum += t;
            if (t < min) min = t;
            if (t > max) max = t;
        }
        long avg = sum / iterations;

        return new BenchmarkResult(
                implementationName,
                imageSizeLabel,
                threadCount,
                avg, min, max,
                heapBefore, heapAfter,
                gcAfter.totalCollections() - gcBefore.totalCollections(),
                gcAfter.totalTimeMs()      - gcBefore.totalTimeMs()
        );
    }

    /** Sums {@link GarbageCollectorMXBean#getCollectionCount()} and time across all collectors. */
    public static GCMetrics collectGCMetrics() {
        long count = 0L;
        long timeMs = 0L;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long c = gc.getCollectionCount();
            long t = gc.getCollectionTime();
            if (c > 0) count  += c;
            if (t > 0) timeMs += t;
        }
        return new GCMetrics(count, timeMs);
    }

    /**
     * Writes every column of every {@link BenchmarkResult} to {@code filename}
     * — used by the {@link BenchmarkRunnerTest test stub}. The {@code main}
     * method instead splits the output into the three specialized CSVs.
     */
    public static void exportCSV(List<BenchmarkResult> results, String filename) {
        try (BufferedWriter w = Files.newBufferedWriter(Paths.get(filename))) {
            w.write("implementation,imageSize,threadCount,"
                    + "avgNanos,minNanos,maxNanos,"
                    + "heapUsedBefore,heapUsedAfter,gcCount,gcTimeMs");
            w.newLine();
            for (BenchmarkResult r : results) {
                w.write(String.format(Locale.ROOT, "%s,%s,%d,%d,%d,%d,%d,%d,%d,%d",
                        r.implementationName(), r.imageSize(), r.threadCount(),
                        r.avgExecutionTimeNanos(), r.minTimeNanos(), r.maxTimeNanos(),
                        r.heapUsedBefore(), r.heapUsedAfter(),
                        r.gcCount(), r.gcTimeMs()));
                w.newLine();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ── Specialized CSV writers (used by main) ─────────────────────

    public static void exportTimeCSV(List<BenchmarkResult> results, Path file) {
        writeCsv(file,
                "implementation,imageSize,threadCount,avgNanos,minNanos,maxNanos,avgMs",
                results, r -> String.format(Locale.ROOT, "%s,%s,%d,%d,%d,%d,%.4f",
                        r.implementationName(), r.imageSize(), r.threadCount(),
                        r.avgExecutionTimeNanos(), r.minTimeNanos(), r.maxTimeNanos(),
                        r.avgExecutionTimeNanos() / 1e6));
    }

    public static void exportMemoryCSV(List<BenchmarkResult> results, Path file) {
        writeCsv(file,
                "implementation,imageSize,threadCount,heapUsedBefore,heapUsedAfter,deltaBytes,deltaMB",
                results, r -> {
                    long delta = r.heapUsedAfter() - r.heapUsedBefore();
                    return String.format(Locale.ROOT, "%s,%s,%d,%d,%d,%d,%.2f",
                            r.implementationName(), r.imageSize(), r.threadCount(),
                            r.heapUsedBefore(), r.heapUsedAfter(),
                            delta, delta / (1024.0 * 1024.0));
                });
    }

    public static void exportGCCSV(List<BenchmarkResult> results, Path file) {
        writeCsv(file,
                "implementation,imageSize,threadCount,gcCount,gcTimeMs",
                results, r -> String.format(Locale.ROOT, "%s,%s,%d,%d,%d",
                        r.implementationName(), r.imageSize(), r.threadCount(),
                        r.gcCount(), r.gcTimeMs()));
    }

    // ── Internals ──────────────────────────────────────────────────

    private static void writeCsv(Path file, String header,
                                 List<BenchmarkResult> results,
                                 java.util.function.Function<BenchmarkResult, String> row) {
        try (BufferedWriter w = Files.newBufferedWriter(file)) {
            w.write(header);
            w.newLine();
            for (BenchmarkResult r : results) {
                w.write(row.apply(r));
                w.newLine();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Builds the {@link ImageProcessor} for {@code (impl, threadCount)}.
     * For {@code ForkJoin}, {@code threadCount} is mapped to a leaf-size
     * threshold so all five implementations can share the same CSV schema.
     */
    static ImageProcessor buildProcessor(String impl, int threadCount, int imageHeight) {
        return switch (impl) {
            case "Sequential"        -> new SequentialHistogramEqualizer();
            case "ManualThread"      -> new ManualThreadHistogramEqualizer(threadCount);
            case "ThreadPool"        -> new ThreadPoolHistogramEqualizer(threadCount);
            case "ForkJoin"          -> new ForkJoinHistogramEqualizer(
                    Math.max(1, imageHeight / threadCount));
            case "CompletableFuture" -> new CompletableFutureHistogramEqualizer(threadCount);
            default -> throw new IllegalArgumentException("Unknown implementation: " + impl);
        };
    }

    private static Color[][] randomImage(int width, int height, long seed) {
        Random rng = new Random(seed);
        Color[][] img = new Color[width][height];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                img[x][y] = new Color(rng.nextInt(256), rng.nextInt(256), rng.nextInt(256));
            }
        }
        return img;
    }

    private static int[] uniqueSorted(int[] xs) {
        Set<Integer> seen = new LinkedHashSet<>();
        for (int x : xs) seen.add(x);
        return seen.stream().sorted().mapToInt(Integer::intValue).toArray();
    }

    private static void printJvmInfo() {
        System.out.println("Java:        " + Runtime.version());
        System.out.println("Cores:       " + Runtime.getRuntime().availableProcessors());
        System.out.println("Max heap:    "
                + (Runtime.getRuntime().maxMemory() / (1024 * 1024)) + " MB");
        System.out.println("Active GCs:");
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            System.out.println("  - " + gc.getName());
        }
        System.out.println();
    }

    // ── Data records (per SPRINT_PLAN stub) ────────────────────────

    public record BenchmarkResult(
            String implementationName,
            String imageSize,
            int threadCount,
            long avgExecutionTimeNanos,
            long minTimeNanos,
            long maxTimeNanos,
            long heapUsedBefore,
            long heapUsedAfter,
            long gcCount,
            long gcTimeMs
    ) {}

    public record GCMetrics(long totalCollections, long totalTimeMs) {}

    /** Image-size preset (used by the main sweep). */
    public record ImageSize(String label, int width, int height) {}
}
