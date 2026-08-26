package com.zack88604.autoupdater.gui.javafx;

import com.zack88604.autoupdater.application.UpdateEvent;
import com.zack88604.autoupdater.application.UpdateListener;
import com.zack88604.autoupdater.application.UpdatePreflight;
import com.zack88604.autoupdater.gui.api.UpdatePhase;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Preflight that prepares the embedded JavaFX runtime while the current session
 * stays on Swing, showing real progress (2B).
 *
 * <p>This is the runtime-repair entry point for the default Swing path. It runs
 * on the update-worker thread, before {@code UpdateService.run()}, and blocks on
 * a gate that is released by <em>one</em> terminal outcome: the repair finished
 * (READY / REPAIRED / a definite failure) or the 10-second inactivity watchdog
 * fired. Only after release does the worker proceed to the Minecraft update, so
 * a runtime repair and the Minecraft file update never run concurrently.</p>
 *
 * <p>The 10 seconds is a <em>stall</em> timeout, not a total-download budget: as
 * long as the repair keeps reporting real progress (bytes, artifact completion,
 * verification, install, next step) the gate stays held, even past 30 s or 60 s.
 * Only 10 consecutive seconds with no meaningful progress count as a stall.</p>
 *
 * <p>Terminal arbitration: the repair thread and the watchdog thread both try to
 * take terminal ownership with an {@link AtomicBoolean#compareAndSet(boolean, boolean)}
 * on a single {@code active} flag. Only the winner emits the terminal runtime
 * status and releases the gate; the loser (e.g. a repair thread whose download is
 * still unwinding after a stall) only cancels and exits silently. After terminal,
 * the observer drops every late event, so the subsequent
 * CHECKING/DOWNLOADING/CLEANING/SUCCESS/ERROR states own the display.</p>
 */
public final class GuiRuntimePreflight implements UpdatePreflight {

    /** 连续多少秒没有任何真实进展才判定本次 repair stall（非总下载时间上限）。 */
    public static final long RUNTIME_STALL_TIMEOUT_SECONDS = 10;

    private static final long WATCHDOG_POLL_MILLIS = 200;

    private static final String STATUS_PREPARING = "Preparing JavaFX UI runtime...";
    private static final String STATUS_DOWNLOADING = "Downloading JavaFX runtime...";
    private static final String STATUS_VERIFYING = "Verifying JavaFX runtime...";
    private static final String STATUS_INSTALLING = "Installing JavaFX runtime...";
    private static final String STATUS_READY = "JavaFX UI is ready and will be used next launch.";
    private static final String STATUS_UNAVAILABLE =
            "JavaFX UI runtime could not be prepared; continuing with Swing.";
    private static final String STATUS_STALLED =
            "JavaFX UI runtime download stalled; continuing with Swing.";

    /** Injectable wall clock (milliseconds) so tests can advance stall time. */
    interface Clock {
        long now();
    }

    /** Runs one online verify + repair; swappable so tests never touch the network. */
    interface RepairRunner {
        JavaFxRuntimeManager.RepairResult run(
                JavaFxRuntimeManager.RepairProgress progress,
                JavaFxRuntimeManager.CancellationToken token);
    }

    private final Clock clock;
    private final RepairRunner repairRunner;
    private final long stallTimeoutMillis;

    /** Package-private so the test suite can inject a fake clock + fake repair
     *  runner (deterministic stall timing, no network); production uses
     *  {@link #create()}. */
    GuiRuntimePreflight(Clock clock, RepairRunner repairRunner,
                        long stallTimeoutMillis) {
        this.clock = clock;
        this.repairRunner = repairRunner;
        this.stallTimeoutMillis = stallTimeoutMillis;
    }

    /** Default production preflight wired to the real runtime manager. */
    public static GuiRuntimePreflight create() {
        return new GuiRuntimePreflight(System::currentTimeMillis,
                JavaFxRuntimeManager::ensureReady,
                RUNTIME_STALL_TIMEOUT_SECONDS * 1000L);
    }

    /**
     * Run the runtime repair and block until a terminal outcome or a 10 s stall.
     * Invoked on the update-worker thread before {@code UpdateService.run()}.
     * Every event emitted here goes through the listener as a normal
     * {@link UpdateEvent}; nothing touches a GUI toolkit directly.
     */
    @Override
    public void run(UpdateListener listener) {
        // Normal wiring only injects us when the runtime is not READY, but be
        // idempotent: an already-READY runtime needs no preflight at all.
        if (JavaFxRuntimeManager.verifyLocal()
                == JavaFxRuntimeManager.RuntimeStatus.READY) {
            return;
        }

        listener.onUpdateEvent(new UpdateEvent.StatusChanged(
                UpdatePhase.PREPARING, STATUS_PREPARING, null, true));

        AtomicBoolean active = new AtomicBoolean(true);
        CountDownLatch released = new CountDownLatch(1);
        AtomicLong lastProgress = new AtomicLong(clock.now());
        JavaFxRuntimeManager.CancellationToken token =
                new JavaFxRuntimeManager.CancellationToken();

        JavaFxRuntimeManager.RepairProgress observer = new JavaFxRuntimeManager.RepairProgress() {
            private long lastBytesTime;
            private long lastBytes;

            @Override
            public void onBytes(String artifact, long downloadedBytes, long totalBytes) {
                // Real progress refreshes the watchdog regardless of terminal state.
                lastProgress.set(clock.now());
                if (!active.get()) {
                    return;   // late event after terminal → drop
                }
                long now = clock.now();
                double bytesPerSecond = 0;
                if (lastBytesTime > 0 && downloadedBytes > lastBytes) {
                    long elapsed = now - lastBytesTime;
                    if (elapsed > 0) {
                        bytesPerSecond = (downloadedBytes - lastBytes) * 1000.0 / elapsed;
                    }
                }
                lastBytes = downloadedBytes;
                lastBytesTime = now;
                listener.onUpdateEvent(UpdateEvent.DownloadProgressChanged.active(
                        artifact, UpdateEvent.DownloadKind.GUI_RUNTIME,
                        totalBytes, downloadedBytes, bytesPerSecond));
            }

            @Override
            public void onPhase(JavaFxRuntimeManager.RepairPhase phase, String artifact) {
                lastProgress.set(clock.now());
                if (!active.get()) {
                    return;   // late event after terminal → drop
                }
                listener.onUpdateEvent(new UpdateEvent.StatusChanged(
                        UpdatePhase.PREPARING, statusForPhase(phase), artifact, true));
            }
        };

        Thread repair = new Thread(() -> {
            JavaFxRuntimeManager.RepairResult result;
            try {
                result = repairRunner.run(observer, token);
            } catch (Throwable throwable) {
                // The JavaFX runtime is optional GUI infrastructure: an unexpected
                // Throwable must never escalate to an updater error — log it, treat
                // it as "unavailable", release the gate, and continue on Swing.
                throwable.printStackTrace();
                result = null;
            }
            if (active.compareAndSet(true, false)) {
                emitTerminal(listener, result);
                released.countDown();
            }
            // else: someone else already took terminal ownership — exit silently.
        }, "gui-runtime-repair");
        repair.setDaemon(true);
        repair.start();

        Thread watchdog = new Thread(() -> {
            while (released.getCount() > 0) {
                try {
                    Thread.sleep(WATCHDOG_POLL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (clock.now() - lastProgress.get() >= stallTimeoutMillis) {
                    if (active.compareAndSet(true, false)) {
                        listener.onUpdateEvent(new UpdateEvent.StatusChanged(
                                UpdatePhase.PREPARING, STATUS_STALLED, null, true));
                        released.countDown();
                    }
                    // Best-effort cancellation AFTER the gate is released: the
                    // Minecraft update must not wait for the repair thread to exit.
                    token.cancel();
                    return;
                }
            }
        }, "gui-runtime-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        try {
            released.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (active.compareAndSet(true, false)) {
                released.countDown();
            }
            token.cancel();
        }
    }

    /** Emit the terminal runtime status from the repair thread (CAS winner only). */
    private void emitTerminal(UpdateListener listener,
                              JavaFxRuntimeManager.RepairResult result) {
        String status;
        if (result == null) {
            status = STATUS_UNAVAILABLE;
        } else {
            switch (result) {
                case READY:
                case REPAIRED:
                    status = STATUS_READY;
                    break;
                case CANCELLED:
                    // The manager knows only that the repair was cancelled; the
                    // 10 s inactivity watchdog is this class's reason for it.
                    status = STATUS_STALLED;
                    break;
                case UNSUPPORTED:
                case DOWNLOAD_FAILED:
                case IO_ERROR:
                case CORRUPTED:
                case MISSING:
                default:
                    status = STATUS_UNAVAILABLE;
                    break;
            }
        }
        listener.onUpdateEvent(new UpdateEvent.StatusChanged(
                UpdatePhase.PREPARING, status, null, true));
    }

    private static String statusForPhase(JavaFxRuntimeManager.RepairPhase phase) {
        switch (phase) {
            case DOWNLOADING:
                return STATUS_DOWNLOADING;
            case VERIFYING:
                return STATUS_VERIFYING;
            case INSTALLING:
                return STATUS_INSTALLING;
            case COMMITTING:
            default:
                return STATUS_PREPARING;
        }
    }
}
