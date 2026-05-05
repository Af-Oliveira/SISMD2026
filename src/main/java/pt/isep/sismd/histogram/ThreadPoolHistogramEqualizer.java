package pt.isep.sismd.histogram;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Histogram equalization using a fixed-size {@link ExecutorService}
 * (Issue #5 — PDF §3 "Multithreaded Solution With Thread Pools").
 *
 * <h2>Parallelization strategy</h2>
 * <ol>
 *   <li><b>Stage 1 — histogram (parallel).</b> One {@link Callable} per
 *       row-slice; each returns its <em>private</em> {@code int[256]} partial
 *       histogram. The main thread reduces all partials sequentially after
 *       collecting the futures (cheap: 256·N additions). This avoids the CAS
 *       contention an {@link java.util.concurrent.atomic.AtomicIntegerArray}
 *       would impose.</li>
 *   <li><b>Stage 2 — cumulative histogram (sequential).</b> Prefix sum on
 *       the main thread.</li>
 *   <li><b>Stage 3 — pixel rewriting (parallel).</b> One {@link Callable}
 *       per row-slice; each writes only into its own disjoint slice of the
 *       output matrix, so no synchronization is needed on the result.</li>
 * </ol>
 *
 * <h2>Pool lifecycle (acceptance criterion: no resource leaks)</h2>
 * The pool is created in {@link #process(Color[][])} and <em>always</em>
 * shut down in a {@code finally} block via {@code shutdown()} +
 * {@code awaitTermination()}, even if a worker throws or the calling thread
 * is interrupted. {@code shutdownNow()} is the fallback if termination times
 * out.
 *
 * <p>Output is pixel-identical to {@link SequentialHistogramEqualizer}.
 *
 * @version 1.0  (Issue #5 — fixed thread pool)
 */
public class ThreadPoolHistogramEqualizer implements ImageProcessor {

    /** Maximum time to wait for a clean pool shutdown before forcing it. */
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 30L;

    private final int poolSize;

    /** Uses {@code Runtime.getRuntime().availableProcessors()} threads. */
    public ThreadPoolHistogramEqualizer() {
        this(Runtime.getRuntime().availableProcessors());
    }

    /**
     * @param poolSize fixed pool size (must be &gt; 0)
     */
    public ThreadPoolHistogramEqualizer(int poolSize) {
        if (poolSize <= 0) {
            throw new IllegalArgumentException("poolSize must be > 0 (got " + poolSize + ")");
        }
        this.poolSize = poolSize;
    }

    @Override
    public Color[][] process(Color[][] sourceImage) {
        if (sourceImage == null || sourceImage.length == 0 || sourceImage[0].length == 0) {
            throw new IllegalArgumentException("sourceImage must be a non-empty 2-D matrix");
        }
        int width  = sourceImage.length;
        int height = sourceImage[0].length;
        int totalPixels = width * height;

        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        try {
            int[] hist       = computeHistogramParallel(sourceImage, pool);
            int[] cumulative = computeCumulativeHistogram(hist);
            return applyEqualizationParallel(sourceImage, cumulative, totalPixels, pool);
        } finally {
            shutdownAndAwait(pool);
        }
    }

    // ── Stage 1: Submit histogram tasks to pool ────────────────────
    /**
     * Splits the rows into {@code min(poolSize, width)} slices, submits one
     * {@link Callable} per slice that returns the slice's partial histogram,
     * and reduces all partials on the main thread.
     */
    protected int[] computeHistogramParallel(Color[][] image, ExecutorService pool) {
        int width = image.length;
        int slices = Math.min(poolSize, width);

        List<Future<int[]>> futures = new ArrayList<>(slices);
        for (int t = 0; t < slices; t++) {
            final int startRow = sliceStart(t, slices, width);
            final int endRow   = sliceStart(t + 1, slices, width);
            futures.add(pool.submit((Callable<int[]>) () -> {
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
            }));
        }

        int[] merged = new int[HistogramUtils.HISTOGRAM_BINS];
        for (Future<int[]> f : futures) {
            int[] partial = await(f);
            for (int i = 0; i < HistogramUtils.HISTOGRAM_BINS; i++) {
                merged[i] += partial[i];
            }
        }
        return merged;
    }

    // ── Stage 2: Cumulative histogram (sequential) ─────────────────
    protected int[] computeCumulativeHistogram(int[] histogram) {
        int[] cumulative = new int[HistogramUtils.HISTOGRAM_BINS];
        cumulative[0] = histogram[0];
        for (int i = 1; i < HistogramUtils.HISTOGRAM_BINS; i++) {
            cumulative[i] = cumulative[i - 1] + histogram[i];
        }
        return cumulative;
    }

    // ── Stage 3: Submit equalization tasks to pool ─────────────────
    /**
     * Splits the rows into {@code min(poolSize, width)} slices, submits one
     * {@link Callable} per slice that rewrites its disjoint slice of the
     * output, and waits for all of them.
     */
    protected Color[][] applyEqualizationParallel(Color[][] image,
                                                   int[] cumulativeHist,
                                                   int totalPixels,
                                                   ExecutorService pool) {
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

        int slices = Math.min(poolSize, width);
        List<Future<Void>> futures = new ArrayList<>(slices);
        for (int t = 0; t < slices; t++) {
            final int startRow = sliceStart(t, slices, width);
            final int endRow   = sliceStart(t + 1, slices, width);
            futures.add(pool.submit(() -> {
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
                return null;
            }));
        }

        for (Future<Void> f : futures) {
            await(f);
        }
        return out;
    }

    // ── Helpers ────────────────────────────────────────────────────

    /**
     * Computes the inclusive start index of slice {@code i} when {@code total}
     * rows are split as evenly as possible across {@code parts} workers.
     */
    private static int sliceStart(int i, int parts, int total) {
        return (int) (((long) total * i) / parts);
    }

    /**
     * Blocks on a {@link Future}, unwrapping checked exceptions into
     * {@link RuntimeException} so the {@link ImageProcessor} contract
     * remains unchecked.
     */
    private static <T> T await(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for pool task", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error er)            throw er;
            throw new RuntimeException("Pool task failed", cause);
        }
    }

    /**
     * Shuts the pool down deterministically: requests an orderly shutdown,
     * waits up to {@link #SHUTDOWN_TIMEOUT_SECONDS}, then forces termination
     * if any tasks remain. Always called from {@code finally} so the pool
     * never leaks, even on exceptional return.
     */
    private static void shutdownAndAwait(ExecutorService pool) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                if (!pool.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new RuntimeException("Thread pool did not terminate cleanly");
                }
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
