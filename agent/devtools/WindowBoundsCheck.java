import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * Dev-only: verify the JavaFX window stays centred and never leaves the visible
 * screen when Details expands (round-4 acceptance item 4).
 *
 * Opens the real view on the real (visible) screen, drives it to a collapsed
 * state and to the ERROR state (which expands Details), waits for the
 * content-driven resize to settle, then asserts:
 *   - the window is fully inside the containing screen's visual bounds;
 *   - the window is centred on that screen (centre within ~3 px).
 * It also prints the screen's output scale so the DPI under test is recorded.
 *
 * Not part of the shipped agent. Run from the agent/ directory:
 *   javac -encoding UTF-8 --module-path lib/javafx --add-modules javafx.controls,javafx.swing \
 *         -cp "lib/javafx/*" -d build-harness src/*.java javafx/*.java devtools/WindowBoundsCheck.java
 *   java --module-path lib/javafx --add-modules javafx.controls,javafx.swing \
 *         -cp "build-harness;.;lib/javafx/*" WindowBoundsCheck
 * Exits 0 on pass, 1 on fail.
 */
public class WindowBoundsCheck {

    /** Tolerance for "the window centre is on the screen centre". */
    private static final double CENTER_TOL = 3.0;
    /** Long enough for the deferred resize (Platform.runLater) + cross-fade to settle. */
    private static final Duration SETTLE = Duration.millis(700);

    private static final String GAME_DIR = "C:\\Minecraft\\game";
    private static boolean pass = true;

    public static void main(String[] args) {
        Platform.startup(() -> {
            JavaFxUpdateView view = new JavaFxUpdateView(stubListener(), new UiModel(GAME_DIR, false));
            view.open();
            // Collapsed state (PREPARING).
            view.showStatus(UpdatePhase.PREPARING, "Checking for updates...", null, true);
            settle(() -> {
                check("collapsed (PREPARING)", view);
                // ERROR — expands Details, so the window must grow but stay centred/on-screen.
                view.showError("Connection reset by peer: /192.168.1.20:25565",
                        new java.io.IOException("Connection reset by peer"));
                settle(() -> {
                    check("expanded (ERROR)", view);
                    view.close();
                    System.out.println(pass
                            ? "[bounds] PASS — window centred and within the visible screen in both states"
                            : "[bounds] FAIL — window off-screen and/or off-centre");
                    Platform.exit();
                    System.exit(pass ? 0 : 1);
                });
            });
        });
    }

    private static void check(String label, JavaFxUpdateView view) {
        Stage stage = findStage(view);
        Rectangle2D screen = screenContaining(stage);
        Rectangle2D bounds = new Rectangle2D(
                stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
        double cx = bounds.getMinX() + bounds.getWidth() / 2.0;
        double cy = bounds.getMinY() + bounds.getHeight() / 2.0;
        double scx = screen.getMinX() + screen.getWidth() / 2.0;
        double scy = screen.getMinY() + screen.getHeight() / 2.0;
        boolean inside = screen.contains(bounds.getMinX(), bounds.getMinY())
                && screen.contains(bounds.getMaxX() - 1, bounds.getMaxY() - 1);
        boolean centered = Math.abs(cx - scx) <= CENTER_TOL && Math.abs(cy - scy) <= CENTER_TOL;
        pass &= inside && centered;
        System.out.printf("[bounds] %-18s window=%.0fx%.0f @(%.0f,%.0f)  screen=%.0fx%.0f  scale=%.2f  inside=%s centred=%s%n",
                label, bounds.getWidth(), bounds.getHeight(), bounds.getMinX(), bounds.getMinY(),
                screen.getWidth(), screen.getHeight(), Screen.getPrimary().getOutputScaleX(),
                inside ? "OK" : "OFF-SCREEN", centered ? "OK" : "OFF-CENTRE");
    }

    private static Rectangle2D screenContaining(Stage stage) {
        Rectangle2D b = new Rectangle2D(
                stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
        double cx = b.getMinX() + b.getWidth() / 2.0;
        double cy = b.getMinY() + b.getHeight() / 2.0;
        for (Screen s : Screen.getScreens()) {
            if (s.getVisualBounds().contains(cx, cy)) {
                return s.getVisualBounds();
            }
        }
        return Screen.getPrimary().getVisualBounds();
    }

    private static Stage findStage(JavaFxUpdateView view) {
        for (javafx.stage.Window w : javafx.stage.Window.getWindows()) {
            if (w instanceof Stage) return (Stage) w;
        }
        throw new IllegalStateException("view stage not found");
    }

    private static UpdateViewListener stubListener() {
        return new UpdateViewListener() {
            @Override public void onWindowClosed() { }
            @Override public void onCloseRequested() { }
        };
    }

    private static void settle(Runnable done) {
        PauseTransition pause = new PauseTransition(SETTLE);
        pause.setOnFinished(e -> done.run());
        pause.play();
    }
}
