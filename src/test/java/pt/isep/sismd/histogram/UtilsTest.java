package pt.isep.sismd.histogram;

import org.junit.jupiter.api.Test;
import java.awt.Color;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke tests for {@link Utils} to verify the Maven layout and JUnit 5
 * wiring work correctly.
 *
 * <p>Full correctness tests will be added in Issue #3 once the
 * {@code ImageProcessor} interface and {@code SequentialHistogramEqualizer}
 * are introduced (Issue #2).
 */
class UtilsTest {

    @Test
    void copyImage_returnsSameDimensions() {
        // Arrange
        Color[][] original = new Color[10][20];
        for (int x = 0; x < 10; x++)
            for (int y = 0; y < 20; y++)
                original[x][y] = new Color(x * 25, y * 12, 0);

        // Act
        Color[][] copy = Utils.copyImage(original);

        // Assert
        assertEquals(original.length,    copy.length,       "Width must match");
        assertEquals(original[0].length, copy[0].length,    "Height must match");
    }

    @Test
    void copyImage_doesNotShareReferences() {
        // Arrange
        Color[][] original = new Color[2][2];
        for (int x = 0; x < 2; x++)
            for (int y = 0; y < 2; y++)
                original[x][y] = new Color(100, 100, 100);

        // Act
        Color[][] copy = Utils.copyImage(original);
        // Mutate the copy
        copy[0][0] = new Color(0, 0, 0);

        // Assert – original is unchanged
        assertEquals(new Color(100, 100, 100), original[0][0],
                "Mutating the copy must not affect the original array");
    }

    @Test
    void copyImage_preservesPixelValues() {
        // Arrange
        Color red   = new Color(255, 0, 0);
        Color green = new Color(0, 255, 0);
        Color[][] original = new Color[][]{{red, green}};

        // Act
        Color[][] copy = Utils.copyImage(original);

        // Assert
        assertEquals(red.getRGB(),   copy[0][0].getRGB(), "Red pixel must be preserved");
        assertEquals(green.getRGB(), copy[0][1].getRGB(), "Green pixel must be preserved");
    }
}
