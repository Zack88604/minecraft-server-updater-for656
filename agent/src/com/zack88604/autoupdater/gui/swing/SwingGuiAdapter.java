package com.zack88604.autoupdater.gui.swing;

import com.zack88604.autoupdater.gui.api.GuiAdapter;
import com.zack88604.autoupdater.gui.api.UiDispatcher;
import com.zack88604.autoupdater.gui.api.UpdateView;
import com.zack88604.autoupdater.gui.api.UpdateViewActions;

import java.util.Objects;

/** Built-in Swing implementation of the public GUI adapter contract. */
public final class SwingGuiAdapter implements GuiAdapter {

    private final String gameDirectory;
    private final boolean debug;
    private final UiDispatcher dispatcher = new SwingUiDispatcher();

    public SwingGuiAdapter(String gameDirectory, boolean debug) {
        this.gameDirectory = Objects.requireNonNull(gameDirectory, "gameDirectory");
        this.debug = debug;
    }

    @Override
    public UiDispatcher dispatcher() {
        return dispatcher;
    }

    @Override
    public UpdateView create(UpdateViewActions actions) {
        return new SwingUpdateView(actions, gameDirectory, debug);
    }
}
