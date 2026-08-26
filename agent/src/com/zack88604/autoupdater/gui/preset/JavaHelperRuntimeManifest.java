package com.zack88604.autoupdater.gui.preset;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Parsed, validated V2 Java-helper runtime manifest. */
final class JavaHelperRuntimeManifest {

    private static final long MAX_MANIFEST_SIZE = 32 * 1024;
    private static final int MAX_RUNTIME_ARTIFACTS = 64;

    private final String helperMainClass;
    private final int minimumJavaVersion;
    private final List<String> classPathResources;
    private final List<String> modulePathResources;
    private final List<String> addModules;
    private final Map<String, String> expectedHashes;

    private JavaHelperRuntimeManifest(String helperMainClass, int minimumJavaVersion,
                                      List<String> classPathResources,
                                      List<String> modulePathResources,
                                      List<String> addModules,
                                      Map<String, String> expectedHashes) {
        this.helperMainClass = helperMainClass;
        this.minimumJavaVersion = minimumJavaVersion;
        this.classPathResources = immutableCopy(classPathResources);
        this.modulePathResources = immutableCopy(modulePathResources);
        this.addModules = immutableCopy(addModules);
        this.expectedHashes = Collections.unmodifiableMap(
                new LinkedHashMap<String, String>(expectedHashes));
    }

    static JavaHelperRuntimeManifest read(GuiPreset preset) throws IOException {
        try (JarFile archive = new JarFile(preset.getArchive())) {
            JarEntry entry = archive.getJarEntry(preset.getRuntimeManifestPath());
            if (entry == null || entry.isDirectory() || entry.getSize() > MAX_MANIFEST_SIZE) {
                throw new IOException("Missing or invalid Java helper runtime manifest");
            }

            Properties values = new Properties();
            try (InputStream input = archive.getInputStream(entry)) {
                values.load(input);
            }

            String helperMainClass = trim(values.getProperty("helper-main-class"));
            if (!isValidClassName(helperMainClass)) {
                throw new IOException("Invalid Java helper main class");
            }
            int minimumJavaVersion = parseMinimumJavaVersion(values);
            List<String> classPath = parseResourceList(values.getProperty("classpath"));
            List<String> modulePath = parseResourceList(values.getProperty("module-path"));
            List<String> addModules = parseModuleList(values.getProperty("add-modules"));

            Set<String> resources = new LinkedHashSet<String>();
            resources.addAll(classPath);
            resources.addAll(modulePath);
            if (resources.size() > MAX_RUNTIME_ARTIFACTS) {
                throw new IOException("Too many Java helper runtime artifacts");
            }

            Map<String, String> hashes = new LinkedHashMap<String, String>();
            for (String resource : resources) {
                JarEntry artifact = archive.getJarEntry(resource);
                if (artifact == null || artifact.isDirectory()) {
                    throw new IOException("Missing Java helper runtime artifact: " + resource);
                }
                String expectedHash = trim(values.getProperty("sha256." + resource));
                if (!isSha256(expectedHash)) {
                    throw new IOException("Missing SHA-256 for Java helper artifact: " + resource);
                }
                hashes.put(resource, expectedHash);
            }
            return new JavaHelperRuntimeManifest(helperMainClass, minimumJavaVersion,
                    classPath, modulePath, addModules, hashes);
        }
    }

    String getHelperMainClass() {
        return helperMainClass;
    }

    int getMinimumJavaVersion() {
        return minimumJavaVersion;
    }

    List<String> getClassPathResources() {
        return classPathResources;
    }

    List<String> getModulePathResources() {
        return modulePathResources;
    }

    List<String> getAddModules() {
        return addModules;
    }

    String getExpectedHash(String resource) {
        return expectedHashes.get(resource);
    }

    private static List<String> parseResourceList(String raw) throws IOException {
        List<String> values = new ArrayList<String>();
        String text = trim(raw);
        if (text == null) {
            return values;
        }
        for (String part : text.split(",")) {
            String resource = trim(part);
            if (!isSafeResourcePath(resource) || values.contains(resource)) {
                throw new IOException("Invalid Java helper runtime resource");
            }
            values.add(resource);
        }
        return values;
    }

    private static List<String> parseModuleList(String raw) throws IOException {
        List<String> values = new ArrayList<String>();
        String text = trim(raw);
        if (text == null) {
            return values;
        }
        for (String part : text.split(",")) {
            String module = trim(part);
            if (!isSafeModuleName(module) || values.contains(module)) {
                throw new IOException("Invalid Java helper module name");
            }
            values.add(module);
        }
        return values;
    }

    private static int parseMinimumJavaVersion(Properties values) throws IOException {
        String raw = trim(values.getProperty("minimum-java-version"));
        if (raw == null) {
            return 8;
        }
        try {
            int value = Integer.parseInt(raw);
            if (value < 8 || value > 99) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid Java helper minimum Java version", exception);
        }
    }

    private static boolean isSafeResourcePath(String path) {
        if (path == null || path.startsWith("/") || path.indexOf('\\') >= 0) {
            return false;
        }
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSafeModuleName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if (!(Character.isLetterOrDigit(character) || character == '.'
                    || character == '-' || character == '_')) {
                return false;
            }
        }
        return true;
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

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static List<String> immutableCopy(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<String>(values));
    }
}
