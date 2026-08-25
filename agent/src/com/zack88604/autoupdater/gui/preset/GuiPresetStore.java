package com.zack88604.autoupdater.gui.preset;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Owns the fixed on-disk location for GUI preset archives and their selection.
 *
 * <p>Preset discovery reads only a small metadata entry from each JAR. It does
 * not define classes or execute preset code.</p>
 */
public final class GuiPresetStore {

    /** Directory under the Minecraft root for updater-owned auxiliary files. */
    public static final String CONFIG_DIRECTORY_NAME = ".mc-update";
    /** Directory under {@link #CONFIG_DIRECTORY_NAME} for external GUI archives. */
    public static final String PRESET_DIRECTORY_NAME = "gui-presets";
    /** Metadata required inside each selectable GUI preset JAR. */
    public static final String PRESET_METADATA_PATH = "META-INF/mc-update-gui.properties";

    private static final String SELECTION_FILE_NAME = "gui-selection.properties";
    private static final String KEY_REMEMBER = "remember";
    private static final String KEY_MODE = "mode";
    private static final String KEY_ARCHIVE = "preset-file";
    private static final String KEY_FACTORY = "factory-class";
    private static final String MODE_SWING = "swing";
    private static final String MODE_PRESET = "preset";
    private static final long MAX_METADATA_SIZE = 16 * 1024;

    private final File configurationDirectory;
    private final File presetDirectory;
    private final File selectionFile;

    /** Bind the store to one configured Minecraft game directory. */
    public GuiPresetStore(String gameDirectory) {
        File root = new File(gameDirectory);
        this.configurationDirectory = new File(root, CONFIG_DIRECTORY_NAME);
        this.presetDirectory = new File(configurationDirectory, PRESET_DIRECTORY_NAME);
        this.selectionFile = new File(configurationDirectory, SELECTION_FILE_NAME);
    }

    /** Return the updater-owned configuration directory. */
    public File getConfigurationDirectory() {
        return configurationDirectory;
    }

    /** Return the fixed directory scanned for GUI preset JARs. */
    public File getPresetDirectory() {
        return presetDirectory;
    }

    /**
     * Create the fixed directories if needed and find selectable preset JARs.
     *
     * <p>Invalid archives and archives without valid metadata are ignored. No
     * classes from those archives are loaded during this scan.</p>
     */
    public List<GuiPreset> findLoadablePresets() throws IOException {
        ensureDirectories();
        File[] archives = presetDirectory.listFiles(new FileFilter() {
            @Override
            public boolean accept(File file) {
                return file.isFile() && file.getName()
                        .toLowerCase(Locale.ROOT).endsWith(".jar");
            }
        });

        List<GuiPreset> presets = new ArrayList<GuiPreset>();
        if (archives == null) {
            return presets;
        }
        for (File archive : archives) {
            File directArchive = resolveDirectPresetArchive(archive);
            GuiPreset preset = directArchive == null ? null : readMetadata(directArchive);
            if (preset != null) {
                presets.add(preset);
            }
        }
        Collections.sort(presets, new Comparator<GuiPreset>() {
            @Override
            public int compare(GuiPreset left, GuiPreset right) {
                return left.getSelectionLabel().compareToIgnoreCase(right.getSelectionLabel());
            }
        });
        return presets;
    }

    /**
     * Return a remembered default resolved against the currently discoverable
     * presets, or {@code null} when no usable default is stored.
     */
    public GuiPresetSelection readDefault(List<GuiPreset> presets) throws IOException {
        if (!selectionFile.isFile()) {
            return null;
        }

        Properties selection = new Properties();
        try (InputStream input = new FileInputStream(selectionFile)) {
            selection.load(input);
        }
        if (!"true".equalsIgnoreCase(selection.getProperty(KEY_REMEMBER))) {
            return null;
        }

        String mode = trim(selection.getProperty(KEY_MODE));
        if (MODE_SWING.equals(mode)) {
            return GuiPresetSelection.swing(true);
        }
        if (!MODE_PRESET.equals(mode)) {
            return null;
        }

        String archiveName = trim(selection.getProperty(KEY_ARCHIVE));
        String factoryClassName = trim(selection.getProperty(KEY_FACTORY));
        if (archiveName == null || factoryClassName == null) {
            return null;
        }
        for (GuiPreset preset : presets) {
            if (preset.matches(archiveName, factoryClassName)) {
                return GuiPresetSelection.preset(preset, true);
            }
        }
        return null;
    }

