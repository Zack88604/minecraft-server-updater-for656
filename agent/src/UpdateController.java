import java.util.concurrent.CountDownLatch;

/**
 * Controller / application-flow layer for the update check.
 *
 * Coordinates the {@link UpdateService} (business layer), the {@link UpdateView}
 * (UI layer) and the application flow. Owns the flow decisions: when to start
 * the update, how to handle success and failure, when to open and close the
 * view, whether to delay, and when to release the {@link CountDownLatch} that
 * gates the Minecraft launch. Implements {@link UpdateListener} to receive
 * business-layer events and {@link UpdateViewListener} so user actions (window
 * close, debug close button) come back here.
 *
 * Contains no business logic — file download, verification, retry and server
 * selection live in {@link UpdateService}. Contains no Swing types: the view is
 * only touched through the toolkit-agnostic {@link UpdateView} contract and every
 * view call is marshalled onto the UI thread through a {@link UiDispatcher}, so
 * the whole flow is reusable for any UI toolkit.
 *
 * The view is bound with {@link #attach(UpdateView)} before {@link #start()},
 * resolving the construction cycle with the view (the view needs this controller
 * as its {@link UpdateViewListener} while it is being built).
 */
final class UpdateController implements UpdateListener, UpdateViewListener {

    private final UpdateService service;
    /** Volatile + swappable so the helper-mode fallback can atomically route to
     *  Swing: the fallback swaps the view first, then the dispatcher. */
    private volatile UiDispatcher dispatcher;
    private final CountDownLatch latch;
    private final boolean debug;

    private volatile UpdateView view;

    UpdateController(UpdateService service, UiDispatcher dispatcher,
                     CountDownLatch latch, boolean debug) {
        this.service = service;
        this.dispatcher = dispatcher;
        this.latch = latch;
        this.debug = debug;
    }

    /** Bind the view. Must be called before {@link #start()}. */
    void attach(UpdateView view) {
        this.view = view;
    }

    /** Swap the dispatcher (used by the Swing fallback to route to the EDT). */
    void setDispatcher(UiDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /** Start the update on a background thread and open the view. */
    void start() {
        Thread worker = new Thread(() -> {
            try {
                service.run(this);
            } catch (Throwable t) {
                // run() catches recoverable exceptions itself and emits Failed,
                // so only Errors escape — surface them as an error.
                dispatcher.invoke(() -> onUpdateError(t));
            }
        }, "update-worker");
        worker.setDaemon(true);
        worker.start();
        dispatcher.invoke(view::open);
    }

    // ── UpdateListener (called on the worker thread) ───────────────

    @Override
    public void onUpdateEvent(UpdateEvent event) {
        dispatcher.invoke(() -> apply(event));
    }

    /** Translate one business event into view calls. Runs on the UI thread. */
    private void apply(UpdateEvent event) {
        switch (event.type) {
            case STATUS_CHANGED: {
                UpdateEvent.StatusChanged e = (UpdateEvent.StatusChanged) event;
                view.showStatus(e.phase, e.status, e.description, e.indeterminate);
                break;
            }
            case OVERALL_PROGRESS_CHANGED:
                view.showOverallProgress(((UpdateEvent.OverallProgressChanged) event).percent);
                break;
            case DOWNLOAD_PROGRESS_CHANGED:
                view.showDownloadProgress(((UpdateEvent.DownloadProgressChanged) event).progress);
                break;
            case LOG_MESSAGE:
                view.showLog(((UpdateEvent.LogMessage) event).message);
                break;
            case SERVER_CHANGED: {
                UpdateEvent.ServerChanged e = (UpdateEvent.ServerChanged) event;
                view.showServer(e.serverUrls, e.currentServer);
                break;
            }
            case COMPLETED: {
                UpdateResult result = ((UpdateEvent.Completed) event).result;
                view.showCompleted(result);
                onUpdateFinished(result);
                break;
            }
            case FAILED: {
                UpdateEvent.Failed e = (UpdateEvent.Failed) event;
                view.showError(e.message, e.cause);
                onUpdateError(e.cause);
                break;
            }
        }
    }

    // ── UpdateViewListener (user actions from the view, on the UI thread) ──

    @Override
    public void onWindowClosed() {
        latch.countDown();
    }

    @Override
    public void onCloseRequested() {
        latch.countDown();
        dispatcher.invoke(view::close);
    }

    // ── Application flow ────────────────────────────────────────────

    /**
     * The update completed. Failed files kill the process; otherwise release the
     * latch so Minecraft can start. All delays and window management live here,
     * not in the view. Runs on the UI thread.
     */
    private void onUpdateFinished(UpdateResult result) {
        if (result.failed > 0) {
            view.showLog("[FATAL] " + result.failed
                    + " file(s) failed to update, killing Minecraft process...");
            delayThen(2000, () -> System.exit(1));
        } else if (debug) {
            // Release now; the window stays open for inspection.
            latch.countDown();
        } else {
            long delay = result.updated > 0 ? 2000 : 1000;
            delayThen(delay, () -> {
                latch.countDown();
                dispatcher.invoke(view::close);
            });
        }
    }

    /**
     * The update threw an exception — print the stack trace and terminate the
     * JVM after a short grace period so the error stays visible. Runs on the
     * UI thread.
     */
    private void onUpdateError(Throwable cause) {
        cause.printStackTrace();
        view.showLog("[FATAL] Killing Minecraft process...");
        delayThen(1000, () -> System.exit(1));
    }

    /** Run an action once after a delay on a daemon thread. No Swing involved. */
    private static void delayThen(long delayMs, Runnable action) {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            action.run();
        }, "update-flow");
        thread.setDaemon(true);
        thread.start();
    }
}
