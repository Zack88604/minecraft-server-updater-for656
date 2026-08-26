package com.zack88604.autoupdater.application;

import com.zack88604.autoupdater.domain.UpdateResult;
import com.zack88604.autoupdater.gui.api.ClosePolicy;
import com.zack88604.autoupdater.gui.api.GuiAdapter;
import com.zack88604.autoupdater.gui.api.UpdateUiState;
import com.zack88604.autoupdater.gui.api.UpdateView;
import com.zack88604.autoupdater.gui.api.UpdateViewActions;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

/**
 * Application-flow controller for one update session.
 *
 * <p>The controller runs {@link UpdateService} away from the UI thread,
 * reduces every business event to an immutable {@link UpdateUiState}, and
 * renders snapshots through a toolkit-neutral {@link GuiAdapter}. It owns
 * lifecycle decisions such as releasing the launch latch and handling close
 * actions; file, HTTP, and manifest work remain in {@link UpdateService}.</p>
 */
public final class UpdateController implements UpdateViewActions {

    private final UpdateService service;
    private final GuiAdapter guiAdapter;
    private final CountDownLatch launchLatch;
    private final CountDownLatch workerFinished = new CountDownLatch(1);
    private final boolean debug;
    private final UpdateExecutionControl executionControl = new UpdateExecutionControl();
    private final LatestStateRenderer stateRenderer;
    private final Object stateLock = new Object();
    private final Object closeLock = new Object();

    private UpdateUiState state = UpdateUiState.initial();
    private volatile UpdateView view;
    private volatile UpdatePreflight preflight;
    private volatile ClosePolicy closePolicy = ClosePolicy.CONFIRM;
    private boolean started;
    private boolean confirmationPaused;
    private boolean closeRequested;

    public UpdateController(UpdateService service, GuiAdapter guiAdapter,
                            CountDownLatch launchLatch, boolean debug) {
        this.service = Objects.requireNonNull(service, "service");
        this.guiAdapter = Objects.requireNonNull(guiAdapter, "guiAdapter");
        this.launchLatch = Objects.requireNonNull(launchLatch, "launchLatch");
        this.debug = debug;
        stateRenderer = new LatestStateRenderer(guiAdapter.dispatcher(), this::renderState);
    }

    /**
     * Set an optional preflight to run on the worker thread before the update
     * use case starts. Must be called before {@link #start()}. A null preflight
     * keeps the existing behavior byte-for-byte identical.
     */
    public void setPreflight(UpdatePreflight preflight) {
        this.preflight = preflight;
    }

    /** Create, open, and begin driving the update view exactly once. */
    public synchronized void start() {
        if (started) {
            throw new IllegalStateException("Update controller has already started");
        }
        started = true;
        guiAdapter.dispatcher().dispatch(() -> {
            UpdateView created = guiAdapter.create(this);
            view = Objects.requireNonNull(created, "GuiAdapter returned null view");
            created.open();
            created.render(snapshot());
            startWorker();
        });
    }

    /** Return the most recent immutable snapshot. */
    public UpdateUiState getState() {
        return snapshot();
    }

    @Override
    public void beginCloseConfirmation() {
        if (closePolicy != ClosePolicy.CONFIRM) {
            return;
        }
        synchronized (closeLock) {
            if (closeRequested || confirmationPaused) {
                return;
            }
            confirmationPaused = true;
            executionControl.pause();
        }
    }

    @Override
    public void cancelCloseConfirmation() {
        synchronized (closeLock) {
            if (!confirmationPaused || closeRequested) {
                return;
            }
            confirmationPaused = false;
            executionControl.resume();
        }
    }

    @Override
    public void requestClose() {
        ClosePolicy currentClosePolicy = closePolicy;
        if (currentClosePolicy == ClosePolicy.EXIT_FAILURE) {
            System.exit(1);
            return;
        }

        if (currentClosePolicy == ClosePolicy.CONFIRM) {
            if (markCloseRequested(true)) {
                rollbackThenLaunch(false);
            }
            return;
        }

        if (markCloseRequested(false)) {
            launchLatch.countDown();
            closeView();
        }
    }

