import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight JSON parsing helpers (no external dependencies).
 */
final class JsonParser {

    private JsonParser() {}

    static String getString(String json, String key) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** Extract an integer value for a key (unquoted number) */
    static int getInt(String json, String key, int defaultVal) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)");
        Matcher m = p.matcher(json);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); }
            catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }

    /** Extract a long integer value for a key */
    static long getLong(String json, String key, long defaultVal) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)");
        Matcher m = p.matcher(json);
        if (m.find()) {
            try { return Long.parseLong(m.group(1)); }
            catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }

    /** Extract a floating-point value for a key (unquoted number). */
    static double getDouble(String json, String key, double defaultVal) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
        Matcher m = p.matcher(json);
        if (m.find()) {
            try { return Double.parseDouble(m.group(1)); }
            catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }

    /** Extract a JSON object value for a key (e.g. "agent": {...}) */
    static String getObject(String json, String key) {
        int k = json.indexOf("\"" + key + "\"");
        if (k < 0) return null;
        int start = json.indexOf('{', k);
        if (start < 0) return null;
        int depth = 1, i = start + 1;
        while (i < json.length() && depth > 0) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            i++;
        }
        return json.substring(start, i);
    }

    static String getArray(String json, String key) {
        int k = json.indexOf("\"" + key + "\"");
        if (k < 0) return null;
        int start = json.indexOf('[', k);
        if (start < 0) return null;
        int depth = 1, i = start + 1;
        while (i < json.length() && depth > 0) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') depth--;
            i++;
        }
        return json.substring(start + 1, i - 1).trim();
    }

    /** Parse a JSON array of strings (may also contain a bare '*' wildcard). */
    static List<String> parseStringArray(String arrayStr) {
        List<String> list = new ArrayList<>();
        if (arrayStr.isEmpty()) return list;
        Pattern p = Pattern.compile("\"([^\"]*)\"");
        Matcher m = p.matcher(arrayStr);
        while (m.find()) list.add(m.group(1));
        // handle bare '*' wildcard
        if (list.isEmpty() && !arrayStr.isEmpty()) list.add("*");
        return list;
    }
}
