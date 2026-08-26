package com.zack88604.autoupdater.gui.api;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * A helper-side session over the updater-owned standard input/output protocol.
 *
 * <p>Do not write ordinary output to {@code System.out} in a helper process;
 * it is reserved for this protocol. Use {@code System.err} for diagnostics.</p>
 */
public final class JavaHelperSession {

    private final BufferedReader input;
    private final PrintWriter output;
    private final GuiAdapterContext context;

    private JavaHelperSession(BufferedReader input, PrintWriter output,
                              GuiAdapterContext context) {
        this.input = input;
        this.output = output;
        this.context = context;
    }

    /** Open the standard input/output session created by the updater. */
    public static JavaHelperSession open() throws IOException {
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in,
                StandardCharsets.UTF_8));
        String init = input.readLine();
        if (init == null) {
            throw new IOException("Updater closed input before helper initialization");
        }
        GuiAdapterContext context = JavaHelperProtocol.decodeInit(init);
        PrintWriter output = new PrintWriter(new OutputStreamWriter(System.out,
                StandardCharsets.UTF_8), true);
        return new JavaHelperSession(input, output, context);
    }

    /** Return presentation-only launch settings supplied by the updater. */
    public GuiAdapterContext getContext() {
        return context;
    }

    /** Signal that the helper has initialized its UI toolkit and can render. */
    public void signalReady() {
        send(JavaHelperProtocol.HelperAction.READY);
    }

    /** Read the next updater command, or {@code null} after the updater exits. */
    public JavaHelperCommand nextCommand() throws IOException {
        String line = input.readLine();
        return line == null ? null : JavaHelperProtocol.decodeCommand(line);
    }

    /** Tell the updater that a close confirmation dialog is about to open. */
    public void beginCloseConfirmation() {
        send(JavaHelperProtocol.HelperAction.BEGIN_CLOSE_CONFIRMATION);
    }

    /** Tell the updater that the close confirmation was rejected or dismissed. */
    public void cancelCloseConfirmation() {
        send(JavaHelperProtocol.HelperAction.CANCEL_CLOSE_CONFIRMATION);
    }

    /** Tell the updater that the user confirmed closing the update window. */
    public void requestClose() {
        send(JavaHelperProtocol.HelperAction.REQUEST_CLOSE);
    }

    /** Tell the updater that the helper's native window is now closed. */
    public void notifyWindowClosed() {
        send(JavaHelperProtocol.HelperAction.WINDOW_CLOSED);
    }

    private synchronized void send(JavaHelperProtocol.HelperAction action) {
        output.println(JavaHelperProtocol.encodeAction(Objects.requireNonNull(action, "action")));
        if (output.checkError()) {
            throw new IllegalStateException("Updater closed helper protocol output");
        }
    }
}
