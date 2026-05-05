package pt.isep.sismd.histogram;

import org.junit.jupiter.api.Test;
import java.awt.Color;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TestImageFactory} (Issue #3).
 */
class TestImageFactoryTest {

    @Test
    void createUniform_fillsEveryPixelWithSameColor() {
        Color c = new Color(123, 45, 67);

        Color[][] img = TestImageFactory.createUniform(8, 5, c);

        assertEquals(8, img.length);
        assertEquals(5, img[0].length);
        for (int x = 0; x < 8; x++)
            for (int y = 0; y < 5; y++)
                assertEquals(c.getRGB(), img[x][y].getRGB());
    }

    @Test
    void createRandom_isReproducibleAcrossCallsWithSameSeed() {
        Color[][] a = TestImageFactory.createRandom(20, 20, 1234L);
        Color[][] b = TestImageFactory.createRandom(20, 20, 1234L);

        for (int x = 0; x < 20; x++)
            for (int y = 0; y < 20; y++)
                assertEquals(a[x][y].getRGB(), b[x][y].getRGB(),
                        "Same seed must yield identical pixel at (" + x + "," + y + ")");
    }

    @Test
    void createRandom_differentSeedsProduceDifferentImages() {
        Color[][] a = TestImageFactory.createRandom(20, 20, 1L);
        Color[][] b = TestImageFactory.createRandom(20, 20, 2L);

        boolean differs = false;
        for (int x = 0; x < 20 && !differs; x++)
            for (int y = 0; y < 20 && !differs; y++)
                if (a[x][y].getRGB() != b[x][y].getRGB()) differs = true;

        assertTrue(differs, "Different seeds should produce different images");
    }

    @Test
    void createGradient_hasRequestedDimensions() {
        Color[][] img = TestImageFactory.createGradient(30, 20);

        assertEquals(30, img.length);
        assertEquals(20, img[0].length);
    }

    @Test
    void presetSizes_haveExpectedDimensions() {
        assertEquals(10,   TestImageFactory.createSmall().length);
        assertEquals(10,   TestImageFactory.createSmall()[0].length);
        assertEquals(500,  TestImageFactory.createMedium().length);
        assertEquals(500,  TestImageFactory.createMedium()[0].length);
        // Large is allocated lazily inside the test only when needed —
        // assert dimensions but skip pixel iteration to keep this test fast.
        Color[][] large = TestImageFactory.createLarge();
        assertEquals(2000, large.length);
        assertEquals(2000, large[0].length);
    }

    @Test
    void invalidDimensions_throwIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> TestImageFactory.createRandom(0, 10, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> TestImageFactory.createUniform(10, -1, Color.BLACK));
        assertThrows(IllegalArgumentException.class,
                () -> TestImageFactory.createGradient(-5, 5));
    }
}
