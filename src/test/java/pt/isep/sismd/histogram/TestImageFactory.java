package pt.isep.sismd.histogram;

import java.awt.Color;
import java.util.Random;

/**
 * Factory for reproducible test images used by the correctness and
 * benchmark suites (Issue #3).
 *
 * <p>All factory methods return a freshly allocated {@code Color[][]} with
 * dimensions {@code [width][height]} so tests can mutate the result safely.
 *
 * <p>Random images use a {@link Random} seeded with the provided {@code seed}
 * so that runs are deterministic across machines and JVM versions.
 */
public final class TestImageFactory {

    private TestImageFactory() {
        // Utility class — no instances.
    }

    /**
     * Creates a uniform image where every pixel has the same {@code Color}.
     *
     * @param width  image width  (&gt; 0)
     * @param height image height (&gt; 0)
     * @param color  pixel value to fill the matrix with
     * @return a {@code [width][height]} matrix of {@code color}
     */
    public static Color[][] createUniform(int width, int height, Color color) {
        validateDimensions(width, height);
        if (color == null) {
            throw new IllegalArgumentException("color must not be null");
        }
        Color[][] img = new Color[width][height];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                img[x][y] = color;
            }
        }
        return img;
    }

    /**
     * Creates a smooth diagonal RGB gradient — useful for histogram tests
     * because it produces a near-uniform luminosity distribution.
     *
     * @param width  image width  (&gt; 0)
     * @param height image height (&gt; 0)
     * @return a {@code [width][height]} matrix with a deterministic gradient
     */
    public static Color[][] createGradient(int width, int height) {
        validateDimensions(width, height);
        Color[][] img = new Color[width][height];
        int maxIndex = Math.max(1, width + height - 2);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int v = (int) Math.round(255.0 * (x + y) / maxIndex);
                img[x][y] = new Color(v, (v + 64) & 0xFF, (v + 128) & 0xFF);
            }
        }
        return img;
    }

    /**
     * Creates a deterministic pseudo-random image.
     *
     * @param width  image width  (&gt; 0)
     * @param height image height (&gt; 0)
     * @param seed   RNG seed — same seed always yields the same image
     * @return a {@code [width][height]} matrix of random colours
     */
    public static Color[][] createRandom(int width, int height, long seed) {
        validateDimensions(width, height);
        Random rng = new Random(seed);
        Color[][] img = new Color[width][height];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                img[x][y] = new Color(rng.nextInt(256), rng.nextInt(256), rng.nextInt(256));
            }
        }
        return img;
    }

    /** Small (10×10) random image — fast smoke tests. */
    public static Color[][] createSmall() {
        return createRandom(10, 10, 42L);
    }

    /** Medium (500×500) random image — default correctness test size. */
    public static Color[][] createMedium() {
        return createRandom(500, 500, 42L);
    }

    /** Large (2000×2000) random image — stress test size. */
    public static Color[][] createLarge() {
        return createRandom(2000, 2000, 42L);
    }

    private static void validateDimensions(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "width and height must be > 0 (got " + width + "×" + height + ")");
        }
    }
}
