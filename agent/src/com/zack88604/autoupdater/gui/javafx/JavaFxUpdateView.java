package com.zack88604.autoupdater.gui.javafx;

import com.zack88604.autoupdater.gui.api.DownloadProgress;
import com.zack88604.autoupdater.gui.api.UpdatePhase;
import com.zack88604.autoupdater.gui.api.UpdateSummary;
import com.zack88604.autoupdater.gui.api.UpdateUiState;
import com.zack88604.autoupdater.gui.api.UpdateView;
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

    // Root layout — carries the .success-state / .error-state state classes.
    private final VBox root = new VBox(8);

    /** External form of /ui.css, or null if the stylesheet is missing. */
    private final String stylesheet;

    /** The scene backing the window; resized when Details expands/collapses. */
    private Scene scene;

    /** Vertical window chrome (title bar + borders), measured once. */
    private double chrome;

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

    /** Show the window. Must be called on the JavaFX Application Thread. */
    @Override
    public void open() {
        stage.show();
        stage.centerOnScreen();
        applyWindowHeight();
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
        if (p == UpdatePhase.PREPARING || p == UpdatePhase.CLEANING
                || p == UpdatePhase.ERROR) {
            return;   // setPhase already configured the indeterminate/error bar
        }
        int value = clamp(state.getOverallProgressPercent());
        overallBar.setProgress(value / 100.0);
        lblOverallPct.setText(value + "%");
    }

    /** Present the per-file / agent download snapshot (current-file area). */
    private void applyDownload(UpdateUiState state) {
        DownloadProgress dl = state.getDownloadProgress();
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
            int pct = clamp((int) (dl.getDownloadedBytes() * 100 / dl.getTotalBytes()));
            dlBar.setProgress(pct / 100.0);
        } else {
            // Unknown content-length: indeterminate per-file bar.
            dlBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
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
            phase = p;
            animateHeaderFade();
            root.getStyleClass().removeAll("success-state", "error-state");
            switch (p) {
            case PREPARING:
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
                break;
            case SUCCESS:
                hideDownloadArea();
                showOverallPercent();
                logArea.setPrefRowCount(6);
                root.getStyleClass().add("success-state");
                break;
            case ERROR:
                hideDownloadArea();
                // Error hides the overall progress bar, resets any residue,
                // expands Details and shows a few more log rows for the failure
                // context. The bar's row stays managed (only its visibility is
                // toggled) so the Details pane below keeps a fixed position.
                overallBar.setProgress(0);
                lblOverallPct.setText("");
                overallArea.setVisible(false);
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
        dlArea.setVisible(false);
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
     * Ask whether to abandon the running update. The update is paused at its next
     * safe checkpoint while the dialog is open ({@code beginCloseConfirmation}),
     * so it cannot race ahead of the user's decision. Only an explicit
     * "Skip update" invokes the close flow; "Keep updating" or dismissing the
     * dialog resumes the update ({@code cancelCloseConfirmation}). The dialog
     * shares the main window's stylesheet, and the skip button gets the
     * {@code danger-button} class so it renders as the destructive action.
     */
    private void confirmQuit() {
        listener.beginCloseConfirmation();
        Alert alert = createQuitAlert();
        Optional<ButtonType> choice = alert.showAndWait();
        if (choice.isPresent() && choice.get() == quitSkipType) {
            listener.userRequestedClose();
            stage.close();
        } else {
            // "Keep updating", or the dialog was dismissed — resume the update.
            listener.cancelCloseConfirmation();
        }
    }

    /**
     * Build the "Quit update?" confirmation. Exposed as a factory (rather than
     * constructed inline) so the screenshot harness can render it. Uses
     * {@link Alert.AlertType#NONE} so no default Question icon appears.
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
        if (stylesheet != null) {
            alert.getDialogPane().getStylesheets().add(stylesheet);
        }
        alert.getDialogPane().getStyleClass().add("root");
        Button skipButton = (Button) alert.getDialogPane().lookupButton(quitSkipType);
        skipButton.getStyleClass().add("danger-button");
        ((Button) alert.getDialogPane().lookupButton(stay)).setDefaultButton(true);
        return alert;
    }

    // ── Construction ──────────────────────────────────────────────

    private void initUI(String gameDir) {
        stage.setTitle("Minecraft Update Check");
        // Forward the user closing the window to the flow controller, gated by
        // the in-progress confirmation.
        stage.setOnCloseRequest(e -> onCloseRequestedByUser(e));

        root.getStyleClass().add("root");

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

        // Root layout — the status header (illustration + text) is the first row.
        root.setPadding(new Insets(18, 22, 18, 22));
        root.getChildren().addAll(statusHeader, overallArea, dlArea, detailsPane);

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
            root.getChildren().addAll(footerLine, bottom);
        }

        // Persistent bottom copyright line — centred and muted.
        lblFooter.getStyleClass().add("footer-copyright");
        lblFooter.setMaxWidth(Double.MAX_VALUE);
        lblFooter.setMaxHeight(Double.MAX_VALUE);
        lblFooter.setAlignment(Pos.BOTTOM_CENTER);
        lblFooter.setText(FOOTER_COPYRIGHT);
        VBox.setVgrow(lblFooter, Priority.ALWAYS);
        root.getChildren().add(lblFooter);

        // Apply the shared visual system (ui.css) — normal and debug alike.
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
     * the space they need. The final size is clamped to the visible screen and
     * the window re-centred (see {@link #fitWindowToScreen}).
     */
    private void resizeToContent() {
        if (stage.getScene() == null || !stage.isShowing()) {
            return;
        }
        double chrome = windowChrome();
        if (chrome <= 0) {
            return;
        }
        Scene scene = stage.getScene();
        scene.getRoot().applyCss();
        double width = scene.getWidth();
        double pref = width > 0 ? scene.getRoot().prefHeight(width) : scene.getRoot().prefHeight(-1);
        double target = Math.max(pref, WINDOW_HEIGHT_COLLAPSED) + chrome;
        fitWindowToScreen(target);
    }

    /** Size the window to the given total height and keep it centred on the
     *  screen that currently contains it, clamped to the visible screen. */
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

    /** Vertical window chrome (title bar + borders) between the stage and the
     *  scene, measured once. */
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
