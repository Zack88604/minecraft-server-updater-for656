import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages the JavaFX runtime directory next to the agent core JAR.
 *
 * The Minecraft JVM never loads {@code javafx.*}; instead a separate helper JVM
 * is launched with {@code --module-path <agentDir>/javafx-runtime/<version>}.
 * This class owns that directory:
 *
 * <ul>
 *   <li>{@code verifyLocal()} — offline install-completeness check used by the
 *       premain UI decision. The reference is the <b>embedded</b>
 *       {@code /javafx-runtime-spec.json} carried by the release itself
 *       (pure client bootstrap — the server manifest never describes the JavaFX
 *       runtime, v3改动说明);</li>
 *   <li>{@code ensureReady()} — a synchronous, blocking, best-effort repair run
 *       as a fixed step of the update flow (the same PREPARING stage as the
 *       updater self-update check). When the local runtime is missing, corrupt
 *       or outdated relative to the embedded spec it repairs it from Maven
 *       Central ({@code .tmp} + SHA-256 + atomic move), and the flow waits for
 *       the download to finish — success or failure — before continuing.</li>
 *   <li>{@code remove()} — the {@code remove-javafx} agent argument.</li>
 * </ul>
 *
 * Upgrades never delete the version currently in use this session: {@code install}
 * records it as {@code previous_version} in {@code runtime.json} and the deletion
 * is deferred until a later launch confirms the new-version helper actually works.
 * {@code ensureReady()} runs best-effort — any failure (e.g. Maven Central
 * unreachable) is logged and ignored, never blocking or failing the update flow
 * or the Minecraft launch.
 */
final class JavaFxRuntimeManager {

    /** Maven Central base URL for OpenJFX artifacts. */
    private static final String MAVEN_BASE = "https://repo1.maven.org/maven2/org/openjfx";

    /** Classpath resource embedded in the core JAR by the build. */
    private static final String EMBEDDED_SPEC = "/javafx-runtime-spec.json";

    /** Install-completeness of the local runtime. */
    enum RuntimeStatus { READY, MISSING, CORRUPTED, UNSUPPORTED }

    /** When true, the in-flow runtime repair is skipped (set by the
     *  {@code remove-javafx} admin arg so a deleted runtime stays deleted). */
    private static volatile boolean repairSuspended;

    // ── Runtime location ────────────────────────────────────────

