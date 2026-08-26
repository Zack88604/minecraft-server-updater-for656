package com.zack88604.autoupdater.gui.javafx;

import com.zack88604.autoupdater.gui.api.ClosePolicy;
import com.zack88604.autoupdater.gui.api.DownloadProgress;
import com.zack88604.autoupdater.gui.api.UpdatePhase;
import com.zack88604.autoupdater.gui.api.UpdateSummary;
import com.zack88604.autoupdater.gui.api.UpdateUiState;
import com.zack88604.autoupdater.infrastructure.json.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hand-written JSONL codec for the agent → JavaFX helper IPC protocol.
 *
 * <p>The agent and the JavaFX helper process exchange one JSON object per line over
 * stdin/stdout. This class encodes {@link UpdateUiState} snapshots and control
 * messages on the agent side, and decodes them on the helper side. It is the only
 * place that knows the wire format, so both sides stay in lock-step.</p>
 *
 * <p>The project is zero-dependency, so the JSON is hand-built (escape on encode,
 * scan-and-unescape on decode). String values may contain quotes, backslashes
 * (Windows paths) and control characters, so a plain regex capture is not used for
 * string fields.</p>
 *
 * <p>Each {@code state} message carries a <b>self-contained log tail</b>
 * (第一阶段 §强制约束 2): {@code logTotal} (source log length), {@code logOmitted}
 * (lines not transmitted) and {@code log} (the latest {@code LOG_TAIL_LIMIT} lines).
 * The helper rebuilds a display log from those three — never pretending the omitted
 * history exists in its memory.</p>
 */
final class UiStateCodec {

    private UiStateCodec() {}

    // ── Agent → helper (control) ───────────────────────────────

    /** First message the agent writes — the helper builds the view from this. */
    static String encodeInit(String gameDir, boolean debug) {
        return "{\"type\":\"init\",\"gameDir\":\"" + esc(gameDir)
                + "\",\"debug\":" + (debug ? "true" : "false") + "}";
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

    // ── Agent → helper (state snapshot) ────────────────────────

    /**
     * Encode a complete {@link UpdateUiState} snapshot with a self-contained log
     * tail: {@code logTotal} / {@code logOmitted} / latest {@code tailLimit} lines.
     * Display-only tail — no precise business counts are derived from it on the
     * helper side (第一阶段 §强制约束 1).
     */
    static String encodeState(UpdateUiState state, int tailLimit) {
        List<String> log = state.getLogLines();
        int logTotal = log.size();
        int tailStart = Math.max(0, logTotal - tailLimit);

        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"type\":\"state\"");
        sb.append(",\"phase\":\"").append(state.getPhase().name()).append('"');
        sb.append(",\"status\":\"").append(esc(state.getStatus())).append('"');
        sb.append(",\"description\":\"").append(esc(state.getDescription())).append('"');
        sb.append(",\"overallPercent\":").append(state.getOverallProgressPercent());
        sb.append(",\"overallIndeterminate\":")
                .append(state.isOverallProgressIndeterminate() ? "true" : "false");
        sb.append(",\"servers\":[");
        List<String> servers = state.getServerUrls();
        for (int i = 0; i < servers.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(esc(servers.get(i))).append('"');
        }
        sb.append(']');
        sb.append(",\"currentServer\":").append(encNullable(state.getCurrentServer()));

        DownloadProgress dl = state.getDownloadProgress();
        String path = dl.getPath() == null ? "" : dl.getPath();
        String kind = dl.getKind() == null ? "" : dl.getKind().name();
        // Speed rounded to whole bytes/sec so the wire format stays a plain integer.
        long speed = Math.round(dl.getBytesPerSecond());
        sb.append(",\"dl\":{\"active\":").append(dl.isActive() ? "true" : "false");
        sb.append(",\"path\":\"").append(esc(path)).append('"');
        sb.append(",\"kind\":\"").append(kind).append('"');
        sb.append(",\"downloaded\":").append(dl.getDownloadedBytes());
        sb.append(",\"total\":").append(dl.getTotalBytes());
        sb.append(",\"speed\":").append(speed).append('}');

        sb.append(",\"closePolicy\":\"").append(state.getClosePolicy().name()).append('"');

        UpdateSummary summary = state.getSummary();
        if (summary == null) {
            sb.append(",\"summary\":null");
        } else {
            sb.append(",\"summary\":{\"updated\":").append(summary.getUpdatedFiles())
              .append(",\"failed\":").append(summary.getFailedFiles()).append('}');
        }
        sb.append(",\"errorMessage\":").append(encNullable(state.getErrorMessage()));

        // Self-contained log tail (constraint 2): total + omitted + newest lines.
        sb.append(",\"logTotal\":").append(logTotal);
        sb.append(",\"logOmitted\":").append(tailStart);
        sb.append(",\"log\":[");
        for (int i = tailStart; i < logTotal; i++) {
            if (i > tailStart) {
                sb.append(',');
            }
            sb.append('"').append(esc(log.get(i))).append('"');
        }
        sb.append("]}");
        return sb.toString();
    }

