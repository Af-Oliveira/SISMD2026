package pt.isep.sismd.histogram;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.awt.Color;

import static org.junit.jupiter.api.Assertions.*;
import static pt.isep.sismd.histogram.ImageAssertions.assertImageEquals;

/**
 * Unit tests for {@link ForkJoinHistogramEqualizer} (Issue #6).
 *
 * <p>Cross-implementation pixel equality is also enforced globally by
 * {@link HistogramEqualizationCorrectnessTest}; the tests below add
 * threshold parameterization, a deep-recursion stress test, and direct
 * exercise of the {@code mergeHistograms} helper.
 */
class ForkJoinHistogramEqualizerTest {

    @Test
    void process_matchesSequentialBaseline() {
        Color[][] input = TestImageFactory.createMedium();
        Color[][] expected = new SequentialHistogramEqualizer().process(Utils.copyImage(input));
        ImageProcessor processor = new ForkJoinHistogramEqualizer();

        Color[][] actual = processor.process(Utils.copyImage(input));

        assertImageEquals(expected, actual);
    }

    @ParameterizedTest(name = "threshold={0}")
    @ValueSource(ints = {1, 10, 50, 100, 500})
    void process_consistentAcrossThresholds(int threshold) {
        Color[][] input = TestImageFactory.createMedium();
        Color[][] expected = new SequentialHistogramEqualizer().process(Utils.copyImage(input));

        Color[][] actual = new ForkJoinHistogramEqualizer(threshold)
                .process(Utils.copyImage(input));

        assertImageEquals(expected, actual);
    }

    @Test
    void histogramTask_correctMerge() {
        // threshold=2 forces multi-level recursion and merge calls.
        Color[][] input = TestImageFactory.createSmall();
        ForkJoinHistogramEqualizer eq = new ForkJoinHistogramEqualizer(2);

        Color[][] result = eq.process(input);

        assertNotNull(result);
        assertEquals(input.length,    result.length);
        assertEquals(input[0].length, result[0].length);
    }

    @Test
    void process_largeImage_smallThreshold_doesNotStackOverflow() {
        // 2000 rows / threshold=10 ≈ 200 leaves, depth ~8 — well within JVM
        // stack limits, but exercises the deep-recursion path end-to-end.
        Color[][] input = TestImageFactory.createLarge();
        ImageProcessor processor = new ForkJoinHistogramEqualizer(10);

        Color[][] result = processor.process(input);

        assertNotNull(result);
        assertEquals(input.length,    result.length);
        assertEquals(input[0].length, result[0].length);
    }

    @Test
    void process_shouldHandleSinglePixelImage() {
        Color[][] input = new Color[][]{{new Color(128, 64, 32)}};
        ImageProcessor processor = new ForkJoinHistogramEqualizer(1);

        Color[][] result = processor.process(input);

        assertNotNull(result);
        assertEquals(1, result.length);
        assertEquals(1, result[0].length);
    }

    @Test
    void constructor_rejectsNonPositiveThreshold() {
        assertThrows(IllegalArgumentException.class,
                () -> new ForkJoinHistogramEqualizer(0));
        assertThrows(IllegalArgumentException.class,
                () -> new ForkJoinHistogramEqualizer(-7));
    }

    @Test
    void process_doesNotMutateSourceImage() {
        Color[][] input = TestImageFactory.createRandom(64, 64, 99L);
        Color[][] snapshot = Utils.copyImage(input);
        new ForkJoinHistogramEqualizer(8).process(input);

        for (int x = 0; x < input.length; x++)
            for (int y = 0; y < input[x].length; y++)
                assertEquals(snapshot[x][y].getRGB(), input[x][y].getRGB(),
                        "Source mutated at (" + x + "," + y + ")");
    }

    @Test
    void mergeHistograms_isElementWiseSum() {
        int[] a = new int[256];
        int[] b = new int[256];
        a[10] = 3;  b[10] = 4;
        a[200] = 7; b[200] = 1;

        int[] merged = ForkJoinHistogramEqualizer.mergeHistograms(a, b);

        assertEquals(7, merged[10]);
        assertEquals(8, merged[200]);
    }

    // ── Concurrency stress test ─────────────────────────────────
    @Test
    void process_noRaceConditions_repeatedExecution() {
        Color[][] input = TestImageFactory.createMedium();
        Color[][] expected = new SequentialHistogramEqualizer().process(Utils.copyImage(input));
        ForkJoinHistogramEqualizer processor = new ForkJoinHistogramEqualizer();

        for (int i = 0; i < 20; i++) {
            Color[][] actual = processor.process(Utils.copyImage(input));
            assertImageEquals(expected, actual);
        }
    }
}
