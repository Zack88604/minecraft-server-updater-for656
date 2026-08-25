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

    /**
     * Notify the controller before a toolkit-specific close confirmation opens.
     * The updater pauses at its next safe checkpoint while the dialog is shown.
     */
    default void beginCloseConfirmation() {
        // Existing third-party adapters remain source and binary compatible.
    }

    /** Resume a paused update after the user rejects the close confirmation. */
    default void cancelCloseConfirmation() {
        // Existing third-party adapters remain source and binary compatible.
    }

    /** The user confirmed that the updater window should close. */
    void requestClose();

    /** The native window has finished closing. */
    void notifyWindowClosed();
}