    /** Encode a nullable string as a JSON string literal or {@code null}. */
    private static String encNullable(String s) {
        return s == null ? "null" : "\"" + esc(s) + "\"";
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

    /** The Quit-update confirmation dialog is about to open; the agent pauses
     *  the update at its next checkpoint while it is shown. */
    static String encodeBeginCloseConfirmation() {
        return "{\"type\":\"beginCloseConfirmation\"}";
    }

    /** The user rejected the Quit-update dialog; the agent resumes the update. */
    static String encodeCancelCloseConfirmation() {
        return "{\"type\":\"cancelCloseConfirmation\"}";
    }

    // ── Decode (helper side) ───────────────────────────────────

    /** The message type of a received line ("init", "open", "state", ...). */
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

    /** Read a string field that may be {@code null} (the literal {@code null}). */
    static String nullableStringOf(String json, String key) {
        String marker = "\"" + key + "\":";
        int idx = json.indexOf(marker);
        if (idx < 0) {
            return null;
        }
        idx += marker.length();
        while (idx < json.length() && (json.charAt(idx) == ' ' || json.charAt(idx) == '\t')) {
            idx++;
        }
        if (json.startsWith("null", idx)) {
            return null;
        }
        return stringOf(json, key);
    }

    /** Read an unquoted boolean field (true/false). */
    static boolean boolOf(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(true|false)").matcher(json);
        return m.find() && "true".equals(m.group(1));
    }

    /** Decode a {@code state} line into a full {@link UpdateUiState} snapshot. */
    static UpdateUiState decodeState(String line) {
        UpdatePhase phase = parsePhase(stringOf(line, "phase"));
        String status = stringOf(line, "status");
        String description = stringOf(line, "description");
        boolean indet = boolOf(line, "overallIndeterminate");
        int percent = clampPercent(JsonParser.getInt(line, "overallPercent", 0));
        List<String> servers = decodeStringArray(line, "servers");
        String currentServer = nullableStringOf(line, "currentServer");
        DownloadProgress dl = decodeDownload(line);
        ClosePolicy closePolicy = parseClosePolicy(stringOf(line, "closePolicy"));
        UpdateSummary summary = decodeSummary(line);
        String errorMessage = nullableStringOf(line, "errorMessage");
        List<String> log = decodeStringArray(line, "log");

        return UpdateUiState.builder()
                .phase(phase)
                .status(status == null ? "" : status)
                .description(description == null ? "" : description)
                .overallProgressPercent(percent)
                .overallProgressIndeterminate(indet)
                .logLines(log)
                .serverUrls(servers)
                .currentServer(currentServer)
                .downloadProgress(dl)
                .closePolicy(closePolicy)
                .summary(summary)
                .errorMessage(errorMessage)
                .build();
    }

    /**
     * Decode a {@code state} line into a render-ready snapshot: the decoded log
     * tail is rebuilt from {@code logTotal}/{@code logOmitted}/{@code log} so an
     * omission marker is prepended when history was not transmitted
     * (第一阶段 §强制约束 2). The view never pretends the untransmitted lines exist.
     */
    static UpdateUiState decodeStateWithDisplayLog(String line) {
        int logTotal = JsonParser.getInt(line, "logTotal", 0);
        int logOmitted = JsonParser.getInt(line, "logOmitted", 0);
        UpdateUiState state = decodeState(line);
        if (logOmitted <= 0) {
            return state;
        }
        return replaceLogLines(state,
                displayLog(decodeStringArray(line, "log"), logTotal, logOmitted));
    }

    private static DownloadProgress decodeDownload(String line) {
        if (!boolOf(line, "active")) {
            return DownloadProgress.inactive();
        }
        String path = stringOf(line, "path");
        long downloaded = JsonParser.getLong(line, "downloaded", 0);
        long total = JsonParser.getLong(line, "total", 0);
        double speed = JsonParser.getLong(line, "speed", 0);
        return DownloadProgress.active(
                path == null ? "" : path,
                parseKind(stringOf(line, "kind")),
                downloaded, total, speed);
    }

    private static UpdateSummary decodeSummary(String line) {
        String obj = JsonParser.getObject(line, "summary");
        if (obj == null) {
            return null;
        }
        return new UpdateSummary(
                JsonParser.getInt(obj, "updated", 0),
                JsonParser.getInt(obj, "failed", 0));
    }

    // ── Log-tail merge (helper side, constraint 2) ─────────────

    /**
     * Build the display log from a truncated tail. When history was not
     * transmitted ({@code logOmitted > 0}) prepend an omission marker — the
     * helper never pretends those old lines exist in its memory.
     */
    static List<String> displayLog(List<String> tail, int logTotal, int logOmitted) {
        List<String> out = new ArrayList<>(tail.size() + 1);
        if (logOmitted > 0) {
            out.add("[... " + logOmitted + " earlier lines not shown ...]");
        }
        out.addAll(tail);
        return out;
    }

    /** Rebuild a state with a different display log (all other fields intact). */
    static UpdateUiState replaceLogLines(UpdateUiState state, List<String> logLines) {
        return UpdateUiState.builder()
                .phase(state.getPhase())
                .status(state.getStatus())
                .description(state.getDescription())
                .logLines(logLines)
                .serverUrls(state.getServerUrls())
                .currentServer(state.getCurrentServer())
                .overallProgressPercent(state.getOverallProgressPercent())
                .overallProgressIndeterminate(state.isOverallProgressIndeterminate())
                .downloadProgress(state.getDownloadProgress())
                .closePolicy(state.getClosePolicy())
                .summary(state.getSummary())
                .errorMessage(state.getErrorMessage())
                .build();
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
            return DownloadProgress.Kind.FILE;
        }
        try {
            return DownloadProgress.Kind.valueOf(name);
        } catch (IllegalArgumentException e) {
            return DownloadProgress.Kind.FILE;
        }
    }

    private static ClosePolicy parseClosePolicy(String name) {
        if (name == null || name.isEmpty()) {
            return ClosePolicy.CONFIRM;
        }
        try {
            return ClosePolicy.valueOf(name);
        } catch (IllegalArgumentException e) {
            return ClosePolicy.CONFIRM;
        }
    }

    private static int clampPercent(int p) {
        return Math.max(0, Math.min(100, p));
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
}
