package pt.isep.sismd.histogram;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;

/**
 * Cross-GC aggregator (Issue #9 — PDF §6 "Garbage Collector Tuning").
 *
 * <p>Reads the CSVs produced by {@link BenchmarkRunner} for every GC
 * sub-directory under a results root (e.g. {@code results/serial},
 * {@code results/parallel}, {@code results/g1}, {@code results/zgc}) and
 * emits a single Markdown report — {@code gc-tuning/comparison.md} by
 * default — with:
 *
 * <ol>
 *   <li>A per-GC throughput / pause-time / ratio summary table.</li>
 *   <li>A per-image-size table giving the best avg-ms achieved by each
 *       implementation under each GC.</li>
 *   <li>A heuristic recommendation, plus the data needed to back it up.</li>
 * </ol>
 *
 * <p>Usage:
 * <pre>
 *   java -cp target/classes pt.isep.sismd.histogram.GcComparisonReport \
 *        [resultsRootDir] [outputMarkdownFile]
 * </pre>
 *
 * <p>The aggregator is metadata-light by design: it only depends on
 * the column layout of {@code results_time.csv} and {@code results_gc.csv}.
 * That keeps it usable without modification when extra GC variants
 * (e.g. {@code Shenandoah}) are added.
 */
public class GcComparisonReport {

    /** Column index of {@code avgMs} in {@code results_time.csv} (0-based). */
    private static final int TIME_AVG_MS_COL = 6;
    /** Column indices in {@code results_gc.csv}. */
    private static final int GC_COUNT_COL    = 3;
    private static final int GC_TIME_MS_COL  = 4;

    public record TimeRow(String impl, String size, int threadCount, double avgMs) {}
    public record GcRow  (String impl, String size, int threadCount, long count, long timeMs) {}

    /** All metrics collected for a single GC run. */
    public record GcRunData(String name, List<TimeRow> times, List<GcRow> gc) {
        public double sumAvgMs()        { return times.stream().mapToDouble(TimeRow::avgMs).sum(); }
        public long   sumGcCount()      { return gc.stream().mapToLong(GcRow::count).sum();         }
        public long   sumGcTimeMs()     { return gc.stream().mapToLong(GcRow::timeMs).sum();        }
        /**
         * Estimated total productive wall-clock time across all measured iterations
         * of every config: {@code Σ avgMs × MEASURED_ITERATIONS}. This is what
         * {@link #gcRatioPercent()} divides {@code Σ GC time} by.
         */
        public double sumWorkMs() {
            return sumAvgMs() * BenchmarkRunner.MEASURED_ITERATIONS;
        }
        public double gcRatioPercent()  {
            double work = sumWorkMs();
            return work > 0 ? (sumGcTimeMs() / work) * 100.0 : 0.0;
        }
    }

    public static void main(String[] args) throws IOException {
        Path resultsRoot = Paths.get(args.length > 0 ? args[0] : "results");
        Path output      = Paths.get(args.length > 1 ? args[1] : "gc-tuning/comparison.md");

        if (!Files.isDirectory(resultsRoot)) {
            System.err.println("Results root is not a directory: " + resultsRoot.toAbsolutePath());
            System.exit(2);
        }

        Map<String, GcRunData> runs = collectRuns(resultsRoot);
        if (runs.isEmpty()) {
            System.err.println("No GC sub-directories with results_time.csv found in "
                    + resultsRoot.toAbsolutePath());
            System.exit(1);
        }

        if (output.getParent() != null) Files.createDirectories(output.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(output)) {
            writeReport(w, runs, resultsRoot);
        }
        System.out.println("Wrote " + output.toAbsolutePath() + " ("
                + runs.size() + " GC runs aggregated)");
    }

    // ── Collection ────────────────────────────────────────────────

