package com.zack88604.autoupdater.gui.api;

/**
 * Schedules a task on the UI thread owned by a GUI toolkit.
 *
 * <p>The update core never imports Swing or JavaFX. A {@link GuiAdapter}
 * supplies the dispatcher appropriate for its toolkit, and the application
 * layer uses it for every {@link UpdateView} call.</p>
 */
@FunctionalInterface
public interface UiDispatcher {

    /** Schedule {@code task} for execution on the toolkit's UI thread. */
    void dispatch(Runnable task);
}
