import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hand-written JSONL codec for the helper-JVM IPC protocol.
 *
 * The agent and the JavaFX helper process exchange one JSON object per line over
 * stdin/stdout. This class encodes {@link UpdateView} calls and control messages
 * on the agent side and decodes them back into {@link UpdateEvent}s on the
 * helper side. It is the only place that knows the wire format, so both sides
 * stay in lock-step.
 *
 * The project is zero-dependency, so the JSON is hand-built (escape on encode,
 * scan-and-unescape on decode). String values may contain quotes, backslashes
 * (Windows paths) and control characters, so a plain regex capture is not used
 * for string fields.
 */
final class EventCodec {

    private EventCodec() {}

    // ── Agent → helper (control) ───────────────────────────────

    /** First message the agent writes — the helper builds the view from this. */
    static String encodeInit(UiModel model) {
        return "{\"type\":\"init\",\"gameDir\":\"" + esc(model.gameDir)
                + "\",\"debug\":" + (model.debug ? "true" : "false") + "}";
    }

    static String encodeOpen() {
        return "{\"type\":\"open\"}";
    }

    static String encodeClose() {
        return "{\"type\":\"close\"}";
    }

    /** Terminate the helper main loop (EOF would do the same; explicit is safer). */
    static String encodeExit() {
        return "{\"type\":\"exit\"}";
    }

    static String encodeCloseEnabled(boolean enabled) {
        return "{\"type\":\"closeEnabled\",\"enabled\":" + (enabled ? "true" : "false") + "}";
    }

    // ── Agent → helper (view rendering) ────────────────────────

    /** Encode a view-rendering {@link UpdateEvent} (status/overall/dl/log/server/completed/failed). */
    static String encode(UpdateEvent event) {
        switch (event.type) {
            case STATUS_CHANGED: {
                UpdateEvent.StatusChanged e = (UpdateEvent.StatusChanged) event;
                return "{\"type\":\"status\",\"phase\":\"" + e.phase.name()
                        + "\",\"status\":\"" + esc(nullToEmpty(e.status))
                        + "\",\"description\":\"" + esc(nullToEmpty(e.description))
                        + "\",\"indeterminate\":" + (e.indeterminate ? "true" : "false") + "}";
            }
            case OVERALL_PROGRESS_CHANGED:
                return "{\"type\":\"overall\",\"percent\":"
                        + ((UpdateEvent.OverallProgressChanged) event).percent + "}";
            case DOWNLOAD_PROGRESS_CHANGED:
                return encodeDownload(((UpdateEvent.DownloadProgressChanged) event).progress);
            case LOG_MESSAGE:
                return "{\"type\":\"log\",\"message\":\""
                        + esc(((UpdateEvent.LogMessage) event).message) + "\"}";
            case SERVER_CHANGED: {
                UpdateEvent.ServerChanged e = (UpdateEvent.ServerChanged) event;
                StringBuilder sb = new StringBuilder("{\"type\":\"server\",\"urls\":[");
                for (int i = 0; i < e.serverUrls.size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append('"').append(esc(e.serverUrls.get(i))).append('"');
                }
                sb.append("],\"current\":\"").append(esc(e.currentServer)).append("\"}");
                return sb.toString();
            }
            case COMPLETED: {
                UpdateResult r = ((UpdateEvent.Completed) event).result;
                return "{\"type\":\"completed\",\"updated\":" + r.updated
                        + ",\"failed\":" + r.failed + "}";
            }
            case FAILED: {
                UpdateEvent.Failed e = (UpdateEvent.Failed) event;
                String msg = e.message == null ? "" : e.message;
                String stack = e.cause == null ? "" : stackTrace(e.cause);
                return "{\"type\":\"failed\",\"message\":\"" + esc(msg)
                        + "\",\"stack\":\"" + esc(stack) + "\"}";
            }
        }
        return null;
    }

    private static String encodeDownload(DownloadProgress p) {
        String path = p.path == null ? "" : p.path;
        String kind = p.kind == null ? "" : p.kind.name();
        // Speed is rounded to whole bytes/sec so the wire format stays a plain
        // integer (no scientific notation that a hand-rolled parser would choke on).
        long speed = Math.round(p.bytesPerSecond);
        return "{\"type\":\"dl\",\"active\":" + (p.active ? "true" : "false")
                + ",\"path\":\"" + esc(path)
                + "\",\"kind\":\"" + kind
                + "\",\"downloaded\":" + p.downloadedBytes
                + ",\"total\":" + p.totalBytes
                + ",\"speed\":" + speed + "}";
    }

    // ── Helper → agent ─────────────────────────────────────────

    static String encodeReady() {
        return "{\"type\":\"ready\"}";
    }

    static String encodeWindowClosed() {
        return "{\"type\":\"windowClosed\"}";
    }

    static String encodeCloseRequested() {
        return "{\"type\":\"closeRequested\"}";
    }

    // ── Decode (helper side) ───────────────────────────────────

    /** The message type of a received line ("init", "open", "status", ...). */
    static String typeOf(String line) {
        return stringOf(line, "type");
    }