    /**
     * Walks {@code resultsRoot} and returns one {@link GcRunData} per
     * sub-directory that contains a {@code results_time.csv}, keyed by
     * the sub-directory name (treated as the GC identifier).
     */
    public static Map<String, GcRunData> collectRuns(Path resultsRoot) throws IOException {
        Map<String, GcRunData> out = new TreeMap<>();
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(resultsRoot, Files::isDirectory)) {
            for (Path dir : dirs) {
                Path timeCsv = dir.resolve("results_time.csv");
                Path gcCsv   = dir.resolve("results_gc.csv");
                if (!Files.isRegularFile(timeCsv)) continue;

                List<TimeRow> times = parseTimeCsv(timeCsv);
                List<GcRow>   gc    = Files.isRegularFile(gcCsv)
                                      ? parseGcCsv(gcCsv) : List.of();
                String name = dir.getFileName().toString();
                out.put(name, new GcRunData(name, times, gc));
            }
        }
        return out;
    }

    static List<TimeRow> parseTimeCsv(Path file) throws IOException {
        List<TimeRow> rows = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(file)) {
            r.readLine(); // skip header
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] f = line.split(",");
                rows.add(new TimeRow(
                        f[0],
                        f[1],
                        Integer.parseInt(f[2].trim()),
                        Double.parseDouble(f[TIME_AVG_MS_COL].trim())));
            }
        }
        return rows;
    }

    static List<GcRow> parseGcCsv(Path file) throws IOException {
        List<GcRow> rows = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(file)) {
            r.readLine(); // skip header
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] f = line.split(",");
                rows.add(new GcRow(
                        f[0],
                        f[1],
                        Integer.parseInt(f[2].trim()),
                        Long.parseLong(f[GC_COUNT_COL].trim()),
                        Long.parseLong(f[GC_TIME_MS_COL].trim())));
            }
        }
        return rows;
    }

    // ── Report writing ─────────────────────────────────────────────

    static void writeReport(BufferedWriter w, Map<String, GcRunData> runs, Path resultsRoot)
            throws IOException {
        write(w, "# GC Comparison Report\n\n");
        write(w, "Generated by `pt.isep.sismd.histogram.GcComparisonReport`"
                + " from `" + resultsRoot.toAbsolutePath() + "`.\n\n");
        write(w, "GC runs aggregated: " + runs.keySet() + "\n\n");

        writePerGcSummary(w, runs);
        writeBestPerImplPerSize(w, runs);
        writeRecommendation(w, runs);
    }

    private static void writePerGcSummary(BufferedWriter w, Map<String, GcRunData> runs)
            throws IOException {
        write(w, "## Per-GC summary\n\n");
        write(w, "Across **all** sweep configurations (image size × implementation × thread count).\n");
        write(w, "`Σ work-ms` = `Σ avg-ms × " + BenchmarkRunner.MEASURED_ITERATIONS
                + "` (one row per iteration). `Σ GC time / Σ work-ms` is the share of productive "
                + "compute time that was matched by GC activity — values > 100% mean the collector "
                + "was busier than the equalizer itself.\n\n");
        write(w, "| GC | Configs | Σ avg-ms | Σ work-ms | Σ GC count | Σ GC time (ms) | GC / work |\n");
        write(w, "|---|---:|---:|---:|---:|---:|---:|\n");
        for (GcRunData r : runs.values()) {
            write(w, String.format(Locale.ROOT,
                    "| %s | %d | %.2f | %.2f | %d | %d | %.2f%% |%n",
                    r.name(), r.times().size(),
                    r.sumAvgMs(), r.sumWorkMs(), r.sumGcCount(), r.sumGcTimeMs(),
                    r.gcRatioPercent()));
        }
        write(w, "\n");
        write(w, "*Lower `Σ avg-ms` = higher throughput. "
                + "Lower `GC / work` = less time stolen by collection.*\n\n");
    }

    private static void writeBestPerImplPerSize(BufferedWriter w, Map<String, GcRunData> runs)
            throws IOException {
        // Discover the union of image sizes and implementations across all runs.
        Set<String> sizes = new LinkedHashSet<>();
        Set<String> impls = new LinkedHashSet<>();
        for (GcRunData r : runs.values()) {
            for (TimeRow t : r.times()) {
                sizes.add(t.size());
                impls.add(t.impl());
            }
        }

        for (String size : sizes) {
            write(w, "## Best avg-ms per implementation — `" + size + "`\n\n");
            write(w, "(Best across all thread/partition counts for that implementation under each GC.)\n\n");
            write(w, "| Implementation |");
            for (String gc : runs.keySet()) write(w, " " + gc + " |");
            write(w, "\n|---|");
            for (int i = 0; i < runs.size(); i++) write(w, "---:|");
            write(w, "\n");

            for (String impl : impls) {
                write(w, "| " + impl + " |");
                for (GcRunData r : runs.values()) {
                    OptionalDouble best = r.times().stream()
                            .filter(t -> t.impl().equals(impl) && t.size().equals(size))
                            .mapToDouble(TimeRow::avgMs)
                            .min();
                    write(w, best.isPresent()
                            ? String.format(Locale.ROOT, " %.2f |", best.getAsDouble())
                            : " — |");
                }
                write(w, "\n");
            }
            write(w, "\n");
        }
    }

    private static void writeRecommendation(BufferedWriter w, Map<String, GcRunData> runs)
            throws IOException {
        write(w, "## Heuristic recommendation\n\n");

        // Throughput winner = lowest sum of avg-ms.
        GcRunData throughputWinner = runs.values().stream()
                .min(Comparator.comparingDouble(GcRunData::sumAvgMs)).orElseThrow();
        // Pause-time winner = lowest sum of GC time (ms).
        GcRunData pauseWinner = runs.values().stream()
                .min(Comparator.comparingLong(GcRunData::sumGcTimeMs)).orElseThrow();
        // Ratio winner = lowest gc time / wall time.
        GcRunData ratioWinner = runs.values().stream()
                .min(Comparator.comparingDouble(GcRunData::gcRatioPercent)).orElseThrow();

        write(w, "- **Highest throughput** (lowest Σ avg-ms): `"
                + throughputWinner.name() + "` ("
                + String.format(Locale.ROOT, "%.2f", throughputWinner.sumAvgMs()) + " ms)\n");
        write(w, "- **Lowest cumulative GC pause time**: `"
                + pauseWinner.name() + "` (" + pauseWinner.sumGcTimeMs() + " ms)\n");
        write(w, "- **Lowest GC overhead ratio**: `"
                + ratioWinner.name() + "` ("
                + String.format(Locale.ROOT, "%.2f%%", ratioWinner.gcRatioPercent()) + ")\n\n");

    }

    private static void write(BufferedWriter w, String s) {
        try { w.write(s); } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
