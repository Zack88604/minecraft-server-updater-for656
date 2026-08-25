import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Dev-only: verify each screenshot renders its phase illustration in the header
 * slot.
 *
 * Each screenshot's 64×64 status slot (at the header origin, 22,18) is compared
 * against the expected source image from {@code agent/images/} alpha-composited
 * over the window background. Transparent source pixels render as the window
 * background, so a faithful render (art present, at the right size/position, no
 * stale cross-fade frame, correct opacity) matches the composite almost exactly.
 *
 * Not part of the shipped agent. Run from the agent/ directory after the
 * screenshot harness:
 *   javac -encoding UTF-8 -d build-harness devtools/ScreenshotProbe.java
 *   java -cp build-harness ScreenshotProbe
 */
public class ScreenshotProbe {

    /** The window background the art composites over (see ui.css --color-window). */
    private static final int BG = 0x0F1417;
    /** Channel tolerance for "faithful render". */
    private static final int TOL = 25;

    /** screenshot file -> source art file it must show. */
    private static final String[][] CASES = {
        {"01_preparing.png",         "preparing.png"},
        {"02_updater_download.png",  "updater.png"},
        {"03_checking.png",          "checking.png"},
        {"04_downloading.png",       "downloading.png"},
        {"05_cleaning.png",          "cleaning.png"},
        {"06_success.png",           "success.png"},
        {"07_partial_failure.png",   "error.png"},
        {"08_error.png",             "error.png"},
    };

    public static void main(String[] args) throws Exception {
        System.out.println("screenshot              slot-avg    expected-avg   status");
        boolean allOk = true;
        for (String[] c : CASES) {
            BufferedImage shot = ImageIO.read(new File("..\\screenshots", c[0]));
            BufferedImage src = ImageIO.read(new File("images", c[1]));
            long[] actual = slotAvg(shot);
            long[] expected = compositeAvg(src);
            boolean ok = Math.abs(actual[0] - expected[0]) < TOL
                    && Math.abs(actual[1] - expected[1]) < TOL
                    && Math.abs(actual[2] - expected[2]) < TOL;
            allOk &= ok;
            System.out.printf("%-24s #%06x  #%06x       %s%n",
                    c[0], toRgb(actual), toRgb(expected), ok ? "OK" : "MISMATCH");
        }
        System.out.println(allOk ? "PASS — every screenshot shows its phase art"
                                 : "FAIL — at least one screenshot is missing/wrong art");
        System.exit(allOk ? 0 : 1);
    }

    /** Average colour of the 64×64 status slot in the screenshot. */
    private static long[] slotAvg(BufferedImage shot) {
        long r = 0, g = 0, b = 0, n = 0;
        for (int y = 18; y < 82; y++) {
            for (int x = 22; x < 86; x++) {
                int c = shot.getRGB(x, y) & 0xFFFFFF;
                r += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; b += c & 0xFF; n++;
            }
        }
        return new long[]{r / n, g / n, b / n};
    }

    /**
     * Average colour of the source art rendered over the window background:
     * opaque pixels at full strength, transparent pixels as the background,
     * semi-transparent pixels blended proportionally.
     */
    private static long[] compositeAvg(BufferedImage src) {
        long r = 0, g = 0, b = 0, n = 0;
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int p = src.getRGB(x, y);
                int a = (p >>> 24) & 0xFF;
                double f = a / 255.0;
                int c = p & 0xFFFFFF;
                r += (int) (((c >> 16) & 0xFF) * f + ((BG >> 16) & 0xFF) * (1 - f));
                g += (int) (((c >> 8) & 0xFF) * f + ((BG >> 8) & 0xFF) * (1 - f));
                b += (int) ((c & 0xFF) * f + (BG & 0xFF) * (1 - f));
                n++;
            }
        }
        return new long[]{r / n, g / n, b / n};
    }

    private static int toRgb(long[] v) {
        return (int) ((v[0] << 16) | (v[1] << 8) | v[2]);
    }
}
