package pt.isep.sismd.histogram;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.awt.Color;

import static org.junit.jupiter.api.Assertions.*;
import static pt.isep.sismd.histogram.ImageAssertions.assertImageEquals;

/**
 * Unit tests for {@link ThreadPoolHistogramEqualizer} (Issue #5).
 *
 * <p>Cross-implementation equality is also covered by
 * {@link HistogramEqualizationCorrectnessTest}; the tests below add
 * pool-size parameterization, single-pixel handling, and a thread-leak
 * check that verifies the pool is always shut down.
 */
class ThreadPoolHistogramEqualizerTest {

    @Test
    void process_matchesSequentialBaseline() {
        Color[][] input = TestImageFactory.createMedium();
        Color[][] expected = new SequentialHistogramEqualizer().process(Utils.copyImage(input));
        ImageProcessor processor = new ThreadPoolHistogramEqualizer();

        Color[][] actual = processor.process(Utils.copyImage(input));

        assertImageEquals(expected, actual);
    }

    @ParameterizedTest(name = "poolSize={0}")
    @ValueSource(ints = {1, 2, 4, 8, 16})
    void process_consistentAcrossPoolSizes(int poolSize) {
        Color[][] input = TestImageFactory.createSmall();
        Color[][] expected = new SequentialHistogramEqualizer().process(Utils.copyImage(input));

        Color[][] actual = new ThreadPoolHistogramEqualizer(poolSize)
                .process(Utils.copyImage(input));

        assertImageEquals(expected, actual);
    }

    @Test
    void process_shouldHandleSinglePixelImage() {
        Color[][] input = new Color[][]{{new Color(128, 64, 32)}};
        ImageProcessor processor = new ThreadPoolHistogramEqualizer(4);

        Color[][] result = processor.process(input);

        assertNotNull(result);
        assertEquals(1, result.length);
        assertEquals(1, result[0].length);
    }

    @Test
    void process_shouldHandleMoreThreadsThanRows() {
        Color[][] input = TestImageFactory.createRandom(3, 50, 7L);
        Color[][] expected = new SequentialHistogramEqualizer().process(Utils.copyImage(input));

        Color[][] actual = new ThreadPoolHistogramEqualizer(16)
                .process(Utils.copyImage(input));

        assertImageEquals(expected, actual);
    }

    @Test
    void constructor_rejectsNonPositiveSizes() {
        assertThrows(IllegalArgumentException.class,
                () -> new ThreadPoolHistogramEqualizer(0));
        assertThrows(IllegalArgumentException.class,
                () -> new ThreadPoolHistogramEqualizer(-3));
    }

    @Test
    void process_doesNotMutateSourceImage() {
        Color[][] input = TestImageFactory.createRandom(64, 64, 99L);
        Color[][] snapshot = Utils.copyImage(input);
        new ThreadPoolHistogramEqualizer(4).process(input);

        for (int x = 0; x < input.length; x++)
            for (int y = 0; y < input[x].length; y++)
                assertEquals(snapshot[x][y].getRGB(), input[x][y].getRGB(),
                        "Source mutated at (" + x + "," + y + ")");
    }

    /**
     * After many invocations the worker threads spawned by the internal
     * pools must have all terminated — i.e. the pool is correctly
     * shut down each time. Asserts no lingering "pool-*" daemon threads.
     */
    @Test
    void process_threadPoolShutsDown_noLeakAfterRepeatedInvocations() throws InterruptedException {
        Color[][] input = TestImageFactory.createSmall();
        ThreadPoolHistogramEqualizer processor = new ThreadPoolHistogramEqualizer(4);

        for (int i = 0; i < 10; i++) {
            processor.process(Utils.copyImage(input));
        }
        // Give the JVM a moment to clear terminated threads from the root group.
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
                "Thread pool leaked workers — found " + lingering + " alive 'pool-*' threads");
    }

    // ── Concurrency stress test ─────────────────────────────────
    @Test
    void process_noRaceConditions_repeatedExecution() {
        Color[][] input = TestImageFactory.createMedium();
        Color[][] expected = new SequentialHistogramEqualizer().process(Utils.copyImage(input));
        ThreadPoolHistogramEqualizer processor = new ThreadPoolHistogramEqualizer(8);

        for (int i = 0; i < 20; i++) {
            Color[][] actual = processor.process(Utils.copyImage(input));
            assertImageEquals(expected, actual);
        }
    }
}
