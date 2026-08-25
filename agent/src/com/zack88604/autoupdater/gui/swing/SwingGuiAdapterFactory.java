package com.zack88604.autoupdater.gui.swing;

import com.zack88604.autoupdater.gui.api.GuiAdapter;
import com.zack88604.autoupdater.gui.api.GuiAdapterContext;
import com.zack88604.autoupdater.gui.api.GuiAdapterFactory;

import java.util.Objects;

/** Creates the built-in Swing adapter used when no custom factory is configured. */
public final class SwingGuiAdapterFactory implements GuiAdapterFactory {

    @Override
    public GuiAdapter create(GuiAdapterContext context) {
        Objects.requireNonNull(context, "context");
        return new SwingGuiAdapter(context.getGameDirectory(), context.isDebug());
    }
}
