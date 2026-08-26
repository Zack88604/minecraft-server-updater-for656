package com.zack88604.autoupdater.gui.javafx;

import com.zack88604.autoupdater.gui.api.GuiAdapterContext;
import com.zack88604.autoupdater.gui.api.UpdateViewActions;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shell around the separate JavaFX helper JVM.
 *
 * <p>The helper is a child process launched with the JavaFX runtime on its module
 * path but never touching the Minecraft JVM's classpath — the Minecraft JVM never
 * loads {@code javafx.*} (第一阶段 acceptance criterion). This class owns the
 * process: launching it, the three pipe threads (stdout protocol, stderr debug
 * drain, stdin writer), the mandatory ready handshake watchdog and the escalation
 * to the Swing fallback when the helper dies or stalls.</p>
 *
 * <p>The agent → helper channel is a {@link TransportMailbox} drained by one
 * dedicated writer thread. Sending never blocks: if the helper stalls, pending
 * state coalesces in the single latest-wins slot and is dropped rather than
 * stalling the Minecraft update flow. The {@link RemoteJavaFxUpdateView} also
 * retains the last snapshot, so a fallback rebuilds the window from it.</p>
 */
final class JavaFxHelperProcess {

    private static final String ENTRY_POINT =
            "com.zack88604.autoupdater.gui.javafx.JavaFxEntryPoint";

    private static final long READY_TIMEOUT_SECONDS = 10;
    private static final long EXIT_BACKSTOP_SECONDS = 3;
    private static final long WRITER_IDLE_MS = 30;
    private static final long STALL_SAMPLE_MS = 1000;
    /** Post-ready stall detection: if the helper stops draining stdin the writer
     *  blocks inside flush, so {@code lastWriteNanos} goes quiet while the mailbox
     *  still has pending work. Quiet for this long → destroy and fall back.
     *  Idle-safe: with nothing pending the mailbox is empty, so no false alarm. */
    private static final int STALL_QUIET_SECONDS = 5;

    private final Process process;
    private final TransportMailbox mailbox = new TransportMailbox();
    private final UpdateViewActions actions;
    private final RemoteJavaFxUpdateView view;
    private final CountDownLatch readyLatch = new CountDownLatch(1);
    private final AtomicBoolean intentionalExit = new AtomicBoolean();
    private final AtomicBoolean terminated = new AtomicBoolean();
    private final Thread writerThread;
    private volatile long lastWriteNanos;

    private JavaFxHelperProcess(Process process, RemoteJavaFxUpdateView view) {
        this.process = process;
        this.actions = view.actions();
        this.view = view;
        this.writerThread = new Thread(this::writeLoop, "javafx-helper-writer");
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }

