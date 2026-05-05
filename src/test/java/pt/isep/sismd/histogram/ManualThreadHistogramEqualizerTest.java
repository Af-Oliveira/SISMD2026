package pt.isep.sismd.histogram;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.awt.Color;

import static org.junit.jupiter.api.Assertions.*;
import static pt.isep.sismd.histogram.ImageAssertions.assertImageEquals;

/**
 * Unit tests for {@link ManualThreadHistogramEqualizer} (Issue #4).
 *
 * <p>Cross-implementation pixel-equality is also covered globally by
 * {@link HistogramEqualizationCorrectnessTest}; the tests below add
 * thread-count parameterization, a single-pixel edge case, and a
 * race-condition stress loop.
 */
class ManualThreadHistogramEqualizerTest {

    @Test
    void process_matchesSequentialBaseline() {
        Color[][] input = TestImageFactory.createMedium();
        Color[][] expected = new SequentialHistogramEqualizer().process(Utils.copyImage(input));
        ImageProcessor processor = new ManualThreadHistogramEqualizer();

        Color[][] actual = processor.process(Utils.copyImage(input));

        assertImageEquals(expected, actual);
    }

    @ParameterizedTest(name = "numThreads={0}")
    @ValueSource(ints = {1, 2, 4, 8, 16})
    void process_consistentAcrossThreadCounts(int numThreads) {
        Color[][] input = TestImageFactory.createSmall();
        Color[][] expected = new SequentialHistogramEqualizer().process(Utils.copyImage(input));

        Color[][] actual = new ManualThreadHistogramEqualizer(numThreads)
                .process(Utils.copyImage(input));

        assertImageEquals(expected, actual);
    }

    @Test
    void computeHistogramParallel_shouldSumToTotalPixels() {
        Color[][] input = TestImageFactory.createSmall();
        ManualThreadHistogramEqualizer eq = new ManualThreadHistogramEqualizer(4);

        int[] hist = eq.computeHistogramParallel(input);

        int sum = 0;
        for (int count : hist) sum += count;
        assertEquals(input.length * input[0].length, sum);
    }

    @Test
    void process_shouldHandleSinglePixelImage() {
        Color[][] input = new Color[][]{{new Color(128, 64, 32)}};
        ImageProcessor processor = new ManualThreadHistogramEqualizer(2);

        Color[][] result = processor.process(input);

        assertNotNull(result);
        assertEquals(1, result.length);
        assertEquals(1, result[0].length);
    }

    @Test
    void process_shouldHandleMoreThreadsThanRows() {
        // 3-row image, 16 threads — must not crash and must match baseline.
        Color[][] input = TestImageFactory.createRandom(3, 50, 7L);
        Color[][] expected = new SequentialHistogramEqualizer().process(Utils.copyImage(input));

        Color[][] actual = new ManualThreadHistogramEqualizer(16)
                .process(Utils.copyImage(input));

        assertImageEquals(expected, actual);
    }

    @Test
    void constructor_rejectsNonPositiveThreadCounts() {
        assertThrows(IllegalArgumentException.class,
                () -> new ManualThreadHistogramEqualizer(0));
        assertThrows(IllegalArgumentException.class,
                () -> new ManualThreadHistogramEqualizer(-1));
    }

    @Test
    void process_doesNotMutateSourceImage() {
        Color[][] input = TestImageFactory.createRandom(64, 64, 99L);
        Color[][] snapshot = Utils.copyImage(input);
        new ManualThreadHistogramEqualizer(4).process(input);

        for (int x = 0; x < input.length; x++)
            for (int y = 0; y < input[x].length; y++)
                assertEquals(snapshot[x][y].getRGB(), input[x][y].getRGB(),
                        "Source mutated at (" + x + "," + y + ")");
    }

    // ── Concurrency stress test ─────────────────────────────────
    @Test
    void process_noRaceConditions_repeatedExecution() {
        Color[][] input = TestImageFactory.createMedium();
        Color[][] expected = new SequentialHistogramEqualizer().process(Utils.copyImage(input));
        ManualThreadHistogramEqualizer processor = new ManualThreadHistogramEqualizer(8);

        for (int i = 0; i < 20; i++) {
            Color[][] actual = processor.process(Utils.copyImage(input));
            assertImageEquals(expected, actual);
        }
    }
}
