package com.zack88604.autoupdater.gui.api;

/**
 * User actions a view may send to the application controller.
 *
 * <p>A view may show a toolkit-specific confirmation dialog before calling
 * {@link #requestClose()}, but it must not release the Minecraft launch latch
 * or terminate the process itself. The controller decides those outcomes from
 * the current {@link UpdateUiState#getClosePolicy()}.</p>
 */
public interface UpdateViewActions {

    /** The user requested that the updater window close. */
    void requestClose();

    /** The native window has finished closing. */
    void notifyWindowClosed();
}
