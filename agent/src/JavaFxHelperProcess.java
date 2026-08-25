import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shell around the separate JavaFX helper JVM.
 *
 * The helper is a child process launched with the JavaFX runtime on its module
 * path but never touching the Minecraft JVM's classpath (v3 acceptance
 * criterion #1 — the Minecraft JVM never loads {@code javafx.*}). This class
 * owns the process: launching it, the three pipe threads (stdout protocol,
 * stderr debug drain, stdin writer), the mandatory ready handshake watchdog and
 * the escalation to the Swing fallback when the helper dies or stalls.
 *
 * <p>The agent → helper channel is a bounded queue drained by one dedicated
 * writer thread. {@link #send} never blocks: if the helper stalls and the
 * queue fills, further lines are dropped rather than stalling the Minecraft
 * update flow (acceptance criterion #5). State is not lost — the
 * {@link RemoteUpdateView} also accumulates a {@link UiSnapshot}, so a fallback
 * rebuilds the window from the frozen snapshot plus whatever in-flight calls
 * were queued in the meantime.
 */
final class JavaFxHelperProcess {

    /** Bound on the outbox so a stuck helper cannot block the update flow. */
    private static final int OUTBOX_CAPACITY = 256;
    private static final long READY_TIMEOUT_SECONDS = 10;
    private static final long EXIT_BACKSTOP_SECONDS = 3;

    private final Process process;
    private final ArrayBlockingQueue<String> outbox;
    private final RemoteUpdateView view;
    private final CountDownLatch readyLatch = new CountDownLatch(1);
    private final AtomicBoolean intentionalExit = new AtomicBoolean();
    private final Thread writerThread;

    private JavaFxHelperProcess(Process process, RemoteUpdateView view) {
        this.process = process;
        this.view = view;
        this.outbox = new ArrayBlockingQueue<>(OUTBOX_CAPACITY);
        this.writerThread = new Thread(this::writeLoop, "javafx-helper-writer");
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }

    /**
     * Launch the helper JVM. Returns {@code null} (and engages nothing) if the
     * process cannot be started at all — no usable java, missing core JAR,
     * missing JavaFX runtime or an OS process-start failure. The caller decides
     * the fallback for that case.
     */
    static JavaFxHelperProcess launch(UpdateController controller, UiModel model,
                                      RemoteUpdateView view, UiSnapshot snapshot) {
        File java = locateJava();
        if (java == null) {
            System.err.println("[javafx] Cannot locate java binary for helper JVM.");
            return null;
        }
        File coreJar = coreJar();
        if (coreJar == null || !coreJar.isFile()) {
            System.err.println("[javafx] Missing core JAR next to the agent.");
            return null;
        }
        File runtime = JavaFxRuntimeManager.runtimeVersionDir();
        if (runtime == null || !runtime.isDirectory()) {
            System.err.println("[javafx] No installed JavaFX runtime (javafx-runtime/<version>).");
            return null;
        }

        // Modular OpenJFX jars on --module-path; the helper's own classes (the
        // view + EventCodec + UpdateEvent...) come from -cp (the core JAR).
        // javafx.controls pulls in graphics + base transitively.
        List<String> cmd = List.of(
                java.getAbsolutePath(),
                "--module-path", runtime.getAbsolutePath(),
                "--add-modules", "javafx.controls",
                "-cp", coreJar.getAbsolutePath(),
                "JavaFxEntryPoint");

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            Process process = pb.start();
            JavaFxHelperProcess helper = new JavaFxHelperProcess(process, view);
            helper.startReaders(controller, snapshot);
            helper.startWatchdog();
            // First message: hand the helper the model so it can build the view.
            // Queued FIFO ahead of any open/events the controller sends later.
            helper.send(EventCodec.encodeInit(model));
            return helper;
        } catch (IOException e) {
            System.err.println("[javafx] Helper process failed to start: " + e);
            return null;
        }
    }

    /** Offer a JSONL line to the helper. Bounded and non-blocking: a stalled
     *  helper silently drops lines rather than blocking the update flow. */
    boolean send(String json) {
        return outbox.offer(json);
    }

    /** Tell the helper to exit and mark the exit as intentional (no fallback).
     *  A 3s backstop force-kills the process if the helper ignores the message. */
    void sendExit() {
        intentionalExit.set(true);
        outbox.offer(EventCodec.encodeExit());
        CompletableFuture.delayedExecutor(EXIT_BACKSTOP_SECONDS, TimeUnit.SECONDS)
                .execute(() -> {
                    if (process.isAlive()) {
                        process.destroy();
                    }
                });
    }

    // ── Pipe threads ────────────────────────────────────────────

    private void startReaders(UpdateController controller, UiSnapshot snapshot) {
        // stdout carries the helper→agent protocol (ready / windowClosed / closeRequested).
        Thread stdout = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    handleProtocolLine(line, controller);
                }
            } catch (IOException ignored) {
                // pipe broken when the process dies
            }
        }, "javafx-helper-stdout");
        stdout.setDaemon(true);
        stdout.start();

        // stderr must be drained continuously or the helper can deadlock on a
        // full pipe buffer; the lines are a debug log, not user-facing events.
        Thread stderr = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    snapshot.onLog("[helper] " + line);
                    System.err.println("[helper] " + line);
                }
            } catch (IOException ignored) {
            }
        }, "javafx-helper-stderr");
        stderr.setDaemon(true);
        stderr.start();

        process.onExit().thenAccept(p -> {
            if (!intentionalExit.get()) {
                System.err.println("[javafx] Helper exited unexpectedly — engaging Swing fallback.");
                view.engageSwingFallback();
            }
        });
    }

    private void handleProtocolLine(String line, UpdateController controller) {
        String type = EventCodec.typeOf(line);
        if ("ready".equals(type)) {
            readyLatch.countDown();
            // The new-version helper confirmed it works — safe to delete the
            // previous version left behind by an upgrade.
            JavaFxRuntimeManager.cleanupOldVersion();
        } else if ("windowClosed".equals(type)) {
            controller.onWindowClosed();
            sendExit();
        } else if ("closeRequested".equals(type)) {
            controller.onCloseRequested();
        }
    }

    private void startWatchdog() {
        Thread t = new Thread(() -> {
            try {
                if (!readyLatch.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    System.err.println("[javafx] No ready handshake within "
                            + READY_TIMEOUT_SECONDS + "s — engaging Swing fallback.");
                    process.destroy();
                    view.engageSwingFallback();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "javafx-helper-watchdog");
        t.setDaemon(true);
        t.start();
    }

    private void writeLoop() {
        try (BufferedWriter w = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
            while (true) {
                String line = outbox.poll(200, TimeUnit.MILLISECONDS);
                if (line == null) {
                    continue;
                }
                w.write(line);
                w.newLine();
                w.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // process gone — nothing left to write
        }
    }

    // ── Locators ────────────────────────────────────────────────

    private static File locateJava() {
        String home = System.getProperty("java.home");
        if (home == null) {
            return null;
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        File exe = os.contains("win")
                ? new File(home, "bin\\java.exe")
                : new File(home, "bin/java");
        return exe.isFile() ? exe : null;
    }

    /** The agent core JAR (contains the helper's view + shared classes). */
    private static File coreJar() {
        File agentDir = JavaFxRuntimeManager.agentDir();
        return agentDir == null ? null : new File(agentDir, "UpdateAgent_core.jar");
    }

    /** Whether a child JVM can be spawned (premain UI decision). */
    static boolean javaAvailable() {
        return locateJava() != null;
    }
}
