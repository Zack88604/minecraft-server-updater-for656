import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JavaFX implementation of the toolkit-agnostic {@link UpdateView} contract,
 * parallel to the Swing {@link UpdateGUI}.
 *
 * Pure View: it creates the Stage/Scene, renders the six update phases and
 * forwards user actions (window close, debug close button) to a
 * {@link UpdateViewListener}. It holds no reference to the
 * {@link UpdateService}, owns no threads and never queries business state —
 * everything displayed arrives through the view callbacks. All methods must be
 * invoked on the JavaFX Application Thread; the {@link UpdateController}
 * guarantees this by marshalling every call through a {@link UiDispatcher}.
 *
 * The six main visual phases (see {@link UpdatePhase}):
 * <ul>
 *   <li>{@code PREPARING} — fetching the manifest and running the self-update
 *       check (indeterminate bar). The updater download is a sub-state of this
 *       phase, distinguished by {@link DownloadProgress.Kind#UPDATER} and shown
 *       through the current-file area.</li>
 *   <li>{@code CHECKING}   — hashing managed files against the manifest</li>
 *   <li>{@code DOWNLOADING}— downloading a regular managed file</li>
 *   <li>{@code CLEANING}   — removing stale files (indeterminate bar)</li>
 *   <li>{@code SUCCESS}    — flow completed with no failed files (bar at 100%)</li>
 *   <li>{@code ERROR}      — flow failed — exception or partial failure (bar
 *                            hidden, error summary)</li>
 * </ul>
 *
 * The phase is carried explicitly by {@link UpdateEvent.StatusChanged}, so the
 * view never infers it from status text.
 *
 * While an update is in progress (PREPARING/CHECKING/DOWNLOADING/CLEANING) the
 * window close request is intercepted and the user must confirm quitting; in
 * the terminal SUCCESS/ERROR phases the close request is honoured directly.
 *
 * The view is styled entirely from {@code /ui.css}; it adds no gradients or
 * looping animations of its own. A fixed 64×64 status-illustration slot
 * ({@code statusStack}) is reserved in the header: all seven transparent PNGs
 * (one per {@link UpdatePhase}, plus the updater art under PREPARING) are
 * preloaded once at startup and cached, so a phase switch never re-decodes from
 * the JAR. Switching art is a real cross-fade between two stacked ImageViews
 * (~200ms), with a light 0.94→1.0 scale-in for the terminal SUCCESS/ERROR art;
 * the status title/description do a very light opacity transition only when the
 * phase actually changes. Per-file counting, download-speed and percentage
 * updates never trigger an animation. A missing or corrupt resource still
 * degrades to a hidden slot without affecting the layout or the update flow,
 * and a new phase always interrupts a running fade safely — an older phase's
 * art can never cover the latest state. Terminal state classes
 * ({@code success-state} / {@code error-state}) are maintained on the root by
 * {@link #setPhase}, and the Quit-update confirmation shares the same
 * stylesheet.
 */
class JavaFxUpdateView implements UpdateView {

    private final Stage stage;
    private final UpdateViewListener listener;
    private final boolean debug;

    // Window sizing: normal mode is deliberately short (Details collapsed).
    // Expanding Details (debug mode or error state) grows the window to its
    // content's preferred height — never a fixed expanded height, so Error /
    // Debug states don't leave dead space at the bottom (see applyWindowHeight).
    private static final double WINDOW_WIDTH = 520;
    private static final double WINDOW_HEIGHT_COLLAPSED = 300;

    // Reserved status-illustration slot: the display size the ImageViews fit
    // the (possibly 128×128+) transparent PNG sources down to.
    private static final double STATUS_IMAGE_SIZE = 64;

    // Round-3 micro-animation timings (视觉精修第三步要求.md §2). The ~200ms
    // image cross-fade is the only "real" animation; the SUCCESS/ERROR scale-in
    // and the header opacity transition are deliberately short and light. No
    // shake / bounce / float / glow / background or looping animations.
    private static final double CROSS_FADE_MS = 200;        // spec 180–220ms
    private static final double ENTRANCE_SCALE_MS = 160;    // spec 150–180ms
    private static final double HEADER_FADE_MS = 130;       // spec 100–150ms
    private static final double ENTRANCE_SCALE_FROM = 0.94; // spec 0.94 → 1.0

    // Persistent bottom attribution line (styled via .footer-copyright in
    // ui.css). Pure presentation — it never affects the update flow.
    private static final String FOOTER_COPYRIGHT =
            "Developed by Zack88604 · MIT License · UI redesign by Eternity_Riguru";

    // Status-illustration resources (JAR-relative), bundled into the core JAR
    // from agent/images/ and preloaded into the statusImages cache at startup.
    private static final String IMG_PREPARING = "/images/preparing.png";
    private static final String IMG_UPDATER = "/images/updater.png";
    private static final String IMG_CHECKING = "/images/checking.png";
    private static final String IMG_DOWNLOADING = "/images/downloading.png";
    private static final String IMG_CLEANING = "/images/cleaning.png";
    private static final String IMG_SUCCESS = "/images/success.png";
    private static final String IMG_ERROR = "/images/error.png";

    // Overall progress area
    private final Label lblStatus = new Label("Preparing update…");
    private final Label lblDescription = new Label("");
    private final HBox overallArea = new HBox(6);
    private final ProgressBar overallBar = new ProgressBar(0);
    private final Label lblOverallPct = new Label("");

    // Current-file / per-download area
    private final VBox dlArea = new VBox(4);
    private final Label lblDlFile = new Label();
    private final ProgressBar dlBar = new ProgressBar(0);
    private final Label lblDlSpeed = new Label("");

    // Counters backing the informational subtitles (see showStatus).
    private int filesSeen;   // per-file downloads started in this run
    private int filesTotal;  // managed file count, captured from CHECKING text

    // Details area (Server URL, Game Directory, full log)
    private final TitledPane detailsPane = new TitledPane("Details", null);
    private final Label lblServer = new Label("Server: -");
    private final Label lblGameDir = new Label();
    private final TextArea logArea = new TextArea();

    // Debug close button
    private final Button btnClose = new Button("Close");

    // Persistent bottom copyright line (always the last row of the root).
    private final Label lblFooter = new Label();

    // Reserved status-illustration slot: a fixed 64×64 StackPane holding two
    // stacked ImageViews so a phase switch is a real cross-fade (the new art
    // fades in on top while the old fades out beneath) — never a
    // setImage-then-fade. statusFront is the settled art on display; statusBack
    // is the incoming view during a fade; the two are swapped when a fade
    // completes. The whole stack hides (managed=false) when no art is
    // available, degrading exactly as before.
    private final StackPane statusStack = new StackPane();
    private ImageView statusFront = new ImageView();
    private ImageView statusBack = new ImageView();

    // Phase art decoded once at startup (see preloadStatusImages) and reused for
    // every phase switch — never re-decoded from the JAR per event. Keyed by the
    // /images/*.png resource path.
    private final Map<String, Image> statusImages = new HashMap<>();

    // In-flight micro-animations, kept so a new phase switch can stop them
    // safely and jump straight to the latest state (no stale overlay).
    private Animation statusFade;
    private Animation headerFade;

    // Root layout — carries the .success-state / .error-state state classes.
    private final VBox root = new VBox(8);

    /** External form of /ui.css, or null if the stylesheet is missing. */
    private final String stylesheet;

    /** The scene backing the window; resized when Details expands/collapses. */
    private Scene scene;

    /** Vertical window chrome (title bar + borders), measured once, see {@link #windowChrome()}. */
    private double chrome;

    /** The destructive "Skip update" action, created per Quit-alert instance. */
    private ButtonType quitSkipType;

    private UpdatePhase phase = UpdatePhase.PREPARING;

    JavaFxUpdateView(UpdateViewListener listener, UiModel model) {
        this.listener = listener;
        this.debug = model.debug;
        this.stage = new Stage();
        java.net.URL css = getClass().getResource("/ui.css");
        this.stylesheet = css == null ? null : css.toExternalForm();
        initUI(model);
    }

    // ── UpdateView ────────────────────────────────────────────────

    /**
     * Rebuild the status hierarchy from the business event. The raw business
     * status string is never shown verbatim as the visual title: each phase
     * maps to a stable main title, and the count / detail is re-worded into the
     * subtitle below it. The file path stays in the current-file area, never in
     * the title.
     */
    @Override
    public void showStatus(UpdatePhase phase, String status, String description, boolean indeterminate) {
        if (indeterminate) {
            overallBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        }
        setPhase(phase);
        switch (phase) {
            case PREPARING:
                // A new run starts here — reset the file counters.
                filesSeen = 0;
                filesTotal = 0;
                lblStatus.setText("Preparing update…");
                lblDescription.setText("Connecting to update server");
                break;
            case CHECKING: {
                int[] counts = extractFileCounts(status);
                if (counts != null) {
                    filesTotal = counts[1];
                }
                lblStatus.setText("Checking files…");
                lblDescription.setText(counts != null
                        ? formatCount(counts[0]) + " of " + formatCount(counts[1]) + " files checked"
                        : "Checking files…");
                break;
            }
            case DOWNLOADING:
                filesSeen++;
                lblStatus.setText("Downloading update…");
                lblDescription.setText(filesTotal > 0
                        ? formatCount(filesSeen) + " of " + formatCount(filesTotal) + " files"
                        : formatCount(filesSeen) + " file(s)");
                break;
            case CLEANING:
                lblStatus.setText("Cleaning up…");
                lblDescription.setText(description == null || description.isEmpty()
                        ? "Removing files that are no longer needed"
                        : description);
                break;
            default:
                // Terminal phases are rendered by showCompleted / showError.
                lblStatus.setText(status);
                lblDescription.setText(description == null ? "" : description);
                break;
        }
    }

    /** Append one log line to the Details log. */
    @Override
    public void showLog(String message) {
        logArea.appendText(message + "\n");
    }

    /**
     * Set the overall progress percentage (0-100). PREPARING and CLEANING keep
     * an indeterminate bar with the percentage hidden; only the determinate
     * CHECKING / DOWNLOADING phases display a percentage.
     */
    @Override
    public void showOverallProgress(int percent) {
        if (phase == UpdatePhase.PREPARING || phase == UpdatePhase.CLEANING) {
            return;
        }
        int p = clamp(percent);
        overallBar.setProgress(p / 100.0);
        lblOverallPct.setText(p + "%");
    }

    /**
     * Present a per-file / agent download snapshot. An inactive snapshot hides
     * and clears the current-file area; an active one switches the phase to
     * DOWNLOADING for a regular managed file, or stays in PREPARING for the
     * updater self-update (a sub-state of PREPARING), and shows the
     * current-file area with the object name, progress and speed. The file or
     * JAR name lives only in this area — never in the status title.
     */
    @Override
    public void showDownloadProgress(DownloadProgress progress) {
        if (!progress.active) {
            hideDownloadArea();
            // Revert any updater art to the current phase's illustration, and
            // shrink the window back (the download area just disappeared).
            updateStatusImage(phase);
            applyWindowHeight();
            return;
        }
        boolean updater = progress.kind == DownloadProgress.Kind.UPDATER;
        setPhase(updater ? UpdatePhase.PREPARING : UpdatePhase.DOWNLOADING);
        if (updater) {
            // The updater self-update never exposes the "agent" jargon in the
            // normal UI — the title and subtitle stay user-facing.
            lblStatus.setText("Updating updater…");
            lblDescription.setText("Preparing update components");
            // The updater stays a sub-state of PREPARING but may use its own
            // illustration (reserved; hidden until the art is bundled).
            showStatusImage(IMG_UPDATER);
        }
        lblDlFile.setText(progress.path == null ? "" : progress.path);
        if (progress.totalBytes > 0) {
            int pct = clamp((int) (progress.downloadedBytes * 100 / progress.totalBytes));
            dlBar.setProgress(pct / 100.0);
        } else {
            // Unknown content-length: indeterminate per-file bar.
            dlBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        }
        lblDlSpeed.setText(FormatUtil.formatSpeed(progress.bytesPerSecond));
        showDownloadArea();
    }

    /** Present the server state carried by the event (inside Details). */
    @Override
    public void showServer(List<String> serverUrls, String currentServer) {
        lblServer.setText(serverUrls.size() <= 1
                ? "Server: " + currentServer
                : "Servers (" + serverUrls.size() + "): " + currentServer);
    }

    /**
     * The update completed. A fully successful run ({@code failed == 0}) renders
     * the SUCCESS state with a full bar and a success summary; a partial failure
     * ({@code failed > 0}) renders the ERROR state — the same visual base state
     * as an exception failure, with the overall bar hidden, Details expanded and
     * the failure count shown as the error summary. Flow control after
     * completion is the application's job.
     */
    @Override
    public void showCompleted(UpdateResult result) {
        if (result.failed > 0) {
            // Partial failure — reuse the ERROR visual base state shared with
            // exception failures: setPhase(ERROR) hides the overall bar, resets
            // any residue, expands Details and hides the current-file area. The
            // failure count becomes the error summary, mirroring showError().
            setPhase(UpdatePhase.ERROR);
            lblStatus.setText("Update failed");
            lblDescription.setText(result.failed + " file(s) failed to update.");
            showLog("[ERROR] " + result.failed + " file(s) failed to update.");
        } else {
            // Success is split into a main title and a subtitle so the green
            // accent marks only the headline, not the whole sentence.
            setPhase(UpdatePhase.SUCCESS);
            overallBar.setProgress(1.0);
            lblOverallPct.setText("100%");
            if (result.updated > 0) {
                lblStatus.setText("Update complete");
                lblDescription.setText(formatFiles(result.updated) + " updated · Launching Minecraft…");
            } else {
                lblStatus.setText("You're up to date");
                lblDescription.setText("Launching Minecraft…");
            }
        }
        if (debug) {
            setCloseEnabled(true);
            showLog("[DEBUG] Update check done. Window stays open for inspection.");
        }
    }

    /**
     * The update failed — render the ERROR state: overall bar reset and hidden,
     * an error summary as the description, and the Details area expanded so the
     * log is reachable. v1 implements no Retry.
     */
    @Override
    public void showError(String message, Throwable cause) {
        String msg = message == null ? "Unknown error" : message;
        setPhase(UpdatePhase.ERROR);
        lblStatus.setText("Update failed");
        lblDescription.setText(msg);
        showLog("[ERROR] " + msg);
    }

    /** Enable or disable the debug close button. */
    @Override
    public void setCloseEnabled(boolean enabled) {
        btnClose.setDisable(!enabled);
    }

    /**
     * Show the window. Must be called on the JavaFX Application Thread. The
     * debug window opens with Details already expanded, so after the stage is
     * realised it is sized to its content — a fixed expanded height would leave
     * dead space at the bottom.
     */
    @Override
    public void open() {
        stage.show();
        // Centre the window on the screen. The content-driven resize scheduled
        // below re-centres it after any height change, so it stays centred from
        // the very first frame (never flashes at the OS default top-left spot).
        stage.centerOnScreen();
        // The debug window opens with Details already expanded; size the window
        // to its content so a fixed expanded height never leaves dead space.
        // applyWindowHeight defers the measurement to the next pulse.
        applyWindowHeight();
    }

    /** Close the window. Must be called on the JavaFX Application Thread. */
    @Override
    public void close() {
        stopStatusFade();
        if (headerFade != null) {
            headerFade.stop();
            headerFade = null;
        }
        stage.close();
    }

    // ── Phase rendering ───────────────────────────────────────────

    /**
     * Track the current phase and apply the phase-specific rendering. The root
     * carries the terminal state classes ({@code success-state} /
     * {@code error-state}); ui.css derives the title colour and related state
     * visuals from them via {@code .root.success-state ...} /
     * {@code .root.error-state ...}. The mid-flow phases carry neither class.
     */
    private void setPhase(UpdatePhase p) {
        if (phase != p) {
            phase = p;
            // A real phase change is the only thing allowed to fade the header
            // text — per-file counting, download-speed and percentage updates
            // keep the phase unchanged and short-circuit before reaching here,
            // so they never animate.
            animateHeaderFade();
            root.getStyleClass().removeAll("success-state", "error-state");
            switch (p) {
            case PREPARING:
            case CLEANING:
                // Indeterminate phases hide the percentage entirely, so a
                // stale value (e.g. the previous 55%) never lingers beside
                // the bar.
                hideDownloadArea();
                clearOverallPercent();
                logArea.setPrefRowCount(6);
                break;
            case CHECKING:
            case DOWNLOADING:
                // Determinate phases show the percentage. The current-file
                // area is (re)shown by showDownloadProgress for DOWNLOADING.
                hideDownloadArea();
                showOverallPercent();
                logArea.setPrefRowCount(6);
                break;
            case SUCCESS:
                hideDownloadArea();
                showOverallPercent();
                logArea.setPrefRowCount(6);
                root.getStyleClass().add("success-state");
                break;
            case ERROR:
                hideDownloadArea();
                // Error hides the overall progress bar, resets any residue
                // (e.g. a previous 100%), expands Details and shows a few
                // more log rows for the failure context. The row count is
                // raised before expanding so the expansion-driven resize
                // sees the final content height.
                overallBar.setProgress(0);
                lblOverallPct.setText("");
                overallArea.setVisible(false);
                overallArea.setManaged(false);
                logArea.setPrefRowCount(10);
                detailsPane.setExpanded(true);
                root.getStyleClass().add("error-state");
                break;
            }
        }
        // Update the status illustration for the phase, then keep the window
        // sized to its content (Debug starts expanded; Error raises the log
        // height). This runs even when the phase is re-asserted unchanged: the
        // view starts in PREPARING, so the first PREPARING event would
        // otherwise short-circuit and its illustration would never appear.
        // No-op while the window is not showing.
        updateStatusImage(p);
        applyWindowHeight();
    }

    /** Hide the overall percentage label, clearing any stale text. */
    private void clearOverallPercent() {
        lblOverallPct.setText("");
        lblOverallPct.setVisible(false);
        lblOverallPct.setManaged(false);
    }

    /** Show the overall percentage label next to the bar. */
    private void showOverallPercent() {
        lblOverallPct.setVisible(true);
        lblOverallPct.setManaged(true);
    }

    /** Reveal the current-file area; grows the window only on the show/hide flip. */
    private void showDownloadArea() {
        boolean wasVisible = dlArea.isVisible();
        dlArea.setVisible(true);
        dlArea.setManaged(true);
        if (!wasVisible) {
            applyWindowHeight();
        }
    }

    /** Hide and clear the current-file area. */
    private void hideDownloadArea() {
        dlArea.setVisible(false);
        dlArea.setManaged(false);
        lblDlFile.setText("");
        dlBar.setProgress(0);
        lblDlSpeed.setText("");
    }

    // ── Status illustration ────────────────────────────────────────

    /** Map a phase to its status-illustration resource, or null for none. */
    private static String statusImageResource(UpdatePhase phase) {
        switch (phase) {
            case PREPARING: return IMG_PREPARING;
            case CHECKING: return IMG_CHECKING;
            case DOWNLOADING: return IMG_DOWNLOADING;
            case CLEANING: return IMG_CLEANING;
            case SUCCESS: return IMG_SUCCESS;
            case ERROR: return IMG_ERROR;
        }
        return null;
    }

    /** Point the status illustration at the given phase's art. */
    private void updateStatusImage(UpdatePhase phase) {
        // The terminal SUCCESS/ERROR art gets a light 0.94→1.0 entrance in
        // addition to the cross-fade; mid-flow phases just cross-fade.
        boolean entrance = phase == UpdatePhase.SUCCESS || phase == UpdatePhase.ERROR;
        showStatusImage(statusImageResource(phase), entrance);
    }

    /** Show the status illustration for a JAR resource, without an entrance. */
    private void showStatusImage(String resource) {
        showStatusImage(resource, false);
    }

    /**
     * Show the status illustration for a JAR resource. The image comes from the
     * preloaded cache (never decoded on a phase switch). Status art is optional:
     * a missing resource, an unresolvable path or a corrupt file just hides the
     * slot — the layout and the update flow are never affected. An image that
     * differs from what is on display is cross-faded in over the current art;
     * a re-asserted identical image (per-file counting, download-speed ticks)
     * never triggers an animation.
     */
    private void showStatusImage(String resource, boolean entrance) {
        Image image = resource == null ? null : statusImages.get(resource);
        if (image == null) {
            hideStatusImage();
            return;
        }
        crossFadeTo(image, entrance);
    }

    /**
     * Cross-fade to a new status illustration over ~200ms using the two stacked
     * ImageViews (statusBack fades in on top while statusFront fades out beneath),
     * with an optional 0.94→1.0 scale-in for SUCCESS/ERROR. If a new phase
     * interrupts a running fade, the running animation is stopped first and the
     * latest art is put straight onto the top view — an older phase's art can
     * never cover the new state.
     */
    private void crossFadeTo(Image image, boolean entrance) {
        // No actual change: the requested art is already the settled display
        // (statusFade == null → statusFront) or already the in-flight target
        // (statusFade running → statusBack). Counting / speed / percentage
        // ticks land here and animate nothing.
        if (statusStack.isVisible()
                && (statusFade == null
                        ? statusFront.getImage() == image
                        : statusBack.getImage() == image)) {
            return;
        }
        stopStatusFade();
        // Incoming art goes on the top view, above the one currently shown.
        statusBack.setImage(image);
        statusBack.toFront();
        statusBack.setOpacity(0.0);
        statusBack.setScaleX(entrance ? ENTRANCE_SCALE_FROM : 1.0);
        statusBack.setScaleY(entrance ? ENTRANCE_SCALE_FROM : 1.0);
        statusBack.setVisible(true);
        statusBack.setManaged(true);
        statusStack.setVisible(true);
        statusStack.setManaged(true);

        FadeTransition fadeIn = new FadeTransition(Duration.millis(CROSS_FADE_MS), statusBack);
        fadeIn.setFromValue(0.0);
        fadeIn.setToValue(1.0);
        FadeTransition fadeOut = new FadeTransition(Duration.millis(CROSS_FADE_MS), statusFront);
        fadeOut.setFromValue(1.0);
        fadeOut.setToValue(0.0);
        ParallelTransition fade = new ParallelTransition(fadeIn, fadeOut);
        if (entrance) {
            ScaleTransition scale = new ScaleTransition(Duration.millis(ENTRANCE_SCALE_MS), statusBack);
            scale.setFromX(ENTRANCE_SCALE_FROM);
            scale.setToX(1.0);
            scale.setFromY(ENTRANCE_SCALE_FROM);
            scale.setToY(1.0);
            fade.getChildren().add(scale);
        }
        fade.setOnFinished(e -> {
            // Old layer fully faded out: release its art and make the incoming
            // view the settled front.
            statusFront.setImage(null);
            statusFront.setVisible(false);
            statusFront.setManaged(false);
            statusFront.setOpacity(1.0);
            statusFront.setScaleX(1.0);
            statusFront.setScaleY(1.0);
            ImageView swap = statusFront;
            statusFront = statusBack;
            statusBack = swap;
            statusFade = null;
        });
        statusFade = fade;
        fade.play();
    }

    /** Stop any in-flight image cross-fade (the next show re-starts cleanly). */
    private void stopStatusFade() {
        if (statusFade != null) {
            statusFade.stop();
            statusFade = null;
        }
    }

    /** Hide the status illustration (the default state). */
    private void hideStatusImage() {
        stopStatusFade();
        statusFront.setImage(null);
        statusBack.setImage(null);
        statusFront.setVisible(false);
        statusFront.setManaged(false);
        statusBack.setVisible(false);
        statusBack.setManaged(false);
        statusStack.setVisible(false);
        statusStack.setManaged(false);
    }

    // ── Status illustration preload ────────────────────────────────

    /** All status-art resources, decoded once at view startup. */
    private static final String[] STATUS_ART = {
        IMG_PREPARING, IMG_UPDATER, IMG_CHECKING, IMG_DOWNLOADING,
        IMG_CLEANING, IMG_SUCCESS, IMG_ERROR,
    };

    /**
     * Decode every status illustration once at startup and cache it, so a phase
     * switch only repaints the ImageView — it never re-decodes from the JAR.
     * Images load in the background (they arrive on the FX thread when ready);
     * a missing or corrupt resource is simply not cached and degrades to the
     * hidden slot, exactly as before.
     */
    private void preloadStatusImages() {
        for (String resource : STATUS_ART) {
            Image image = loadStatusImage(resource);
            if (image != null) {
                statusImages.put(resource, image);
            }
        }
    }

    /**
     * Load one status image at its native resolution — the ImageView scales it
     * to the 64×64 slot with preserveRatio + smooth, so 128×128+ transparent PNG
     * sources are supported directly. Returns null (→ safe degradation) if the
     * resource is missing or the decode fails.
     */
    private Image loadStatusImage(String resource) {
        java.net.URL url = getClass().getResource(resource);
        if (url == null) {
            return null;
        }
        Image image = new Image(url.toExternalForm());
        if (image.isError()) {
            return null;
        }
        image.errorProperty().addListener((obs, wasError, isError) -> {
            if (isError) {
                // Drop the broken image so future shows degrade, and if it is
                // currently on display, hide the slot.
                statusImages.remove(resource);
                if (statusFront.getImage() == image || statusBack.getImage() == image) {
                    hideStatusImage();
                }
            }
        });
        return image;
    }

    /** Dev/diagnostic (used by devtools/PhaseSwitchTest): the art resource
     *  currently on display after any fade settles, or null when the slot is
     *  hidden. Not part of the {@link UpdateView} contract. */
    String displayedStatusImageResource() {
        Image shown = statusFront.getImage();
        if (shown == null) {
            return null;
        }
        for (Map.Entry<String, Image> e : statusImages.entrySet()) {
            if (e.getValue() == shown) {
                return e.getKey();
            }
        }
        return null;
    }

    // ── Header micro-transition ────────────────────────────────────

    /**
     * Very light opacity transition on the status title/description when the
     * phase actually changes (~130ms). The caller sets the new phase's text
     * right after setPhase returns, in the same FX-thread call, so what the
     * user sees is the new text fading in. Counting, speed and percentage
     * updates never change the phase, so they never reach this method.
     */
    private void animateHeaderFade() {
        if (headerFade != null) {
            headerFade.stop();
            headerFade = null;
        }
        lblStatus.setOpacity(0.0);
        lblDescription.setOpacity(0.0);
        FadeTransition title = new FadeTransition(Duration.millis(HEADER_FADE_MS), lblStatus);
        title.setFromValue(0.0);
        title.setToValue(1.0);
        FadeTransition desc = new FadeTransition(Duration.millis(HEADER_FADE_MS), lblDescription);
        desc.setFromValue(0.0);
        desc.setToValue(1.0);
        ParallelTransition fade = new ParallelTransition(title, desc);
        fade.setOnFinished(e -> {
            lblStatus.setOpacity(1.0);
            lblDescription.setOpacity(1.0);
            headerFade = null;
        });
        headerFade = fade;
        fade.play();
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    /** Business CHECKING status carries "{checked}/{total}", e.g. "Checked: 247/1247". */
    private static final Pattern FILE_COUNT_PATTERN = Pattern.compile("(\\d+)/(\\d+)");

    /** Parse "{checked}/{total}" out of a business CHECKING status string. */
    private static int[] extractFileCounts(String status) {
        if (status == null) {
            return null;
        }
        Matcher m = FILE_COUNT_PATTERN.matcher(status);
        if (m.find()) {
            try {
                return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** Render a count with thousands grouping, e.g. 1247 → "1,247". */
    private static String formatCount(int n) {
        return String.format("%,d", n);
    }

    /** Natural plural for a file count, e.g. 1 → "1 file", 3 → "3 files". */
    private static String formatFiles(int n) {
        return formatCount(n) + (n == 1 ? " file" : " files");
    }

    // ── Window close handling ─────────────────────────────────────

    /** True while the update flow is still running (non-terminal phases). */
    private boolean isUpdateInProgress() {
        return phase == UpdatePhase.PREPARING
                || phase == UpdatePhase.CHECKING
                || phase == UpdatePhase.DOWNLOADING
                || phase == UpdatePhase.CLEANING;
    }

    /**
     * Intercept the window close request. While an update is running the close
     * is consumed and the user is asked to confirm; the terminal SUCCESS/ERROR
     * phases close directly without a second prompt.
     */
    private void onCloseRequestedByUser(javafx.event.Event event) {
        if (isUpdateInProgress()) {
            event.consume();
            confirmQuit();
        } else {
            listener.onWindowClosed();
        }
    }

    /**
     * Ask whether to abandon the running update. The default action stays with
     * the update; only an explicit "Skip update" invokes the existing
     * {@link UpdateViewListener} close flow. Closing the dialog also counts as
     * staying. The dialog shares the main window's stylesheet, and the skip
     * button gets the {@code danger-button} class so it renders as the
     * destructive action.
     */
    private void confirmQuit() {
        Alert alert = createQuitAlert();
        alert.showAndWait().ifPresent(choice -> {
            if (choice == quitSkipType) {
                listener.onWindowClosed();
                stage.close();
            }
        });
    }

    /**
     * Build the "Quit update?" confirmation. Exposed as a factory (rather than
     * constructed inline) so the screenshot harness can render it. Uses
     * {@link Alert.AlertType#NONE} so no default Question icon appears — the
     * dialog is deliberately icon-free to match the flat visual system.
     */
    Alert createQuitAlert() {
        Alert alert = new Alert(Alert.AlertType.NONE);
        alert.setTitle("Quit update?");
        alert.setHeaderText(null);
        alert.setContentText("The update is still in progress. Skipping it may leave Minecraft out of date.");
        ButtonType stay = new ButtonType("Keep updating", ButtonBar.ButtonData.OK_DONE);
        quitSkipType = new ButtonType("Skip update", ButtonBar.ButtonData.OTHER);
        alert.getButtonTypes().setAll(stay, quitSkipType);
        alert.initOwner(stage);
        // Apply the same visual system as the main window.
        if (stylesheet != null) {
            alert.getDialogPane().getStylesheets().add(stylesheet);
        }
        alert.getDialogPane().getStyleClass().add("root");
        // "Skip update" is the destructive action — style it red.
        Button skipButton = (Button) alert.getDialogPane().lookupButton(quitSkipType);
        skipButton.getStyleClass().add("danger-button");
        // Enter / the default stays with the update.
        ((Button) alert.getDialogPane().lookupButton(stay)).setDefaultButton(true);
        return alert;
    }

    // ── Construction ──────────────────────────────────────────────

    private void initUI(UiModel model) {
        stage.setTitle("Minecraft Update Check");
        // Forward the user closing the window to the flow controller, gated by
        // the in-progress confirmation.
        stage.setOnCloseRequest(e -> onCloseRequestedByUser(e));

        // Style classes (mapped in ui.css). The root also carries the terminal
        // state classes maintained by setPhase.
        root.getStyleClass().add("root");

        // Status header: reserved status-illustration slot + title/subtitle.
        // The slot starts hidden and appears with the first phase's art; a
        // missing/corrupt resource keeps it hidden (safe degradation).
        lblStatus.getStyleClass().add("status-title");
        lblDescription.getStyleClass().add("status-description");
        lblDescription.setWrapText(true);
        // The display slot stays a fixed 64×64 square; the ImageViews scale the
        // (possibly 128×128+) transparent PNG sources down with preserveRatio +
        // smooth fit. Decode happens once in preloadStatusImages, not per phase.
        statusFront.getStyleClass().add("status-image");
        statusFront.setPreserveRatio(true);
        statusFront.setSmooth(true);
        statusFront.setFitWidth(STATUS_IMAGE_SIZE);
        statusFront.setFitHeight(STATUS_IMAGE_SIZE);
        statusBack.setPreserveRatio(true);
        statusBack.setSmooth(true);
        statusBack.setFitWidth(STATUS_IMAGE_SIZE);
        statusBack.setFitHeight(STATUS_IMAGE_SIZE);
        statusStack.getChildren().addAll(statusFront, statusBack);
        hideStatusImage();
        preloadStatusImages();
        VBox statusText = new VBox(4, lblStatus, lblDescription);
        HBox statusHeader = new HBox(12, statusStack, statusText);
        statusHeader.getStyleClass().add("status-header");
        statusHeader.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(statusText, Priority.ALWAYS);

        // Overall progress area: bar + percent label.
        overallArea.getStyleClass().add("overall-progress");
        lblOverallPct.getStyleClass().add("pct");
        lblOverallPct.setPrefWidth(44);
        HBox.setHgrow(overallBar, Priority.ALWAYS);
        overallArea.getChildren().addAll(overallBar, lblOverallPct);
        overallArea.setAlignment(Pos.CENTER_LEFT);
        overallBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);

        // Current-file area: path, bar, speed. Hidden until a download becomes
        // active; the phase title above already names the action.
        dlArea.getStyleClass().add("file-area");
        lblDlFile.getStyleClass().add("file-path");
        dlBar.getStyleClass().add("file-progress");
        lblDlSpeed.getStyleClass().add("download-speed");
        dlArea.getChildren().addAll(lblDlFile, dlBar, lblDlSpeed);
        dlArea.setVisible(false);
        dlArea.setManaged(false);

        // Details area: Server URL, Game Directory and the full log. Collapsed
        // in normal mode, expanded in debug mode (and on error).
        detailsPane.getStyleClass().add("details-pane");
        lblGameDir.setText("Game dir: " + model.gameDir);
        logArea.getStyleClass().add("log");
        logArea.setEditable(false);
        logArea.setWrapText(false);
        logArea.setPrefRowCount(6);
        VBox detailsContent = new VBox(6, lblServer, lblGameDir, logArea);
        detailsPane.setContent(detailsContent);
        // No expand/collapse animation: the window height is sized to the
        // Details content, and an animated pane would interpolate its preferred
        // height over ~350ms and make that measurement unreliable. Round-3
        // explicitly defers animations until the status art is final.
        detailsPane.setAnimated(false);
        detailsPane.setExpanded(debug);

        // Root layout — the status header (illustration + text) is the first
        // row; lblDescription no longer needs a wrap flag set here.
        // Roomier canvas: wider horizontal padding and a little more vertical
        // breathing room, inside a shorter normal-mode window.
        root.setPadding(new Insets(18, 22, 18, 22));
        root.getChildren().addAll(statusHeader, overallArea, dlArea, detailsPane);

        // Debug footer — only present in debug mode, enabled by the controller
        // once the flow allows the user to close. A separator hairline above
        // the right-aligned button anchors it as a footer row rather than a
        // control left floating in the corner.
        if (debug) {
            btnClose.getStyleClass().add("debug-close-button");
            btnClose.setDisable(true);
            btnClose.setOnAction(e -> listener.onCloseRequested());
            Separator footerLine = new Separator();
            footerLine.getStyleClass().add("debug-footer-separator");
            HBox bottom = new HBox(btnClose);
            bottom.getStyleClass().add("debug-footer");
            bottom.setAlignment(Pos.CENTER_RIGHT);
            root.getChildren().addAll(footerLine, bottom);
        }

        // Persistent bottom copyright line — centred and muted. Added after the
        // debug footer so it is always the last row; the content-driven window
        // sizing (applyWindowHeight) already accounts for its extra height.
        // The label grows (VBox.setVgrow ALWAYS) to absorb the leftover height in
        // the short collapsed states, so its text is pinned to the bottom edge of
        // the window instead of floating above dead space; its pref height is
        // unchanged, so it never inflates the content-driven window size.
        lblFooter.getStyleClass().add("footer-copyright");
        lblFooter.setMaxWidth(Double.MAX_VALUE);
        lblFooter.setMaxHeight(Double.MAX_VALUE);
        lblFooter.setAlignment(Pos.BOTTOM_CENTER);
        lblFooter.setText(FOOTER_COPYRIGHT);
        VBox.setVgrow(lblFooter, Priority.ALWAYS);
        root.getChildren().add(lblFooter);

        // Apply the shared visual system (ui.css) — normal and debug alike.
        // Short by default; the window grows when Details expands (debug mode
        // and the error state) so collapsed layouts never sit in a tall window
        // full of dead space.
        scene = new Scene(root, WINDOW_WIDTH, WINDOW_HEIGHT_COLLAPSED);
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet);
        }
        stage.setScene(scene);
        stage.setMinWidth(WINDOW_WIDTH - 40);
        stage.setMinHeight(WINDOW_HEIGHT_COLLAPSED);
        detailsPane.expandedProperty().addListener((obs, wasExpanded, expanded) ->
                applyWindowHeight());
    }

    /**
     * Schedule a content-driven window resize. The measurement is deferred to
     * the next pulse so the window's insets and the current layout are known —
     * measuring immediately after, e.g., setExpanded(true) can see the stage
     * before it has fully realised its size.
     */
    private void applyWindowHeight() {
        if (stage.getScene() == null || !stage.isShowing()) {
            return;
        }
        Platform.runLater(this::resizeToContent);
    }

    /**
     * Resize the window so the client area (the scene) matches the content's
     * preferred height. The height is content-driven — never a fixed expanded
     * height — so Error / Debug states take exactly the space they need instead
     * of leaving dead space at the bottom; the collapsed window keeps its
     * deliberate short height as a floor. The Details pane animates nothing
     * (setAnimated(false)), so its expanded content height is exact at measure
     * time. The final size is clamped to the visible screen and the window is
     * re-centred (see {@link #fitWindowToScreen}), so expanding Details never
     * pushes the window off-screen, at any DPI.
     */
    private void resizeToContent() {
        if (stage.getScene() == null || !stage.isShowing()) {
            return;
        }
        double chrome = windowChrome();
        if (chrome <= 0) {
            // Window not fully realised yet — a later scheduled resize retries.
            return;
        }
        Scene scene = stage.getScene();
        scene.getRoot().applyCss();
        double width = scene.getWidth();
        double pref = width > 0 ? scene.getRoot().prefHeight(width) : scene.getRoot().prefHeight(-1);
        double target = Math.max(pref, WINDOW_HEIGHT_COLLAPSED) + chrome;
        fitWindowToScreen(target);
    }

    /**
     * Size the window to the given total height (content + chrome) and keep it
     * centred on the screen that currently contains it. The height is clamped
     * to the visible screen with a small margin, so an expanded Details pane
     * never leaves the visible screen — even at high DPI or on a small logical
     * screen (the measurements here are in JavaFX logical pixels, so the clamp
     * is DPI-invariant). A deliberately off-screen window (the dev screenshot
     * harness) is resized but never yanked back onto the visible screen.
     */
    private void fitWindowToScreen(double targetHeight) {
        Rectangle2D bounds = currentScreenVisualBounds();
        double margin = 24;
        double maxHeight = Math.max(WINDOW_HEIGHT_COLLAPSED, bounds.getHeight() - margin);
        double maxWidth = Math.max(WINDOW_WIDTH, bounds.getWidth() - margin);
        double chrome = windowChrome();
        double collapsed = WINDOW_HEIGHT_COLLAPSED + chrome;
        double h = Math.min(Math.max(targetHeight, collapsed), maxHeight);
        double w = Math.min(stage.getWidth(), maxWidth);
        if (Math.abs(stage.getHeight() - h) > 1.0) {
            stage.setHeight(h);
        }
        if (Math.abs(stage.getWidth() - w) > 1.0) {
            stage.setWidth(w);
        }
        if (bounds.contains(stage.getX() + stage.getWidth() / 2.0,
                            stage.getY() + stage.getHeight() / 2.0)) {
            double x = bounds.getMinX() + (bounds.getWidth() - stage.getWidth()) / 2.0;
            double y = bounds.getMinY() + (bounds.getHeight() - stage.getHeight()) / 2.0;
            if (Math.abs(stage.getX() - x) > 1.0) {
                stage.setX(x);
            }
            if (Math.abs(stage.getY() - y) > 1.0) {
                stage.setY(y);
            }
        }
    }

    /**
     * The visible bounds of the screen that currently contains the window's
     * centre, falling back to the primary screen (e.g. before the window has a
     * real position).
     */
    private Rectangle2D currentScreenVisualBounds() {
        Screen screen = Screen.getPrimary();
        double cx = stage.getX() + stage.getWidth() / 2.0;
        double cy = stage.getY() + stage.getHeight() / 2.0;
        for (Screen s : Screen.getScreens()) {
            if (s.getVisualBounds().contains(cx, cy)) {
                screen = s;
                break;
            }
        }
        return screen.getVisualBounds();
    }

    /**
     * Vertical window chrome (title bar + borders) between the stage and the
     * scene. Measured once while the window is still at its initial size — the
     * scene lags the stage during resizes, so re-measuring {@code
     * stage.getHeight() - scene.getHeight()} mid-resize would inflate the
     * chrome and oversize the window.
     */
    private double windowChrome() {
        if (chrome > 0) {
            return chrome;
        }
        Scene scene = stage.getScene();
        if (scene == null || scene.getHeight() <= 0 || !stage.isShowing()) {
            return 0;
        }
        chrome = stage.getHeight() - scene.getHeight();
        return chrome;
    }
}
