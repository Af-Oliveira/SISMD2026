package pt.isep.sismd.histogram;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.awt.Color;
import java.util.stream.Stream;

import static pt.isep.sismd.histogram.ImageAssertions.assertImageEquals;

/**
 * Cross-implementation correctness suite (Issue #3).
 *
 * <p>Every {@link ImageProcessor} listed in {@link #implementations()} must
 * produce <strong>pixel-identical</strong> output to the
 * {@link SequentialHistogramEqualizer} baseline, for small, medium, large
 * random images and for a smooth gradient. If a parallel implementation
 * diverges by even one pixel, the corresponding test fails.
 *
 * <p>The five implementations registered in {@link #implementations()} cover
 * Issues #2 (sequential), #4 (manual threads), #5 (thread pool), #6
 * (fork/join) and #7 ({@link java.util.concurrent.CompletableFuture}).
 */
class HistogramEqualizationCorrectnessTest {

    /** Implementations under test (one entry per Issue #2 / #4 / #5 / #6 / #7). */
    static Stream<ImageProcessor> implementations() {
        return Stream.of(
                new SequentialHistogramEqualizer(),
                new ManualThreadHistogramEqualizer(),
                new ThreadPoolHistogramEqualizer(),
                new ForkJoinHistogramEqualizer(),
                new CompletableFutureHistogramEqualizer()
        );
    }

    @ParameterizedTest(name = "small/{0}")
    @MethodSource("implementations")
    void smallImage_shouldMatchSequentialBaseline(ImageProcessor impl) {
        Color[][] input = TestImageFactory.createSmall();
        Color[][] expected = new SequentialHistogramEqualizer().process(Utils.copyImage(input));

        Color[][] actual = impl.process(Utils.copyImage(input));

        assertImageEquals(expected, actual);
    }

    @ParameterizedTest(name = "medium/{0}")
    @MethodSource("implementations")
    void mediumImage_shouldMatchSequentialBaseline(ImageProcessor impl) {
        Color[][] input = TestImageFactory.createMedium();
        Color[][] expected = new SequentialHistogramEqualizer().process(Utils.copyImage(input));

        Color[][] actual = impl.process(Utils.copyImage(input));

        assertImageEquals(expected, actual);
    }

    @ParameterizedTest(name = "large/{0}")
    @MethodSource("implementations")
    void largeImage_shouldMatchSequentialBaseline(ImageProcessor impl) {
        Color[][] input = TestImageFactory.createLarge();
        Color[][] expected = new SequentialHistogramEqualizer().process(Utils.copyImage(input));

        Color[][] actual = impl.process(Utils.copyImage(input));

        assertImageEquals(expected, actual);
    }

    @ParameterizedTest(name = "gradient/{0}")
    @MethodSource("implementations")
    void gradientImage_shouldMatchSequentialBaseline(ImageProcessor impl) {
        Color[][] input = TestImageFactory.createGradient(256, 256);
        Color[][] expected = new SequentialHistogramEqualizer().process(Utils.copyImage(input));

        Color[][] actual = impl.process(Utils.copyImage(input));

        assertImageEquals(expected, actual);
    }
}
