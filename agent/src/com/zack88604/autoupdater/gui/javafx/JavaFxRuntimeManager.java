package com.zack88604.autoupdater.gui.javafx;

import com.zack88604.autoupdater.infrastructure.json.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the JavaFX runtime directory next to the agent core JAR.
 *
 * <p>The Minecraft JVM never loads {@code javafx.*}; instead a separate helper JVM
 * is launched with {@code --module-path <agentDir>/javafx-runtime/<version>}. This
 * class owns the <b>offline</b> side of that directory: locating it and checking
 * that the installed runtime matches the <b>embedded</b> {@code /javafx-runtime-spec.json}
 * carried by the release itself (pure client bootstrap — the server manifest never
 * describes the JavaFX runtime).</p>
 *
 * <p>第一阶段 does <em>not</em> implement the network download / repair flow: a
 * missing or corrupt runtime simply reports {@link RuntimeStatus#MISSING} /
 * {@link RuntimeStatus#CORRUPTED}, the helper launch is skipped, and
 * {@link RemoteJavaFxUpdateView} transparently falls back to Swing. The Phase-2
 * {@code ensureReady} repair step is a deliberately deferred TODO.</p>
 */
final class JavaFxRuntimeManager {

    /** Classpath resource embedded in the core JAR by the build. */
    private static final String EMBEDDED_SPEC = "/javafx-runtime-spec.json";

    /** Install-completeness of the local runtime. */
    enum RuntimeStatus { READY, MISSING, CORRUPTED, UNSUPPORTED }

    // ── Runtime location ────────────────────────────────────────

    /** Directory that contains the agent core JAR (and {@code javafx-runtime/}). */
    static File agentDir() {
        try {
            String path = JavaFxRuntimeManager.class.getProtectionDomain()
                    .getCodeSource().getLocation().getPath();
            String decoded = URLDecoder.decode(path, StandardCharsets.UTF_8);
            File jar = new File(decoded);
            return jar.isFile() ? jar.getParentFile() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** The {@code javafx-runtime/} directory next to the core JAR, or null. */
    static File runtimeDir() {
        File agent = agentDir();
        return agent == null ? null : new File(agent, "javafx-runtime");
    }

    /** The installed runtime version subdirectory, or null if not installed. */
    static File runtimeVersionDir() {
        File dir = runtimeDir();
        if (dir == null) {
            return null;
        }
        String version = readRuntimeVersion();
        if (version == null) {
            return null;
        }
        return new File(dir, version);
    }

    private static String readRuntimeVersion() {
        File dir = runtimeDir();
        if (dir == null) {
            return null;
        }
        File f = new File(dir, "runtime.json");
        String json = readFile(f);
        return json == null ? null : JsonParser.getString(json, "version");
    }

    // ── Offline verification (no network) ───────────────────────

    /**
     * Check the local runtime against the <b>embedded</b> runtime spec (the
     * release's own source of truth). No spec → MISSING; the current platform has
     * no artifact in the spec → UNSUPPORTED; missing {@code runtime.json} /
     * {@code .installed} or a version/classifier that predates this release →
     * MISSING; a jar whose hash differs from the spec → CORRUPTED; else READY.
     */
    static RuntimeStatus verifyLocal() {
        JavaFxSpec spec = loadEmbeddedSpec();
        if (spec == null) {
            return RuntimeStatus.MISSING;
        }
        String cls = platformClassifier();
        boolean supported = false;
        for (Artifact a : spec.artifacts) {
            if (cls.equals(a.classifier)) {
                supported = true;
                break;
            }
        }
        if (!supported) {
            return RuntimeStatus.UNSUPPORTED;
        }
        File dir = runtimeDir();
        if (dir == null) {
            return RuntimeStatus.MISSING;
        }
        File runtimeJson = new File(dir, "runtime.json");
        File installedMarker = new File(dir, ".installed");
        if (!runtimeJson.isFile() || !installedMarker.isFile()) {
            return RuntimeStatus.MISSING;
        }
        String json = readFile(runtimeJson);
        if (json == null) {
            return RuntimeStatus.CORRUPTED;
        }
        if (!spec.version.equals(JsonParser.getString(json, "version"))) {
            return RuntimeStatus.MISSING;   // local runtime predates this release
        }
        if (!cls.equals(JsonParser.getString(json, "classifier"))) {
            return RuntimeStatus.MISSING;
        }
        File versionDir = new File(dir, spec.version);
        for (Artifact a : spec.artifacts) {
            if (!cls.equals(a.classifier)) {
                continue;
            }
            File jar = new File(versionDir, a.file);
            if (!jar.isFile()) {
                return RuntimeStatus.MISSING;
            }
            String hash = sha256(jar);
            if (hash == null || !hash.equalsIgnoreCase(a.hash)) {
                return RuntimeStatus.CORRUPTED;
            }
        }
        return RuntimeStatus.READY;
    }

    // ── Embedded spec ───────────────────────────────────────────

    /** Load the release's {@code /javafx-runtime-spec.json} from the core JAR,
     *  or null when absent/unreadable. */
    static JavaFxSpec loadEmbeddedSpec() {
        try (InputStream in = JavaFxRuntimeManager.class.getResourceAsStream(EMBEDDED_SPEC)) {
            if (in == null) {
                return null;
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return JavaFxSpec.parse(json);
        } catch (IOException e) {
            return null;
        }
    }

    // ── Small helpers ───────────────────────────────────────────

    /** OpenJFX platform classifier for the current OS. */
    static String platformClassifier() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return "win";
        }
        if (os.contains("mac")) {
            return "mac";
        }
        return "linux";
    }

    static String sha256(File file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                while ((n = in.read(buf)) != -1) {
                    md.update(buf, 0, n);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String readFile(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** Parse a JSON array body of artifact objects into a list. */
    private static List<Artifact> parseArtifacts(String arrayBody) {
        List<Artifact> list = new ArrayList<>();
        if (arrayBody == null) {
            return list;
        }
        int depth = 0, start = -1;
        for (int i = 0; i < arrayBody.length(); i++) {
            char c = arrayBody.charAt(i);
            if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    String obj = arrayBody.substring(start, i + 1);
                    String module = JsonParser.getString(obj, "module");
                    String classifier = JsonParser.getString(obj, "classifier");
                    String file = JsonParser.getString(obj, "file");
                    String hash = JsonParser.getString(obj, "hash");
                    long size = JsonParser.getLong(obj, "size", 0);
                    if (module != null && classifier != null && file != null && hash != null) {
                        list.add(new Artifact(module, classifier, file, size, hash));
                    }
                    start = -1;
                }
            }
        }
        return list;
    }

    /** The {@code javafx} runtime spec embedded in this release. */
    static final class JavaFxSpec {
        final String version;
        final int minJdk;
        final List<String> modules;
        final List<Artifact> artifacts;

        private JavaFxSpec(String version, int minJdk,
                           List<String> modules, List<Artifact> artifacts) {
            this.version = version;
            this.minJdk = minJdk;
            this.modules = modules;
            this.artifacts = artifacts;
        }

        static JavaFxSpec parse(String json) {
            String version = JsonParser.getString(json, "version");
            int minJdk = JsonParser.getInt(json, "min_jdk", 0);
            List<String> modules = JsonParser.parseStringArray(
                    JsonParser.getArray(json, "modules"));
            List<Artifact> artifacts = parseArtifacts(JsonParser.getArray(json, "artifacts"));
            return new JavaFxSpec(version, minJdk, modules, artifacts);
        }
    }

    /** One runtime jar descriptor from the embedded spec. */
    static final class Artifact {
        final String module;
        final String classifier;
        final String file;
        final long size;
        final String hash;

        Artifact(String module, String classifier, String file, long size, String hash) {
            this.module = module;
            this.classifier = classifier;
            this.file = file;
            this.size = size;
            this.hash = hash;
        }
    }
}
