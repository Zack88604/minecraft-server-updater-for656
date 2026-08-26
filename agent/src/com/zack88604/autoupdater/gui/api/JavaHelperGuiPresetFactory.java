package com.zack88604.autoupdater.gui.api;

/**
 * Bootstrap entry point for a preset whose GUI runs in a separate Java
 * process.
 *
 * <p>The implementation is loaded only after the user accepts the external
 * preset warning. It must not reference runtime-specific UI classes from its
 * signature or static initializers; those classes belong in the helper process
 * declared by the preset runtime manifest.</p>
 */
@FunctionalInterface
public interface JavaHelperGuiPresetFactory {

    /**
     * Return launch customisation for one helper process.
     *
     * @param context immutable presentation settings for this updater launch
     * @return immutable helper launch settings
     */
    JavaHelperLaunchSpec create(GuiAdapterContext context);
}
