package com.zack88604.autoupdater.application;

import com.zack88604.autoupdater.domain.UpdateResult;
import com.zack88604.autoupdater.gui.api.ClosePolicy;
import com.zack88604.autoupdater.gui.api.GuiAdapter;
import com.zack88604.autoupdater.gui.api.UpdateUiState;
import com.zack88604.autoupdater.gui.api.UpdateView;
import com.zack88604.autoupdater.gui.api.UpdateViewActions;

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
    private final boolean debug;
    private final Object stateLock = new Object();

    private UpdateUiState state = UpdateUiState.initial();
    private volatile UpdateView view;
    private boolean started;

    public UpdateController(UpdateService service, GuiAdapter guiAdapter,
                            CountDownLatch launchLatch, boolean debug) {
        this.service = Objects.requireNonNull(service, "service");
        this.guiAdapter = Objects.requireNonNull(guiAdapter, "guiAdapter");
        this.launchLatch = Objects.requireNonNull(launchLatch, "launchLatch");
        this.debug = debug;
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
    public void requestClose() {
        ClosePolicy closePolicy = snapshot().getClosePolicy();
        if (closePolicy == ClosePolicy.EXIT_FAILURE) {
            System.exit(1);
            return;
        }
        launchLatch.countDown();
        closeView();
    }

    @Override
    public void notifyWindowClosed() {
        if (snapshot().getClosePolicy() == ClosePolicy.EXIT_FAILURE) {
            System.exit(1);
            return;
        }
        launchLatch.countDown();
    }

    private void startWorker() {
        Thread worker = new Thread(() -> {
            try {
                UpdateResult result = service.run(this::onUpdateEvent);
                onUpdateEvent(new UpdateEvent.Completed(result));
            } catch (Throwable cause) {
                String message = cause.getMessage() != null ? cause.getMessage() : cause.toString();
                onUpdateEvent(new UpdateEvent.Failed("Update error: " + message, cause));
            }
        }, "update-worker");
        worker.setDaemon(true);
        worker.start();
    }

    /** Receive a business event on the update worker and schedule one render. */
    private void onUpdateEvent(UpdateEvent event) {
        UpdateUiState next;
        synchronized (stateLock) {
            state = UpdateStateReducer.reduce(state, event);
            next = state;
        }

        guiAdapter.dispatcher().dispatch(() -> {
            UpdateView target = view;
            if (target != null) {
                target.render(next);
            }
            if (event instanceof UpdateEvent.Completed) {
                handleCompletion(((UpdateEvent.Completed) event).getResult());
            } else if (event instanceof UpdateEvent.Failed) {
                ((UpdateEvent.Failed) event).getCause().printStackTrace();
            }
        });
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
