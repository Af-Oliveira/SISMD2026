package pt.isep.sismd.histogram;

import java.awt.Color;
import java.io.IOException;
import java.util.Scanner;

/**
 * Entry point for the Histogram Equalizer application.
 *
 * <p>Prompts the user for an image file path, then applies histogram
 * equalization through the {@link ImageProcessor} strategy interface and
 * writes the result to {@code output/output.jpg}.
 *
 * <p>The default processor is {@link SequentialHistogramEqualizer}; future
 * issues will plug in concurrent implementations behind the same interface.
 *
 * @version 1.2  (Issue #2 — uses ImageProcessor strategy interface)
 */
public class ApplyFilters {

    public static void main(String[] args) throws IOException {
        Scanner input = new Scanner(System.in);
        System.out.println("Insert the name of the file path you would like to use.");
        String filePath = input.nextLine();
        input.close();

        Color[][] source = Utils.loadImage(filePath);

        ImageProcessor processor = new SequentialHistogramEqualizer();
        Color[][] result = processor.process(source);

        Utils.writeImage(result, "output/output.jpg");
        System.out.println("Wrote equalized image to output/output.jpg");
    }
}
