package pt.isep.sismd.histogram;

import java.awt.Color;

/**
 * Histogram equalization using <strong>manually managed threads</strong>
 * (Issue #4 — PDF Section 2 "Multithreaded Solution (Without Thread Pools)").
 *
 * <p>This implementation uses {@code new Thread(...)}, {@code start()} and
 * {@code join()} only — no {@link java.util.concurrent.ExecutorService},
 * {@link java.util.concurrent.ForkJoinPool} or
 * {@link java.util.concurrent.CompletableFuture}.
 *
 * <h2>Parallelization strategy</h2>
 * <ol>
 *   <li><b>Stage 1 — histogram (parallel).</b> The image rows are partitioned
 *       across {@code numThreads} workers. Each worker fills its own
 *       <em>thread-local</em> {@code int[256]} histogram, eliminating
 *       contention. After {@code join()} the partial histograms are merged
 *       sequentially on the main thread (cheap: 256·N additions).</li>
 *   <li><b>Stage 2 — cumulative histogram (sequential).</b> Prefix sum is
 *       inherently sequential and cheap (256 additions); kept on the main
 *       thread.</li>
 *   <li><b>Stage 3 — pixel rewriting (parallel).</b> Rows are partitioned
 *       again. Each worker writes only into its own disjoint slice of the
 *       output matrix, so no synchronization is needed on the result.</li>
 * </ol>
 *
 * <h2>Thread-safety mechanism (acceptance criterion)</h2>
 * Thread-local partial histograms + sequential merge. This avoids the
 * 256-way CAS contention that an {@link java.util.concurrent.atomic.AtomicIntegerArray}
 * would impose, and the merge phase happens on the main thread <em>after</em>
 * all workers have terminated via {@code join()} — establishing a
 * happens-before relationship that makes the merge visibility-safe.
 *
 * <p>Output is pixel-identical to {@link SequentialHistogramEqualizer}.
 *
 * @version 1.0  (Issue #4 — manual threads)
 */
public class ManualThreadHistogramEqualizer implements ImageProcessor {

    private final int numThreads;

    /** Uses {@code Runtime.getRuntime().availableProcessors()} threads. */
    public ManualThreadHistogramEqualizer() {
        this(Runtime.getRuntime().availableProcessors());
    }

    /**
     * @param numThreads number of worker threads (must be bigger than 0)
     */
    public ManualThreadHistogramEqualizer(int numThreads) {
        if (numThreads <= 0) {
            throw new IllegalArgumentException("numThreads must be > 0 (got " + numThreads + ")");
        }
        this.numThreads = numThreads;
    }

    @Override
    public Color[][] process(Color[][] sourceImage) {
        if (sourceImage == null || sourceImage.length == 0 || sourceImage[0].length == 0) {
            throw new IllegalArgumentException("sourceImage must be a non-empty 2-D matrix");
        }
        int width  = sourceImage.length;
        int height = sourceImage[0].length;
        int totalPixels = width * height;

        int[] hist       = computeHistogramParallel(sourceImage);
        int[] cumulative = computeCumulativeHistogram(hist);

        return applyEqualizationParallel(sourceImage, cumulative, totalPixels);
    }

    // ── Stage 1: Parallel histogram computation ────────────────────
    /**
     * Spawns up to {@code numThreads} threads; each computes a partial
     * histogram for its row slice into a private {@code int[256]} buffer,
     * then the main thread merges all partials after {@code join()}.
     */
    protected int[] computeHistogramParallel(Color[][] image) {
        int width = image.length;
        int actualThreads = Math.min(numThreads, width);
        int[][] partials = new int[actualThreads][HistogramUtils.HISTOGRAM_BINS];
        Thread[] workers = new Thread[actualThreads];

        for (int t = 0; t < actualThreads; t++) {
            final int startRow = sliceStart(t, actualThreads, width);
            final int endRow   = sliceStart(t + 1, actualThreads, width);
            final int[] local  = partials[t];
            workers[t] = new Thread(() -> {
                for (int x = startRow; x < endRow; x++) {
                    Color[] column = image[x];
                    for (int y = 0; y < column.length; y++) {
                        Color p = column[y];
                        int lum = HistogramUtils.computeLuminosity(
                                p.getRed(), p.getGreen(), p.getBlue());
                        local[lum]++;
                    }
                }
            }, "HistWorker-" + t);
            workers[t].start();
        }
        joinAll(workers);

        // Merge partial histograms (sequential, 256·actualThreads adds).
        int[] merged = new int[HistogramUtils.HISTOGRAM_BINS];
        for (int[] partial : partials) {
            for (int i = 0; i < HistogramUtils.HISTOGRAM_BINS; i++) {
                merged[i] += partial[i];
            }
        }
        return merged;
    }

    // ── Stage 2: Cumulative histogram (sequential — prefix sum) ────
    protected int[] computeCumulativeHistogram(int[] histogram) {
        int[] cumulative = new int[HistogramUtils.HISTOGRAM_BINS];
        cumulative[0] = histogram[0];
        for (int i = 1; i < HistogramUtils.HISTOGRAM_BINS; i++) {
            cumulative[i] = cumulative[i - 1] + histogram[i];
        }
        return cumulative;
    }

    // ── Stage 3: Parallel pixel rewriting ──────────────────────────
    /**
     * Spawns up to {@code numThreads} threads; each rewrites its row slice
     * using the cumulative histogram lookup. Output slices are disjoint, so
     * no synchronization is needed on the result array.
     */
    protected Color[][] applyEqualizationParallel(Color[][] image,
                                                  int[] cumulativeHist,
                                                  int totalPixels) {
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

        int actualThreads = Math.min(numThreads, width);
        Thread[] workers = new Thread[actualThreads];

        for (int t = 0; t < actualThreads; t++) {
            final int startRow = sliceStart(t, actualThreads, width);
            final int endRow   = sliceStart(t + 1, actualThreads, width);
            workers[t] = new Thread(() -> {
                for (int x = startRow; x < endRow; x++) {
                    Color[] srcCol = image[x];
                    Color[] dstCol = out[x];
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
            }, "EqWorker-" + t);
            workers[t].start();
        }
        joinAll(workers);
        return out;
    }

    // ── Helpers ────────────────────────────────────────────────────

    /**
     * Computes the inclusive start index of slice {@code i} when {@code total}
     * rows are split as evenly as possible across {@code parts} threads.
     * Guarantees every row is assigned to exactly one slice.
     */
    private static int sliceStart(int i, int parts, int total) {
        return (int) (((long) total * i) / parts);
    }

    /**
     * Joins every worker, restoring the interrupt flag and rethrowing as a
     * {@link RuntimeException} on interruption (no checked exceptions on the
     * {@link ImageProcessor#process(Color[][])} contract).
     */
    private static void joinAll(Thread[] workers) {
        try {
            for (Thread w : workers) {
                w.join();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while joining worker threads", e);
        }
    }
}
