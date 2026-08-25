package com.zack88604.autoupdater.gui.preset;

import java.io.File;
import java.util.Objects;

/**
 * Metadata for one external GUI preset archive.
 *
 * <p>The archive is not loaded while this object is discovered. Its metadata
 * is read from the archive only after basic validation.</p>
 */
public final class GuiPreset {

    private final File archive;
    private final String displayName;
    private final String version;
    private final String factoryClassName;

    GuiPreset(File archive, String displayName, String version, String factoryClassName) {
        this.archive = Objects.requireNonNull(archive, "archive");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.version = version;
        this.factoryClassName = Objects.requireNonNull(factoryClassName, "factoryClassName");
    }

    /** Return the preset JAR selected by the user. */
    public File getArchive() {
        return archive;
    }

    /** Return the human-readable name declared by the preset. */
    public String getDisplayName() {
        return displayName;
    }

    /** Return the optional preset version. */
    public String getVersion() {
        return version;
    }

    /** Return the GUI factory implementation class declared by the preset. */
    public String getFactoryClassName() {
        return factoryClassName;
    }

    /** Return the file name used to persist a selection. */
    public String getArchiveName() {
        return archive.getName();
    }

    /** Return a compact label for selection UI. */
    public String getSelectionLabel() {
        if (version == null || version.isEmpty()) {
            return displayName;
        }
        return displayName + " (" + version + ")";
    }

    boolean matches(String archiveName, String expectedFactoryClassName) {
        return getArchiveName().equals(archiveName)
                && factoryClassName.equals(expectedFactoryClassName);
    }
}
