package com.zack88604.autoupdater.gui.javafx;

import com.zack88604.autoupdater.infrastructure.json.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages the JavaFX runtime directory next to the agent core JAR.
 *
 * <p>The Minecraft JVM never loads {@code javafx.*}; instead a separate helper JVM
 * is launched with {@code --module-path <agentDir>/javafx-runtime/<version>}. This
 * class owns that directory: locating it, checking it against the <b>embedded</b>
 * {@code /javafx-runtime-spec.json} carried by the release itself (pure client
 * bootstrap — the server manifest never describes the JavaFX runtime), and — since
 * Phase 2A — repairing it over the network when it is missing or corrupt.</p>
 *
 * <p>Phase 2A responsibilities:</p>
 *
 * <ul>
 *   <li>{@link #verifyLocal()} is the single offline runtime decision point
 *       (spec → platform → min JDK → directory → markers → version → classifier →
 *       per-jar size + SHA-256). It is called by {@code JavaFxHelperProcess.launch()}
 *       before any helper JVM is started.</li>
 *   <li>{@link #ensureReady()} is the online verify + repair entry. It returns a
 *       distinct {@link RepairResult} (READY / REPAIRED / MISSING / CORRUPTED /
 *       UNSUPPORTED / DOWNLOAD_FAILED / IO_ERROR / CANCELLED) so callers can
 *       distinguish "already usable" from "repaired" from "cannot be supported"
 *       from each failure class from "cooperatively cancelled".</li>
 *   <li>Downloads use the embedded spec's fixed SHA-256 as the only trusted hash
 *       (no remote {@code .sha1}/{@code .md5}, no trust in a remote checksum), the
 *       spec's {@code size} as a second check (Content-Length early check + final
 *       byte count), and a safe install: unique UUID temp file → verify →
 *       {@code ATOMIC_MOVE + REPLACE_EXISTING} → temp cleanup on any failure.
 *       Repair is per-artifact (only what is missing / wrong size / wrong hash).</li>
 *   <li>{@code runtime.json} and {@code .installed} are written atomically only
 *       after the whole runtime installs and a final {@link #verifyLocal()} returns
 *       READY — a partial install can never be mistaken for READY.</li>
 * </ul>
 *
 * <p>Failure isolation: every failure mode (network unreachable, DNS, HTTP non-2xx,
 * interrupted transfer, size/hash mismatch, no write permission, read-only dir, disk
 * full, failed move) is contained here — it cleans up its temp files, logs a
 * diagnostic, and returns a {@link RepairResult}. Nothing propagates an unchecked
 * exception to the Minecraft update flow; the JavaFX runtime is optional GUI
 * infrastructure, never a hard dependency of launching Minecraft.</p>
 *
 * <p>Concurrency: downloads use UUID temp files, formal files are only replaced
 * after full verification, and {@code .installed}/{@code runtime.json} are only
 * written with identical content after a complete install. Two Minecraft instances
 * sharing this directory therefore never produce a corrupt runtime; a lightweight
 * in-JVM lock is deliberately not used so concurrent repairs are just redundant
 * (safe) downloads rather than a serialization point.</p>
 */
public final class JavaFxRuntimeManager {

    /** Classpath resource embedded in the core JAR by the build. */
    private static final String EMBEDDED_SPEC = "/javafx-runtime-spec.json";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    /** Install-completeness of the local runtime (offline check). */
    public enum RuntimeStatus { READY, MISSING, CORRUPTED, UNSUPPORTED }

    /** Outcome of {@link #ensureReady()} — the online verify + repair entry. */
    enum RepairResult {
        /** Already usable, nothing to download. */
        READY,
        /** Missing/corrupt runtime was downloaded, installed and re-verified. */
        REPAIRED,
        /** Nothing installable (e.g. no embedded spec on this release). */
        MISSING,
        /** Install finished but the final verification failed (unexpected). */
        CORRUPTED,
        /** Current platform or JDK cannot host this runtime — never downloads. */
        UNSUPPORTED,
        /** Network / HTTP / size / hash failure during download. */
        DOWNLOAD_FAILED,
        /** Local filesystem / permission failure during install. */
        IO_ERROR,
        /** The repair was cooperatively cancelled before reaching a terminal
         *  result (the manager knows only that it was cancelled, never why). */
        CANCELLED
    }

    /** A distinct step of one online repair, reported through {@link RepairProgress}. */
    enum RepairPhase {
        /** A new artifact download has started. */
        DOWNLOADING,
        /** A downloaded artifact is being verified against the spec. */
        VERIFYING,
        /** A verified artifact is being installed into its formal location. */
        INSTALLING,
        /** The whole runtime is being committed (metadata + final verification). */
        COMMITTING
    }

    /**
     * Toolkit-independent progress sink for one online repair. A caller may pass
     * {@code null}; the manager then reports nothing. Byte callbacks fire on every
     * real buffer written (never on timer ticks or UI repaints); phase callbacks
     * fire on each distinct {@link RepairPhase} transition.
     */
    interface RepairProgress {
        /** A real byte-count increase on one artifact download. */
        void onBytes(String artifact, long downloadedBytes, long totalBytes);

        /** Entering a distinct repair step. */
        void onPhase(RepairPhase phase, String artifact);
    }

    /**
     * Cooperative cancellation for one repair. {@link #cancel()} sets the flag and
     * best-effort disconnects the in-flight HTTP connection so a blocked read
     * unblocks; the download loop also observes {@link #isCancelled()} between
     * buffers. Never stops or force-kills threads.
     */
    static final class CancellationToken {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile HttpURLConnection currentConnection;
        private volatile InputStream currentStream;

        boolean isCancelled() {
            return cancelled.get();
        }

        /** Request cancellation: set the flag and best-effort unblock an in-flight
         *  read (closing the stream / disconnecting the socket makes a blocked
         *  {@code read()} throw; the download loop also observes the flag between
         *  buffers). Never stops or force-kills threads. */
        void cancel() {
            cancelled.set(true);
            InputStream stream = currentStream;
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // Best effort — the connection disconnect + flag also apply.
                }
            }
            HttpURLConnection connection = currentConnection;
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (RuntimeException ignored) {
                    // Best effort — the read loop also observes the flag.
                }
            }
        }

        void setCurrentConnection(HttpURLConnection connection) {
            currentConnection = connection;
        }

        void setCurrentStream(InputStream stream) {
            currentStream = stream;
        }
    }

    /** Maven artifact source; default Maven Central, swappable in tests. */
    private static volatile MavenRepository repository = new MavenRepository();

    // ── Test seams (package-private, never touched by production callers) ──
    /** Override the runtime directory (otherwise derived from the code source). */
    private static volatile File runtimeDirOverride;
    /** Override the JDK-major check (0 = auto-detect from the running JVM). */
    private static volatile int jdkMajorOverride;
    /** Override the final whole-runtime verification outcome (null = real check). */
    private static volatile RuntimeStatus finalVerifyOverride;

    static void setRuntimeDirForTest(File dir) {
        runtimeDirOverride = dir;
    }

    static void setRepositoryForTest(MavenRepository repo) {
        repository = repo == null ? new MavenRepository() : repo;
    }

    static void overrideJdkMajorForTest(int major) {
        jdkMajorOverride = major;
    }

    /** Clear the in-flight repair guard so a later test case starts a fresh repair. */
    static void resetRepairGuardForTest() {
        activeRepair.set(null);
    }

    /** Force the post-commit verification outcome (null = real verifyLocal()). */
    static void overrideFinalVerifyForTest(RuntimeStatus status) {
        finalVerifyOverride = status;
    }

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
        File override = runtimeDirOverride;
        if (override != null) {
            return override;
        }
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
     * no artifact in the spec, or the current JVM is older than {@code min_jdk} →
     * UNSUPPORTED; missing {@code runtime.json} / {@code .installed} or a
     * version/classifier that predates this release → MISSING; a jar whose size or
     * SHA-256 differs from the spec → CORRUPTED; else READY. This is the single
     * offline runtime decision point for {@code JavaFxHelperProcess.launch()}.
     */
    public static RuntimeStatus verifyLocal() {
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
        // The helper launches with the current java.home, so a JVM older than the
        // spec's min_jdk can never host this runtime. Reject before looking at the
        // local directory or attempting any download (Phase 2A requirement 9).
        if (currentJavaMajorVersion() < spec.minJdk) {
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
            if (a.size > 0 && jar.length() != a.size) {
                return RuntimeStatus.CORRUPTED;
            }
            String hash = sha256(jar);
            if (hash == null || !hash.equalsIgnoreCase(a.hash)) {
                return RuntimeStatus.CORRUPTED;
            }
        }
        return RuntimeStatus.READY;
    }

    // ── Online verify + repair (Phase 2A) ──────────────────────

    /**
     * Verify the runtime and, when it is missing or corrupt, repair it over the
     * network so the <i>next</i> launch can use JavaFX. Never downloads for an
     * unsupported platform/JDK and never switches the current session — callers
     * keep their existing Swing path and only consult the result for diagnostics /
     * the next launch. Not synchronized: concurrent repairs are safe because
     * downloads use UUID temp files and installs only ever atomically replace files
     * with identical, spec-verified content.
     */
    static RepairResult ensureReady() {
        return ensureReady(null, null);
    }

    /**
     * Verify the runtime and, when it is missing or corrupt, repair it over the
     * network while reporting real progress and honoring cooperative
     * cancellation. {@code progress} and {@code token} are optional (may be
     * null): the no-arg {@link #ensureReady()} and {@code ensureReadyAsync()}
     * keep working unchanged.
     *
     * <p>A cancelled repair returns {@link RepairResult#CANCELLED} without
     * writing {@code runtime.json}/{@code .installed}, so a cancelled repair can
     * never be mistaken for READY on the next launch.</p>
     */
    static RepairResult ensureReady(RepairProgress progress, CancellationToken token) {
        if (isCancelled(token)) {
            return RepairResult.CANCELLED;
        }
        JavaFxSpec spec = loadEmbeddedSpec();
        if (spec == null) {
            return RepairResult.MISSING;
        }
        RuntimeStatus status = verifyLocal();
        switch (status) {
            case READY:
                return RepairResult.READY;
            case UNSUPPORTED:
                return RepairResult.UNSUPPORTED;
            case MISSING:
            case CORRUPTED:
                break;   // → repair below
        }
        try {
            return repair(spec, progress, token);
        } catch (IOException e) {
            if (isCancelled(token)) {
                return RepairResult.CANCELLED;
            }
            System.err.println("[javafx] Runtime repair local IO failure: " + e);
            return RepairResult.IO_ERROR;
        }
    }

    private static boolean isCancelled(CancellationToken token) {
        return token != null && token.isCancelled();
    }

    private static void notifyPhase(RepairProgress progress, RepairPhase phase,
                                    String artifact) {
        if (progress != null) {
            progress.onPhase(phase, artifact);
        }
    }

    /** In-flight repair guard: one background repair per JVM is enough — a second
     *  call while one is running would just re-download identical content. Cleared
     *  when the repair thread finishes, so a later call can start a fresh one. */
    private static final AtomicReference<Thread> activeRepair = new AtomicReference<>();

    /**
     * Kick off {@link #ensureReady()} on a daemon thread (returned for tests to
     * join). Used when the runtime is not READY: the current session stays on
     * Swing immediately, and the repair runs in the background so a slow or failed
     * download never blocks the Minecraft update flow (Phase 2A requirements
     * 11/12). Idempotent within one JVM: if a repair is already in flight the
     * running thread is returned instead of spawning a duplicate — both the
     * composition root ({@code AgentBootstrap}) and the helper launch gate
     * ({@code JavaFxHelperProcess#launch}) can ask on the same launch.
     */
    public static Thread ensureReadyAsync() {
        Thread t = new Thread(() -> {
            try {
                System.err.println("[javafx] JavaFX runtime repair: " + ensureReady());
            } catch (Throwable e) {
                System.err.println("[javafx] JavaFX runtime repair failed unexpectedly: " + e);
            } finally {
                activeRepair.compareAndSet(Thread.currentThread(), null);
            }
        }, "javafx-runtime-repair");
        t.setDaemon(true);
        if (!activeRepair.compareAndSet(null, t)) {
            return activeRepair.get();
        }
        t.start();
        return t;
    }

    /** Install every artifact for the current platform, then write metadata. */
    private static RepairResult repair(JavaFxSpec spec, RepairProgress progress,
                                       CancellationToken token) throws IOException {
        String cls = platformClassifier();
        File runtimeDir = runtimeDir();
        if (runtimeDir == null) {
            return RepairResult.IO_ERROR;
        }
        ensureDirectory(runtimeDir);
        File versionDir = new File(runtimeDir, spec.version);
        ensureDirectory(versionDir);

        for (Artifact a : spec.artifacts) {
            if (!cls.equals(a.classifier)) {
                continue;
            }
            if (isCancelled(token)) {
                return RepairResult.CANCELLED;
            }
            File jar = new File(versionDir, a.file);
            if (isValidArtifact(jar, a)) {
                continue;   // artifact-granularity: only re-fetch what is wrong
            }
            notifyPhase(progress, RepairPhase.DOWNLOADING, a.file);
            if (!downloadAndInstall(a, versionDir, spec, progress, token)) {
                return isCancelled(token) ? RepairResult.CANCELLED
                        : RepairResult.DOWNLOAD_FAILED;
            }
        }

        // Commit the install transaction (2A.1): runtime.json is written first,
        // then `.installed` LAST as the commit marker — so `.installed` means
        // "this install transaction fully committed". If the final whole-runtime
        // verification is not READY the commit is revoked (marker + metadata
        // removed) and CORRUPTED is returned: a broken install must never leave a
        // `.installed` that a later verifyLocal() could mistake for READY.
        if (isCancelled(token)) {
            return RepairResult.CANCELLED;
        }
        notifyPhase(progress, RepairPhase.COMMITTING, null);
        writeMetadata(runtimeDir, spec, cls);
        if (finalVerifyResult() != RuntimeStatus.READY) {
            revokeInstall(runtimeDir);
            return RepairResult.CORRUPTED;
        }
        return RepairResult.REPAIRED;
    }

    /** True when the on-disk jar matches the spec's size and SHA-256. */
    private static boolean isValidArtifact(File jar, Artifact a) {
        if (!jar.isFile()) {
            return false;
        }
        if (a.size > 0 && jar.length() != a.size) {
            return false;
        }
        String hash = sha256(jar);
        return hash != null && hash.equalsIgnoreCase(a.hash);
    }

    /** Download one artifact to a unique temp file, verify, then atomically
     *  replace the formal jar. Returns false (DOWNLOAD_FAILED / CANCELLED) on any
     *  network/HTTP/size/hash problem or on cancellation; throws IOException
     *  (IO_ERROR) on a local filesystem problem. The temp file is removed on
     *  every failure, so a cancelled download never leaves a partial temp file. */
    private static boolean downloadAndInstall(Artifact a, File versionDir, JavaFxSpec spec,
                                              RepairProgress progress, CancellationToken token)
            throws IOException {
        File temp = new File(versionDir, ".mc-update-runtime-" + UUID.randomUUID() + ".tmp");
        // Create the temp before any network I/O: a permission / read-only / disk
        // failure here surfaces as a local IOException → IO_ERROR, not a download
        // failure. UUID keeps concurrent repairs from ever sharing a filename.
        Files.createFile(temp.toPath());
        boolean installed = false;
        try {
            URL url = repository.artifactUrl(spec.version, a);
            if (!downloadTo(url, temp, a, progress, token)) {
                return false;
            }
            notifyPhase(progress, RepairPhase.VERIFYING, a.file);
            if (isCancelled(token)) {
                return false;
            }
            moveReplacing(temp, new File(versionDir, a.file));
            installed = true;
            notifyPhase(progress, RepairPhase.INSTALLING, a.file);
            return true;
        } finally {
            if (!installed) {
                Files.deleteIfExists(temp.toPath());
            }
        }
    }

    /** Stream one artifact to {@code temp}, verifying Content-Length (early),
     *  final byte count and the spec's fixed SHA-256 (authoritative). Returns
     *  false on any download-side failure (network / HTTP / size / hash /
     *  cancellation); throws IOException on a local filesystem failure (temp
     *  write). Never writes the formal jar. */
    private static boolean downloadTo(URL url, File temp, Artifact a,
                                      RepairProgress progress, CancellationToken token)
            throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) url.openConnection();
            if (token != null) {
                token.setCurrentConnection(conn);   // lets cancel() unblock a read
            }
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            int code;
            try {
                code = conn.getResponseCode();
            } catch (IOException e) {
                if (isCancelled(token)) {
                    logDownloadFailure(url, "cancelled");
                    return false;
                }
                // Connection refused / DNS / unreachable before any response.
                logDownloadFailure(url, "cannot reach (" + e + ")");
                return false;
            }
            if (code < 200 || code >= 300) {
                logDownloadFailure(url, "HTTP " + code);
                return false;
            }
            // Early size check: Content-Length, when the server sends one, must
            // match the spec. The final byte count + SHA-256 remain authoritative.
            long contentLength = conn.getContentLengthLong();
            if (contentLength >= 0 && contentLength != a.size) {
                logDownloadFailure(url, "Content-Length " + contentLength
                        + " != spec " + a.size);
                return false;
            }
            MessageDigest md;
            try {
                md = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                logDownloadFailure(url, "SHA-256 unavailable");
                return false;
            }
            long total = 0;
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(temp)) {
                if (token != null) {
                    token.setCurrentStream(in);   // lets cancel() unblock a blocked read
                }
                byte[] buf = new byte[8192];
                while (true) {
                    if (isCancelled(token)) {
                        logDownloadFailure(url, "cancelled");
                        return false;
                    }
                    int n;
                    try {
                        n = in.read(buf);
                    } catch (IOException e) {
                        if (isCancelled(token)) {
                            logDownloadFailure(url, "cancelled");
                            return false;
                        }
                        // Network-side interruption (drop / premature EOF) is a
                        // download failure; the caller cleans up the temp file.
                        logDownloadFailure(url, "interrupted (" + e + ")");
                        return false;
                    }
                    if (n == -1) {
                        break;
                    }
                    try {
                        out.write(buf, 0, n);
                    } catch (IOException e) {
                        // Writing to the local temp failed (disk full / permission)
                        // — a filesystem problem → IO_ERROR, not DOWNLOAD_FAILED.
                        throw new LocalWriteException(
                                "local write failed for " + temp + ": " + e, e);
                    }
                    md.update(buf, 0, n);
                    total += n;
                    if (progress != null) {
                        progress.onBytes(a.file, total, a.size);
                    }
                }
            }
            if (total != a.size) {
                logDownloadFailure(url, "size " + total + " != spec " + a.size);
                return false;
            }
            String hash = hex(md.digest());
            if (!hash.equalsIgnoreCase(a.hash)) {
                logDownloadFailure(url, "SHA-256 mismatch");
                return false;
            }
            return true;
        } catch (LocalWriteException e) {
            throw e;   // filesystem problem → IO_ERROR
        } catch (IOException e) {
            if (isCancelled(token)) {
                logDownloadFailure(url, "cancelled");
                return false;
            }
            logDownloadFailure(url, "download failed (" + e + ")");
            return false;
        } finally {
            if (token != null) {
                token.setCurrentConnection(null);
                token.setCurrentStream(null);
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static void logDownloadFailure(URL url, String reason) {
        System.err.println("[javafx] Download failed: " + reason + " — " + url);
    }

    /** Write {@code runtime.json} + {@code .installed} atomically (temp + replace)
     *  so a partial/aborted write can never leave a half-installed marker. The
     *  order matters (2A.1): {@code runtime.json} first, then {@code .installed}
     *  last — the latter is the transaction commit marker. */
    private static void writeMetadata(File runtimeDir, JavaFxSpec spec, String classifier)
            throws IOException {
        String json = "{\"version\":\"" + spec.version + "\",\"classifier\":\"" + classifier + "\"}";
        writeAtomically(new File(runtimeDir, "runtime.json"),
                json.getBytes(StandardCharsets.UTF_8));
        writeAtomically(new File(runtimeDir, ".installed"), new byte[0]);
    }

    /** Final whole-runtime verification after the commit (test-overridable). */
    private static RuntimeStatus finalVerifyResult() {
        RuntimeStatus override = finalVerifyOverride;
        return override != null ? override : verifyLocal();
    }

    /** Revoke an un-committed install: remove the commit marker and its metadata
     *  so a failed install can never be mistaken for READY on the next launch. */
    private static void revokeInstall(File runtimeDir) {
        try {
            Files.deleteIfExists(new File(runtimeDir, ".installed").toPath());
        } catch (IOException ignored) {
            // Best effort — verifyLocal() re-checks the on-disk state anyway.
        }
        try {
            Files.deleteIfExists(new File(runtimeDir, "runtime.json").toPath());
        } catch (IOException ignored) {
        }
    }

    private static void writeAtomically(File target, byte[] content) throws IOException {
        File temp = new File(target.getParentFile(),
                target.getName() + "." + UUID.randomUUID() + ".tmp");
        boolean written = false;
        try {
            Files.write(temp.toPath(), content);
            moveReplacing(temp, target);
            written = true;
        } finally {
            if (!written) {
                Files.deleteIfExists(temp.toPath());
            }
        }
    }

    private static void ensureDirectory(File dir) throws IOException {
        if (dir.isDirectory()) {
            return;
        }
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Unable to create directory: " + dir);
        }
    }

    private static void moveReplacing(File source, File target) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Files.move(source.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException e) {
                // Transient contention when another instance replaces the same jar
                // concurrently (Phase 2A req 10) — retry before giving up.
                last = e;
                try {
                    Thread.sleep(50L * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while replacing " + target, ie);
                }
            }
        }
        throw last != null ? last
                : new IOException("Unable to replace " + target);
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

    /** Major version of the current JVM (21.0.11 → 21, 1.8.0_121 → 8). */
    private static int currentJavaMajorVersion() {
        int override = jdkMajorOverride;
        if (override > 0) {
            return override;
        }
        try {
            return Runtime.version().feature();
        } catch (Throwable t) {
            // Pre-Java-9 JVM has no Runtime.Version — conservatively below min_jdk.
            return 8;
        }
    }

    static String sha256(File file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            try (FileInputStream in = new FileInputStream(file)) {
                while ((n = in.read(buf)) != -1) {
                    md.update(buf, 0, n);
                }
            }
            return hex(md.digest());
        } catch (Exception e) {
            return null;
        }
    }

    private static String hex(byte[] digest) {
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
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

    /** Marker for a filesystem-side write failure during download (→ IO_ERROR). */
    private static final class LocalWriteException extends IOException {
        LocalWriteException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
