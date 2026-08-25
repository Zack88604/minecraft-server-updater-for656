import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** Dev-only: sanity-check the generated status images (transparency, bounds, glyph, accent). */
public class ImageCheck {
    public static void main(String[] args) throws Exception {
        String[][] spec = {
            {"preparing",  "0xFF66CC"}, {"updater", "0xFF00FF"},
            {"checking",   "0x3399FF"}, {"downloading", "0xFF9933"},
            {"cleaning",   "0x99CC33"}, {"success", "0x33CC66"},
            {"error",      "0xE05252"},
        };
        for (String[] s : spec) {
            File f = new File("images", s[0] + ".png");
            BufferedImage img = ImageIO.read(f);
            int minX = 99, minY = 99, maxX = -1, maxY = -1, white = 0, accent = 0;
            int target = Integer.decode(s[1]);
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    int argb = img.getRGB(x, y);
                    int a = (argb >>> 24) & 0xFF;
                    if (a > 10) {
                        minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                        minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                        int rgb = argb & 0xFFFFFF;
                        if (rgb == 0xFFFFFF) white++;
                        if (Math.abs((rgb >> 16) - ((target >> 16) & 0xFF)) < 90
                                && Math.abs(((rgb >> 8) & 0xFF) - ((target >> 8) & 0xFF)) < 90
                                && Math.abs((rgb & 0xFF) - (target & 0xFF)) < 90) accent++;
                    }
                }
            }
            // corners must be transparent
            int cornerA = (img.getRGB(0, 0) >>> 24) & 0xFF;
            System.out.printf("%-12s %dx%d opaque=(%d,%d)-(%d,%d) whiteGlyph=%3d accent≈%4d cornerA=%d %s%n",
                    s[0], img.getWidth(), img.getHeight(), minX, minY, maxX, maxY,
                    white, accent, cornerA,
                    (minX >= 1 && maxX <= 62 && white > 40 && accent > 800 && cornerA == 0) ? "OK" : "SUSPECT");
        }
    }
}
