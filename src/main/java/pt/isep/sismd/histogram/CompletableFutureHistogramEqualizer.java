package pt.isep.sismd.histogram;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Histogram equalization composed as a {@link CompletableFuture} pipeline
 * (Issue #7 — PDF section 5 "CompletableFutures-Based Solution").
 *
 * <h2>Async pipeline</h2>
 * <pre>
 *   Stage 1 partitions ──supplyAsync──▶  partial int[256]
 *                              │
 *                          thenCombine (pairwise merge)
 *                              ▼
 *                       full histogram
 *                              │
 *                          thenApply (cumulative — sequential, 256 ops)
 *                              ▼
 *                          cumulative
 *                              │
 *                          thenCompose
 *                              ▼
 *   Stage 3 partitions ──runAsync──▶  disjoint output slice writes
 *                              │
 *                          allOf  →  thenApply (return out)
 *                              ▼
 *                         join() once
 * </pre>
 *
 * <p>The {@code process} method itself never touches a {@code Thread} or a
 * {@code Future} directly — it only composes {@link CompletableFuture}s, in
 * keeping with the acceptance criterion "no explicit thread management".
 * The backing {@link ExecutorService} is created locally and shut down in a
 * {@code finally} block so the pool never leaks (same lifecycle discipline
 * as {@link ThreadPoolHistogramEqualizer}).
 *
 * <p>Output is pixel-identical to {@link SequentialHistogramEqualizer}.
 *
 * @version 1.0  (Issue #7 — CompletableFuture pipeline)
 */
public class CompletableFutureHistogramEqualizer implements ImageProcessor {

    /** Maximum time to wait for a clean executor shutdown before forcing it. */
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 30L;

    private final int partitions;

    /** Uses {@code Runtime.getRuntime().availableProcessors()} partitions. */
    public CompletableFutureHistogramEqualizer() {
        this(Runtime.getRuntime().availableProcessors());
    }

    /**
     * @param partitions number of row-slices / async tasks per stage
     *                   (must be &gt; 0)
     */
    public CompletableFutureHistogramEqualizer(int partitions) {
        if (partitions <= 0) {
            throw new IllegalArgumentException(
                    "partitions must be > 0 (got " + partitions + ")");
        }
        this.partitions = partitions;
    }

    @Override
    public Color[][] process(Color[][] sourceImage) {
        if (sourceImage == null || sourceImage.length == 0 || sourceImage[0].length == 0) {
            throw new IllegalArgumentException("sourceImage must be a non-empty 2-D matrix");
        }
        int width  = sourceImage.length;
        int height = sourceImage[0].length;
        int totalPixels = width * height;

        ExecutorService executor = Executors.newFixedThreadPool(partitions);
        try {
            CompletableFuture<Color[][]> pipeline =
                    computeHistogramAsync(sourceImage, executor)
                            .thenApply(this::computeCumulativeHistogram)
                            .thenCompose(cum ->
                                    applyEqualizationAsync(sourceImage, cum, totalPixels, executor));
            return pipeline.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error er)            throw er;
            throw new RuntimeException("Pipeline failed", cause);
        } finally {
            shutdownAndAwait(executor);
        }
    }

    // ── Stage 1: Async partial histogram computation ───────────────
    /**
     * Returns a {@link CompletableFuture} that resolves to the merged
     * luminosity histogram of the full image.
     *
     * <p>One {@code supplyAsync} per row-slice produces a private
     * {@code int[256]} partial; the partials are reduced via a left-fold of
     * {@code thenCombine(prev, mergeHistograms)} so the merges themselves
     * happen asynchronously as inputs become available.
     */
    protected CompletableFuture<int[]> computeHistogramAsync(Color[][] image,
                                                              ExecutorService executor) {
        int width = image.length;
        int slices = Math.min(partitions, width);

        List<CompletableFuture<int[]>> partials = new ArrayList<>(slices);
        for (int t = 0; t < slices; t++) {
            final int startRow = sliceStart(t, slices, width);
            final int endRow   = sliceStart(t + 1, slices, width);
            partials.add(CompletableFuture.supplyAsync(
                    () -> computePartialHistogram(image, startRow, endRow),
                    executor));
        }
        // Pairwise reduce via thenCombine → final merged histogram future.
        return partials.stream()
                .reduce((acc, next) ->
                        acc.thenCombine(next, CompletableFutureHistogramEqualizer::mergeHistograms))
                .orElseThrow(() -> new IllegalStateException("No partition produced"));
    }

    private static int[] computePartialHistogram(Color[][] image, int startRow, int endRow) {
        int[] local = new int[HistogramUtils.HISTOGRAM_BINS];
        for (int x = startRow; x < endRow; x++) {
            Color[] column = image[x];
            for (int y = 0; y < column.length; y++) {
                Color p = column[y];
                int lum = HistogramUtils.computeLuminosity(
                        p.getRed(), p.getGreen(), p.getBlue());
                local[lum]++;
            }
        }
        return local;
    }

    // ── Stage 2: Cumulative histogram (sequential, ran via thenApply) ─
    protected int[] computeCumulativeHistogram(int[] histogram) {
        int[] cumulative = new int[HistogramUtils.HISTOGRAM_BINS];
        cumulative[0] = histogram[0];
        for (int i = 1; i < HistogramUtils.HISTOGRAM_BINS; i++) {
            cumulative[i] = cumulative[i - 1] + histogram[i];
        }
        return cumulative;
    }

    // ── Stage 3: Async pixel rewriting ─────────────────────────────
    /**
     * Returns a {@link CompletableFuture} that resolves to the equalized
     * output image.
     *
     * <p>One {@code runAsync} per row-slice writes its disjoint slice of the
     * shared output array; {@link CompletableFuture#allOf} aggregates the
     * completion signals, and {@code thenApply} hands back the assembled
     * matrix. Slices are disjoint, so no synchronization is needed on the
     * output.
     */
    protected CompletableFuture<Color[][]> applyEqualizationAsync(Color[][] image,
                                                                   int[] cumulativeHist,
                                                                   int totalPixels,
                                                                   ExecutorService executor) {
        int cdfMin = 0;
        for (int i = 0; i < HistogramUtils.HISTOGRAM_BINS; i++) {
            if (cumulativeHist[i] != 0) {
                cdfMin = cumulativeHist[i];
                break;
            }
        }
        final int denom = totalPixels - cdfMin;
        final boolean degenerate = (denom == 0);

        int width  = image.length;
        int height = image[0].length;
        Color[][] out = new Color[width][height];

        int slices = Math.min(partitions, width);
        CompletableFuture<?>[] sliceFutures = new CompletableFuture<?>[slices];
        for (int t = 0; t < slices; t++) {
            final int startRow = sliceStart(t, slices, width);
            final int endRow   = sliceStart(t + 1, slices, width);
            sliceFutures[t] = CompletableFuture.runAsync(() ->
                    writeSlice(image, out, cumulativeHist, denom, degenerate, startRow, endRow),
                    executor);
        }
        return CompletableFuture.allOf(sliceFutures).thenApply(v -> out);
    }

    private static void writeSlice(Color[][] source, Color[][] output,
                                   int[] cumulativeHist, int denom, boolean degenerate,
                                   int startRow, int endRow) {
        for (int x = startRow; x < endRow; x++) {
            Color[] srcCol = source[x];
            Color[] dstCol = output[x];
            for (int y = 0; y < srcCol.length; y++) {
                Color p = srcCol[y];
                int lum = HistogramUtils.computeLuminosity(
                        p.getRed(), p.getGreen(), p.getBlue());
                int newLum;
                if (degenerate) {
                    newLum = 0;
                } else {
                    double cdf = (double) cumulativeHist[lum] / (double) denom;
                    newLum = (int) Math.round(255.0 * cdf);
                    if (newLum < 0)   newLum = 0;
                    if (newLum > 255) newLum = 255;
                }
                dstCol[y] = new Color(newLum, newLum, newLum);
            }
        }
    }

    // ── Utility: merge two partial histograms ──────────────────────
    /**
     * Element-wise sum of two 256-bin histograms. Returns a fresh array so
     * the inputs (which may still be referenced by upstream
     * {@link CompletableFuture}s) are not mutated.
     */
    protected static int[] mergeHistograms(int[] a, int[] b) {
        int[] merged = new int[HistogramUtils.HISTOGRAM_BINS];
        for (int i = 0; i < HistogramUtils.HISTOGRAM_BINS; i++) {
            merged[i] = a[i] + b[i];
        }
        return merged;
    }

    // ── Helpers ────────────────────────────────────────────────────

    private static int sliceStart(int i, int parts, int total) {
        return (int) (((long) total * i) / parts);
    }

    /**
     * Same shutdown discipline as {@link ThreadPoolHistogramEqualizer}:
     * orderly shutdown, bounded wait, fall back to {@code shutdownNow()}.
     */
    private static void shutdownAndAwait(ExecutorService pool) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                if (!pool.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new RuntimeException("Executor did not terminate cleanly");
                }
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
