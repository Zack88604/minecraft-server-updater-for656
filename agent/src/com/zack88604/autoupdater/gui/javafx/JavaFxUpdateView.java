package com.zack88604.autoupdater.gui.javafx;

import com.zack88604.autoupdater.gui.api.DownloadProgress;
import com.zack88604.autoupdater.gui.api.UpdatePhase;
import com.zack88604.autoupdater.gui.api.UpdateSummary;
import com.zack88604.autoupdater.gui.api.UpdateUiState;
import com.zack88604.autoupdater.gui.api.UpdateView;
import javafx.animation.Animation;
import javafx.animation.AnimationTimer;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
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
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.WindowEvent;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JavaFX implementation of the toolkit-agnostic {@link UpdateView} contract,
 * parallel to the Swing {@code SwingUpdateView}. Runs only inside the helper JVM.
 *
 * <p>Pure View: it creates the Stage/Scene, renders the six update phases from a
 * complete {@link UpdateUiState} snapshot and forwards user actions (window close,
 * debug close button) to a {@link JavaFxViewListener}, which the helper entry point
 * relays over the protocol channel. It holds no reference to the update service,
 * owns no threads and never queries business state. All methods must be invoked on
 * the JavaFX Application Thread; {@code JavaFxEntryPoint} marshals every protocol
 * message through {@link Platform#runLater}.</p>
 *
 * <p>The phase is carried explicitly by the snapshot, so the view never infers it
 * from status text. While an update is in progress the window close request is
 * intercepted and the user must confirm quitting; the update is paused while the
 * Quit-update dialog is open (begin/cancel close confirmation) and in the
 * terminal SUCCESS/ERROR phases the close request is honoured directly.</p>
 *
 * <p>The window is frameless ({@link StageStyle#TRANSPARENT}): the custom title
 * bar carries a × button that fires the same {@code WINDOW_CLOSE_REQUEST} the
 * system title bar used to, so every close decision flows through the identical
 * {@code onCloseRequest} path (confirm / terminal close / UpdateViewActions
 * lifecycle). The title bar is also the drag region. The Quit-update dialog is
 * frameless too and shows its title as an internal header instead of a system
 * title bar.</p>
 *
 * <p><b>第一阶段 §强制约束 1</b>: the log tail is display-only. This view does not
 * derive {@code cleanScanned}/{@code cleanRemoved} counts from the (possibly
 * truncated) log — the CLEANING phase shows a degraded descriptive subtitle and
 * whatever {@code [DEL]}/{@code [SKIP]} lines actually arrived. <b>§强制约束 2</b>:
 * the Details log is rendered as a whole from the snapshot's log lines (an omission
 * marker is prepended by the codec when history was not transmitted), idempotently —
 * re-rendering the same state changes nothing.</p>
 */
final class JavaFxUpdateView implements UpdateView {

    private final Stage stage;
    private final JavaFxViewListener listener;
    private final boolean debug;

    // Window sizing: normal mode is deliberately short (Details collapsed).
    // Expanding Details (debug mode or error state) grows the window to its
    // content's preferred height — never a fixed expanded height, so Error /
    // Debug states don't leave dead space at the bottom (see applyWindowHeight).
    // The window is frameless (WINDOW_STYLE), so the custom title bar lives
    // inside the scene; the collapsed floor therefore includes TITLE_BAR_HEIGHT.
    private static final double WINDOW_WIDTH = 520;
    private static final double WINDOW_HEIGHT_COLLAPSED = 300;

    // Reserved status-illustration slot: the display size the ImageViews fit
    // the (possibly 128×128+) transparent PNG sources down to.
    private static final double STATUS_IMAGE_SIZE = 64;

    // Round-3 micro-animation timings (视觉精修第三步要求.md §2). The ~200ms
    // image cross-fade is the only "real" animation; the SUCCESS/ERROR scale-in
    // and the header opacity transition are deliberately short and light.
    private static final double CROSS_FADE_MS = 200;
    private static final double ENTRANCE_SCALE_MS = 160;
    private static final double HEADER_FADE_MS = 130;
    private static final double ENTRANCE_SCALE_FROM = 0.94;

    // Progress motion is deliberately short: every new real snapshot interrupts
    // the existing keyframes and retargets from the value currently on screen.
    // The two Timeline instances are created once and reused for the lifetime of
    // the view, so high-frequency render calls cannot build an animation queue.
    private static final double PROGRESS_TWEEN_MS = 240;
    private static final double OVERALL_SHIMMER_SWEEP_MS = 1500;
    private static final double OVERALL_SHIMMER_PAUSE_MS = 1050;
    private static final double FILE_SHIMMER_SWEEP_MS = 880;
    private static final double FILE_SHIMMER_PAUSE_MS = 620;

    // Quit-update dim overlay (UI修复.md 一): a ~120ms fade in, matching the
    // existing micro-animation budget. While the "Quit update?" confirmation
    // dialog is open the main window is dimmed to OVERLAY_OPACITY so the dialog
    // becomes the clear visual focus. The overlay's CSS background is solid
    // black, so OVERLAY_OPACITY is the *effective* dimming level (0.35 = 35%
    // black) — deepened from 0.25 at the user's request; the two are never
    // multiplied, which had diluted the original 0.25×0.25 to a barely visible
    // ~6%.
    private static final double OVERLAY_FADE_MS = 120;
    private static final double OVERLAY_OPACITY = 0.35;

    private static final String FOOTER_COPYRIGHT =
            "Developed by Zack88604 · MIT License · UI redesign by Eternity_Riguru";

    // Frameless-window decoration. TRANSPARENT is preferred over UNDECORATED
    // (UI美化.md / 第二轮 ui美化.md): the scene is transparent and the window
    // root paints the dark background with WINDOW_RADIUS rounded corners, so
    // the desktop shows through the four corners and the drop shadow
    // composites cleanly over the desktop — no black right-angle base. The
    // main window and the "Quit update?" dialog share the same radius. No
    // business behaviour depends on the window chrome; the close path, helper
    // lifecycle, terminal state, preflight and preset handling all run through
    // the exact same code as a decorated window.
    private static final StageStyle WINDOW_STYLE = StageStyle.TRANSPARENT;
    /** Height of the custom title bar (drag region + window close button). */
    private static final double TITLE_BAR_HEIGHT = 34;

    // Rounded frameless window (第二轮 ui美化.md 一). The main window shares the
    // dialog's corner radius so the two form one design language. The scene is
    // transparent and the window root paints the dark background with these
    // rounded corners, so the desktop shows through the four corners — no black
    // right-angle base.
    private static final double WINDOW_RADIUS = 12;

    // Shadow margin reserved INSIDE the transparent scene, around the rounded
    // client area, so the drop shadow composites over the desktop instead of
    // being clipped at the scene edge. Larger on the bottom because the shadow
    // is offset downward.
    private static final double SHADOW_TOP = 12;
    private static final double SHADOW_SIDE = 18;
    private static final double SHADOW_BOTTOM = 24;

    /** Scene height of the collapsed window, including the title bar and the
     *  shadow margin. The content floor stays WINDOW_HEIGHT_COLLAPSED. */
    private static double collapsedSceneHeight() {
        return WINDOW_HEIGHT_COLLAPSED + TITLE_BAR_HEIGHT + SHADOW_TOP + SHADOW_BOTTOM;
    }

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
    private final StackPane overallBarStack = new StackPane();
    private final Pane overallShimmerLayer = new Pane();
    private final Region overallShimmer = new Region();
    private final Label lblOverallPct = new Label("");

    // Current-file / per-download area
    private final VBox dlArea = new VBox(4);
    private final Label lblDlFile = new Label();
    private final ProgressBar dlBar = new ProgressBar(0);
    private final StackPane dlBarStack = new StackPane();
    private final Pane dlShimmerLayer = new Pane();
    private final Region dlShimmer = new Region();
    private final Label lblDlSpeed = new Label("");

    // One reusable interpolation Timeline per bar. They are stopped on every
    // retarget, reset, file switch and terminal transition.
    private final Timeline overallProgressTween = new Timeline();
    private final Timeline fileProgressTween = new Timeline();
    private boolean overallProgressInitialized;
    private boolean fileProgressInitialized;
    private double lastOverallTarget = Double.NaN;
    private double lastFileTarget = Double.NaN;
    private String interpolatedFilePath;

    // ERROR snapshots intentionally carry an inactive DownloadProgress. Keep the
    // last real, non-zero values in the View so terminal rendering can preserve
    // useful information without changing UpdateUiState or the reducer.
    private boolean hasMeaningfulOverallProgress;
    private double lastMeaningfulOverallProgress;
    private boolean hasMeaningfulFileProgress;
    private double lastMeaningfulFileProgress;
    private String lastMeaningfulFilePath;
    private String lastMeaningfulFileSpeed;

    // A single pulse-driven shimmer clock animates both highlights. Unlike a
    // Timeline per render, it has a fixed allocation and is started/stopped only
    // when entering/leaving DOWNLOADING.
    private long shimmerEpochNanos;
    private boolean shimmerRunning;
    private final AnimationTimer shimmerTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            if (shimmerEpochNanos == 0L) {
                shimmerEpochNanos = now;
            }
            double elapsedMs = (now - shimmerEpochNanos) / 1_000_000.0;
            positionShimmer(overallShimmer, overallShimmerLayer, elapsedMs,
                    OVERALL_SHIMMER_SWEEP_MS, OVERALL_SHIMMER_PAUSE_MS);
            positionShimmer(dlShimmer, dlShimmerLayer, elapsedMs,
                    FILE_SHIMMER_SWEEP_MS, FILE_SHIMMER_PAUSE_MS);
            updateShimmerVisibility();
        }
    };

    // Counters backing the informational subtitles. Derived from the snapshot's
    // structured fields (CHECKING status "{checked}/{total}" and per-file phase
    // transitions), never from the truncated log (第一阶段 §强制约束 1).
    private int filesSeen;   // per-file downloads started in this run
    private int filesTotal;  // managed file count, captured from CHECKING status
    private String lastDlPath;  // guards filesSeen against per-file progress ticks

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
    // stacked ImageViews so a phase switch is a real cross-fade (see crossFadeTo).
    private final StackPane statusStack = new StackPane();
    private ImageView statusFront = new ImageView();
    private ImageView statusBack = new ImageView();

    // Phase art decoded once at startup (see preloadStatusImages) and reused for
    // every phase switch — never re-decoded from the JAR per render.
    private final Map<String, Image> statusImages = new HashMap<>();

    // In-flight micro-animations, kept so a new phase switch can stop them
    // safely and jump straight to the latest state (no stale overlay).
    private Animation statusFade;
    private Animation headerFade;

    // Quit-update dim overlay (UI修复.md 一): fills the main window's rounded
    // client area and fades in while the "Quit update?" confirmation dialog is
    // open, so the dialog is the clear visual focus. It sits on top of the
    // window root inside the scene frame and is pickable over its whole area, so
    // the controls beneath are unreachable while it is shown. Faded out and
    // hidden once the dialog closes (Keep updating / Skip update / dismiss).
    private final StackPane quitOverlay = new StackPane();
    private Animation quitOverlayFade;

    // Root layout — carries the .success-state / .error-state state classes.
    // A BorderPane so the custom title bar (drag region + ×) spans the full
    // window width above the padded content column (frameless window).
    private final BorderPane root = new BorderPane();

    // Transparent scene root that holds `root` and reserves the shadow margin
    // around it (rounded frameless window). The scene fill is TRANSPARENT, so
    // the desktop shows through the corners; `root` paints the rounded dark
    // background + drop shadow.
    private final StackPane frame = new StackPane();

    // Custom title bar (frameless window): the title label doubles as the drag
    // region; the × button reuses the normal close-request path.
    private final HBox titleBar = new HBox();
    private final Label lblWindowTitle = new Label("Minecraft Update Check");
    private final Button btnWindowClose = new Button("✕");
    // Drag offsets captured on mouse press, applied on mouse drag.
    private double dragX;
    private double dragY;

    /** External form of /ui.css, or null if the stylesheet is missing. */
    private final String stylesheet;

    /** The scene backing the window; resized when Details expands/collapses. */
    private Scene scene;

    /** The destructive "Skip update" action, created per Quit-alert instance. */
    private ButtonType quitSkipType;

    /** The last log text rendered, for idempotent whole-set replacement. */
    private String lastRenderedLog;

    /** True once the controller asked us to close — suppresses the onCloseRequest
     *  handler from re-reporting {@code windowClosed} for our own programmatic
     *  {@code stage.close()} (v2.1 §11: a must-handle close-lifecycle detail). */
    private boolean closing;

    private UpdatePhase phase = UpdatePhase.PREPARING;

    JavaFxUpdateView(JavaFxViewListener listener, boolean debug, String gameDir) {
        this.listener = listener;
        this.debug = debug;
        this.stage = new Stage();
        java.net.URL css = getClass().getResource("/ui.css");
        this.stylesheet = css == null ? null : css.toExternalForm();
        initUI(gameDir);
    }

    // ── UpdateView ────────────────────────────────────────────────

    /** Show the window. Must be called on the JavaFX Application Thread.
     *  <p>This is the ONLY place the window position is set: the freshly shown
     *  window is centred once. Every later render resizes content but never
     *  moves the window, so a dragged window stays where the user put it
     *  (UI修复.md 二) — {@link #resizeToContent()} is strictly size-only.</p> */
    @Override
    public void open() {
        stage.show();
        centerFirstOpen();
        applyWindowHeight();
    }

    /**
     * Centre the freshly shown window once on the screen that currently contains
     * it. JavaFX's own {@link Stage#centerOnScreen()} puts the window a third of
     * the way down the screen instead of at the geometric centre; the
     * pre-refactor {@code fitWindowToScreen} centred geometrically, so this keeps
     * first-open placement identical to that while remaining strictly a
     * first-show-only operation — later renders never move the window
     * (UI修复.md 二).
     */
    private void centerFirstOpen() {
        Rectangle2D bounds = currentScreenVisualBounds();
        double x = bounds.getMinX() + (bounds.getWidth() - stage.getWidth()) / 2.0;
        double y = bounds.getMinY() + (bounds.getHeight() - stage.getHeight()) / 2.0;
        stage.setX(x);
        stage.setY(y);
    }

    /**
     * Render a complete snapshot. Every structured field maps to a stable visual:
     * the phase picks the title/illustration, the overall/download areas come from
     * their dedicated fields, and the Details log is replaced whole (idempotently)
     * from the snapshot's log lines. Must be called on the JavaFX Application
     * Thread.
     */
    @Override
    public void render(UpdateUiState state) {
        setPhase(state.getPhase());
        applyHeader(state);
        applyOverall(state);
        applyDownload(state);
        updateShimmerVisibility();
        applyServer(state);
        applyLog(state);
        applyCloseButton(state);
    }

    /** Close the window. Must be called on the JavaFX Application Thread. */
    @Override
    public void close() {
        // Mark first so the stage.close() below does not re-report a user close.
        closing = true;
        stopStatusFade();
        if (headerFade != null) {
            headerFade.stop();
            headerFade = null;
        }
        stopQuitOverlayFade();
        stopProgressAnimations();
        stopShimmer();
        stage.close();
    }

    // ── Snapshot → visual mapping ─────────────────────────────────

    /**
     * The main title/subtitle per phase. The raw business status string is never
     * shown verbatim as the visual title; each phase maps to a stable main title,
     * and the count / detail is re-worded into the subtitle. The file path stays
     * in the current-file area, never in the title.
     */
    private void applyHeader(UpdateUiState state) {
        switch (state.getPhase()) {
            case PREPARING:
                // The updater self-update sub-state is rendered by applyDownload
                // (it overrides the header + illustration while a UPDATER download
                // is active). Otherwise the standard PREPARING header shows.
                if (isUpdaterDownload(state)) {
                    return;
                }
                lblStatus.setText("Preparing update…");
                lblDescription.setText("Connecting to update server");
                break;
            case CHECKING: {
                int[] counts = extractFileCounts(state.getStatus());
                if (counts != null) {
                    filesTotal = counts[1];
                }
                lblStatus.setText("Checking files…");
                lblDescription.setText(counts != null
                        ? formatCount(counts[0]) + " of " + formatCount(counts[1]) + " files checked"
                        : "Checking files…");
                break;
            }
            case DOWNLOADING: {
                String path = state.getDownloadProgress().isActive()
                        ? state.getDownloadProgress().getPath() : null;
                if (path != null && !path.equals(lastDlPath)) {
                    lastDlPath = path;
                    filesSeen++;
                }
                lblStatus.setText("Downloading update…");
                lblDescription.setText(filesTotal > 0
                        ? formatCount(filesSeen) + " of " + formatCount(filesTotal) + " files"
                        : formatCount(filesSeen) + " file(s)");
                break;
            }
            case CLEANING:
                lblStatus.setText("Cleaning up…");
                lblDescription.setText(state.getDescription() == null
                        || state.getDescription().isEmpty()
                        ? "Removing files that are no longer needed"
                        : state.getDescription());
                break;
            case SUCCESS: {
                UpdateSummary s = state.getSummary();
                if (s != null && s.getUpdatedFiles() > 0) {
                    // Success is split into a main title and a subtitle so the
                    // green accent marks only the headline.
                    lblStatus.setText("Update complete");
                    lblDescription.setText(formatFiles(s.getUpdatedFiles())
                            + " updated · Launching Minecraft…");
                } else {
                    lblStatus.setText("You're up to date");
                    lblDescription.setText("Launching Minecraft…");
                }
                break;
            }
            case ERROR: {
                String em = state.getErrorMessage();
                UpdateSummary s = state.getSummary();
                lblStatus.setText("Update failed");
                if (em != null && !em.isEmpty()) {
                    lblDescription.setText(em);
                } else if (s != null && s.getFailedFiles() > 0) {
                    lblDescription.setText(s.getFailedFiles() + " file(s) failed to update.");
                } else {
                    lblDescription.setText("");
                }
                break;
            }
        }
    }

    /** Set the overall progress bar and percentage for determinate phases. */
    private void applyOverall(UpdateUiState state) {
        UpdatePhase p = state.getPhase();
        if (p == UpdatePhase.ERROR) {
            stopProgressTween(overallProgressTween);
            if (!hasMeaningfulOverallProgress
                    && !state.isOverallProgressIndeterminate()
                    && state.getOverallProgressPercent() > 0) {
                hasMeaningfulOverallProgress = true;
                lastMeaningfulOverallProgress =
                        clamp(state.getOverallProgressPercent()) / 100.0;
            }
            if (hasMeaningfulOverallProgress) {
                setProgressDirect(overallBar, lastMeaningfulOverallProgress);
                overallProgressInitialized = true;
                lastOverallTarget = lastMeaningfulOverallProgress;
                lblOverallPct.setText(percentText(lastMeaningfulOverallProgress));
                showOverallPercent();
                overallArea.setVisible(true);
            } else {
                overallArea.setVisible(false);
            }
            return;
        }
        if (p == UpdatePhase.PREPARING || p == UpdatePhase.CLEANING) {
            return;   // setPhase already configured the indeterminate bar
        }
        double target = clamp(state.getOverallProgressPercent()) / 100.0;
        if (target > 0.0) {
            hasMeaningfulOverallProgress = true;
            lastMeaningfulOverallProgress = target;
        }
        boolean animate = p == UpdatePhase.DOWNLOADING
                && overallProgressInitialized
                && !Double.isNaN(lastOverallTarget)
                && target >= lastOverallTarget;
        boolean targetChanged = Double.isNaN(lastOverallTarget)
                || Math.abs(target - lastOverallTarget) >= 0.0001;
        if (targetChanged || overallProgressTween.getStatus() != Animation.Status.RUNNING) {
            setProgressTarget(overallBar, overallProgressTween, target, animate);
        }
        overallProgressInitialized = true;
        lastOverallTarget = target;
        lblOverallPct.setText(percentText(target));
        overallArea.setVisible(true);
    }

    /** Present the per-file / agent download snapshot (current-file area). */
    private void applyDownload(UpdateUiState state) {
        DownloadProgress dl = state.getDownloadProgress();
        if (state.getPhase() == UpdatePhase.ERROR) {
            stopProgressTween(fileProgressTween);
            if (hasMeaningfulFileProgress) {
                lblDlFile.setText(lastMeaningfulFilePath == null ? "" : lastMeaningfulFilePath);
                lblDlSpeed.setText(lastMeaningfulFileSpeed == null ? "" : lastMeaningfulFileSpeed);
                setProgressDirect(dlBar, lastMeaningfulFileProgress);
                fileProgressInitialized = true;
                lastFileTarget = lastMeaningfulFileProgress;
                showDownloadArea();
            } else {
                hideDownloadArea();
            }
            return;
        }
        if (!dl.isActive()) {
            hideDownloadArea();
            updateStatusImage(phase);
            applyWindowHeight();
            return;
        }
        if (isGuiRuntimeDownload(state)) {
            // A JavaFX UI runtime download is a sub-state of PREPARING, never a
            // DOWNLOADING phase: it is optional GUI infrastructure being prepared
            // before the real update (2B), so it must not shift the phase art.
            setPhase(UpdatePhase.PREPARING);
            lblStatus.setText("Preparing JavaFX UI runtime…");
            lblDescription.setText("Downloading JavaFX runtime");
            showStatusImage(IMG_PREPARING);
        } else if (isUpdaterDownload(state)) {
            // The updater self-update never exposes "agent" jargon in the normal
            // UI — it stays a sub-state of PREPARING with its own illustration.
            setPhase(UpdatePhase.PREPARING);
            lblStatus.setText("Updating updater…");
            lblDescription.setText("Preparing update components");
            showStatusImage(IMG_UPDATER);
        } else if (phase != UpdatePhase.DOWNLOADING) {
            // Defensive: the reducer normally carries the DOWNLOADING phase.
            setPhase(UpdatePhase.DOWNLOADING);
        }
        lblDlFile.setText(dl.getPath() == null ? "" : dl.getPath());
        if (dl.getTotalBytes() > 0) {
            double target = clampProgress(dl.getDownloadedBytes() / (double) dl.getTotalBytes());
            boolean fileChanged = interpolatedFilePath == null
                    || !interpolatedFilePath.equals(dl.getPath());
            if (fileChanged) {
                stopProgressTween(fileProgressTween);
                fileProgressInitialized = false;
                lastFileTarget = Double.NaN;
                interpolatedFilePath = dl.getPath();
                // A new 0% file must not inherit the previous file's ERROR cache.
                hasMeaningfulFileProgress = false;
                lastMeaningfulFilePath = null;
                lastMeaningfulFileSpeed = null;
            }
            if (target > 0.0) {
                hasMeaningfulFileProgress = true;
                lastMeaningfulFileProgress = target;
                lastMeaningfulFilePath = dl.getPath();
                lastMeaningfulFileSpeed = formatSpeed(dl.getBytesPerSecond());
            }
            boolean animate = phase == UpdatePhase.DOWNLOADING
                    && fileProgressInitialized
                    && !Double.isNaN(lastFileTarget)
                    && target >= lastFileTarget;
            boolean targetChanged = Double.isNaN(lastFileTarget)
                    || Math.abs(target - lastFileTarget) >= 0.0001;
            if (targetChanged || fileProgressTween.getStatus() != Animation.Status.RUNNING) {
                setProgressTarget(dlBar, fileProgressTween, target, animate);
            }
            fileProgressInitialized = true;
            lastFileTarget = target;
        } else {
            // Unknown content-length: indeterminate per-file bar.
            stopProgressTween(fileProgressTween);
            dlBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            fileProgressInitialized = false;
            lastFileTarget = Double.NaN;
        }
        lblDlSpeed.setText(formatSpeed(dl.getBytesPerSecond()));
        showDownloadArea();
    }

    private static boolean isUpdaterDownload(UpdateUiState state) {
        DownloadProgress dl = state.getDownloadProgress();
        return dl.isActive() && dl.getKind() == DownloadProgress.Kind.UPDATER;
    }

    private static boolean isGuiRuntimeDownload(UpdateUiState state) {
        DownloadProgress dl = state.getDownloadProgress();
        return dl.isActive() && dl.getKind() == DownloadProgress.Kind.GUI_RUNTIME;
    }

    /** Present the server state (inside Details). */
    private void applyServer(UpdateUiState state) {
        List<String> urls = state.getServerUrls();
        String current = state.getCurrentServer();
        lblServer.setText(urls.size() <= 1
                ? "Server: " + current
                : "Servers (" + urls.size() + "): " + current);
    }

    /**
     * Replace the Details log whole from the snapshot's log lines (idempotent:
     * an unchanged text is left untouched). While CLEANING, per-file "[SKIP]"
     * lines are suppressed in normal mode — they carry no real change and the
     * flood of them on a large managed tree is what wedges the FX thread; only
     * actual changes ("[DEL]") are shown. Debug mode renders every line verbatim.
     * The omission marker prepended by the codec for untransmitted history is
     * just another line and renders as-is. Lines outside CLEANING always render
     * verbatim. No business counts are derived from the tail (约束 1).
     */
    private void applyLog(UpdateUiState state) {
        boolean cleaning = state.getPhase() == UpdatePhase.CLEANING;
        StringBuilder sb = new StringBuilder();
        for (String line : state.getLogLines()) {
            if (cleaning && !debug && line.startsWith("  [SKIP]")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        String text = sb.toString();
        if (text.equals(lastRenderedLog)) {
            return;   // idempotent render
        }
        lastRenderedLog = text;
        logArea.setText(text);
        logArea.setScrollTop(Double.MAX_VALUE);   // keep the newest lines in view
    }

    /** Enable the debug close button only for terminal (closable) phases. */
    private void applyCloseButton(UpdateUiState state) {
        UpdatePhase p = state.getPhase();
        btnClose.setDisable(!(p == UpdatePhase.SUCCESS || p == UpdatePhase.ERROR));
    }

    // ── Phase rendering ───────────────────────────────────────────

    /**
     * Track the current phase and apply the phase-specific rendering. The root
     * carries the terminal state classes ({@code success-state} /
     * {@code error-state}); ui.css derives the title colour and related state
     * visuals from them. The mid-flow phases carry neither class.
     */
    private void setPhase(UpdatePhase p) {
        if (phase != p) {
            UpdatePhase previous = phase;
            phase = p;
            animateHeaderFade();
            root.getStyleClass().removeAll("success-state", "error-state");
            if (p != UpdatePhase.DOWNLOADING) {
                stopShimmer();
            }
            if (p == UpdatePhase.SUCCESS || p == UpdatePhase.ERROR) {
                stopProgressAnimations();
            }
            switch (p) {
            case PREPARING:
                overallArea.setVisible(true);
                if (previous == UpdatePhase.SUCCESS || previous == UpdatePhase.ERROR) {
                    clearRememberedProgress();
                }
            case CLEANING:
                // Indeterminate phases hide the percentage entirely, so a stale
                // value (e.g. the previous 55%) never lingers beside the bar.
                hideDownloadArea();
                clearOverallPercent();
                logArea.setPrefRowCount(6);
                break;
            case CHECKING:
            case DOWNLOADING:
                // Determinate phases show the percentage.
                hideDownloadArea();
                showOverallPercent();
                logArea.setPrefRowCount(6);
                overallArea.setVisible(true);
                if (p == UpdatePhase.DOWNLOADING) {
                    // The first real value in this phase is an anchor, not a
                    // transition from an unrelated CHECKING visual.
                    overallProgressInitialized = false;
                    startShimmer();
                }
                break;
            case SUCCESS:
                hideDownloadArea();
                showOverallPercent();
                logArea.setPrefRowCount(6);
                root.getStyleClass().add("success-state");
                break;
            case ERROR:
                // applyOverall/applyDownload restore the last meaningful real
                // values. They stay static and the error-state class recolours
                // only the completed fill.
                logArea.setPrefRowCount(10);
                detailsPane.setExpanded(true);
                root.getStyleClass().add("error-state");
                break;
            }
        }
        // Update the status illustration for the phase, then keep the window
        // sized to its content. This runs even when the phase is re-asserted
        // unchanged: the view starts in PREPARING, so the first PREPARING render
        // would otherwise short-circuit and its illustration would never appear.
        updateStatusImage(p);
        applyWindowHeight();
    }

    /** Hide the overall percentage label, clearing any stale text. */
    private void clearOverallPercent() {
        lblOverallPct.setText("");
        lblOverallPct.setVisible(false);
        lblOverallPct.setPrefWidth(0);
    }

    /** Show the overall percentage label next to the bar. */
    private void showOverallPercent() {
        lblOverallPct.setVisible(true);
        lblOverallPct.setPrefWidth(44);
    }

    /** Reveal the current-file area. Its row is always reserved in the layout,
     *  so showing it changes no heights and the window never resizes on the flip. */
    private void showDownloadArea() {
        dlArea.setVisible(true);
    }

    /** Hide and clear the current-file area. The row stays managed so the
     *  Details pane below keeps a fixed position. */
    private void hideDownloadArea() {
        stopProgressTween(fileProgressTween);
        fileProgressInitialized = false;
        lastFileTarget = Double.NaN;
        interpolatedFilePath = null;
        dlArea.setVisible(false);
        lblDlFile.setText("");
        dlBar.setProgress(0);
        lblDlSpeed.setText("");
    }

    private void setProgressTarget(ProgressBar bar, Timeline tween,
                                   double target, boolean animate) {
        double safeTarget = clampProgress(target);
        double current = clampProgress(bar.getProgress());
        stopProgressTween(tween);
        if (!animate || Math.abs(safeTarget - current) < 0.001) {
            setProgressDirect(bar, safeTarget);
            return;
        }
        tween.getKeyFrames().setAll(
                new KeyFrame(Duration.ZERO, new KeyValue(bar.progressProperty(), current)),
                new KeyFrame(Duration.millis(PROGRESS_TWEEN_MS),
                        new KeyValue(bar.progressProperty(), safeTarget, Interpolator.EASE_OUT)));
        tween.playFromStart();
    }

    private static void setProgressDirect(ProgressBar bar, double value) {
        bar.setProgress(clampProgress(value));
    }

    private static double clampProgress(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String percentText(double progress) {
        return Math.round(clampProgress(progress) * 100.0) + "%";
    }

    private static void stopProgressTween(Timeline tween) {
        tween.stop();
        tween.getKeyFrames().clear();
    }

    private void stopProgressAnimations() {
        stopProgressTween(overallProgressTween);
        stopProgressTween(fileProgressTween);
    }

    private void clearRememberedProgress() {
        hasMeaningfulOverallProgress = false;
        lastMeaningfulOverallProgress = 0.0;
        hasMeaningfulFileProgress = false;
        lastMeaningfulFileProgress = 0.0;
        lastMeaningfulFilePath = null;
        lastMeaningfulFileSpeed = null;
        interpolatedFilePath = null;
        lastOverallTarget = Double.NaN;
        lastFileTarget = Double.NaN;
        overallProgressInitialized = false;
        fileProgressInitialized = false;
    }

    private void startShimmer() {
        if (shimmerRunning) {
            return;
        }
        shimmerRunning = true;
        shimmerEpochNanos = 0L;
        shimmerTimer.start();
    }

    private void stopShimmer() {
        if (shimmerRunning) {
            shimmerTimer.stop();
            shimmerRunning = false;
        }
        shimmerEpochNanos = 0L;
        overallShimmer.setVisible(false);
        dlShimmer.setVisible(false);
    }

    private void updateShimmerVisibility() {
        boolean downloading = phase == UpdatePhase.DOWNLOADING && shimmerRunning;
        overallShimmer.setVisible(downloading && overallArea.isVisible()
                && overallBar.getProgress() > 0.0 && overallBar.getProgress() <= 1.0);
        dlShimmer.setVisible(downloading && dlArea.isVisible()
                && dlBar.getProgress() > 0.0 && dlBar.getProgress() <= 1.0);
    }

    private static void positionShimmer(Region shimmer, Pane layer,
                                        double elapsedMs, double sweepMs, double pauseMs) {
        double cycleMs = sweepMs + pauseMs;
        double withinCycle = elapsedMs % cycleMs;
        if (withinCycle >= sweepMs) {
            shimmer.setOpacity(0.0);
            return;
        }
        shimmer.setOpacity(1.0);
        double fraction = withinCycle / sweepMs;
        double distance = layer.getWidth() + shimmer.getWidth();
        shimmer.setTranslateX(-shimmer.getWidth() + distance * fraction);
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
        boolean entrance = phase == UpdatePhase.SUCCESS || phase == UpdatePhase.ERROR;
        showStatusImage(statusImageResource(phase), entrance);
    }

    /** Show the status illustration for a JAR resource, without an entrance. */
    private void showStatusImage(String resource) {
        showStatusImage(resource, false);
    }

    /**
     * Show the status illustration for a JAR resource from the preloaded cache
     * (never decoded on a phase switch). Status art is optional: a missing
     * resource or corrupt file just hides the slot. An image that differs from
     * what is on display is cross-faded in; a re-asserted identical image never
     * triggers an animation.
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
        if (statusStack.isVisible()
                && (statusFade == null
                        ? statusFront.getImage() == image
                        : statusBack.getImage() == image)) {
            return;
        }
        stopStatusFade();
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
     * Images load in the background; a missing or corrupt resource is simply not
     * cached and degrades to the hidden slot.
     */
    private void preloadStatusImages() {
        for (String resource : STATUS_ART) {
            Image image = loadStatusImage(resource);
            if (image != null) {
                statusImages.put(resource, image);
            }
        }
    }

    /** Load one status image at its native resolution. Returns null (→ safe
     *  degradation) if the resource is missing or the decode fails. */
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
                statusImages.remove(resource);
                if (statusFront.getImage() == image || statusBack.getImage() == image) {
                    hideStatusImage();
                }
            }
        });
        return image;
    }

    // ── Header micro-transition ────────────────────────────────────

    /**
     * Very light opacity transition on the status title/description when the
     * phase actually changes (~130ms). The caller sets the new phase's text
     * right after setPhase returns, in the same FX-thread call, so what the user
     * sees is the new text fading in.
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

    /** Render a download speed, e.g. 1.2 MB/s (matches me's FormatUtil). */
    private static String formatSpeed(double bytesPerSec) {
        if (bytesPerSec < 0) {
            bytesPerSec = 0;
        }
        if (bytesPerSec >= 1_000_000_000) return String.format("%.1f GB/s", bytesPerSec / 1_000_000_000);
        if (bytesPerSec >= 1_000_000)     return String.format("%.1f MB/s", bytesPerSec / 1_000_000);
        if (bytesPerSec >= 1_000)         return String.format("%.0f KB/s", bytesPerSec / 1_000);
        return String.format("%.0f B/s", bytesPerSec);
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
     * phases close directly (notifying the agent the window closed).
     */
    private void onCloseRequestedByUser(javafx.event.Event event) {
        if (closing) {
            // Our own programmatic close (controller → close → stage.close()):
            // let it proceed, but don't re-report it as a user action.
            return;
        }
        if (isUpdateInProgress()) {
            event.consume();
            confirmQuit();
        } else {
            listener.windowClosed();
        }
    }

    /**
     * The custom title-bar × button. It must behave exactly like clicking the
     * old system title-bar close, so it fires the same
     * {@link WindowEvent#WINDOW_CLOSE_REQUEST} the OS would and lets the normal
     * {@code onCloseRequest} path run unchanged: in-progress phases open the
     * Quit-update confirmation (begin/cancel/confirm close confirmation +
     * UpdateViewActions lifecycle), terminal phases report {@code windowClosed}
     * straight to the agent. It never calls {@code stage.close()} directly.
     * When the handler did not consume the request (terminal phase), the
     * platform would have hidden the window after delivering the close request —
     * emulate that here so the visible behaviour matches the decorated window;
     * the agent independently replies to {@code windowClosed} with close/exit.
     */
    private void requestCloseFromTitleBar() {
        WindowEvent closeRequest = new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST);
        stage.fireEvent(closeRequest);
        if (!closeRequest.isConsumed()) {
            stage.close();
        }
    }

    /**
     * Make a region drag the whole window: record where in the window the mouse
     * was pressed, then move the stage so the cursor stays at the same spot.
     * Only the title bar carries this handler, so the rest of the UI is not
     * draggable. Pressing the × button never starts a drag (Button consumes its
     * own presses, and a bubbled press whose target is the button is skipped).
     */
    private void installWindowDrag(javafx.scene.Node dragRegion) {
        dragRegion.setOnMousePressed(e -> {
            if (e.getTarget() instanceof Button) {
                return;   // let the × button handle its own press
            }
            dragX = e.getScreenX() - stage.getX();
            dragY = e.getScreenY() - stage.getY();
        });
        dragRegion.setOnMouseDragged(e -> {
            stage.setX(e.getScreenX() - dragX);
            stage.setY(e.getScreenY() - dragY);
        });
    }

    /**
     * Ask whether to abandon the running update. The update is paused at its next
     * safe checkpoint while the dialog is open ({@code beginCloseConfirmation}),
     * so it cannot race ahead of the user's decision. Only an explicit
     * "Skip update" invokes the close flow; "Keep updating" or dismissing the
     * dialog resumes the update ({@code cancelCloseConfirmation}). The dialog
     * shares the main window's stylesheet; Keep updating renders as the primary
     * green action and Skip update as a quiet, borderless secondary
     * (第二轮 ui美化.md 三) — neither is a red primary button.
     *
     * <p>While the dialog is open the main window is dimmed by the quit overlay
     * (UI修复.md 一): {@code showQuitOverlay()} fades it in just before the
     * dialog appears and {@code hideQuitOverlay()} fades it out as soon as the
     * dialog closes, whatever the user chose.</p>
     */
    private void confirmQuit() {
        listener.beginCloseConfirmation();
        showQuitOverlay();
        Alert alert = createQuitAlert();
        Optional<ButtonType> choice = alert.showAndWait();
        hideQuitOverlay();
        if (choice.isPresent() && choice.get() == quitSkipType) {
            listener.userRequestedClose();
            stage.close();
        } else {
            // "Keep updating", or the dialog was dismissed — resume the update.
            listener.cancelCloseConfirmation();
        }
    }

    // ── Quit-update dim overlay ──────────────────────────────────

    /**
     * Fade the quit overlay in over the main window. Runs on the FX thread just
     * before the confirmation dialog appears, so the dialog is the clear visual
     * focus. The overlay is made visible synchronously (so it is already in place
     * when the modal dialog opens) and only its opacity animates.
     */
    private void showQuitOverlay() {
        stopQuitOverlayFade();
        quitOverlay.setVisible(true);
        quitOverlay.setOpacity(0.0);
        FadeTransition fade = new FadeTransition(Duration.millis(OVERLAY_FADE_MS), quitOverlay);
        fade.setFromValue(0.0);
        fade.setToValue(OVERLAY_OPACITY);
        fade.setOnFinished(e -> quitOverlayFade = null);
        quitOverlayFade = fade;
        fade.play();
    }

    /**
     * Remove the quit overlay. Called as soon as the dialog closes (Keep
     * updating / Skip update / dismiss). The overlay is hidden synchronously on
     * the FX thread — never faded out over time — so no residue can linger if
     * the caller then blocks the FX thread (e.g. a harness joining a clicker
     * thread that polls {@code isVisible}), and a re-opened dialog simply fades
     * it in again from its reset state. The light fade is kept on the way in
     * only, which stays within the "no obvious animation burden" bound.
     */
    private void hideQuitOverlay() {
        stopQuitOverlayFade();
        if (!quitOverlay.isVisible()) {
            return;
        }
        quitOverlay.setVisible(false);
        quitOverlay.setOpacity(OVERLAY_OPACITY);   // reset for the next show
    }

    /** Stop any in-flight overlay fade (the next show/hide starts cleanly). */
    private void stopQuitOverlayFade() {
        if (quitOverlayFade != null) {
            quitOverlayFade.stop();
            quitOverlayFade = null;
        }
    }

    /**
     * Build the "Quit update?" confirmation. Exposed as a factory (rather than
     * constructed inline) so the screenshot harness can render it. Uses
     * {@link Alert.AlertType#NONE} so no default Question icon appears.
     *
     * <p>The dialog is frameless like the main window (no system title bar) and
     * deliberately carries no extra × — it can only be ended by the existing
     * "Keep updating" / "Skip update" buttons (begin/cancel close confirmation
     * and the final close decision stay untouched). With the title bar gone, the
     * "Quit update?" title is shown as a styled header inside the dialog instead.</p>
     */
    Alert createQuitAlert() {
        Alert alert = new Alert(Alert.AlertType.NONE);
        alert.setTitle("Quit update?");
        // Frameless dialog: same TRANSPARENT stage style as the main window.
        alert.initStyle(WINDOW_STYLE);
        // A TRANSPARENT stage only makes the window compositor transparent — the
        // Alert still builds its own scene with the default WHITE fill, and that
        // white shows through the rounded corners of .dialog-pane (the corners
        // are transparent). The main window solves this with an explicit
        // scene.setFill(TRANSPARENT); do the same here as soon as the dialog's
        // scene exists (i.e. before the first paint), so the desktop shows
        // through the corners instead of a white square.
        if (alert.getDialogPane().getScene() != null) {
            alert.getDialogPane().getScene().setFill(Color.TRANSPARENT);
        }
        alert.getDialogPane().sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                newScene.setFill(Color.TRANSPARENT);
            }
        });
        alert.setHeaderText(null);
        Label header = new Label("Quit update?");
        header.getStyleClass().add("dialog-header");
        alert.getDialogPane().setHeader(header);
        alert.setContentText("The update is still in progress. Skipping it may leave Minecraft out of date.");
        ButtonType stay = new ButtonType("Keep updating", ButtonBar.ButtonData.OK_DONE);
        quitSkipType = new ButtonType("Skip update", ButtonBar.ButtonData.OTHER);
        alert.getButtonTypes().setAll(stay, quitSkipType);
        alert.initOwner(stage);
        if (stylesheet != null) {
            alert.getDialogPane().getStylesheets().add(stylesheet);
        }
        alert.getDialogPane().getStyleClass().add("root");
        // Button hierarchy (第二轮 ui美化.md 三): Keep updating is the primary
        // (green solid, default) action; Skip update is a quiet, borderless
        // secondary control styled like the window × — never a red primary.
        ((Button) alert.getDialogPane().lookupButton(stay)).getStyleClass().add("primary-button");
        ((Button) alert.getDialogPane().lookupButton(stay)).setDefaultButton(true);
        Button skipButton = (Button) alert.getDialogPane().lookupButton(quitSkipType);
        skipButton.getStyleClass().add("window-close-button");
        return alert;
    }

    // ── Construction ──────────────────────────────────────────────

    private void initUI(String gameDir) {
        stage.setTitle("Minecraft Update Check");
        // Frameless window: no system title bar. The window keeps the title for
        // the taskbar / accessibility, but the visible chrome is the custom
        // title bar below. TRANSPARENT (not UNDECORATED) so the rounded dialog
        // and its shadow composite cleanly over the desktop.
        stage.initStyle(WINDOW_STYLE);
        // Forward the user closing the window to the flow controller, gated by
        // the in-progress confirmation. This is the SAME handler the custom ×
        // button drives (requestCloseFromTitleBar) — the close path is
        // byte-for-byte identical to clicking the old system title-bar close.
        stage.setOnCloseRequest(e -> onCloseRequestedByUser(e));

        root.getStyleClass().add("root");
        root.getStyleClass().add("window-root");

        // Custom title bar — the top row of the frameless window. The title
        // label fills the bar and is the drag region; the × button reuses the
        // normal close-request path (never stage.close() directly).
        lblWindowTitle.getStyleClass().add("title-bar-title");
        lblWindowTitle.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(lblWindowTitle, Priority.ALWAYS);
        btnWindowClose.getStyleClass().add("window-close-button");
        btnWindowClose.setOnAction(e -> requestCloseFromTitleBar());
        titleBar.getChildren().addAll(lblWindowTitle, btnWindowClose);
        titleBar.getStyleClass().add("title-bar");
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setPrefHeight(TITLE_BAR_HEIGHT);
        titleBar.setMinHeight(TITLE_BAR_HEIGHT);
        installWindowDrag(titleBar);
        root.setTop(titleBar);

        // Status header: reserved status-illustration slot + title/subtitle.
        lblStatus.getStyleClass().add("status-title");
        lblDescription.getStyleClass().add("status-description");
        lblDescription.setWrapText(true);
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
        configureShimmerBar(overallBarStack, overallBar, overallShimmerLayer,
                overallShimmer, "overall-shimmer", 46.0, 8.0);
        HBox.setHgrow(overallBarStack, Priority.ALWAYS);
        overallArea.getChildren().addAll(overallBarStack, lblOverallPct);
        overallArea.setAlignment(Pos.CENTER_LEFT);
        overallBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);

        // Current-file area: path, bar, speed. Hidden until a download becomes
        // active; the phase title above already names the action.
        dlArea.getStyleClass().add("file-area");
        lblDlFile.getStyleClass().add("file-path");
        dlBar.getStyleClass().add("file-progress");
        configureShimmerBar(dlBarStack, dlBar, dlShimmerLayer,
                dlShimmer, "file-shimmer", 38.0, 6.0);
        lblDlSpeed.getStyleClass().add("download-speed");
        dlArea.getChildren().addAll(lblDlFile, dlBarStack, lblDlSpeed);
        dlArea.setVisible(false);

        // Details area: Server URL, Game Directory and the full log. Collapsed
        // in normal mode, expanded in debug mode (and on error).
        detailsPane.getStyleClass().add("details-pane");
        lblGameDir.setText("Game dir: " + gameDir);
        logArea.getStyleClass().add("log");
        logArea.setEditable(false);
        logArea.setWrapText(false);
        logArea.setPrefRowCount(6);
        VBox detailsContent = new VBox(6, lblServer, lblGameDir, logArea);
        detailsPane.setContent(detailsContent);
        detailsPane.setAnimated(false);
        detailsPane.setExpanded(debug);

        // Content column — the status header (illustration + text) is the first
        // row, below the custom title bar. Same children and padding as the old
        // root, so the content layout is unchanged by the frameless window.
        VBox content = new VBox(8);
        content.setPadding(new Insets(18, 22, 18, 22));
        content.getChildren().addAll(statusHeader, overallArea, dlArea, detailsPane);

        // Debug footer — only present in debug mode, enabled once the flow allows
        // the user to close.
        if (debug) {
            btnClose.getStyleClass().add("debug-close-button");
            btnClose.setDisable(true);
            btnClose.setOnAction(e -> listener.userRequestedClose());
            Separator footerLine = new Separator();
            footerLine.getStyleClass().add("debug-footer-separator");
            HBox bottom = new HBox(btnClose);
            bottom.getStyleClass().add("debug-footer");
            bottom.setAlignment(Pos.CENTER_RIGHT);
            content.getChildren().addAll(footerLine, bottom);
        }

        // Persistent bottom copyright line — centred and muted.
        lblFooter.getStyleClass().add("footer-copyright");
        lblFooter.setMaxWidth(Double.MAX_VALUE);
        lblFooter.setMaxHeight(Double.MAX_VALUE);
        lblFooter.setAlignment(Pos.BOTTOM_CENTER);
        lblFooter.setText(FOOTER_COPYRIGHT);
        VBox.setVgrow(lblFooter, Priority.ALWAYS);
        content.getChildren().add(lblFooter);
        root.setCenter(content);

        // Rounded frameless window: wrap the BorderPane in a transparent
        // StackPane that reserves the shadow margin, and make the scene fill
        // transparent so the desktop shows through the rounded corners. The
        // scene is sized WINDOW_WIDTH wide + shadow margins so the client area
        // (the BorderPane) keeps its exact 520px layout width.
        frame.getStyleClass().add("window-frame");
        // Quit-update dim overlay: the scene frame reserves the shadow margin via
        // its padding, so a second child of the same StackPane fills exactly the
        // rounded client area the BorderPane covers — never the shadow margin or
        // the transparent corners — and stays correct as the window resizes.
        // Added after `root` so it stacks on top.
        quitOverlay.getStyleClass().add("quit-overlay");
        quitOverlay.setVisible(false);
        quitOverlay.setPickOnBounds(true);   // unreachable controls underneath
        frame.getChildren().addAll(root, quitOverlay);
        scene = new Scene(frame,
                WINDOW_WIDTH + 2 * SHADOW_SIDE,
                collapsedSceneHeight());
        scene.setFill(Color.TRANSPARENT);
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet);
        }
        stage.setScene(scene);
        stage.setMinWidth(WINDOW_WIDTH);
        stage.setMinHeight(collapsedSceneHeight());
        detailsPane.expandedProperty().addListener((obs, wasExpanded, expanded) ->
                applyWindowHeight());
    }

    /** Build a flat ProgressBar with a mouse-transparent highlight layer. The
     * layer is clipped to the interpolated completed width, so the highlight can
     * never cross into the grey track even while the bar is moving. */
    private static void configureShimmerBar(StackPane stack, ProgressBar bar,
                                            Pane layer, Region shimmer,
                                            String shimmerClass,
                                            double shimmerWidth, double barHeight) {
        stack.setMaxWidth(Double.MAX_VALUE);
        stack.setPrefHeight(barHeight);
        stack.setMinHeight(barHeight);
        stack.setMaxHeight(barHeight);
        bar.setMaxWidth(Double.MAX_VALUE);

        layer.setMouseTransparent(true);
        layer.setMaxWidth(Double.MAX_VALUE);
        shimmer.getStyleClass().addAll("progress-shimmer", shimmerClass);
        shimmer.setManaged(false);
        shimmer.setVisible(false);
        shimmer.resize(shimmerWidth, barHeight);
        shimmer.setPrefSize(shimmerWidth, barHeight);
        shimmer.setMinSize(shimmerWidth, barHeight);
        shimmer.setMaxSize(shimmerWidth, barHeight);
        layer.getChildren().add(shimmer);

        Rectangle completedClip = new Rectangle();
        completedClip.heightProperty().bind(layer.heightProperty());
        completedClip.widthProperty().bind(Bindings.createDoubleBinding(
                () -> layer.getWidth() * clampProgress(bar.getProgress()),
                layer.widthProperty(), bar.progressProperty()));
        completedClip.setArcWidth(barHeight);
        completedClip.setArcHeight(barHeight);
        layer.setClip(completedClip);
        stack.getChildren().addAll(bar, layer);
    }

    /**
     * Schedule a content-driven window resize. The measurement is deferred to
     * the next pulse so the window's insets and the current layout are known.
     */
    private void applyWindowHeight() {
        if (stage.getScene() == null || !stage.isShowing()) {
            return;
        }
        Platform.runLater(this::resizeToContent);
    }

    /**
     * Resize the window so the client area matches the content's preferred
     * height — never a fixed expanded height, so Error/Debug states take exactly
     * the space they need. The final size is clamped to the visible screen, but
     * the window position is NEVER touched here: re-centring lives exclusively
     * in {@link #open()} (first show only). A content-driven resize must never
     * yank a dragged window back to the screen centre, and high-frequency
     * progress renders must never move the window (UI修复.md 二). The frame's
     * preferred height already includes the custom title bar and the shadow
     * margin (it lives inside the scene), so the rounded window is sized
     * straight to it — no OS chrome to add.
     */
    private void resizeToContent() {
        if (stage.getScene() == null || !stage.isShowing()) {
            return;
        }
        Scene scene = stage.getScene();
        scene.getRoot().applyCss();
        double width = scene.getWidth();
        double pref = width > 0 ? scene.getRoot().prefHeight(width) : scene.getRoot().prefHeight(-1);
        Rectangle2D bounds = currentScreenVisualBounds();
        double margin = 24;
        double collapsed = collapsedSceneHeight();
        double maxHeight = Math.max(collapsed, bounds.getHeight() - margin);
        double maxWidth = Math.max(WINDOW_WIDTH, bounds.getWidth() - margin);
        double h = Math.min(Math.max(pref, collapsed), maxHeight);
        double w = Math.min(stage.getWidth(), maxWidth);
        if (Math.abs(stage.getHeight() - h) > 1.0) {
            stage.setHeight(h);
        }
        if (Math.abs(stage.getWidth() - w) > 1.0) {
            stage.setWidth(w);
        }
    }

    /** The visible bounds of the screen that currently contains the window's
     *  centre, falling back to the primary screen. */
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
}
