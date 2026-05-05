package pt.isep.sismd.histogram;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.awt.Color;

import static org.junit.jupiter.api.Assertions.*;
import static pt.isep.sismd.histogram.ImageAssertions.assertImageEquals;

/**
 * Unit tests for {@link CompletableFutureHistogramEqualizer} (Issue #7).
 *
 * <p>Cross-implementation pixel equality is also covered globally by
 * {@link HistogramEqualizationCorrectnessTest}; the tests below add
 * partition-count parameterization, direct exercise of
 * {@code mergeHistograms}, a non-blocking pipeline check, and a
 * race-condition stress loop.
 */
class CompletableFutureHistogramEqualizerTest {

    @Test
    void process_matchesSequentialBaseline() {
        Color[][] input = TestImageFactory.createMedium();
        Color[][] expected = new SequentialHistogramEqualizer().process(Utils.copyImage(input));
        ImageProcessor processor = new CompletableFutureHistogramEqualizer();

        Color[][] actual = processor.process(Utils.copyImage(input));

        assertImageEquals(expected, actual);
    }

    @ParameterizedTest(name = "partitions={0}")
    @ValueSource(ints = {1, 2, 4, 8})
    void process_consistentAcrossPartitionCounts(int partitions) {
        Color[][] input = TestImageFactory.createSmall();
        Color[][] expected = new SequentialHistogramEqualizer().process(Utils.copyImage(input));

        Color[][] actual = new CompletableFutureHistogramEqualizer(partitions)
                .process(Utils.copyImage(input));

        assertImageEquals(expected, actual);
    }

    @Test
    void mergeHistograms_correctSum() {
        int[] a = new int[256];
        int[] b = new int[256];
        a[100] = 5;
        b[100] = 3;
        a[200] = 10;

        int[] merged = CompletableFutureHistogramEqualizer.mergeHistograms(a, b);

        assertEquals(8,  merged[100]);
        assertEquals(10, merged[200]);
    }

    @Test
    void mergeHistograms_doesNotMutateInputs() {
        int[] a = new int[256];
        int[] b = new int[256];
        a[42] = 7;
        b[42] = 1;
        int aBefore = a[42];
        int bBefore = b[42];

        CompletableFutureHistogramEqualizer.mergeHistograms(a, b);

        assertEquals(aBefore, a[42], "Left input must not be mutated");
        assertEquals(bBefore, b[42], "Right input must not be mutated");
    }

    @Test
    void process_pipelineCompletesWithoutBlocking() {
        Color[][] input = TestImageFactory.createSmall();
        CompletableFutureHistogramEqualizer processor = new CompletableFutureHistogramEqualizer();

        Color[][] result = processor.process(input);

        assertNotNull(result);
        assertEquals(input.length,    result.length);
        assertEquals(input[0].length, result[0].length);
    }

    @Test
    void process_shouldHandleSinglePixelImage() {
        Color[][] input = new Color[][]{{new Color(128, 64, 32)}};
        ImageProcessor processor = new CompletableFutureHistogramEqualizer(4);

        Color[][] result = processor.process(input);

        assertNotNull(result);
        assertEquals(1, result.length);
        assertEquals(1, result[0].length);
    }

    @Test
    void process_shouldHandleMorePartitionsThanRows() {
        Color[][] input = TestImageFactory.createRandom(3, 50, 7L);
        Color[][] expected = new SequentialHistogramEqualizer().process(Utils.copyImage(input));

        Color[][] actual = new CompletableFutureHistogramEqualizer(16)
                .process(Utils.copyImage(input));

        assertImageEquals(expected, actual);
    }

    @Test
    void constructor_rejectsNonPositivePartitionCounts() {
        assertThrows(IllegalArgumentException.class,
                () -> new CompletableFutureHistogramEqualizer(0));
        assertThrows(IllegalArgumentException.class,
                () -> new CompletableFutureHistogramEqualizer(-2));
    }

    @Test
    void process_doesNotMutateSourceImage() {
        Color[][] input = TestImageFactory.createRandom(64, 64, 99L);
        Color[][] snapshot = Utils.copyImage(input);
        new CompletableFutureHistogramEqualizer(4).process(input);

        for (int x = 0; x < input.length; x++)
            for (int y = 0; y < input[x].length; y++)
                assertEquals(snapshot[x][y].getRGB(), input[x][y].getRGB(),
                        "Source mutated at (" + x + "," + y + ")");
    }

    /**
     * After many invocations the worker threads spawned by the internal
     * executors must have all terminated — i.e. each pipeline shuts its
     * executor down deterministically.
     */
    @Test
    void process_executorShutsDown_noLeakAfterRepeatedInvocations() throws InterruptedException {
        Color[][] input = TestImageFactory.createSmall();
        CompletableFutureHistogramEqualizer processor = new CompletableFutureHistogramEqualizer(4);

        for (int i = 0; i < 10; i++) {
            processor.process(Utils.copyImage(input));
        }
        Thread.sleep(200);

        Thread[] all = new Thread[Thread.activeCount() * 2];
        int n = Thread.enumerate(all);
        long lingering = 0;
        for (int i = 0; i < n; i++) {
            Thread t = all[i];
            if (t != null && t.getName().startsWith("pool-") && t.isAlive()) {
                lingering++;
            }
        }
        assertEquals(0L, lingering,
                "Executor leaked workers — found " + lingering + " alive 'pool-*' threads");
    }

    // ── Concurrency stress test ─────────────────────────────────
    @Test
    void process_noRaceConditions_repeatedExecution() {
        Color[][] input = TestImageFactory.createMedium();
        Color[][] expected = new SequentialHistogramEqualizer().process(Utils.copyImage(input));
        CompletableFutureHistogramEqualizer processor = new CompletableFutureHistogramEqualizer(8);

        for (int i = 0; i < 20; i++) {
            Color[][] actual = processor.process(Utils.copyImage(input));
            assertImageEquals(expected, actual);
        }
    }
}
