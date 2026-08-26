package com.zack88604.autoupdater.gui.javafx;

import com.zack88604.autoupdater.gui.api.GuiAdapter;
import com.zack88604.autoupdater.gui.api.GuiAdapterContext;
import com.zack88604.autoupdater.gui.api.GuiAdapterFactory;

import java.util.Objects;

/**
 * Creates the JavaFX helper-JVM adapter.
 *
 * <p>Selected by class name via the existing {@code guiAdapterFactoryClassName}
 * configuration (AgentBootstrap's reflective lookup requires a public no-arg
 * constructor). The adapter only runs the JavaFX path when the local JavaFX
 * runtime is installed; otherwise it falls back to Swing transparently.</p>
 */
public final class JavaFxGuiAdapterFactory implements GuiAdapterFactory {

    /** Public no-arg constructor required by AgentBootstrap's reflective lookup. */
    public JavaFxGuiAdapterFactory() {
    }

    @Override
    public GuiAdapter create(GuiAdapterContext context) {
        return new JavaFxGuiAdapter(Objects.requireNonNull(context, "context"));
    }
}
