/**
 * Abstraction over the UI toolkit's "run on the UI thread" mechanism.
 *
 * The Swing implementation wraps {@code SwingUtilities.invokeLater}; a JavaFX
 * implementation would wrap {@code Platform.runLater}. The update flow drives
 * the {@link UpdateView} through this interface so the controller and the
 * business layer never depend on a specific UI toolkit.
 */
interface UiDispatcher {

    /** Run the given action on the UI thread. Never blocks. */
    void invoke(Runnable action);
}