    @Override
    public void notifyWindowClosed() {
        ClosePolicy currentClosePolicy = closePolicy;
        if (currentClosePolicy == ClosePolicy.EXIT_FAILURE) {
            System.exit(1);
            return;
        }

        if (currentClosePolicy == ClosePolicy.CONFIRM) {
            if (markCloseRequested(true)) {
                rollbackThenLaunch(true);
            }
            return;
        }

        synchronized (closeLock) {
            if (closeRequested) {
                return;
            }
            closeRequested = true;
            confirmationPaused = false;
            executionControl.resume();
        }
        launchLatch.countDown();
    }

    private boolean markCloseRequested(boolean cancelUpdate) {
        synchronized (closeLock) {
            if (closeRequested) {
                return false;
            }
            closeRequested = true;
            confirmationPaused = false;
            if (cancelUpdate) {
                executionControl.cancel();
            } else {
                executionControl.resume();
            }
            return true;
        }
    }

    private void rollbackThenLaunch(boolean viewAlreadyClosed) {
        Thread rollback = new Thread(() -> {
            try {
                workerFinished.await();
                service.rollbackCancelledUpdate();
                launchLatch.countDown();
                if (!viewAlreadyClosed) {
                    closeView();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                handleRollbackFailure(exception, viewAlreadyClosed);
            } catch (IOException exception) {
                handleRollbackFailure(exception, viewAlreadyClosed);
            }
        }, "update-rollback");
        rollback.setDaemon(true);
        rollback.start();
    }

    private void handleRollbackFailure(Throwable cause, boolean viewAlreadyClosed) {
        if (viewAlreadyClosed) {
            cause.printStackTrace();
            System.exit(1);
            return;
        }
        synchronized (closeLock) {
            closeRequested = false;
        }
        String message = cause.getMessage() != null ? cause.getMessage() : cause.toString();
        onUpdateEvent(new UpdateEvent.Failed(
                "Unable to restore files after cancelling the update: " + message, cause));
    }

    private void startWorker() {
        Thread worker = new Thread(() -> {
            try {
                runPreflight();
                UpdateResult result = service.run(this::onUpdateEvent, executionControl);
                onUpdateEvent(new UpdateEvent.Completed(result));
            } catch (UpdateExecutionControl.CancelledException ignored) {
                // The rollback thread restores the transaction before Minecraft starts.
            } catch (Throwable cause) {
                String message = cause.getMessage() != null ? cause.getMessage() : cause.toString();
                onUpdateEvent(new UpdateEvent.Failed("Update error: " + message, cause));
            } finally {
                workerFinished.countDown();
            }
        }, "update-worker");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Run the optional preflight on the worker thread, before the update use
     * case. A preflight is optional GUI infrastructure (never part of the
     * Minecraft update), so even an unexpected Throwable must never become an
     * updater {@link UpdateEvent.Failed}; it is logged and the flow continues.
     */
    private void runPreflight() {
        UpdatePreflight p = preflight;
        if (p == null) {
            return;
        }
        try {
            p.run(this::onUpdateEvent);
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }

    /** Receive a business event on the update worker and schedule its latest state. */
    private void onUpdateEvent(UpdateEvent event) {
        UpdateUiState next;
        synchronized (stateLock) {
            state = UpdateStateReducer.reduce(state, event);
            closePolicy = state.getClosePolicy();
            next = state;
        }

        stateRenderer.submit(next);
        if (event instanceof UpdateEvent.Completed) {
            handleCompletion(((UpdateEvent.Completed) event).getResult());
        } else if (event instanceof UpdateEvent.Failed) {
            ((UpdateEvent.Failed) event).getCause().printStackTrace();
        }
    }

    private void renderState(UpdateUiState next) {
        UpdateView target = view;
        if (target != null) {
            target.render(next);
        }
    }

    private void handleCompletion(UpdateResult result) {
        if (result.getFailedFiles() > 0) {
            return;
        }
        if (debug) {
            launchLatch.countDown();
            return;
        }
        long delayMillis = result.getUpdatedFiles() > 0 ? 2000 : 1000;
        delayThen(delayMillis, () -> {
            launchLatch.countDown();
            closeView();
        });
    }

    private UpdateUiState snapshot() {
        synchronized (stateLock) {
            return state;
        }
    }

    private void closeView() {
        guiAdapter.dispatcher().dispatch(() -> {
            UpdateView target = view;
            if (target != null) {
                target.close();
            }
        });
    }

    private static void delayThen(long delayMillis, Runnable action) {
        Thread delay = new Thread(() -> {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            action.run();
        }, "update-flow");
        delay.setDaemon(true);
        delay.start();
    }
}
