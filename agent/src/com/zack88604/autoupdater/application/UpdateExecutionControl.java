package com.zack88604.autoupdater.application;

/**
 * Cooperative pause and cancellation state shared by one update worker and
 * the application controller.
 */
public final class UpdateExecutionControl {

    private boolean paused;
    private boolean cancelled;

    /** Stop progress at the next cooperative checkpoint. */
    public synchronized void pause() {
        if (!cancelled) {
            paused = true;
        }
    }

    /** Allow a paused update to continue. */
    public synchronized void resume() {
        paused = false;
        notifyAll();
    }

    /** Prevent future work and release any paused worker. */
    public synchronized void cancel() {
        cancelled = true;
        paused = false;
        notifyAll();
    }

    /** Return whether cancellation has been requested. */
    public synchronized boolean isCancelled() {
        return cancelled;
    }

    /**
     * Block while paused, or stop the worker when cancellation is requested.
     *
     * @throws CancelledException when the update must stop without reporting an
     *                            update failure to the user
     */
    public synchronized void checkpoint() {
        while (paused && !cancelled) {
            try {
                wait();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                cancelled = true;
            }
        }
        if (cancelled) {
            throw new CancelledException();
        }
    }

    /** Signals intentional user cancellation rather than an update failure. */
    public static final class CancelledException extends RuntimeException {
        private CancelledException() {
            super("Update cancelled by the user");
        }
    }
}
