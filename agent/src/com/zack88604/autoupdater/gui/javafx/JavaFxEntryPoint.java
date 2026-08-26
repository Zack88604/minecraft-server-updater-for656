package com.zack88604.autoupdater.gui.javafx;

import com.zack88604.autoupdater.gui.api.UpdateUiState;
import javafx.application.Platform;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Helper-JVM entry point for the JavaFX view.
 *
 * <p>This class runs in a separate JVM launched by {@code JavaFxHelperProcess}
 * with the JavaFX runtime on its module path, so the Minecraft JVM never loads
 * {@code javafx.*} (第一阶段 acceptance criterion). It speaks the JSONL protocol
 * over stdin/stdout:</p>
 *
 * <ul>
 *   <li>reads {@code init} (gameDir, debug) on the first line, boots the JavaFX
 *       toolkit with {@link Platform#startup}, builds the {@link JavaFxUpdateView}
 *       and answers {@code ready};</li>
 *   <li>renders every later line on the JavaFX Application Thread via
 *       {@link Platform#runLater}: {@code open} / {@code close} are control
 *       messages, {@code state} is a decoded snapshot;</li>
 *   <li>forwards user actions (window close, debug close button, close
 *       confirmation) back over the protocol channel as {@code windowClosed} /
 *       {@code closeRequested} / {@code beginCloseConfirmation} /
 *       {@code cancelCloseConfirmation}.</li>
 * </ul>
 *
 * <p>The agent reads the helper's stdout as the protocol channel, so the real
 * {@code FileDescriptor.out} is captured as {@code protocolOut} <b>before</b> this
 * main redirects {@code System.out} to stderr; all helper debug output therefore
 * goes to stderr, which the agent drains continuously as a log.</p>
 */
public final class JavaFxEntryPoint {

    private JavaFxEntryPoint() {}

    public static void main(String[] args) throws Exception {
        // Capture the real stdout BEFORE redirecting, so the protocol channel is
        // independent of any helper debug output.
        PrintWriter protocolOut = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(FileDescriptor.out), StandardCharsets.UTF_8), true);
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err),
                true, StandardCharsets.UTF_8));

        final Object lock = new Object();
        final Holder<JavaFxUpdateView> viewRef = new Holder<>();
        final Queue<String> bufferedLines = new ArrayDeque<>();
        // 阶段1.5 验收 §四: count protocol lines read but not yet rendered on the FX
        // thread (including lines buffered until the view exists), so the exit path
        // can wait for the terminal state to actually render before the JVM dies.
        final AtomicInteger pendingFx = new AtomicInteger();
        boolean fxStarted = false;
        // Test/debug observability (第一阶段 1.5 验收 §四): when the agent launches
        // the helper with -Djavafx.helper.traceActions=true, every action executed
        // on the JavaFX Application Thread is echoed to stderr so the acceptance
        // suite can assert render-before-close ordering on the real FX thread.
        // Off by default; production behavior is unchanged.
        boolean trace = Boolean.getBoolean("javafx.helper.traceActions");

        BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            String type = UiStateCodec.typeOf(line);
            if ("exit".equals(type)) {
                break;
            }
            if ("init".equals(type)) {
                fxStarted = true;
                String gameDir = UiStateCodec.stringOf(line, "gameDir");
                boolean debug = UiStateCodec.boolOf(line, "debug");
                Platform.startup(() -> {
                    // View construction + ready are delegated to the FX thread so
                    // every UI object is created there. Lines that arrived before
                    // the view existed are replayed in order from the buffer.
                    JavaFxUpdateView v = new JavaFxUpdateView(
                            new RemoteListener(protocolOut), debug, gameDir);
                    synchronized (lock) {
                        viewRef.value = v;
                        String buffered;
                        while ((buffered = bufferedLines.poll()) != null) {
                            // Already counted as pending at read time — only
                            // count the execution here.
                            Platform.runLater(runAndCountDown(v, buffered, trace, pendingFx));
                        }
                    }
                    protocolOut.println(UiStateCodec.encodeReady());
                });
                continue;
            }
            synchronized (lock) {
                if (viewRef.value != null) {
                    pendingFx.incrementAndGet();
                    Platform.runLater(runAndCountDown(viewRef.value, line, trace, pendingFx));
                } else {
                    // Count at read time so the exit drain also covers lines still
                    // waiting for the view to be built.
                    pendingFx.incrementAndGet();
                    bufferedLines.add(line);
                }
            }
        }
        if (fxStarted) {
            // 阶段1.5 验收 §四: the terminal SUCCESS/ERROR must actually render on
            // the FX thread before the helper exits. The stdin reader can outrun
            // the FX thread (lines queued via runLater, not yet executed) and break
            // on "exit" — so wait for every pending render/close action to run first.
            // Bounded so a wedged FX thread cannot hang the helper forever (the agent
            // force-destroys it via its exit backstop anyway). This is an
            // execution-order fix, not an ACK protocol: the agent's TransportMailbox
            // already orders terminal-state before close/exit.
            long deadline = System.currentTimeMillis() + 5000;
            while (pendingFx.get() > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(5);
            }
            try {
                Platform.exit();
            } catch (IllegalStateException ignored) {
                // toolkit was never fully started; the process ends on main exit
            }
        }
    }

    /** Run one action on the FX thread and count down its pending slot. */
    private static Runnable runAndCountDown(JavaFxUpdateView view, String line, boolean trace,
                                            AtomicInteger pendingFx) {
        return () -> {
            try {
                renderAction(view, line, trace).run();
            } finally {
                pendingFx.decrementAndGet();
            }
        };
    }

    /**
     * Translate one agent line into an FX-thread render action. The trace echo
     * (when enabled) runs inside the returned runnable, so it reports the order in
     * which actions actually execute on the JavaFX Application Thread.
     */
    private static Runnable renderAction(JavaFxUpdateView view, String line, boolean trace) {
        String type = UiStateCodec.typeOf(line);
        switch (type) {
            case "open":
                return () -> {
                    if (trace) {
                        System.err.println("[trace] open");
                    }
                    view.open();
                };
            case "close":
                return () -> {
                    if (trace) {
                        System.err.println("[trace] close");
                    }
                    view.close();
                };
            case "state":
                // Render-ready decode: the codec merges logTotal/logOmitted into a
                // display log with the omission marker prepended (constraint 2).
                return () -> {
                    UpdateUiState state = UiStateCodec.decodeStateWithDisplayLog(line);
                    if (trace) {
                        System.err.println("[trace] state:" + state.getPhase().name());
                    }
                    view.render(state);
                };
            default:
                return () -> { };
        }
    }

    /** Mutable single-slot holder so a lambda can publish the view. */
    private static final class Holder<T> {
        T value;
    }

    /** Helper-side view listener: forwards user actions over the protocol. */
    private static final class RemoteListener implements JavaFxViewListener {
        private final PrintWriter protocolOut;

        RemoteListener(PrintWriter protocolOut) {
            this.protocolOut = protocolOut;
        }

        @Override
        public void userRequestedClose() {
            protocolOut.println(UiStateCodec.encodeCloseRequested());
        }

        @Override
        public void windowClosed() {
            protocolOut.println(UiStateCodec.encodeWindowClosed());
        }

        @Override
        public void beginCloseConfirmation() {
            protocolOut.println(UiStateCodec.encodeBeginCloseConfirmation());
        }

        @Override
        public void cancelCloseConfirmation() {
            protocolOut.println(UiStateCodec.encodeCancelCloseConfirmation());
        }
    }
}
