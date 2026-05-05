package pt.isep.sismd.histogram;

import org.junit.jupiter.api.Test;
import java.awt.Color;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SequentialHistogramEqualizer} (Issue #2).
 *
 * <p>The cross-implementation correctness suite is added in Issue #3.
 */
class SequentialHistogramEqualizerTest {

    @Test
    void process_shouldReturnGrayscaleImage() {
        Color[][] input = createTestImage();
        ImageProcessor processor = new SequentialHistogramEqualizer();

        Color[][] result = processor.process(input);

        for (int x = 0; x < result.length; x++) {
            for (int y = 0; y < result[x].length; y++) {
                assertEquals(result[x][y].getRed(),   result[x][y].getGreen());
                assertEquals(result[x][y].getGreen(), result[x][y].getBlue());
            }
        }
    }

    @Test
    void process_shouldNotMutateSourceImage() {
        Color[][] input = createTestImage();
        Color[][] inputCopy = Utils.copyImage(input);
        ImageProcessor processor = new SequentialHistogramEqualizer();

        processor.process(input);

        for (int x = 0; x < input.length; x++) {
            for (int y = 0; y < input[x].length; y++) {
                assertEquals(inputCopy[x][y].getRGB(), input[x][y].getRGB(),
                        "Source pixel mutated at (" + x + "," + y + ")");
            }
        }
    }

    @Test
    void process_outputDimensionsMatchInput() {
        Color[][] input = createTestImage();
        ImageProcessor processor = new SequentialHistogramEqualizer();

        Color[][] result = processor.process(input);

        assertEquals(input.length,    result.length);
        assertEquals(input[0].length, result[0].length);
    }

    @Test
    void computeHistogram_shouldSumToTotalPixels() {
        Color[][] input = createTestImage();
        SequentialHistogramEqualizer eq = new SequentialHistogramEqualizer();

        int[] hist = eq.computeHistogram(input);

        int sum = 0;
        for (int count : hist) sum += count;
        assertEquals(input.length * input[0].length, sum);
    }

    @Test
    void computeCumulativeHistogram_lastEntryEqualsTotalPixels() {
        int[] hist = new int[256];
        hist[100] = 5;
        hist[200] = 10;
        SequentialHistogramEqualizer eq = new SequentialHistogramEqualizer();

        int[] cum = eq.computeCumulativeHistogram(hist);

        assertEquals(15, cum[255]);
        assertEquals(5,  cum[100]);
        assertEquals(15, cum[200]);
    }

    @Test
    void process_shouldMatchLegacyFiltersAlgorithm() {
        // Verifies the refactored sequential implementation produces the same
        // pixel output as the original Filters.HistogramFilter for a known input.
        Color[][] input = createTestImage();
        Color[][] expected = legacyEqualize(Utils.copyImage(input));

        Color[][] actual = new SequentialHistogramEqualizer().process(input);

        for (int x = 0; x < expected.length; x++) {
            for (int y = 0; y < expected[x].length; y++) {
                assertEquals(expected[x][y].getRGB(), actual[x][y].getRGB(),
                        "Mismatch at (" + x + "," + y + ")");
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────

    private Color[][] createTestImage() {
        Color[][] img = new Color[64][48];
        for (int x = 0; x < 64; x++)
            for (int y = 0; y < 48; y++)
                img[x][y] = new Color((x * 4) % 256, (y * 5) % 256, (x + y) % 256);
        return img;
    }

    /** Reproduces the original {@code Filters.HistogramFilter} algorithm. */
    private Color[][] legacyEqualize(Color[][] tmp) {
        int total = tmp.length * tmp[0].length;
        int[] hist = new int[256];
        for (int i = 0; i < tmp.length; i++)
            for (int j = 0; j < tmp[i].length; j++) {
                Color p = tmp[i][j];
                hist[(int) Math.round(0.299 * p.getRed() + 0.587 * p.getGreen() + 0.114 * p.getBlue())]++;
            }
        int[] cum = new int[256];
        cum[0] = hist[0];
        for (int i = 1; i < 256; i++) cum[i] = cum[i - 1] + hist[i];
        int cdfMin = 0;
        for (int i = 0; i < 256; i++) if (cum[i] != 0) { cdfMin = cum[i]; break; }
        Color[][] out = new Color[tmp.length][tmp[0].length];
        for (int i = 0; i < tmp.length; i++)
            for (int j = 0; j < tmp[i].length; j++) {
                Color p = tmp[i][j];
                int lum = (int) Math.round(0.299 * p.getRed() + 0.587 * p.getGreen() + 0.114 * p.getBlue());
                double cdf = (double) cum[lum] / (double) (total - cdfMin);
                int newLum = (int) Math.round(255.0 * cdf);
                out[i][j] = new Color(newLum, newLum, newLum);
            }
        return out;
    }
}
