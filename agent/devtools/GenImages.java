import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Dev-only: generate the example status-illustration PNGs (64x64, transparent)
 * into {@code agent/images/}, the JAR-resource root the JavaFX view loads
 * {@code /images/*.png} from. Not part of the shipped agent — run it once to
 * (re)create the placeholder art:
 *
 * <pre>
 *   javac -encoding UTF-8 -d build-harness devtools/GenImages.java
 *   java -cp build-harness GenImages
 * </pre>
 *
 * Each image is a rounded "chip" with a vertical gradient in the phase accent
 * colour and a simple white glyph: check (SUCCESS), cross (ERROR), down arrow
 * (DOWNLOADING), up arrow (UPDATER), magnifier (CHECKING), sparkle (CLEANING),
 * loading ring (PREPARING). These are placeholders until the real art arrives.
 */
public class GenImages {

    // phase -> file name, accent colour, glyph kind
    static final String[][] SPEC = {
        {"preparing",  "0xFF66CC", "loading"},
        {"updater",    "0xFF00FF", "up"},
        {"checking",   "0x3399FF", "magnifier"},
        {"downloading","0xFF9933", "down"},
        {"cleaning",   "0x99CC33", "sparkle"},
        {"success",    "0x33CC66", "check"},
        {"error",      "0xE05252", "cross"},
    };

    public static void main(String[] args) throws Exception {
        File dir = new File("images");
        dir.mkdirs();
        for (String[] s : SPEC) {
            ImageIO.write(render(s[1], s[2]), "png", new File(dir, s[0] + ".png"));
            System.out.println("wrote " + dir.getPath() + "\\" + s[0] + ".png");
        }
    }

    static BufferedImage render(String hexColor, String glyph) {
        int size = 64;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        int base = Integer.decode(hexColor);
        Color c0 = new Color(base);
        Color c1 = new Color(base).darker();

        // Rounded chip with a subtle top-light gradient.
        GradientPaint grad = new GradientPaint(0, 6, c0.brighter(), 0, 58, c1);
        g.setPaint(grad);
        g.fillRoundRect(2, 2, 60, 60, 18, 18);

        // White glyph, centred on (32,32).
        g.setColor(Color.WHITE);
        BasicStroke stroke = new BasicStroke(6, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        g.setStroke(stroke);
        switch (glyph) {
            case "check":
                g.drawPolyline(new int[]{22, 29, 42}, new int[]{34, 42, 25}, 3);
                break;
            case "cross":
                g.drawLine(22, 22, 42, 42);
                g.drawLine(42, 22, 22, 42);
                break;
            case "down":
                g.drawLine(32, 18, 32, 40);
                g.drawPolyline(new int[]{24, 32, 40}, new int[]{33, 40, 33}, 3);
                g.drawLine(22, 48, 42, 48);
                break;
            case "up":
                g.drawLine(32, 46, 32, 24);
                g.drawPolyline(new int[]{24, 32, 40}, new int[]{31, 24, 31}, 3);
                g.drawLine(22, 16, 42, 16);
                break;
            case "magnifier":
                g.drawOval(17, 17, 22, 22);
                g.drawLine(34, 34, 46, 46);
                break;
            case "sparkle": {
                // 4-point star: outer points at 0/90/180/270 deg, inner at 45/135/...
                GeneralPath star = new GeneralPath();
                for (int i = 0; i < 8; i++) {
                    double ang = Math.toRadians(i * 45.0);
                    double r = (i % 2 == 0) ? 17 : 7;
                    double x = 32 + r * Math.cos(ang);
                    double y = 32 + r * Math.sin(ang);
                    if (i == 0) {
                        star.moveTo(x, y);
                    } else {
                        star.lineTo(x, y);
                    }
                }
                star.closePath();
                g.fill(star);
                break;
            }
            case "loading":
                // 300° ring with a dot at the gap — reads as "checking/refresh".
                g.drawArc(24, 24, 16, 16, 20, 300);
                double ax = Math.toRadians(20);
                g.fillOval((int) (32 + 8 * Math.cos(ax)) - 3,
                        (int) (32 + 8 * Math.sin(ax)) - 3, 6, 6);
                break;
            default:
                break;
        }
        g.dispose();
        return img;
    }
}