    /**
     * Launch the helper JVM and attach it to the view. On any failure (no usable
     * java, missing core JAR, missing JavaFX runtime, OS process-start failure)
     * the view keeps its null helper and falls back to Swing transparently.
     */
    static void launch(GuiAdapterContext context, RemoteJavaFxUpdateView view) {
        File java = locateJava();
        if (java == null) {
            System.err.println("[javafx] Cannot locate java binary for helper JVM.");
            view.attachHelper(null);
            return;
        }
        File coreJar = coreJar();
        if (coreJar == null || !coreJar.isFile()) {
            System.err.println("[javafx] Missing UpdateAgent_core.jar next to the agent.");
            view.attachHelper(null);
            return;
        }
        File runtime = JavaFxRuntimeManager.runtimeVersionDir();
        if (runtime == null || !runtime.isDirectory()) {
            System.err.println("[javafx] No installed JavaFX runtime (javafx-runtime/<version>).");
            view.attachHelper(null);
            return;
        }

        // Modular OpenJFX jars on --module-path; the helper's own classes (the
        // view + UiStateCodec + UpdateUiState...) come from -cp (the core JAR).
        // javafx.controls pulls in graphics + base transitively.
        List<String> cmd = new ArrayList<>();
        cmd.add(java.getAbsolutePath());
        // Test/debug observability (第一阶段 1.5 验收 §四): when the agent itself
        // runs with -Dzack.helperTrace=true, the helper is asked to echo its
        // FX-thread action order to stderr. Off by default.
        if (Boolean.getBoolean("zack.helperTrace")) {
            cmd.add("-Djavafx.helper.traceActions=true");
        }
        cmd.add("--module-path");
        cmd.add(runtime.getAbsolutePath());
        cmd.add("--add-modules");
        cmd.add("javafx.controls");
        cmd.add("-cp");
        cmd.add(coreJar.getAbsolutePath());
        cmd.add(ENTRY_POINT);

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            Process p = pb.start();
            JavaFxHelperProcess helper = new JavaFxHelperProcess(p, view);
            helper.startReaders();
            helper.startWatchdog();
            view.attachHelper(helper);
            // First message: hand the helper the context so it can build the view.
            // Queued FIFO ahead of any open/state the controller sends later.
            helper.mailbox.postControl(UiStateCodec.encodeInit(
                    context.getGameDirectory(), context.isDebug()));
        } catch (IOException e) {
            System.err.println("[javafx] Helper process failed to start: " + e);
            view.attachHelper(null);
        }
    }

    /** Offer the {@code open} control message. Non-blocking, FIFO. */
    void sendOpen() {
        mailbox.postControl(UiStateCodec.encodeOpen());
    }

    /** Offer a state snapshot to the helper. Non-blocking, latest-wins. */
    void sendState(String json, boolean terminal) {
        mailbox.postState(json, terminal);
    }

    /**
     * Request the helper to close its window and exit. Idempotent and
     * non-blocking: queues {@code close} then {@code exit}; a pending terminal
     * snapshot is protected and flushed first (constraint 3). A 3s backstop
     * force-kills the process if the helper ignores the messages.
     */
    void closeAndExit() {
        if (!terminated.compareAndSet(false, true)) {
            return;
        }
        intentionalExit.set(true);
        mailbox.shutdown(UiStateCodec.encodeClose(), UiStateCodec.encodeExit());
        CompletableFuture.delayedExecutor(EXIT_BACKSTOP_SECONDS, TimeUnit.SECONDS)
                .execute(() -> {
                    if (process.isAlive()) {
                        process.destroy();
                    }
                });
    }

    // ── Pipe threads ────────────────────────────────────────────

    private void startReaders() {
        // stdout carries the helper→agent protocol
        // (ready / windowClosed / closeRequested / beginCloseConfirmation / cancelCloseConfirmation).
        Thread stdout = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    handleProtocolLine(line);
                }
                // EOF on the protocol channel means the helper is gone even if
                // the process somehow hasn't exited — engage the fallback directly
                // rather than waiting for onExit.
                if (!intentionalExit.get()) {
                    System.err.println("[javafx] Helper stdout closed unexpectedly — engaging Swing fallback.");
                    view.engageSwingFallback();
                }
            } catch (IOException e) {
                if (!intentionalExit.get()) {
                    System.err.println("[javafx] Helper stdout read error: " + e
                            + " — engaging Swing fallback.");
                    view.engageSwingFallback();
                }
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
                    System.err.println("[helper] " + line);
                }
            } catch (IOException e) {
                // Routine when the process exits — stderr is a debug drain, not
                // the IPC channel, so this never triggers a fallback.
                System.err.println("[javafx] Helper stderr closed: " + e);
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

    private void handleProtocolLine(String line) {
        String type = UiStateCodec.typeOf(line);
        if ("ready".equals(type)) {
            readyLatch.countDown();
            // The helper is alive and responsive; start watching for a later
            // hang that the ready watchdog can no longer see.
            startStallMonitor();
        } else if ("windowClosed".equals(type)) {
            actions.notifyWindowClosed();
            closeAndExit();
        } else if ("closeRequested".equals(type)) {
            actions.requestClose();
        } else if ("beginCloseConfirmation".equals(type)) {
            // The Quit-update dialog is open on the helper side; pause the update
            // at its next safe checkpoint (UpdateViewActions.beginCloseConfirmation).
            actions.beginCloseConfirmation();
        } else if ("cancelCloseConfirmation".equals(type)) {
            // The user rejected the Quit-update dialog; resume the update.
            actions.cancelCloseConfirmation();
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

    /**
     * Post-ready liveness watchdog. Once the helper has sent {@code ready}, the
     * ready watchdog is spent, so a helper that hangs later (deadlocked FX thread,
     * stopped draining stdin) would freeze the window forever. When the helper
     * stops reading stdin the OS pipe fills and the writer blocks inside flush, so
     * {@code lastWriteNanos} goes quiet while the mailbox still has pending work.
     * If that quiet stretch exceeds {@link #STALL_QUIET_SECONDS}, destroy and fall
     * back. Never fires on idle: with nothing pending the mailbox is empty.
     */
    private void startStallMonitor() {
        Thread t = new Thread(() -> {
            while (!intentionalExit.get() && process.isAlive()) {
                try {
                    Thread.sleep(STALL_SAMPLE_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (intentionalExit.get() || !process.isAlive()) {
                    return;
                }
                long quietSeconds = TimeUnit.NANOSECONDS.toSeconds(
                        System.nanoTime() - lastWriteNanos);
                if (mailbox.hasPending() && quietSeconds >= STALL_QUIET_SECONDS) {
                    System.err.println("[javafx] Helper stalled (no write progress ~"
                            + STALL_QUIET_SECONDS + "s with pending messages) — engaging Swing fallback.");
                    process.destroy();
                    view.engageSwingFallback();
                    return;
                }
            }
        }, "javafx-helper-stall-monitor");
        t.setDaemon(true);
        t.start();
    }

    private void writeLoop() {
        try (BufferedWriter w = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
            while (!mailbox.isClosed() || mailbox.hasPending()) {
                String line = mailbox.pollForWrite();
                if (line == null) {
                    try {
                        Thread.sleep(WRITER_IDLE_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    continue;
                }
                w.write(line);
                w.newLine();
                w.flush();
                lastWriteNanos = System.nanoTime();
            }
            // Draining the writer also closes stdin, giving the helper an EOF —
            // a clean fallback if it ignored the explicit exit message.
        } catch (IOException e) {
            // Broken pipe when the process dies (or the stall monitor destroys a
            // helper stuck on a full pipe) — a failed write is an IPC failure,
            // so log and engage the fallback. Idempotent with onExit.
            if (!intentionalExit.get()) {
                System.err.println("[javafx] Helper write failed: " + e
                        + " — engaging Swing fallback.");
                view.engageSwingFallback();
            }
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
