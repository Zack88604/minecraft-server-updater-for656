package com.zack88604.autoupdater.gui.api;

/**
 * Toolkit-neutral rendering surface for one updater session.
 *
 * <p>Every method is invoked on the UI thread provided by the owning
 * {@link GuiAdapter}. Implementations render state and forward user intent
 * through {@link UpdateViewActions}; they never perform network, file, process,
 * or update-flow operations themselves.</p>
 */
public interface UpdateView {

    /** Open the window. Called once before the first or an early render. */
    void open();

    /** Render the complete current state of the update session. */
    void render(UpdateUiState state);

    /** Close the window because the application controller requested it. */
    void close();
}
