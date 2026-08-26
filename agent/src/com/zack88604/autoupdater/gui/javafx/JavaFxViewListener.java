package com.zack88604.autoupdater.gui.javafx;

/**
 * User-action channel from the JavaFX view to the helper entry point.
 *
 * <p>The view is a pure View: on a user close request while the update is still
 * running it asks for confirmation and, if the user skips, reports
 * {@link #userRequestedClose()} — which the entry point relays to the agent as
 * {@code closeRequested} (→ {@code UpdateViewActions.requestClose()}). On a close
 * in a terminal phase it reports {@link #windowClosed()} — relayed as
 * {@code windowClosed} (→ {@code UpdateViewActions.notifyWindowClosed()}). Both
 * callbacks run on the JavaFX Application Thread.</p>
 */
interface JavaFxViewListener {

    /** The user explicitly skipped the in-progress update. */
    void userRequestedClose();

    /** The window was closed while no update was in progress. */
    void windowClosed();

    /**
     * The user initiated a close while an update is in progress; the Quit-update
     * confirmation dialog is about to open. Relayed to the agent as
     * {@code beginCloseConfirmation} (→ {@code UpdateViewActions.beginCloseConfirmation()}),
     * which pauses the update at its next safe checkpoint while the dialog is
     * shown. Runs on the JavaFX Application Thread.
     */
    void beginCloseConfirmation();

    /** The user rejected the Quit-update dialog; the update may resume. Relayed
     *  as {@code cancelCloseConfirmation} (→ {@code UpdateViewActions.cancelCloseConfirmation()}).
     *  Runs on the JavaFX Application Thread. */
    void cancelCloseConfirmation();
}
