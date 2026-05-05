package pt.isep.sismd.histogram;

import java.awt.Color;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Shared image-equality assertions reused by every correctness and
 * concurrency test (Issue #3).
 */
final class ImageAssertions {

    private ImageAssertions() {
        // Utility class — no instances.
    }

    /**
     * Asserts that two pixel matrices have identical dimensions and identical
     * RGB values for every pixel. Fails fast with a {@code (x,y)} pinpoint on
     * the first mismatch.
     *
     * @param expected reference matrix (typically the sequential baseline)
     * @param actual   matrix produced by the implementation under test
     */
    static void assertImageEquals(Color[][] expected, Color[][] actual) {
        assertEquals(expected.length, actual.length, "Width mismatch");
        for (int x = 0; x < expected.length; x++) {
            assertEquals(expected[x].length, actual[x].length,
                    "Height mismatch at column " + x);
            for (int y = 0; y < expected[x].length; y++) {
                assertEquals(expected[x][y].getRGB(), actual[x][y].getRGB(),
                        String.format("Pixel mismatch at (%d,%d)", x, y));
            }
        }
    }
}
