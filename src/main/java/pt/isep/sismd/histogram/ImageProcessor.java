package pt.isep.sismd.histogram;

import java.awt.Color;

/**
 * Strategy interface for histogram equalization.
 *
 * <p>All implementations (sequential, manual threads, thread pool, fork/join,
 * {@code CompletableFuture}) implement this contract so they can be swapped
 * during benchmarking.
 *
 * @version 1.0  (Issue #2 — interface extraction)
 */
public interface ImageProcessor {

    /**
     * Applies histogram equalization to the source image and returns a new
     * image (grayscale) with enhanced contrast.
     *
     * <p>Implementations must <em>not</em> mutate the {@code sourceImage}
     * argument; they must return a freshly allocated pixel matrix.
     *
     * @param sourceImage the original pixel matrix (width × height)
     * @return a new {@code Color[][]} with equalized luminosity
     */
    Color[][] process(Color[][] sourceImage);
}
