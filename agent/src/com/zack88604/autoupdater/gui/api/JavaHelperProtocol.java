package com.zack88604.autoupdater.gui.api;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Versioned line protocol shared by the updater and an isolated Java helper.
 *
 * <p>This class is public so a preset can compile its helper against the core
 * JAR, but normal preset code should use {@link JavaHelperSession} rather than
 * writing protocol lines itself.</p>
 */
public final class JavaHelperProtocol {

    /** Current wire version. It is encoded inside each rendered snapshot. */
    public static final int VERSION = 1;

    /** Messages that a helper may send to the updater. */
    public enum HelperAction {
        READY,
        BEGIN_CLOSE_CONFIRMATION,
        CANCEL_CLOSE_CONFIRMATION,
        REQUEST_CLOSE,
        WINDOW_CLOSED
    }

    private static final String INIT = "INIT";
    private static final String OPEN = "OPEN";
    private static final String RENDER = "RENDER";
    private static final String CLOSE = "CLOSE";
    private static final int MAX_STRING_BYTES = 4 * 1024 * 1024;
    private static final int MAX_LIST_SIZE = 10_000;

    private JavaHelperProtocol() {
    }

    /** Encode the first core-to-helper message. */
    public static String encodeInit(GuiAdapterContext context) {
        return INIT + "\t" + encodeText(context.getGameDirectory()) + "\t"
                + encodeText(context.getUpdaterConfigurationDirectory()) + "\t"
                + context.isDebug();
    }

    /** Decode the first core-to-helper message. */
    public static GuiAdapterContext decodeInit(String line) throws IOException {
        String[] parts = split(line);
        if (parts.length != 4 || !INIT.equals(parts[0])) {
            throw new IOException("Expected helper INIT message");
        }
        if (!"true".equals(parts[3]) && !"false".equals(parts[3])) {
            throw new IOException("Invalid helper debug flag");
        }
        return new GuiAdapterContext(decodeText(parts[1]), decodeText(parts[2]),
                Boolean.parseBoolean(parts[3]));
    }

    /** Encode a request to show the helper window. */
    public static String encodeOpen() {
        return OPEN;
    }

    /** Encode a complete immutable UI snapshot. */
    public static String encodeRender(UpdateUiState state) throws IOException {
        return RENDER + "\t" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(encodeState(state));
    }

    /** Encode a request to close and terminate the helper window. */
    public static String encodeClose() {
        return CLOSE;
    }

    /** Decode a command delivered to a helper. */
    public static JavaHelperCommand decodeCommand(String line) throws IOException {
        String[] parts = split(line);
        if (parts.length == 1 && OPEN.equals(parts[0])) {
            return new JavaHelperCommand(JavaHelperCommand.Type.OPEN, null);
        }
        if (parts.length == 1 && CLOSE.equals(parts[0])) {
            return new JavaHelperCommand(JavaHelperCommand.Type.CLOSE, null);
        }
        if (parts.length == 2 && RENDER.equals(parts[0])) {
            try {
                byte[] bytes = Base64.getUrlDecoder().decode(parts[1]);
                return new JavaHelperCommand(JavaHelperCommand.Type.RENDER, decodeState(bytes));
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid helper render payload", exception);
            }
        }
        throw new IOException("Unknown helper command");
    }

    /** Encode a helper action sent back to the updater. */
    public static String encodeAction(HelperAction action) {
        return action.name();
    }

    /** Decode a helper action, or return {@code null} for an unknown line. */
    public static HelperAction decodeAction(String line) {
        try {
            return HelperAction.valueOf(line);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static byte[] encodeState(UpdateUiState state) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(VERSION);
            writeText(output, state.getPhase().name());
            writeText(output, state.getStatus());
            writeText(output, state.getDescription());
            writeTextList(output, state.getLogLines());
            writeTextList(output, state.getServerUrls());
            writeNullableText(output, state.getCurrentServer());
            output.writeInt(state.getOverallProgressPercent());
            output.writeBoolean(state.isOverallProgressIndeterminate());

            DownloadProgress progress = state.getDownloadProgress();
            output.writeBoolean(progress.isActive());
            if (progress.isActive()) {
                writeText(output, progress.getPath());
                writeText(output, progress.getKind().name());
                output.writeLong(progress.getDownloadedBytes());
                output.writeLong(progress.getTotalBytes());
                output.writeDouble(progress.getBytesPerSecond());
            }

            writeText(output, state.getClosePolicy().name());
            UpdateSummary summary = state.getSummary();
            output.writeBoolean(summary != null);
            if (summary != null) {
                output.writeInt(summary.getUpdatedFiles());
                output.writeInt(summary.getFailedFiles());
            }
            writeNullableText(output, state.getErrorMessage());
        }
        return bytes.toByteArray();
    }

    private static UpdateUiState decodeState(byte[] bytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int version = input.readInt();
            if (version != VERSION) {
                throw new IOException("Unsupported helper protocol version: " + version);
            }

            UpdateUiState.Builder builder = UpdateUiState.builder()
                    .phase(UpdatePhase.valueOf(readText(input)))
                    .status(readText(input))
                    .description(readText(input))
                    .logLines(readTextList(input))
                    .serverUrls(readTextList(input))
                    .currentServer(readNullableText(input))
                    .overallProgressPercent(input.readInt())
                    .overallProgressIndeterminate(input.readBoolean());

            boolean downloadActive = input.readBoolean();
            if (downloadActive) {
                String path = readText(input);
                DownloadProgress.Kind kind = DownloadProgress.Kind.valueOf(readText(input));
                long downloaded = input.readLong();
                long total = input.readLong();
                double speed = input.readDouble();
                builder.downloadProgress(DownloadProgress.active(path, kind, downloaded, total, speed));
            } else {
                builder.downloadProgress(DownloadProgress.inactive());
            }

            builder.closePolicy(ClosePolicy.valueOf(readText(input)));
            if (input.readBoolean()) {
                builder.summary(new UpdateSummary(input.readInt(), input.readInt()));
            }
            builder.errorMessage(readNullableText(input));
            if (input.available() != 0) {
                throw new IOException("Unexpected trailing helper state data");
            }
            return builder.build();
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid helper state data", exception);
        }
    }

    private static void writeTextList(DataOutputStream output, List<String> values) throws IOException {
        output.writeInt(values.size());
        for (String value : values) {
            writeText(output, value);
        }
    }

    private static List<String> readTextList(DataInputStream input) throws IOException {
        int size = input.readInt();
        if (size < 0 || size > MAX_LIST_SIZE) {
            throw new IOException("Invalid helper list size");
        }
        List<String> values = new ArrayList<String>(size);
        for (int index = 0; index < size; index++) {
            values.add(readText(input));
        }
        return values;
    }

    private static void writeNullableText(DataOutputStream output, String value) throws IOException {
        output.writeBoolean(value != null);
        if (value != null) {
            writeText(output, value);
        }
    }

    private static String readNullableText(DataInputStream input) throws IOException {
        return input.readBoolean() ? readText(input) : null;
    }

    private static void writeText(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readText(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("Invalid helper string length");
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String[] split(String line) {
        return line.split("\\t", -1);
    }

    private static String encodeText(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeText(String value) throws IOException {
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid helper text field", exception);
        }
    }
}
