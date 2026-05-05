package pt.isep.sismd.histogram;

/**
 * Shared, stateless helper methods used by every {@link ImageProcessor}
 * implementation.
 *
 * <p>Currently exposes the perceived-luminosity formula. Kept separate from
 * {@link Utils} (which is the original instructor-supplied I/O helper) so the
 * legacy file remains untouched.
 *
 * @version 1.0  (Issue #2 — shared luminosity helper)
 */
public final class HistogramUtils {

    /** Number of distinct luminosity buckets ([0, 255]). */
    public static final int HISTOGRAM_BINS = 256;

    private HistogramUtils() {
        // Utility class — no instances.
    }

    /**
     * Computes the perceived luminosity of an RGB pixel using the standard
     * Rec. 601 weighting: {@code 0.299·R + 0.587·G + 0.114·B}.
     *
     * @param r red   component, [0, 255]
     * @param g green component, [0, 255]
     * @param b blue  component, [0, 255]
     * @return luminosity in the range [0, 255]
     */
    public static int computeLuminosity(int r, int g, int b) {
        return (int) Math.round(0.299 * r + 0.587 * g + 0.114 * b);
    }
}