    /** Persist a checked "use as default" GUI choice. */
    public void saveDefault(GuiPresetSelection selection) throws IOException {
        if (!selection.shouldRemember()) {
            clearDefault();
            return;
        }

        ensureDirectories();
        Properties values = new Properties();
        values.setProperty(KEY_REMEMBER, "true");
        if (selection.isSwing()) {
            values.setProperty(KEY_MODE, MODE_SWING);
        } else {
            GuiPreset preset = selection.getPreset();
            values.setProperty(KEY_MODE, MODE_PRESET);
            values.setProperty(KEY_ARCHIVE, preset.getArchiveName());
            values.setProperty(KEY_FACTORY, preset.getFactoryClassName());
        }

        File temporary = File.createTempFile("gui-selection-", ".tmp", configurationDirectory);
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                values.store(output, "Minecraft updater GUI selection");
            }
            moveReplacing(temporary, selectionFile);
        } finally {
            if (temporary.exists()) {
                Files.deleteIfExists(temporary.toPath());
            }
        }
    }

    /** Remove any remembered GUI selection. */
    public void clearDefault() throws IOException {
        Files.deleteIfExists(selectionFile.toPath());
    }

    private void ensureDirectories() throws IOException {
        createDirectory(configurationDirectory);
        createDirectory(presetDirectory);
    }

    /**
     * Reject archives reached through a symlink or an unexpected parent path.
     * Presets must be direct children of the fixed preset directory.
     */
    private File resolveDirectPresetArchive(File archive) {
        try {
            File root = presetDirectory.getCanonicalFile();
            File canonicalArchive = archive.getCanonicalFile();
            return root.equals(canonicalArchive.getParentFile()) ? canonicalArchive : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static void createDirectory(File directory) throws IOException {
        if (directory.isDirectory()) {
            return;
        }
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Unable to create GUI preset directory: " + directory);
        }
    }

    private static GuiPreset readMetadata(File archive) {
        try (JarFile jar = new JarFile(archive)) {
            JarEntry metadata = jar.getJarEntry(PRESET_METADATA_PATH);
            if (metadata == null || metadata.getSize() > MAX_METADATA_SIZE) {
                return null;
            }

            Properties values = new Properties();
            try (InputStream input = jar.getInputStream(metadata)) {
                values.load(input);
            }
            String factoryClassName = trim(values.getProperty("factory-class"));
            if (!isValidClassName(factoryClassName)) {
                return null;
            }

            String displayName = trim(values.getProperty("name"));
            if (displayName == null) {
                displayName = stripJarExtension(archive.getName());
            }
            String version = trim(values.getProperty("version"));
            return new GuiPreset(archive, displayName, version, factoryClassName);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static void moveReplacing(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String stripJarExtension(String fileName) {
        int extension = fileName.toLowerCase(Locale.ROOT).lastIndexOf(".jar");
        return extension > 0 ? fileName.substring(0, extension) : fileName;
    }

    private static boolean isValidClassName(String className) {
        if (className == null) {
            return false;
        }
        String[] parts = className.split("\\.");
        for (String part : parts) {
            if (part.isEmpty() || !Character.isJavaIdentifierStart(part.charAt(0))) {
                return false;
            }
            for (int index = 1; index < part.length(); index++) {
                if (!Character.isJavaIdentifierPart(part.charAt(index))) {
                    return false;
                }
            }
        }
        return true;
    }
}
