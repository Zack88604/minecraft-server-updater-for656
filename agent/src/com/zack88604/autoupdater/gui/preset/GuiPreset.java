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

    /** The execution model declared by a preset's metadata. */
    public enum RuntimeKind {
        /** The preset factory creates a GUI adapter in the Minecraft JVM. */
        IN_PROCESS,
        /** The preset starts its GUI in an isolated Java helper JVM. */
        JAVA_HELPER
    }

    private final File archive;
    private final String displayName;
    private final String version;
    private final String factoryClassName;
    private final RuntimeKind runtimeKind;
    private final String runtimeManifestPath;

    GuiPreset(File archive, String displayName, String version, String factoryClassName,
              RuntimeKind runtimeKind, String runtimeManifestPath) {
        this.archive = Objects.requireNonNull(archive, "archive");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.version = version;
        this.factoryClassName = Objects.requireNonNull(factoryClassName, "factoryClassName");
        this.runtimeKind = Objects.requireNonNull(runtimeKind, "runtimeKind");
        this.runtimeManifestPath = runtimeManifestPath;
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

    /** Return whether this preset runs in-process or in a Java helper JVM. */
    public RuntimeKind getRuntimeKind() {
        return runtimeKind;
    }

    /** Return whether this preset requests the V2 isolated Java helper runtime. */
    public boolean usesJavaHelperRuntime() {
        return runtimeKind == RuntimeKind.JAVA_HELPER;
    }

    /**
     * Return the archive-internal helper runtime manifest path, or {@code null}
     * for a V1 in-process preset.
     */
    public String getRuntimeManifestPath() {
        return runtimeManifestPath;
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
