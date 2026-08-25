package com.zack88604.autoupdater.gui.api;

import java.util.Objects;

/**
 * Immutable launch settings supplied to a {@link GuiAdapterFactory}.
 *
 * <p>This context contains presentation settings only. It deliberately does
 * not expose updater services, mutable configuration, or process control.</p>
 */
public final class GuiAdapterContext {

    private final String gameDirectory;
    private final boolean debug;

    /** Create settings for one GUI adapter instance. */
    public GuiAdapterContext(String gameDirectory, boolean debug) {
        this.gameDirectory = Objects.requireNonNull(gameDirectory, "gameDirectory");
        this.debug = debug;
    }

    /** Return the configured Minecraft directory for display purposes. */
    public String getGameDirectory() {
        return gameDirectory;
    }

    /** Return whether the updater should keep a successful view open for inspection. */
    public boolean isDebug() {
        return debug;
    }
}
