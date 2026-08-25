package com.zack88604.autoupdater.gui.api;

import java.io.File;
import java.util.Objects;

/**
 * Immutable launch settings supplied to a {@link GuiAdapterFactory}.
 *
 * <p>This context contains presentation settings only. It deliberately does
 * not expose updater services, mutable configuration, or process control.</p>
 */
public final class GuiAdapterContext {

    private final String gameDirectory;
    private final String updaterConfigurationDirectory;
    private final boolean debug;

    /**
     * Create settings for one GUI adapter instance using the standard updater
     * configuration directory beneath the game root.
     */
    public GuiAdapterContext(String gameDirectory, boolean debug) {
        this(gameDirectory, new File(gameDirectory, ".mc-update").getPath(), debug);
    }

    /** Create settings for one GUI adapter instance. */
    public GuiAdapterContext(String gameDirectory, String updaterConfigurationDirectory,
                             boolean debug) {
        this.gameDirectory = Objects.requireNonNull(gameDirectory, "gameDirectory");
        this.updaterConfigurationDirectory = Objects.requireNonNull(
                updaterConfigurationDirectory, "updaterConfigurationDirectory");
        this.debug = debug;
    }

    /** Return the configured Minecraft directory for presentation resources. */
    public String getGameDirectory() {
        return gameDirectory;
    }

    /**
     * Return the fixed updater-owned directory for GUI configuration and
     * preset-related presentation resources.
     */
    public String getUpdaterConfigurationDirectory() {
        return updaterConfigurationDirectory;
    }

    /** Return whether the updater should keep a successful view open for inspection. */
    public boolean isDebug() {
        return debug;
    }
}
