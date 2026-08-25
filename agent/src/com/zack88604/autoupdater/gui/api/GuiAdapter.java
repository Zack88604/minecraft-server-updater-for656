package com.zack88604.autoupdater.gui.api;

/**
 * Factory and thread bridge supplied by a GUI implementation.
 *
 * <p>Implementations may use Swing, JavaFX, or another desktop UI toolkit.
 * They must not start update work or access updater infrastructure directly;
 * the application layer owns that work and only renders immutable snapshots
 * through the returned {@link UpdateView}.</p>
 */
public interface GuiAdapter {

    /** Return the dispatcher for this adapter's UI thread. */
    UiDispatcher dispatcher();

    /**
     * Create a view bound to actions owned by the application controller.
     *
     * @param actions callbacks for close-related user actions
     * @return a new, not-yet-opened view
     */
    UpdateView create(UpdateViewActions actions);
}
