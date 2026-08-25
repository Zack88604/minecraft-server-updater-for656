import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-data snapshot of the current UI state, maintained alongside the remote
 * (helper) view.
 *
 * Every view call that the {@link RemoteUpdateView} forwards to the helper is
 * also folded into this snapshot, so when the helper dies we can rebuild the
 * equivalent Swing window from scratch — full current state, not a shallow
 * 64-event replay (v3 acceptance criterion #6). The log is kept as a bounded
 * tail of the most recent lines, and the snapshot is copied under a lock so the
 * fallback switch never tears it mid-update.
 *
 * The snapshot is rendering-only: applying it never re-triggers flow
 * side-effects (those belong to the {@link UpdateController}), so a crash
 * replay can never double-fire {@code System.exit} or the launch latch.
 */
final class UiSnapshot {

    /** How many recent log lines to keep for the fallback window. */
    private static final int LOG_TAIL_LIMIT = 200;

    private UpdatePhase phase = UpdatePhase.PREPARING;
    private String status;
    private String description;
    private boolean indeterminate;
    private boolean overallSet;
    private int overallPercent;
    private DownloadProgress download = DownloadProgress.inactive();
    private List<String> serverUrls;
    private String currentServer;
    private boolean closeEnabled;
    private boolean opened;
    private boolean closed;
    private boolean completed;
    private UpdateResult result;
    private boolean failed;
    private String errorMessage;
    private final ArrayDeque<String> logTail = new ArrayDeque<>();

    UiSnapshot() {}

    /** Copy constructor used to freeze the state at fallback time. */
    private UiSnapshot(UiSnapshot src) {
        this.phase = src.phase;
        this.status = src.status;
        this.description = src.description;
        this.indeterminate = src.indeterminate;
        this.overallSet = src.overallSet;
        this.overallPercent = src.overallPercent;
        this.download = src.download;
        this.serverUrls = src.serverUrls;
        this.currentServer = src.currentServer;
        this.closeEnabled = src.closeEnabled;
        this.opened = src.opened;
        this.closed = src.closed;
        this.completed = src.completed;
        this.result = src.result;
        this.failed = src.failed;
        this.errorMessage = src.errorMessage;
        this.logTail.addAll(src.logTail);
    }

    synchronized void onStatus(UpdatePhase phase, String status, String description, boolean indeterminate) {
        this.phase = phase;
        this.status = status;
        this.description = description;
        this.indeterminate = indeterminate;
    }

    synchronized void onLog(String message) {
        logTail.addLast(message);
        while (logTail.size() > LOG_TAIL_LIMIT) {
            logTail.removeFirst();
        }
    }

    synchronized void onOverallProgress(int percent) {
        this.overallSet = true;
        this.overallPercent = percent;
    }

    synchronized void onDownloadProgress(DownloadProgress progress) {
        this.download = progress;
    }

    synchronized void onServer(List<String> urls, String current) {
        this.serverUrls = urls;
        this.currentServer = current;
    }

    synchronized void onSetCloseEnabled(boolean enabled) {
        this.closeEnabled = enabled;
    }

    synchronized void onOpen() {
        this.opened = true;
    }

    synchronized void onClose() {
        this.closed = true;
    }

    synchronized void onCompleted(UpdateResult result) {
        this.completed = true;
        this.result = result;
    }

    synchronized void onError(String message) {
        this.failed = true;
        this.errorMessage = message;
    }

    /** The latest log tail, for debug output. */
    synchronized List<String> logLines() {
        return new ArrayList<>(logTail);
    }

    /** Whether the window was already closed (flow finished) — then don't reopen. */
    synchronized boolean isClosed() {
        return closed;
    }

    /** Freeze a consistent copy of the current state. */
    synchronized UiSnapshot copy() {
        return new UiSnapshot(this);
    }

    /**
     * Render the snapshot into a fresh view. Runs on the view's UI thread
     * (the caller guarantees it). Rendering-only: never re-fires flow side
     * effects. If the window had been closed, the view is not reopened.
     */
    synchronized void applyTo(UpdateView v) {
        if (serverUrls != null && currentServer != null) {
            v.showServer(serverUrls, currentServer);
        }
        for (String line : logTail) {
            v.showLog(line);
        }
        v.showStatus(phase, status, description, indeterminate);
        if (overallSet) {
            v.showOverallProgress(overallPercent);
        }
        if (download.active) {
            v.showDownloadProgress(download);
        }
        if (completed) {
            v.showCompleted(result);
        }
        if (failed) {
            v.showError(errorMessage, null);
        }
        if (closeEnabled) {
            v.setCloseEnabled(true);
        }
        if (opened && !closed) {
            v.open();
        }
    }
}
