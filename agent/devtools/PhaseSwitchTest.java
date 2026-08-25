import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * Dev-only: rapid consecutive phase-switch test for the JavaFX view (round 3).
 *
 * Drives the view through every phase in quick succession — each switch much
 * faster than the ~200ms cross-fade — to confirm that the preloaded image cache
 * and the cross-fade / scale / header micro-animations never leave the view in
 * a disordered state. In particular it checks the interruption rule: a new
 * phase must stop the previous animation and jump straight to the latest state,
 * so the art finally on display is exactly the last phase's cached image (no
 * stale overlay from an older phase), and the slot is not hidden or stuck
 * mid-fade.
 *
 * Not part of the shipped agent (lives in devtools/). Run after the screenshot
 * harness from the agent/ directory:
 *   javac -encoding UTF-8 -cp "lib/javafx/*" -d build-harness \
 *       src/*.java javafx/*.java devtools/*.java
 *   cp javafx/ui.css build-harness/
 *   java -cp "build-harness;.;lib/javafx/*" PhaseSwitchTest
 * Exits 0 on pass, 1 on fail.
 */
public class PhaseSwitchTest {

    // Every switch is much faster than the 200ms cross-fade, so nearly every
    // transition interrupts the previous animation mid-flight.
    private static final Duration GAP = Duration.millis(60);
    // Long enough for the final cross-fade + scale-in to finish completely.
    private static final Duration SETTLE = Duration.millis(600);

    private static final String GAME_DIR = "C:\\Minecraft\\game";
    private static final String EXPECTED = "/images/success.png"; // final phase art

    public static void main(String[] args) {
        Platform.startup(() -> {
            JavaFxUpdateView view = new JavaFxUpdateView(stubListener(), new UiModel(GAME_DIR, false));
            view.open();
            offScreen(view);
            run(view, 0, () -> {
                String shown = view.displayedStatusImageResource();
                boolean ok = EXPECTED.equals(shown);
                System.out.println("[phase-switch] final art on display: " + shown);
                System.out.println("[phase-switch] expected:             " + EXPECTED);
                System.out.println(ok
                        ? "[phase-switch] PASS — image cache + interrupted animations kept the state consistent"
                        : "[phase-switch] FAIL — stale/wrong art (or a hidden slot) after rapid switching");
                view.close();
                Platform.exit();
                System.exit(ok ? 0 : 1);
            });
        });
    }

    /** Fire each phase switch, spaced by {@link #GAP}, then verify. */
    private static void run(JavaFxUpdateView view, int step, Runnable done) {
        List<Runnable> steps = buildSteps(view);
        if (step >= steps.size()) {
            PauseTransition settle = new PauseTransition(SETTLE);
            settle.setOnFinished(e -> done.run());
            settle.play();
            return;
        }
        steps.get(step).run();
        PauseTransition gap = new PauseTransition(GAP);
        gap.setOnFinished(e -> run(view, step + 1, done));
        gap.play();
    }

    /**
     * Rapid-fire every phase, including the updater sub-state and a terminal
     * round-trip (SUCCESS → ERROR → SUCCESS), so the final display must be the
     * SUCCESS art — anything else means a fade was left in the wrong state.
     */
    private static List<Runnable> buildSteps(JavaFxUpdateView view) {
        List<Runnable> steps = new ArrayList<>();
        steps.add(() -> view.showStatus(UpdatePhase.PREPARING, "Checking for updates...", null, true));
        steps.add(() -> view.showStatus(UpdatePhase.CHECKING, "Checked: 247/1247", null, false));
        steps.add(() -> view.showStatus(UpdatePhase.DOWNLOADING, "Downloading: mods/a.jar", null, false));
        steps.add(() -> view.showDownloadProgress(DownloadProgress.active(
                "versions/client.jar", DownloadProgress.Kind.FILE,
                51L * 1024 * 1024, 100L * 1024 * 1024, 3.1 * 1024 * 1024)));
        steps.add(() -> view.showStatus(UpdatePhase.CLEANING, "Cleaning up…",
                "Removing files that are no longer needed", true));
        steps.add(() -> {
            // Updater self-update — sub-state of PREPARING with its own art.
            view.showStatus(UpdatePhase.PREPARING, "Downloading agent update...", null, false);
            view.showDownloadProgress(DownloadProgress.active(
                    "update-agent.jar", DownloadProgress.Kind.UPDATER,
                    245L * 1024 * 1024, 1200L * 1024 * 1024, 4.2 * 1024 * 1024));
        });
        steps.add(() -> view.showCompleted(new UpdateResult(3, 0)));       // SUCCESS (entrance)
        steps.add(() -> view.showError("Connection reset by peer",
                new java.io.IOException("Connection reset by peer")));     // ERROR (entrance)
        steps.add(() -> view.showCompleted(new UpdateResult(3, 0)));       // SUCCESS (final)
        return steps;
    }

    private static UpdateViewListener stubListener() {
        return new UpdateViewListener() {
            @Override public void onWindowClosed() { }
            @Override public void onCloseRequested() { }
        };
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
}
