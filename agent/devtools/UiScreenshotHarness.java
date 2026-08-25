import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.DialogPane;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Off-screen rendering harness for the JavaFX view (dev-only).
 *
 * Drives each UI state through the real {@link JavaFxUpdateView} callbacks and
 * writes one PNG per state into {@code <repo>/screenshots/} for visual review.
 * Not part of the shipped agent: it lives in devtools/ so the standard build
 * (build.sh/build.bat, which only compile src/ + javafx/) never includes it.
 *
 * Captures run sequentially on the FX thread; each waits one short pulse
 * (PauseTransition) before snapshotting so window resizes (e.g. Details
 * expanding on ERROR) and the indeterminate bar have rendered.
 *
 * Usage (from the agent/ directory):
 *   javac -encoding UTF-8 -cp "lib/javafx/*" -d build-harness \
 *       src/*.java javafx/*.java devtools/*.java
 *   cp javafx/ui.css build-harness/
 *   java -cp "build-harness;lib/javafx/*" UiScreenshotHarness
 */
public class UiScreenshotHarness {

    private static final String GAME_DIR = "C:\\Minecraft\\game";
    private static final File OUT_DIR = new File("..", "screenshots");
    // Long enough for the TitledPane expand/collapse animation (default ~350ms)
    // to finish, so Details-expanded states snapshot fully open.
    private static final Duration SETTLE = Duration.millis(700);

    public static void main(String[] args) {
        Platform.startup(() -> {
            OUT_DIR.mkdirs();
            List<CaptureTask> tasks = buildTasks();
            runSequentially(tasks, 0, () -> {
                captureQuitAlert("11_quit_alert.png", () -> {
                    System.out.println("[harness] done -> " + OUT_DIR.getAbsolutePath());
                    Platform.exit();
                });
            });
        });
    }

    private static List<CaptureTask> buildTasks() {
        List<CaptureTask> tasks = new ArrayList<>();

        tasks.add(new CaptureTask("01_preparing.png", false, view ->
                view.showStatus(UpdatePhase.PREPARING, "Checking for updates...", null, true)));

        // Updater self-update — a sub-state of Preparing.
        tasks.add(new CaptureTask("02_updater_download.png", false, view -> {
            view.showStatus(UpdatePhase.PREPARING, "Downloading agent update...", null, false);
            view.showDownloadProgress(DownloadProgress.active(
                    "update-agent.jar", DownloadProgress.Kind.UPDATER,
                    245L * 1024 * 1024, 1200L * 1024 * 1024, 4.2 * 1024 * 1024));
        }));

        // Checking — determinate percentage shown.
        tasks.add(new CaptureTask("03_checking.png", false, view -> {
            view.showStatus(UpdatePhase.CHECKING, "Checked: 247/1247", null, false);
            view.showOverallProgress(18);
        }));

        // Downloading — subtitle "3 of 8 files", file area with path/bar/speed.
        tasks.add(new CaptureTask("04_downloading.png", false, view -> {
            view.showStatus(UpdatePhase.CHECKING, "Checked: 2/8", null, false);
            view.showStatus(UpdatePhase.DOWNLOADING, "Downloading: mods/a.jar", null, false);
            view.showStatus(UpdatePhase.DOWNLOADING, "Downloading: mods/b.jar", null, false);
            view.showStatus(UpdatePhase.DOWNLOADING, "Downloading: versions/client.jar", null, false);
            view.showDownloadProgress(DownloadProgress.active(
                    "versions/client.jar", DownloadProgress.Kind.FILE,
                    51L * 1024 * 1024, 100L * 1024 * 1024, 3.1 * 1024 * 1024));
            view.showOverallProgress(30);
        }));

        // Cleaning — indeterminate, percentage hidden.
        tasks.add(new CaptureTask("05_cleaning.png", false, view ->
                view.showStatus(UpdatePhase.CLEANING, "Cleaning up…",
                        "Removing files that are no longer needed", true)));

        // Success — split title / subtitle.
        tasks.add(new CaptureTask("06_success.png", false, view -> {
            view.showServer(List.of("https://update.example.com"), "https://update.example.com");
            view.showCompleted(new UpdateResult(3, 0));
        }));

        // Partial failure — ERROR visual base, Details expanded.
        tasks.add(new CaptureTask("07_partial_failure.png", false, view -> {
            view.showServer(List.of("https://update.example.com"), "https://update.example.com");
            view.showLog("Manifest contains 8 file(s)");
            view.showCompleted(new UpdateResult(3, 2));
        }));

        // Exception failure — ERROR visual base.
        tasks.add(new CaptureTask("08_error.png", false, view -> {
            view.showServer(List.of("https://update.example.com"), "https://update.example.com");
            view.showError("Connection reset by peer: /192.168.1.20:25565",
                    new java.io.IOException("Connection reset by peer"));
        }));

        // Debug window, Details expanded, close disabled during the flow.
        tasks.add(new CaptureTask("09_debug_close_disabled.png", true, view -> {
            view.showLog("Servers (1):");
            view.showLog("  [1] https://update.example.com");
            view.showLog("Game dir: " + GAME_DIR);
            view.showStatus(UpdatePhase.CHECKING, "Checked: 5/8", null, false);
            view.showOverallProgress(60);
        }));

        // Debug window after completion — close enabled.
        tasks.add(new CaptureTask("10_debug_close_enabled.png", true, view -> {
            view.showStatus(UpdatePhase.CHECKING, "Checked: 8/8", null, false);
            view.showOverallProgress(95);
            view.showCompleted(new UpdateResult(2, 0));
        }));

        return tasks;
    }

