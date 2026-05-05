package pt.isep.sismd.histogram;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BenchmarkRunner} (Issue #8).
 *
 * <p>The harness itself is functional, not algorithmic, so the tests only
 * exercise the public API surface and verify the CSV writer produces
 * non-empty, well-formed output.
 */
class BenchmarkRunnerTest {

    @Test
    void runBenchmark_returnsValidMetrics() {
        Color[][] input = TestImageFactory.createSmall();
        ImageProcessor processor = new SequentialHistogramEqualizer();

        BenchmarkRunner.BenchmarkResult result =
                BenchmarkRunner.runBenchmark(processor, input, 3);

        assertTrue(result.avgExecutionTimeNanos() > 0,
                "avg execution time should be positive");
        assertTrue(result.minTimeNanos() <= result.avgExecutionTimeNanos(),
                "min should be <= avg");
        assertTrue(result.avgExecutionTimeNanos() <= result.maxTimeNanos(),
                "avg should be <= max");
        assertTrue(result.heapUsedBefore() >= 0);
        assertTrue(result.heapUsedAfter()  >= 0);
        assertTrue(result.gcCount()  >= 0);
        assertTrue(result.gcTimeMs() >= 0);
        assertEquals("SequentialHistogramEqualizer", result.implementationName());
        assertEquals("10x10", result.imageSize());
    }

    @Test
    void runBenchmark_rejectsNonPositiveIterations() {
        Color[][] input = TestImageFactory.createSmall();
        ImageProcessor processor = new SequentialHistogramEqualizer();

        assertThrows(IllegalArgumentException.class,
                () -> BenchmarkRunner.runBenchmark(processor, input, 0));
        assertThrows(IllegalArgumentException.class,
                () -> BenchmarkRunner.runBenchmark(processor, input, -1));
    }

    @Test
    void collectGCMetrics_returnsNonNegativeValues() {
        BenchmarkRunner.GCMetrics metrics = BenchmarkRunner.collectGCMetrics();

        assertTrue(metrics.totalCollections() >= 0);
        assertTrue(metrics.totalTimeMs()      >= 0);
    }

    @Test
    void exportCSV_createsFileWithHeaders(@TempDir Path tmp) throws Exception {
        var results = List.of(
                new BenchmarkRunner.BenchmarkResult(
                        "Sequential", "10x10", 1,
                        1000L, 900L, 1100L,
                        1024L, 2048L, 1L, 5L)
        );
        Path file = tmp.resolve("test_output.csv");

        BenchmarkRunner.exportCSV(results, file.toString());

        assertTrue(Files.exists(file), "CSV file should be created");
        List<String> lines = Files.readAllLines(file);
        assertEquals(2, lines.size(), "header + 1 data row");
        assertTrue(lines.get(0).startsWith("implementation,"),
                "header must start with 'implementation,'");
        assertTrue(lines.get(1).startsWith("Sequential,10x10,1,"),
                "data row should encode the result fields");
    }

    @Test
    void exportTimeMemoryGcCSVs_areAllWritten(@TempDir Path tmp) throws Exception {
        var results = List.of(
                new BenchmarkRunner.BenchmarkResult(
                        "Sequential", "10x10", 1,
                        1_000_000L, 900_000L, 1_100_000L,
                        1024L, 2048L, 1L, 5L),
                new BenchmarkRunner.BenchmarkResult(
                        "ManualThread", "10x10", 4,
                        500_000L, 450_000L, 600_000L,
                        2048L, 4096L, 0L, 0L)
        );
        Path time   = tmp.resolve("results_time.csv");
        Path memory = tmp.resolve("results_memory.csv");
        Path gc     = tmp.resolve("results_gc.csv");

        BenchmarkRunner.exportTimeCSV(results,   time);
        BenchmarkRunner.exportMemoryCSV(results, memory);
        BenchmarkRunner.exportGCCSV(results,     gc);

        assertTrue(Files.exists(time));
        assertTrue(Files.exists(memory));
        assertTrue(Files.exists(gc));

        List<String> timeLines = Files.readAllLines(time);
        assertEquals(3, timeLines.size(), "time CSV should have header + 2 rows");
        assertTrue(timeLines.get(0).contains("avgNanos"));
        assertTrue(timeLines.get(0).contains("avgMs"));

        List<String> memLines = Files.readAllLines(memory);
        assertTrue(memLines.get(0).contains("deltaBytes"));
        assertTrue(memLines.get(0).contains("deltaMB"));

        List<String> gcLines = Files.readAllLines(gc);
        assertTrue(gcLines.get(0).contains("gcCount"));
        assertTrue(gcLines.get(0).contains("gcTimeMs"));
    }

    @Test
    void buildProcessor_returnsCorrectImplementation() {
        // Verifies the dispatch table used by main.
        assertInstanceOf(SequentialHistogramEqualizer.class,
                BenchmarkRunner.buildProcessor("Sequential", 1, 100));
        assertInstanceOf(ManualThreadHistogramEqualizer.class,
                BenchmarkRunner.buildProcessor("ManualThread", 4, 100));
        assertInstanceOf(ThreadPoolHistogramEqualizer.class,
                BenchmarkRunner.buildProcessor("ThreadPool", 8, 100));
        assertInstanceOf(ForkJoinHistogramEqualizer.class,
                BenchmarkRunner.buildProcessor("ForkJoin", 4, 100));
        assertInstanceOf(CompletableFutureHistogramEqualizer.class,
                BenchmarkRunner.buildProcessor("CompletableFuture", 4, 100));

        assertThrows(IllegalArgumentException.class,
                () -> BenchmarkRunner.buildProcessor("Unknown", 4, 100));
    }
}
