package com.zack88604.autoupdater.gui.javafx;

import com.zack88604.autoupdater.gui.api.UpdateUiState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Turns display-safe error context into a short list of actionable hints. */
final class ErrorHintResolver {

    private ErrorHintResolver() {
    }

    static List<String> resolve(UpdateUiState state) {
        String context = buildContext(state).toLowerCase(Locale.ROOT);
        List<String> hints = new ArrayList<>(3);

        if (containsAny(context, "timeout", "timed out", "connection", "connect",
                "network", "socket", "reset by peer", "unreachable", "dns",
                "unknown host", "http", "ssl", "certificate")) {
            hints.add("Check your internet connection, then try the update again.");
            hints.add("If you use a proxy, VPN, or firewall, allow the updater to reach the update server.");
        } else if (containsAny(context, "access denied", "permission denied", "not permitted",
                "unauthorized", "read-only", "readonly", "being used", "in use",
                "locked", "another process")) {
            hints.add("Close Minecraft and any launcher or program using files in the game folder.");
            hints.add("Make sure your account can write to the game folder, then run the update again.");
        } else if (containsAny(context, "no space", "disk full", "insufficient space",
                "not enough space", "out of space")) {
            hints.add("Free some disk space on the drive containing the game folder.");
            hints.add("Run the update again after confirming the drive has room for the download.");
        } else if (containsAny(context, "checksum", "hash mismatch", "digest", "corrupt",
                "integrity", "unexpected size")) {
            hints.add("Try the update again; the downloaded file may have been incomplete.");
            hints.add("If it fails again, temporarily disable caching proxies or download accelerators.");
        } else {
            hints.add("Try the update again after closing Minecraft and its launcher.");
            hints.add("Check Details for the failed file or server message.");
        }

        hints.add("If the problem continues, copy the Details log when asking for support.");
        return List.copyOf(hints.subList(0, Math.min(3, hints.size())));
    }

    private static String buildContext(UpdateUiState state) {
        StringBuilder text = new StringBuilder();
        append(text, state.getErrorMessage());
        append(text, state.getStatus());
        append(text, state.getDescription());
        for (String line : state.getLogLines()) {
            append(text, line);
        }
        return text.toString();
    }

    private static void append(StringBuilder target, String value) {
        if (value != null && !value.isBlank()) {
            target.append(' ').append(value);
        }
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
