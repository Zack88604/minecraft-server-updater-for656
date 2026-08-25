package com.zack88604.autoupdater.gui.swing;

import com.zack88604.autoupdater.gui.api.UiDispatcher;

import javax.swing.SwingUtilities;

/** Dispatches GUI work onto Swing's Event Dispatch Thread. */
public final class SwingUiDispatcher implements UiDispatcher {

    @Override
    public void dispatch(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }
}
