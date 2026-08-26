package com.zack88604.autoupdater.gui.preset;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Extracts and verifies approved Java-helper runtime artifacts into updater storage. */
final class JavaHelperRuntimeInstaller {

    private static final String RUNTIME_DIRECTORY_NAME = "gui-runtimes";
    private static final long MAX_ARTIFACT_SIZE = 512L * 1024L * 1024L;

    private JavaHelperRuntimeInstaller() {
    }

    static PreparedRuntime prepare(GuiPreset preset, File configurationDirectory)
            throws IOException {
        JavaHelperRuntimeManifest manifest = JavaHelperRuntimeManifest.read(preset);
        File cacheRoot = new File(configurationDirectory, RUNTIME_DIRECTORY_NAME);
        ensureDirectory(cacheRoot);
        String archiveHash = sha256(preset.getArchive());
        if (archiveHash == null) {
            throw new IOException("Unable to hash Java helper preset archive");
        }
        File runtimeDirectory = new File(cacheRoot, archiveHash);
        ensureDirectory(runtimeDirectory);

        List<File> classPath = extractAll(preset, manifest,
                manifest.getClassPathResources(), runtimeDirectory);
        List<File> modulePath = extractAll(preset, manifest,
                manifest.getModulePathResources(), runtimeDirectory);
        return new PreparedRuntime(manifest.getHelperMainClass(), manifest.getMinimumJavaVersion(),
                classPath, modulePath, manifest.getAddModules());
    }

    private static List<File> extractAll(GuiPreset preset, JavaHelperRuntimeManifest manifest,
                                         List<String> resources, File runtimeDirectory)
            throws IOException {
        List<File> extracted = new ArrayList<File>();
        try (JarFile archive = new JarFile(preset.getArchive())) {
            for (String resource : resources) {
                extracted.add(extractOne(archive, resource, manifest.getExpectedHash(resource),
                        runtimeDirectory));
            }
        }
        return extracted;
    }

    private static File extractOne(JarFile archive, String resource, String expectedHash,
                                   File runtimeDirectory) throws IOException {
        File target = resolveInside(runtimeDirectory, resource);
        if (target.isFile() && expectedHash.equalsIgnoreCase(sha256(target))) {
            return target;
        }

        JarEntry source = archive.getJarEntry(resource);
        if (source == null || source.isDirectory() || source.getSize() > MAX_ARTIFACT_SIZE) {
            throw new IOException("Invalid Java helper runtime artifact: " + resource);
        }
        File parent = target.getParentFile();
        ensureDirectory(parent);
        File temporary = new File(parent, ".mc-update-runtime-" + UUID.randomUUID() + ".tmp");
        try {
            try (InputStream input = archive.getInputStream(source);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            }
            String actualHash = sha256(temporary);
            if (!expectedHash.equalsIgnoreCase(actualHash)) {
                throw new IOException("Java helper runtime artifact checksum mismatch: " + resource);
            }
            moveReplacing(temporary, target);
        } finally {
            if (temporary.exists()) {
                Files.deleteIfExists(temporary.toPath());
            }
        }
        return target;
    }

    private static File resolveInside(File root, String relativePath) throws IOException {
        File canonicalRoot = root.getCanonicalFile();
        File target = new File(canonicalRoot, relativePath).getCanonicalFile();
        if (!target.toPath().startsWith(canonicalRoot.toPath())) {
            throw new IOException("Unsafe Java helper runtime path");
        }
        return target;
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (directory.isDirectory()) {
            return;
        }
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Unable to create Java helper runtime directory: " + directory);
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

    private static String sha256(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            StringBuilder value = new StringBuilder();
            for (byte byteValue : digest.digest()) {
                value.append(String.format("%02x", byteValue));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    /** Runtime files and launch data prepared after user approval. */
    static final class PreparedRuntime {
        private final String helperMainClass;
        private final int minimumJavaVersion;
        private final List<File> classPath;
        private final List<File> modulePath;
        private final List<String> addModules;

        private PreparedRuntime(String helperMainClass, int minimumJavaVersion,
                                List<File> classPath, List<File> modulePath,
                                List<String> addModules) {
            this.helperMainClass = helperMainClass;
            this.minimumJavaVersion = minimumJavaVersion;
            this.classPath = new ArrayList<File>(classPath);
            this.modulePath = new ArrayList<File>(modulePath);
            this.addModules = new ArrayList<String>(addModules);
        }

        String getHelperMainClass() {
            return helperMainClass;
        }

        int getMinimumJavaVersion() {
            return minimumJavaVersion;
        }

        List<File> getClassPath() {
            return classPath;
        }

        List<File> getModulePath() {
            return modulePath;
        }

        List<String> getAddModules() {
            return addModules;
        }
    }
}