    /** Read a string field, honouring JSON escapes. Returns null if absent. */
    static String stringOf(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            return null;
        }
        start += marker.length();
        StringBuilder raw = new StringBuilder();
        int i = start;
        boolean escaped = false;
        for (; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                raw.append(c);
                escaped = false;
            } else if (c == '\\') {
                raw.append(c);
                escaped = true;
            } else if (c == '"') {
                break;
            } else {
                raw.append(c);
            }
        }
        return unescape(raw.toString());
    }

    /** Read an unquoted boolean field (true/false). */
    static boolean boolOf(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(true|false)").matcher(json);
        return m.find() && "true".equals(m.group(1));
    }

    /** Decode a view-rendering line into an {@link UpdateEvent}. Returns null for
     *  control messages (init/open/close/closeEnabled/exit) or malformed lines. */
    static UpdateEvent decodeViewEvent(String line) {
        String type = typeOf(line);
        if ("status".equals(type)) {
            UpdatePhase phase = parsePhase(stringOf(line, "phase"));
            String status = stringOf(line, "status");
            String description = stringOf(line, "description");
            boolean indet = boolOf(line, "indeterminate");
            return new UpdateEvent.StatusChanged(phase, status, description, indet);
        }
        if ("overall".equals(type)) {
            return new UpdateEvent.OverallProgressChanged(JsonParser.getInt(line, "percent", 0));
        }
        if ("dl".equals(type)) {
            return new UpdateEvent.DownloadProgressChanged(decodeDownload(line));
        }
        if ("log".equals(type)) {
            return new UpdateEvent.LogMessage(stringOf(line, "message"));
        }
        if ("server".equals(type)) {
            return new UpdateEvent.ServerChanged(
                    decodeStringArray(line, "urls"), stringOf(line, "current"));
        }
        if ("completed".equals(type)) {
            return new UpdateEvent.Completed(new UpdateResult(
                    JsonParser.getInt(line, "updated", 0),
                    JsonParser.getInt(line, "failed", 0)));
        }
        if ("failed".equals(type)) {
            // The stack is only a debugging aid; the helper renders message only.
            return new UpdateEvent.Failed(stringOf(line, "message"), null);
        }
        return null;
    }

    private static DownloadProgress decodeDownload(String line) {
        if (!boolOf(line, "active")) {
            return DownloadProgress.inactive();
        }
        String path = stringOf(line, "path");
        DownloadProgress.Kind kind = parseKind(stringOf(line, "kind"));
        long downloaded = JsonParser.getLong(line, "downloaded", 0);
        long total = JsonParser.getLong(line, "total", 0);
        double speed = JsonParser.getDouble(line, "speed", 0);
        return DownloadProgress.active(path, kind, downloaded, total, speed);
    }

    /** Parse a JSON array of strings under the key, honouring escapes. */
    private static List<String> decodeStringArray(String json, String key) {
        List<String> out = new ArrayList<>();
        String marker = "\"" + key + "\":[";
        int start = json.indexOf(marker);
        if (start < 0) {
            return out;
        }
        int i = start + marker.length();
        while (i < json.length()) {
            while (i < json.length()
                    && (json.charAt(i) == ' ' || json.charAt(i) == '\t' || json.charAt(i) == ',')) {
                i++;
            }
            if (i >= json.length() || json.charAt(i) == ']') {
                break;
            }
            if (json.charAt(i) != '"') {
                i++;
                continue;
            }
            i++;
            StringBuilder raw = new StringBuilder();
            boolean escaped = false;
            while (i < json.length()) {
                char c = json.charAt(i);
                if (escaped) {
                    raw.append(c);
                    escaped = false;
                } else if (c == '\\') {
                    raw.append(c);
                    escaped = true;
                } else if (c == '"') {
                    break;
                } else {
                    raw.append(c);
                }
                i++;
            }
            i++;
            out.add(unescape(raw.toString()));
        }
        return out;
    }

    private static UpdatePhase parsePhase(String name) {
        if (name == null || name.isEmpty()) {
            return UpdatePhase.PREPARING;
        }
        try {
            return UpdatePhase.valueOf(name);
        } catch (IllegalArgumentException e) {
            return UpdatePhase.PREPARING;
        }
    }

    private static DownloadProgress.Kind parseKind(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        try {
            return DownloadProgress.Kind.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ── String escaping / unescaping ───────────────────────────

    /** Escape a string for a JSON string literal. Never returns null. */
    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /** Unescape a JSON string literal body. */
    private static String unescape(String s) {
        if (s == null || s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (++i >= s.length()) {
                break;
            }
            char e = s.charAt(i);
            switch (e) {
                case '"':  sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case 'n':  sb.append('\n'); break;
                case 'r':  sb.append('\r'); break;
                case 't':  sb.append('\t'); break;
                case 'u':
                    if (i + 4 < s.length()) {
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException ex) {
                            sb.append('u');
                        }
                    } else {
                        sb.append('u');
                    }
                    break;
                default:
                    sb.append(e);
                    break;
            }
        }
        return sb.toString();
    }

    private static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
