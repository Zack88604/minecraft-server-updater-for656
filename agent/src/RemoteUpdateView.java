import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Agent-side {@link UpdateView} that renders through the remote JavaFX helper.
 *
 * Every render call is encoded by {@link EventCodec} into one JSONL line and
 * offered to the helper's bounded outbox, and simultaneously folded into a
 * {@link UiSnapshot}. That snapshot is what lets us rebuild an equivalent Swing
 * window from scratch if the helper dies — full current state plus a bounded
 * log tail, not a 64-event replay (v3 acceptance criterion #6).
 *
 * <p>The view also owns the atomic fallback switch (acceptance criterion #7).
 * {@link #engageSwingFallback()} freezes the snapshot and flips {@code dead}
 * under one lock, then on the EDT builds a fresh {@link UpdateGUI}, swaps the
 * controller's view and dispatcher and replays the frozen snapshot + any
 * in-flight calls that were queued in the meantime. A stale render call that
 * was already past the controller's dispatcher when the swap happened is
 * forwarded to the new Swing target instead of being lost. No event between the
 * freeze and the install is dropped: it lands in {@code pending}.
 */
final class RemoteUpdateView implements UpdateView, UpdateViewListener {

    private final UpdateController controller;
    private final UiModel model;
    private final UiSnapshot snapshot;

    /** JSONL sink — set to {@code helper::send} after the helper is launched. */
    private volatile Consumer<String> sender;

    /** The helper process, used to request a clean exit after the view closes
     *  (programmatic {@code close()} never produces a {@code windowClosed}). */
    private volatile JavaFxHelperProcess helper;

    /** The Swing view installed once the fallback engages (EDT access only). */
    private volatile UpdateView swingTarget;

    /** Guards {@code dead} + {@code pending} and serialises against render calls. */
    private final Object lock = new Object();
    private boolean dead;
    private final List<Runnable> pending = new ArrayList<>();

    RemoteUpdateView(UpdateController controller, UiModel model, UiSnapshot snapshot) {
        this.controller = controller;
        this.model = model;
        this.snapshot = snapshot;
    }

    /** Bind the JSONL sink (the helper's outbox). Called once, before start(). */
    void setSender(Consumer<String> sender) {
        this.sender = sender;
    }

    /** Bind the helper process so {@code close()} can request a clean exit. */
    void setHelper(JavaFxHelperProcess helper) {
        this.helper = helper;
    }

    // ── UpdateView ──────────────────────────────────────────────

    @Override
    public void showStatus(UpdatePhase phase, String status, String description, boolean indeterminate) {
        record(() -> snapshot.onStatus(phase, status, description, indeterminate),
               () -> sendJson(EventCodec.encode(
                       new UpdateEvent.StatusChanged(phase, status, description, indeterminate))),
               () -> { UpdateView t = swingTarget; if (t != null) t.showStatus(phase, status, description, indeterminate); });
    }

    @Override
    public void showLog(String message) {
        record(() -> snapshot.onLog(message),
               () -> sendJson(EventCodec.encode(new UpdateEvent.LogMessage(message))),
               () -> { UpdateView t = swingTarget; if (t != null) t.showLog(message); });
    }

    @Override
    public void showOverallProgress(int percent) {
        record(() -> snapshot.onOverallProgress(percent),
               () -> sendJson(EventCodec.encode(new UpdateEvent.OverallProgressChanged(percent))),
               () -> { UpdateView t = swingTarget; if (t != null) t.showOverallProgress(percent); });
    }

    @Override
    public void showDownloadProgress(DownloadProgress progress) {
        record(() -> snapshot.onDownloadProgress(progress),
               () -> sendJson(EventCodec.encode(new UpdateEvent.DownloadProgressChanged(progress))),
               () -> { UpdateView t = swingTarget; if (t != null) t.showDownloadProgress(progress); });
    }

    @Override
    public void showServer(List<String> serverUrls, String currentServer) {
        record(() -> snapshot.onServer(serverUrls, currentServer),
               () -> sendJson(EventCodec.encode(
                       new UpdateEvent.ServerChanged(serverUrls, currentServer))),
               () -> { UpdateView t = swingTarget; if (t != null) t.showServer(serverUrls, currentServer); });
    }

    @Override
    public void showCompleted(UpdateResult result) {
        record(() -> snapshot.onCompleted(result),
               () -> sendJson(EventCodec.encode(new UpdateEvent.Completed(result))),
               () -> { UpdateView t = swingTarget; if (t != null) t.showCompleted(result); });
    }

    @Override
    public void showError(String message, Throwable cause) {
        record(() -> snapshot.onError(message),
               () -> sendJson(EventCodec.encode(new UpdateEvent.Failed(message, cause))),
               () -> { UpdateView t = swingTarget; if (t != null) t.showError(message, cause); });
    }

    @Override
    public void setCloseEnabled(boolean enabled) {
        record(() -> snapshot.onSetCloseEnabled(enabled),
               () -> sendJson(EventCodec.encodeCloseEnabled(enabled)),
               () -> { UpdateView t = swingTarget; if (t != null) t.setCloseEnabled(enabled); });
    }

    @Override
    public void open() {
        record(() -> snapshot.onOpen(),
               () -> sendJson(EventCodec.encodeOpen()),
               () -> { UpdateView t = swingTarget; if (t != null) t.open(); });
    }

    @Override
    public void close() {
        record(() -> snapshot.onClose(),
               () -> sendJson(EventCodec.encodeClose()),
               () -> { UpdateView t = swingTarget; if (t != null) t.close(); });
        // Programmatic close() never produces a helper windowClosed message, so
        // ask the helper to exit explicitly. The exit is marked intentional, so
        // the agent never falls back to Swing for a close it initiated itself.
        JavaFxHelperProcess h = helper;
        if (h != null) {
            h.sendExit();
        }
    }

    // ── UpdateViewListener (user actions from the helper, via protocol) ──

    @Override
    public void onWindowClosed() {
        controller.onWindowClosed();
    }

    @Override
    public void onCloseRequested() {
        controller.onCloseRequested();
    }

    // ── Fallback ────────────────────────────────────────────────

    /**
     * Engage the Swing fallback. Freezes the snapshot and marks the view dead
     * under one lock, then on the EDT builds the Swing window, swaps the
     * controller's view + dispatcher, replays the snapshot and drains any
     * in-flight calls queued since the freeze. Idempotent — subsequent calls
     * are no-ops. If the flow had already finished, nothing is rebuilt.
     */
    void engageSwingFallback() {
        final UiSnapshot frozen;
        final List<Runnable> toDrain;
        synchronized (lock) {
            if (dead) {
                return;
            }
            dead = true;
            frozen = snapshot.copy();
            toDrain = new ArrayList<>(pending);
            pending.clear();
        }
        if (frozen.isClosed()) {
            return; // flow already finished — nothing to rebuild
        }
        SwingUtilities.invokeLater(() -> {
            UpdateGUI swing = new UpdateGUI(controller, model);
            swingTarget = swing;                    // route late renders here first
            controller.attach(swing);               // swap the view
            controller.setDispatcher(new SwingUiDispatcher()); // swap the dispatcher
            frozen.applyTo(swing);                  // full state replay
            for (Runnable r : toDrain) {
                r.run();                            // in-flight renders since the freeze
            }
            swing.open();
        });
    }

    // ── Internals ───────────────────────────────────────────────

    /** Route one render call: live → snapshot + helper; dead → Swing target
     *  (immediately, or buffered until the fallback window is installed). */
    private void record(Runnable snapshotUpdate, Runnable helperSend, Runnable swingRender) {
        synchronized (lock) {
            if (!dead) {
                snapshotUpdate.run();
                helperSend.run();
                return;
            }
            if (swingTarget != null) {
                SwingUtilities.invokeLater(swingRender);
            } else {
                pending.add(swingRender);
            }
        }
    }

    private void sendJson(String json) {
        Consumer<String> s = sender;
        if (s != null) {
            s.accept(json);
        }
    }
}