    // ── helpers ────────────────────────────────────────────────────

    private interface Drive {
        void drive(JavaFxUpdateView view);
    }

    private static final class CaptureTask {
        final String name;
        final boolean debug;
        final Drive drive;

        CaptureTask(String name, boolean debug, Drive drive) {
            this.name = name;
            this.debug = debug;
            this.drive = drive;
        }
    }

    private static UpdateViewListener stubListener() {
        return new UpdateViewListener() {
            @Override public void onWindowClosed() { }
            @Override public void onCloseRequested() { }
        };
    }

    private static void runSequentially(List<CaptureTask> tasks, int index, Runnable done) {
        if (index >= tasks.size()) {
            done.run();
            return;
        }
        CaptureTask t = tasks.get(index);
        JavaFxUpdateView view = new JavaFxUpdateView(stubListener(), new UiModel(GAME_DIR, t.debug));
        view.open();
        Stage stage = offScreen(view);
        t.drive.drive(view);
        PauseTransition settle = new PauseTransition(SETTLE);
        settle.setOnFinished(e -> {
            Scene scene = stage.getScene();
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            try {
                writePng(scene.snapshot(null), t.name);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            view.close();
            runSequentially(tasks, index + 1, done);
        });
        settle.play();
    }

    private static void captureQuitAlert(String name, Runnable done) {
        JavaFxUpdateView view = new JavaFxUpdateView(stubListener(), new UiModel(GAME_DIR, false));
        view.open();
        offScreen(view);
        // Mid-flow state so the Quit-update prompt is meaningful.
        view.showStatus(UpdatePhase.DOWNLOADING, "Downloading: versions/client.jar", null, false);
        Alert alert = view.createQuitAlert();
        alert.show();
        PauseTransition settle = new PauseTransition(SETTLE);
        settle.setOnFinished(e -> {
            DialogPane pane = alert.getDialogPane();
            if (pane.getScene() != null && pane.getScene().getWindow() instanceof Stage) {
                Stage dialogStage = (Stage) pane.getScene().getWindow();
                dialogStage.setX(-20000);
                dialogStage.setY(-20000);
            }
            pane.applyCss();
            pane.layout();
            try {
                writePng(pane.snapshot(null, null), name);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            alert.close();
            view.close();
            done.run();
        });
        settle.play();
    }

    /** Move the view's (just-opened) window off-screen so nothing flashes. */
    private static Stage offScreen(JavaFxUpdateView view) {
        for (Window w : Window.getWindows()) {
            if (w instanceof Stage) {
                Stage s = (Stage) w;
                s.setX(-20000);
                s.setY(-20000);
                return s;
            }
        }
        throw new IllegalStateException("view stage not found");
    }

    private static void writePng(WritableImage img, String name) throws Exception {
        BufferedImage b = SwingFXUtils.fromFXImage(img, null);
        File out = new File(OUT_DIR, name);
        ImageIO.write(b, "png", out);
        System.out.println("[harness] wrote " + out.getPath()
                + " (" + (int) img.getWidth() + "x" + (int) img.getHeight() + ")");
    }
}
