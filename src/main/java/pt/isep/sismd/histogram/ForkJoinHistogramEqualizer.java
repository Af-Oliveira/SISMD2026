package pt.isep.sismd.histogram;

import java.awt.Color;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.RecursiveTask;

/**
 * Histogram equalization using the Fork/Join framework
 * (Issue #6 — PDF §4 "Fork/Join Framework Solution").
 *
 * <p>The image rows are recursively split into halves until each leaf task
 * spans at most {@code threshold} rows, at which point the work is performed
 * inline. Results bubble up via {@link RecursiveTask#join()} and are merged
 * with the freshly computed sibling — the textbook fork/join pattern.
 *
 * <h2>Parallelization strategy</h2>
 * <ol>
 *   <li><b>Stage 1 — histogram.</b> {@link HistogramTask} (a
 *       {@link RecursiveTask}{@code <int[]>}) recursively splits row ranges
 *       until {@code rows ≤ threshold}, then computes the partial
 *       {@code int[256]} for that slice. Parents fork one half, compute the
 *       other half, join the forked half, and merge the two partials.</li>
 *   <li><b>Stage 2 — cumulative histogram.</b> Sequential prefix sum on the
 *       caller (256 adds — recursive overhead would dominate).</li>
 *   <li><b>Stage 3 — pixel rewriting.</b> {@link EqualizationTask} (a
 *       {@link RecursiveAction}) splits the same way; each leaf writes only
 *       its disjoint slice of the output, so no synchronization is needed.</li>
 * </ol>
 *
 * <p>Uses {@link ForkJoinPool#commonPool()} — no explicit pool lifecycle,
 * which is the principal advantage of fork/join over a custom executor
 * (Issue #5). Output is pixel-identical to {@link SequentialHistogramEqualizer}.
 *
 * @version 1.0  (Issue #6 — Fork/Join framework)
 */
public class ForkJoinHistogramEqualizer implements ImageProcessor {

    /** Default leaf-size threshold (rows per leaf task). */
    public static final int DEFAULT_THRESHOLD = 50;

    private final int threshold;

    /** Uses the {@link #DEFAULT_THRESHOLD} (50 rows) as the leaf threshold. */
    public ForkJoinHistogramEqualizer() {
        this(DEFAULT_THRESHOLD);
    }

    /**
     * @param threshold leaf-size threshold in rows (must be &gt; 0). Smaller
     *                  values create more, finer tasks; larger values reduce
     *                  scheduling overhead at the cost of parallelism.
     */
    public ForkJoinHistogramEqualizer(int threshold) {
        if (threshold <= 0) {
            throw new IllegalArgumentException("threshold must be > 0 (got " + threshold + ")");
        }
        this.threshold = threshold;
    }

    @Override
    public Color[][] process(Color[][] sourceImage) {
        if (sourceImage == null || sourceImage.length == 0 || sourceImage[0].length == 0) {
            throw new IllegalArgumentException("sourceImage must be a non-empty 2-D matrix");
        }
        int width  = sourceImage.length;
        int height = sourceImage[0].length;
        int totalPixels = width * height;

        ForkJoinPool pool = ForkJoinPool.commonPool();

        // ── Stage 1 ────────────────────────────────────────────
        int[] hist = pool.invoke(new HistogramTask(sourceImage, 0, width, threshold));

        // ── Stage 2 ────────────────────────────────────────────
        int[] cumulative = computeCumulativeHistogram(hist);

        // ── Stage 3 ────────────────────────────────────────────
        int cdfMin = 0;
        for (int i = 0; i < HistogramUtils.HISTOGRAM_BINS; i++) {
            if (cumulative[i] != 0) {
                cdfMin = cumulative[i];
                break;
            }
        }
        int denom = totalPixels - cdfMin;
        boolean degenerate = (denom == 0);
        Color[][] out = new Color[width][height];
        pool.invoke(new EqualizationTask(
                sourceImage, out, cumulative, denom, degenerate, 0, width, threshold));
        return out;
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

    // ── RecursiveTask for Stage 1: histogram computation ───────────
    /**
     * Recursive {@code int[256]}-producing task. Splits the row range in
     * half until {@code endRow - startRow ≤ threshold}, then computes a
     * partial histogram for the slice. Parents merge their two children.
     */
    static final class HistogramTask extends RecursiveTask<int[]> {

        private static final long serialVersionUID = 1L;

        private final Color[][] image;
        private final int startRow, endRow, threshold;

        HistogramTask(Color[][] image, int startRow, int endRow, int threshold) {
            this.image = image;
            this.startRow = startRow;
            this.endRow = endRow;
            this.threshold = threshold;
        }

        @Override
        protected int[] compute() {
            int rows = endRow - startRow;
            if (rows <= threshold) {
                return computeLeaf();
            }
            int mid = startRow + (rows >>> 1);
            HistogramTask left  = new HistogramTask(image, startRow, mid, threshold);
            HistogramTask right = new HistogramTask(image, mid, endRow, threshold);
            left.fork();              // schedule left asynchronously
            int[] rightHist = right.compute();   // run right inline
            int[] leftHist  = left.join();       // wait for left
            return mergeHistograms(leftHist, rightHist);
        }

        private int[] computeLeaf() {
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
    }

    // ── RecursiveAction for Stage 3: pixel rewriting ───────────────
    /**
     * Recursive void task. Splits the row range in half until each leaf
     * spans at most {@code threshold} rows, then rewrites that slice of the
     * output array. Slices are disjoint, so no synchronization is needed.
     */
    static final class EqualizationTask extends RecursiveAction {

        private static final long serialVersionUID = 1L;

        private final Color[][] source;
        private final Color[][] output;
        private final int[] cumulativeHist;
        private final int denom;
        private final boolean degenerate;
        private final int startRow, endRow, threshold;

        EqualizationTask(Color[][] source, Color[][] output, int[] cumulativeHist,
                         int denom, boolean degenerate,
                         int startRow, int endRow, int threshold) {
            this.source = source;
            this.output = output;
            this.cumulativeHist = cumulativeHist;
            this.denom = denom;
            this.degenerate = degenerate;
            this.startRow = startRow;
            this.endRow = endRow;
            this.threshold = threshold;
        }

        @Override
        protected void compute() {
            int rows = endRow - startRow;
            if (rows <= threshold) {
                computeLeaf();
                return;
            }
            int mid = startRow + (rows >>> 1);
            EqualizationTask left  = new EqualizationTask(
                    source, output, cumulativeHist, denom, degenerate,
                    startRow, mid, threshold);
            EqualizationTask right = new EqualizationTask(
                    source, output, cumulativeHist, denom, degenerate,
                    mid, endRow, threshold);
            invokeAll(left, right);   // standard fork/join idiom for void tasks
        }

        private void computeLeaf() {
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
    }

    // ── Helpers ────────────────────────────────────────────────────

    /**
     * Element-wise sum of two 256-bin histograms. Reuses {@code a} as the
     * accumulator to avoid an extra allocation per merge.
     */
    static int[] mergeHistograms(int[] a, int[] b) {
        for (int i = 0; i < HistogramUtils.HISTOGRAM_BINS; i++) {
            a[i] += b[i];
        }
        return a;
    }
}
