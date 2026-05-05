package pt.isep.sismd.histogram;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Utility methods for image I/O and basic image operations.
 *
 * @author Jorge Coelho
 * @contact jmn@isep.ipp.pt
 * @version 1.1  (moved to pt.isep.sismd.histogram package — Issue #1)
 */
public class Utils {

    Utils() {}

    /**
     * Loads an image from the filesystem into a {@code Color} pixel matrix.
     *
     * @param filename path to the source image file
     * @return 2-D {@code Color} array with dimensions [width][height]
     */
    public static Color[][] loadImage(String filename) {
        BufferedImage buffImg = loadImageFile(filename);
        return convertTo2DFromBuffered(buffImg);
    }

    /**
     * Writes a {@code Color} pixel matrix to a JPEG file.
     *
     * @param image    the pixel matrix to write
     * @param filename destination file path (should end in {@code .jpg})
     */
    public static void writeImage(Color[][] image, String filename) {
        File outputfile = new File(filename);
        BufferedImage bufferedImage = matrixToBuffered(image);
        try {
            ImageIO.write(bufferedImage, "jpg", outputfile);
        } catch (IOException e) {
            System.out.println("Could not write image " + filename + " !");
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Creates a deep copy of a {@code Color} pixel matrix.
     * Useful to avoid mutating the original image during processing.
     *
     * @param image the source pixel matrix
     * @return a new matrix containing the same {@code Color} references
     */
    public static Color[][] copyImage(Color[][] image) {
        Color[][] copy = new Color[image.length][image[0].length];
        for (int i = 0; i < image.length; i++) {
            for (int j = 0; j < image[i].length; j++) {
                copy[i][j] = image[i][j];
            }
        }
        return copy;
    }

    // ── Private helpers ──────────────────────────────────────────

    /**
     * Reads an image file into a {@link BufferedImage}.
     *
     * @param filename path to the image file
     * @return the loaded {@link BufferedImage}, or exits on failure
     */
    private static BufferedImage loadImageFile(String filename) {
        BufferedImage img = null;
        try {
            img = ImageIO.read(new File(filename));
        } catch (IOException e) {
            System.out.println("Could not load image " + filename + " !");
            e.printStackTrace();
            System.exit(1);
        }
        return img;
    }

    /**
     * Converts a {@code Color} pixel matrix to a {@link BufferedImage}
     * ready for writing to the filesystem.
     *
     * @param image the source pixel matrix
     * @return the equivalent {@link BufferedImage}
     */
    private static BufferedImage matrixToBuffered(Color[][] image) {
        int width  = image.length;
        int height = image[0].length;
        BufferedImage bImg = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                bImg.setRGB(x, y, image[x][y].getRGB());
            }
        }
        return bImg;
    }

    /**
     * Converts a {@link BufferedImage} to a 2-D array of {@link Color} objects.
     *
     * @param image the source {@link BufferedImage}
     * @return pixel matrix with dimensions [width][height]
     */
    private static Color[][] convertTo2DFromBuffered(BufferedImage image) {
        int width  = image.getWidth();
        int height = image.getHeight();
        Color[][] result = new Color[width][height];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int pixel = image.getRGB(x, y);
                int red   = (pixel >> 16) & 0xFF;
                int green = (pixel >>  8) & 0xFF;
                int blue  =  pixel        & 0xFF;
                result[x][y] = new Color(red, green, blue);
            }
        }
        return result;
    }
}
