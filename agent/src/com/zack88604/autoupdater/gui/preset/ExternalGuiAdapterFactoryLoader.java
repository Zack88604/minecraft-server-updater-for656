package com.zack88604.autoupdater.gui.preset;

import com.zack88604.autoupdater.gui.api.GuiAdapterFactory;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * Creates a GUI factory from an external preset only after user confirmation.
 */
public final class ExternalGuiAdapterFactoryLoader {

    /**
     * Load and instantiate the factory declared by a selected preset.
     *
     * <p>Calling this method executes third-party code, including static
     * initializers. Callers must obtain explicit user confirmation first.</p>
     */
    public GuiAdapterFactory load(GuiPreset preset) {
        URLClassLoader classLoader = null;
        try {
            URL archiveUrl = preset.getArchive().toURI().toURL();
            classLoader = new URLClassLoader(new URL[] {archiveUrl},
                    GuiAdapterFactory.class.getClassLoader());
            Class<?> candidate = Class.forName(preset.getFactoryClassName(), true, classLoader);
            Class<? extends GuiAdapterFactory> factoryType =
                    candidate.asSubclass(GuiAdapterFactory.class);
            return factoryType.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | ClassCastException | LinkageError
                | java.net.MalformedURLException exception) {
            closeQuietly(classLoader);
            throw new IllegalStateException("Unable to load GUI preset "
                    + preset.getArchiveName(), exception);
        }
    }

    private static void closeQuietly(URLClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        try {
            classLoader.close();
        } catch (java.io.IOException ignored) {
            // The failed class loader is about to become unreachable.
        }
    }
}
