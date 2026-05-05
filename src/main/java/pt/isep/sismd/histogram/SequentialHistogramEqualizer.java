package pt.isep.sismd.histogram;

import java.awt.Color;

/**
 * Sequential baseline implementation of histogram equalization.
 *
 * <p>Algorithm (extracted from the original {@code Filters.HistogramFilter}):
 * <ol>
 *   <li>Compute the luminosity histogram over all pixels.</li>
 *   <li>Compute the cumulative histogram (prefix sum) and find {@code cdfMin}.</li>
 *   <li>Remap each pixel using {@code newLum = round(255 · cdf[lum] /
 *       (totalPixels − cdfMin))} and write it back as a grayscale colour.</li>
 * </ol>
 *
 * <p>The class is the reference against which every concurrent implementation
 * is compared (see Issue #3 — golden test).
 *
 * @version 1.0  (Issue #2 — sequential refactor)
 */
public class SequentialHistogramEqualizer implements ImageProcessor {

    @Override
    public Color[][] process(Color[][] sourceImage) {
        if (sourceImage == null || sourceImage.length == 0 || sourceImage[0].length == 0) {
            throw new IllegalArgumentException("sourceImage must be a non-empty 2-D matrix");
        }
        int width  = sourceImage.length;
        int height = sourceImage[0].length;
        int totalPixels = width * height;

        int[] hist       = computeHistogram(sourceImage);
        int[] cumulative = computeCumulativeHistogram(hist);

        return applyEqualization(sourceImage, cumulative, totalPixels);
    }

    // ── Stage 1: Compute luminosity histogram ──────────────────────
    /**
     * Counts how many pixels fall in each luminosity bucket [0, 255].
     *
     * @param image source pixel matrix
     * @return a 256-entry histogram; entry {@code i} = number of pixels with
     *         luminosity {@code i}
     */
    protected int[] computeHistogram(Color[][] image) {
        int[] hist = new int[HistogramUtils.HISTOGRAM_BINS];
        for (int x = 0; x < image.length; x++) {
            Color[] column = image[x];
            for (int y = 0; y < column.length; y++) {
                Color pixel = column[y];
                int lum = HistogramUtils.computeLuminosity(
                        pixel.getRed(), pixel.getGreen(), pixel.getBlue());
                hist[lum]++;
            }
        }
        return hist;
    }

    // ── Stage 2: Compute cumulative histogram ──────────────────────
    /**
     * Builds the cumulative distribution function as a prefix sum of the
     * histogram.
     *
     * @param histogram a 256-entry histogram
     * @return a 256-entry cumulative histogram (CDF, unscaled)
     */
    protected int[] computeCumulativeHistogram(int[] histogram) {
        int[] cumulative = new int[HistogramUtils.HISTOGRAM_BINS];
        cumulative[0] = histogram[0];
        for (int i = 1; i < HistogramUtils.HISTOGRAM_BINS; i++) {
            cumulative[i] = cumulative[i - 1] + histogram[i];
        }
        return cumulative;
    }

    // ── Stage 3: Apply equalization to pixels ──────────────────────
    /**
     * Rewrites each pixel of {@code image} using the equalization formula and
     * returns a new grayscale matrix. The source matrix is <em>not</em>
     * modified.
     *
     * @param image          source pixel matrix
     * @param cumulativeHist cumulative histogram from {@link #computeCumulativeHistogram(int[])}
     * @param totalPixels    {@code image.length · image[0].length}
     * @return a new {@code Color[][]} with equalized grayscale pixels
     */
    protected Color[][] applyEqualization(Color[][] image, int[] cumulativeHist, int totalPixels) {
        int cdfMin = 0;
        for (int i = 0; i < HistogramUtils.HISTOGRAM_BINS; i++) {
            if (cumulativeHist[i] != 0) {
                cdfMin = cumulativeHist[i];
                break;
            }
        }
        int denom = totalPixels - cdfMin;
        // When all pixels share the same luminosity (totalPixels == cdfMin),
        // the equalization formula is undefined — output a flat black image
        // (matches the legacy behaviour of producing a constant grayscale).
        boolean degenerate = (denom == 0);

        int width  = image.length;
        int height = image[0].length;
        Color[][] out = new Color[width][height];
        for (int x = 0; x < width; x++) {
            Color[] srcCol = image[x];
            Color[] dstCol = out[x];
            for (int y = 0; y < height; y++) {
                Color pixel = srcCol[y];
                int lum = HistogramUtils.computeLuminosity(
                        pixel.getRed(), pixel.getGreen(), pixel.getBlue());
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
        return out;
    }
}
