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

/**
 * Helper-JVM entry point for the JavaFX view (v3 architecture).
 *
 * This class runs in a separate JVM launched by {@code JavaFxHelperProcess}
 * with the JavaFX runtime on its module path. It never runs inside the
 * Minecraft JVM — the Minecraft JVM never loads {@code javafx.*} (v3 acceptance
 * criterion #1). It speaks the JSONL protocol over stdin/stdout:
 *
 * <ul>
 *   <li>reads {@code init} on the first line, boots the JavaFX toolkit with
 *       {@link Platform#startup}, builds the view and answers {@code ready};</li>
 *   <li>renders every later view event on the JavaFX Application Thread via
 *       {@link Platform#runLater} ({@code open} / {@code close} /
 *       {@code closeEnabled} are control messages, everything else is a decoded
 *       {@link UpdateEvent});</li>
 *   <li>forwards user actions (window close, debug close button) back over the
 *       protocol channel as {@code windowClosed} / {@code closeRequested}.</li>
 * </ul>
 *
 * The agent reads the helper's stdout as the protocol channel, so the real
 * {@code FileDescriptor.out} is captured as {@code protocolOut} <b>before</b>
 * this main redirects {@code System.out} to stderr (v3 acceptance criterion
 * #2); all helper debug output therefore goes to stderr, which the agent
 * drains continuously as a log.
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
        boolean fxStarted = false;

        BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            String type = EventCodec.typeOf(line);
            if ("exit".equals(type)) {
                break;
            }
            if ("init".equals(type)) {
                fxStarted = true;
                UiModel model = new UiModel(
                        EventCodec.stringOf(line, "gameDir"),
                        EventCodec.boolOf(line, "debug"));
                Platform.startup(() -> {
                    JavaFxUpdateView v = new JavaFxUpdateView(
                            new RemoteListener(protocolOut), model);
                    synchronized (lock) {
                        viewRef.value = v;
                        String buffered;
                        while ((buffered = bufferedLines.poll()) != null) {
                            Platform.runLater(renderAction(v, buffered));
                        }
                    }
                    protocolOut.println(EventCodec.encodeReady());
                });
                continue;
            }
            synchronized (lock) {
                if (viewRef.value != null) {
                    Platform.runLater(renderAction(viewRef.value, line));
                } else {
                    bufferedLines.add(line);
                }
            }
        }
        if (fxStarted) {
            try {
                Platform.exit();
            } catch (IllegalStateException ignored) {
                // toolkit was never fully started; the process ends on main exit
            }
        }
    }

    /** Translate one agent line into an FX-thread render action. */
    private static Runnable renderAction(JavaFxUpdateView view, String line) {
        String type = EventCodec.typeOf(line);
        switch (type) {
            case "open":
                return view::open;
            case "close":
                return view::close;
            case "closeEnabled":
                return () -> view.setCloseEnabled(EventCodec.boolOf(line, "enabled"));
            default: {
                UpdateEvent event = EventCodec.decodeViewEvent(line);
                return event == null ? () -> { } : () -> ViewApplier.apply(view, event);
            }
        }
    }

    /** Mutable single-slot holder so a lambda can publish the view. */
    private static final class Holder<T> {
        T value;
    }

    /** Helper-side view listener: forwards user actions over the protocol. */
    private static final class RemoteListener implements UpdateViewListener {
        private final PrintWriter protocolOut;

        RemoteListener(PrintWriter protocolOut) {
            this.protocolOut = protocolOut;
        }

        @Override
        public void onWindowClosed() {
            protocolOut.println(EventCodec.encodeWindowClosed());
        }

        @Override
        public void onCloseRequested() {
            protocolOut.println(EventCodec.encodeCloseRequested());
        }
    }
}
