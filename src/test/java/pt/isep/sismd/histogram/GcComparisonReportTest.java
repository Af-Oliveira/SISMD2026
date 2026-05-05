package pt.isep.sismd.histogram;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GcComparisonReport} (Issue #9).
 *
 * <p>Builds a synthetic results tree under {@link TempDir} and exercises
 * the parsing → aggregation → Markdown-emitting pipeline end-to-end.
 */
class GcComparisonReportTest {

    private static final String TIME_HEADER =
            "implementation,imageSize,threadCount,avgNanos,minNanos,maxNanos,avgMs";
    private static final String GC_HEADER =
            "implementation,imageSize,threadCount,gcCount,gcTimeMs";

    @Test
    void collectRuns_aggregatesEverySubdirWithATimeCsv(@TempDir Path tmp) throws IOException {
        writeRun(tmp.resolve("serial"),
                "Sequential,small_640x480,1,5000000,4000000,6000000,5.0",
                "Sequential,medium,1,10000000,9000000,12000000,10.0");
        writeRun(tmp.resolve("g1"),
                "Sequential,small_640x480,1,4500000,4000000,5500000,4.5");
        // Sub-dir without a results_time.csv should be ignored.
        Files.createDirectories(tmp.resolve("empty"));

        Map<String, GcComparisonReport.GcRunData> runs = GcComparisonReport.collectRuns(tmp);

        assertEquals(2, runs.size());
        assertTrue(runs.containsKey("serial"));
        assertTrue(runs.containsKey("g1"));
        assertEquals(2, runs.get("serial").times().size());
        assertEquals(1, runs.get("g1").times().size());
    }

    @Test
    void gcRunData_aggregatesTotalsCorrectly(@TempDir Path tmp) throws IOException {
        writeRun(tmp.resolve("serial"),
                "Sequential,small,1,5000000,4000000,6000000,5.0",
                "ManualThread,small,4,3000000,2500000,4000000,3.0");

        Map<String, GcComparisonReport.GcRunData> runs = GcComparisonReport.collectRuns(tmp);
        GcComparisonReport.GcRunData serial = runs.get("serial");

        assertEquals(8.0,  serial.sumAvgMs(),    1e-9);
        assertEquals(3L,   serial.sumGcCount());        // 1 + 2 from synthetic gc CSV
        assertEquals(15L,  serial.sumGcTimeMs());       // 5 + 10 from synthetic gc CSV
        // sumWorkMs = sumAvgMs * MEASURED_ITERATIONS (10) = 80 ms
        assertEquals(8.0 * BenchmarkRunner.MEASURED_ITERATIONS,
                serial.sumWorkMs(), 1e-9);
        // gcRatioPercent = (15 / 80) * 100 = 18.75
        assertEquals(18.75, serial.gcRatioPercent(), 1e-9);
    }

    @Test
    void mainEmitsMarkdownReport(@TempDir Path tmp) throws Exception {
        Path resultsRoot = tmp.resolve("results");
        writeRun(resultsRoot.resolve("serial"),
                "Sequential,small,1,5000000,4000000,6000000,5.0",
                "ForkJoin,small,8,2500000,2000000,3000000,2.5");
        writeRun(resultsRoot.resolve("g1"),
                "Sequential,small,1,4800000,4000000,5500000,4.8",
                "ForkJoin,small,8,2300000,2000000,2800000,2.3");

        Path output = tmp.resolve("comparison.md");
        GcComparisonReport.main(new String[]{ resultsRoot.toString(), output.toString() });

        assertTrue(Files.exists(output), "comparison.md should be created");
        String md = Files.readString(output);
        assertTrue(md.contains("# GC Comparison Report"));
        assertTrue(md.contains("## Per-GC summary"));
        assertTrue(md.contains("## Best avg-ms per implementation"));
        assertTrue(md.contains("## Heuristic recommendation"));
        assertTrue(md.contains("serial") && md.contains("g1"),
                "Both GC names should appear in the report");
        assertTrue(md.contains("Sequential") && md.contains("ForkJoin"),
                "Implementations from the CSVs should appear");
    }

    @Test
    void collectRuns_emptyRootProducesEmptyMap(@TempDir Path tmp) throws IOException {
        Path empty = tmp.resolve("empty_results");
        Files.createDirectories(empty);

        Map<String, GcComparisonReport.GcRunData> runs = GcComparisonReport.collectRuns(empty);

        assertTrue(runs.isEmpty(),
                "An empty results root must yield no GC runs (main() then exits with code 1)");
    }

    // ── helpers ────────────────────────────────────────────────────

    private static void writeRun(Path runDir, String... timeRows) throws IOException {
        Files.createDirectories(runDir);
        StringBuilder time = new StringBuilder(TIME_HEADER).append('\n');
        StringBuilder gc   = new StringBuilder(GC_HEADER).append('\n');
        long n = 1;
        long t = 5;
        for (String row : timeRows) {
            time.append(row).append('\n');
            // Mirror the time row's metadata into the GC CSV with deterministic counters.
            String[] f = row.split(",");
            gc.append(f[0]).append(',').append(f[1]).append(',').append(f[2])
              .append(',').append(n++).append(',').append(t).append('\n');
            t += 5;
        }
        Files.writeString(runDir.resolve("results_time.csv"), time.toString());
        Files.writeString(runDir.resolve("results_gc.csv"),   gc.toString());
    }

}
