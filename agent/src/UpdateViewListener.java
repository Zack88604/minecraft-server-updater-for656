/**
 * User-action callback from the {@link UpdateView} to the flow controller.
 *
 * Lets a view forward user operations — closing the window or pressing the
 * debug close button — without holding any reference to the flow owner.
 * Implemented by {@link UpdateController}.
 *
 * No Swing or JavaFX types appear here, so any UI toolkit can call through
 * it. All methods must be invoked on the UI thread of the implementing
 * toolkit (the {@link UpdateGUI} does so from its event handlers).
 */
interface UpdateViewListener {

    /** The user closed the view window. */
    void onWindowClosed();

    /** The user pressed the view's close button (debug mode). */
    void onCloseRequested();
}
