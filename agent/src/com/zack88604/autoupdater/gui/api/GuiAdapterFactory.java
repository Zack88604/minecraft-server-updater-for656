package com.zack88604.autoupdater.gui.api;

/**
 * Creates a GUI adapter for one updater launch.
 *
 * <p>Implement this interface to make another UI toolkit selectable without
 * changing the updater's application or bootstrap code.</p>
 */
@FunctionalInterface
public interface GuiAdapterFactory {

    /**
     * Create a new adapter from presentation-only launch settings.
     *
     * @param context immutable GUI launch settings
     * @return a new GUI adapter
     */
    GuiAdapter create(GuiAdapterContext context);
}
