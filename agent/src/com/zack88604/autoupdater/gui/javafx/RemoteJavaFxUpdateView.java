package com.zack88604.autoupdater.gui.javafx;

import com.zack88604.autoupdater.gui.api.GuiAdapter;
import com.zack88604.autoupdater.gui.api.UpdatePhase;
import com.zack88604.autoupdater.gui.api.UpdateUiState;
import com.zack88604.autoupdater.gui.api.UpdateView;
import com.zack88604.autoupdater.gui.api.UpdateViewActions;

/**
 * Agent-side {@link UpdateView} that renders a remote JavaFX helper JVM over the
 * JSONL transport, and transparently switches to the built-in Swing view when the
 * helper cannot start or dies mid-session (第一阶段 §强制约束 4).
 *
 * <p>This view runs entirely inside the Minecraft JVM. Every {@code open}/{@code render}/
 * {@code close} call is forwarded over the transport to the helper, which owns the
 * actual window; it never touches {@code javafx.*} itself. When the remote channel
 * is unavailable, all Swing work happens on the EDT through the public
 * {@code SwingGuiAdapterFactory → SwingGuiAdapter} creation path — gui.swing's
 * package-private visibility is never widened.</p>
 *
 * <p>Threading: the controller invokes {@code open}/{@code render}/{@code close}
 * from the premain, update-worker and flow-delay threads; the helper process
 * invokes {@link #engageSwingFallback()} from its reader/watchdog/stall threads.
 * All mutable state is guarded by {@code lock} or is volatile, and every Swing
 * call is dispatched onto the EDT.</p>
 */
final class RemoteJavaFxUpdateView implements UpdateView {

    /** The newest log lines forwarded per state snapshot. Display-only tail
     *  (第一阶段 §强制约束 1): the helper never derives precise business counts
     *  from it, and this view does not compute any itself. */
    static final int LOG_TAIL_LIMIT = 200;

    private final UpdateViewActions actions;
    private final GuiAdapter swingFallback;      // public Swing creation path
    private final Object lock = new Object();

    /** The live helper, or null while unattached / after a failed launch. */
    private volatile JavaFxHelperProcess helper;
    private volatile boolean fallbackEngaged;
    private volatile boolean closed;
    private volatile UpdateUiState lastState = UpdateUiState.initial();
    /** Swing view, created lazily on the EDT and confined to it. */
    private UpdateView swingView;

    RemoteJavaFxUpdateView(UpdateViewActions actions, GuiAdapter swingFallback) {
        this.actions = actions;
        this.swingFallback = swingFallback;
    }

    /** Attach the launched helper (or null when the launch failed). */
    void attachHelper(JavaFxHelperProcess helper) {
        this.helper = helper;
    }

    UpdateViewActions actions() {
        return actions;
    }

    @Override
    public void open() {
        JavaFxHelperProcess h = helper;
        if (h != null) {
            h.sendOpen();
            return;
        }
        engageSwingFallback();
    }

    @Override
    public void render(UpdateUiState state) {
        if (closed) {
            return;
        }
        lastState = state;
        JavaFxHelperProcess h = helper;
        if (h != null && !fallbackEngaged) {
            boolean terminal = state.getPhase() == UpdatePhase.SUCCESS
                    || state.getPhase() == UpdatePhase.ERROR;
            h.sendState(UiStateCodec.encodeState(state, LOG_TAIL_LIMIT), terminal);
            return;
        }
        dispatchSwing(() -> swingView.render(state));
    }

    @Override
    public void close() {
        closed = true;
        JavaFxHelperProcess h = helper;
        if (h != null) {
            h.closeAndExit();
            return;
        }
        dispatchSwing(() -> {
            if (swingView != null) {
                swingView.close();
            }
        });
    }

    /**
     * Transparently switch to the Swing view because the helper cannot start,
     * died, or stalled. Thread-safe and idempotent: the first caller wins; the
     * window is rebuilt from the latest snapshot on the EDT.
     */
    void engageSwingFallback() {
        synchronized (lock) {
            if (fallbackEngaged || closed) {
                return;
            }
            fallbackEngaged = true;
        }
        // Ensure the fallback window opens even if no further render arrives,
        // then replay the latest snapshot.
        dispatchSwing(() -> { /* creation + open happen lazily in dispatchSwing */ });
        dispatchSwing(() -> swingView.render(lastState));
    }

    /** Run {@code task} on the Swing EDT, lazily creating + opening the view. */
    private void dispatchSwing(Runnable task) {
        swingFallback.dispatcher().dispatch(() -> {
            if (swingView == null) {
                swingView = swingFallback.create(actions);
                if (!closed) {
                    swingView.open();
                }
            }
            task.run();
        });
    }
}
