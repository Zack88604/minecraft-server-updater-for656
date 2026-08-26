package com.zack88604.autoupdater.application;

import com.zack88604.autoupdater.gui.api.UiDispatcher;
import com.zack88604.autoupdater.gui.api.UpdateUiState;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Coalesces and rate-limits state snapshots so a slow GUI never accumulates
 * an unbounded rendering queue or monopolizes its event thread during file
 * checking, downloads, or stale-file cleanup.
 */
final class LatestStateRenderer {

    private static final long FRAME_INTERVAL_MILLIS = 50L;
    private static final ScheduledExecutorService RENDER_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "update-ui-render-scheduler");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    interface Renderer {
        void render(UpdateUiState state);
    }

    private final UiDispatcher dispatcher;
    private final Renderer renderer;

    private UpdateUiState pending;
    private boolean dispatchScheduled;
    private boolean rendering;

    LatestStateRenderer(UiDispatcher dispatcher, Renderer renderer) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    /**
     * Schedule rendering of the newest snapshot, replacing an older pending
     * one. At most one toolkit task can be pending or running at any time.
     */
    void submit(UpdateUiState state) {
        boolean shouldSchedule = false;
        synchronized (this) {
            pending = Objects.requireNonNull(state, "state");
            if (!dispatchScheduled && !rendering) {
                dispatchScheduled = true;
                shouldSchedule = true;
            }
        }
        if (shouldSchedule) {
            scheduleDispatch();
        }
    }

    private void scheduleDispatch() {
        RENDER_SCHEDULER.schedule(new Runnable() {
            @Override
            public void run() {
                dispatcher.dispatch(LatestStateRenderer.this::renderPending);
            }
        }, FRAME_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void renderPending() {
        UpdateUiState state;
        synchronized (this) {
            state = pending;
            pending = null;
            dispatchScheduled = false;
            rendering = state != null;
        }

        if (state == null) {
            return;
        }
        try {
            renderer.render(state);
        } finally {
            boolean scheduleAgain = false;
            synchronized (this) {
                rendering = false;
                if (pending != null && !dispatchScheduled) {
                    dispatchScheduled = true;
                    scheduleAgain = true;
                }
            }
            if (scheduleAgain) {
                scheduleDispatch();
            }
        }
    }
}