    /** Directory that contains the agent core JAR (and {@code javafx-runtime/}). */
    static File agentDir() {
        try {
            String path = UpdateService.class.getProtectionDomain()
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
        if (json == null) {
            return null;
        }
        return JsonParser.getString(json, "version");
    }

    // ── Offline verification (premain, no network) ──────────────

    /**
     * Check the local runtime against the <b>embedded</b> runtime spec (the
     * release's own source of truth). No spec → MISSING; the current platform
     * has no artifact in the spec → UNSUPPORTED; missing {@code runtime.json} /
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

    // ── Runtime repair (network, blocking, best-effort) ─────────

    /**
     * Synchronously make sure the local JavaFX runtime matches the embedded
     * spec. Runs as a fixed step of the update flow — the same PREPARING stage
     * as the updater self-update check. A READY runtime short-circuits; a
     * missing, corrupt or outdated one is repaired from Maven Central, and the
     * flow waits for the download to finish before continuing. Best-effort:
     * whether the repair succeeds or fails the flow always continues — any
     * failure is logged and swallowed, never blocking or failing the update.
     */
    static void ensureReady(UpdateListener listener) {
        try {
            if (repairSuspended) {
                log(listener, "[javafx] Runtime repair suspended (remove-javafx).");
                return;
            }
            JavaFxSpec spec = loadEmbeddedSpec();
            if (spec == null) {
                log(listener, "[javafx] No embedded runtime spec; nothing to do.");
                return;
            }
            RuntimeStatus st = verifyLocal();
            if (st == RuntimeStatus.READY) {
                log(listener, "[javafx] Runtime " + spec.version + " up to date.");
                return;
            }
            if (st == RuntimeStatus.UNSUPPORTED) {
                log(listener, "[javafx] Current platform has no artifact in the embedded runtime spec.");
                return;
            }
            log(listener, "[javafx] Runtime " + st + " (spec version " + spec.version
                    + ") — repairing from Maven Central...");
            ServerClient client = new ServerClient(Collections.emptyList());
            client.setListener(listener);
            install(spec, client, listener);
        } catch (Throwable t) {
            System.err.println("[javafx] Runtime repair error (best-effort, ignored): " + t);
        }
    }

    /**
     * Download the current classifier's JavaFX jars from Maven Central, verify
     * each against the spec hash and install them. On success writes
     * {@code runtime.json} + {@code .installed}; the previous version (if any)
     * is recorded but <b>not deleted</b> — cleanup is deferred to the next
     * launch that confirms the new helper works. Fail-fast: the first download
     * or hash failure aborts and cleans the temp dir.
     */
    private static boolean install(JavaFxSpec spec, ServerClient client, UpdateListener listener) {
        String cls = platformClassifier();
        File dir = runtimeDir();
        if (dir == null) {
            return false;
        }
        dir.mkdirs();
        File versionDir = new File(dir, spec.version);
        File tmpDir = new File(versionDir, ".tmp");
        try {
            if (!tmpDir.isDirectory() && !tmpDir.mkdirs()) {
                return false;
            }
            for (Artifact a : spec.artifacts) {
                if (!cls.equals(a.classifier)) {
                    continue;
                }
                String url = MAVEN_BASE + "/" + a.module + "/" + spec.version + "/" + a.file;
                File dest = new File(versionDir, a.file);
                File tmp = new File(tmpDir, a.file);
                log(listener, "[javafx] Downloading " + a.file + " ...");
                if (!client.httpDownload(url, tmp, a.file, DownloadProgress.Kind.JAVAFX)) {
                    log(listener, "[javafx] FAIL download: " + a.file);
                    return false;
                }
                String hash = sha256(tmp);
                if (hash == null || !hash.equalsIgnoreCase(a.hash)) {
                    log(listener, "[javafx] FAIL hash mismatch after download: " + a.file);
                    return false;
                }
                if (!move(tmp, dest)) {
                    log(listener, "[javafx] FAIL cannot move into place: " + a.file);
                    return false;
                }
            }
            writeRuntimeJson(dir, spec, cls);
            touch(new File(dir, ".installed"));
            log(listener, "[javafx] Runtime " + spec.version + " installed.");
            return true;
        } finally {
            deleteRecursive(tmpDir);
        }
    }

    /** Record the installed version. Preserves a previous_version for deferred
     *  cleanup when the version actually changed. */
    private static void writeRuntimeJson(File runtimeDir, JavaFxSpec spec, String classifier) {
        File f = new File(runtimeDir, "runtime.json");
        String previousVersion = null;
        if (f.isFile()) {
            String old = readFile(f);
            String oldVersion = old == null ? null : JsonParser.getString(old, "version");
            if (oldVersion != null && !oldVersion.equals(spec.version)) {
                previousVersion = oldVersion;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"version\": \"").append(spec.version).append("\",\n");
        sb.append("  \"classifier\": \"").append(classifier).append("\",\n");
        sb.append("  \"min_jdk\": ").append(spec.minJdk).append(",\n");
        sb.append("  \"module_path\": \"").append(spec.version).append("\",\n");
        if (previousVersion != null) {
            sb.append("  \"previous_version\": \"").append(previousVersion).append("\",\n");
        }
        sb.append("  \"artifacts\": [\n");
        boolean first = true;
        for (Artifact a : spec.artifacts) {
            if (!classifier.equals(a.classifier)) {
                continue;
            }
            if (!first) {
                sb.append(",\n");
            }
            first = false;
            sb.append("    { \"module\": \"").append(a.module)
              .append("\", \"classifier\": \"").append(a.classifier)
              .append("\", \"file\": \"").append(a.file)
              .append("\", \"size\": ").append(a.size)
              .append(", \"hash\": \"").append(a.hash).append("\" }");
        }
        sb.append("\n  ]\n}\n");
        writeFile(f, sb.toString());
    }

    /**
     * Delete a recorded {@code previous_version} directory. Called only after a
     * helper running the current version has confirmed it works (the ready
     * handshake).
     */
    static void cleanupOldVersion() {
        File dir = runtimeDir();
        if (dir == null) {
            return;
        }
        File f = new File(dir, "runtime.json");
        if (!f.isFile()) {
            return;
        }
        String json = readFile(f);
        if (json == null) {
            return;
        }
        String prev = JsonParser.getString(json, "previous_version");
        if (prev == null) {
            return;
        }
        File oldDir = new File(dir, prev);
        if (oldDir.isDirectory()) {
            deleteRecursive(oldDir);
        }
        String cleaned = json.replaceAll(
                "(?m)^[ \\t]*\"previous_version\": \"[^\"]*\",?\\r?\\n?", "");
        writeFile(f, cleaned);
    }

    /** Remove the whole {@code javafx-runtime/} directory (remove-javafx). */
    static void remove() {
        File dir = runtimeDir();
        if (dir != null && dir.isDirectory()) {
            deleteRecursive(dir);
        }
    }

    /** Suspend the in-flow runtime repair (used by the {@code remove-javafx}
     *  admin arg so a deleted runtime is not silently re-downloaded). */
    static void suspendRepair() {
        repairSuspended = true;
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

    private static boolean move(File tmp, File dest) {
        try {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void touch(File f) {
        try {
            Files.write(f.toPath(), new byte[0]);
        } catch (IOException ignored) {
        }
    }

    private static String readFile(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeFile(File f, String content) {
        try {
            Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    private static void deleteRecursive(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursive(c);
            }
        }
        f.delete();
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

    private static void log(UpdateListener listener, String msg) {
        if (listener != null) {
            listener.onUpdateEvent(new UpdateEvent.LogMessage(msg));
        }
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

    /** One runtime jar descriptor from the embedded spec or runtime.json. */
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
