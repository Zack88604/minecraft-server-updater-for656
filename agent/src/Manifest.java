import java.util.ArrayList;
import java.util.List;

/**
 * Parsed update manifest: file entries, managed/excluded paths and optional
 * agent self-update info.
 */
class Manifest {

    final List<FileEntry> files;
    final List<String> managedPaths;
    final List<String> excludedPaths;
    final boolean hasFiles;
    final boolean hasAgent;
    final String agentHash;
    final long agentSize;

    private Manifest(List<FileEntry> files, boolean hasFiles,
                     List<String> managedPaths, List<String> excludedPaths,
                     boolean hasAgent, String agentHash, long agentSize) {
        this.files = files;
        this.hasFiles = hasFiles;
        this.managedPaths = managedPaths;
        this.excludedPaths = excludedPaths;
        this.hasAgent = hasAgent;
        this.agentHash = agentHash;
        this.agentSize = agentSize;
    }

    /** Parse a manifest JSON document. Never throws on malformed input. */
    static Manifest parse(String json) {
        String agentObj = JsonParser.getObject(json, "agent");
        boolean hasAgent = agentObj != null;
        String agentHash = hasAgent ? JsonParser.getString(agentObj, "hash") : null;
        long agentSize = hasAgent ? JsonParser.getLong(agentObj, "size", -1) : -1;

        String filesArray = JsonParser.getArray(json, "files");
        boolean hasFiles = filesArray != null;
        List<FileEntry> files = hasFiles ? parseFileEntries(filesArray) : new ArrayList<>();

        String managedArray = JsonParser.getArray(json, "managed_paths");
        List<String> managedPaths = JsonParser.parseStringArray(
                managedArray != null ? managedArray : "");

        String excludedArray = JsonParser.getArray(json, "excluded_paths");
        List<String> excludedPaths = JsonParser.parseStringArray(
                excludedArray != null ? excludedArray : "");

        return new Manifest(files, hasFiles, managedPaths, excludedPaths,
                hasAgent, agentHash, agentSize);
    }

    /** Split a top-level JSON array of file objects into {@link FileEntry} list. */
    private static List<FileEntry> parseFileEntries(String filesArray) {
        List<FileEntry> list = new ArrayList<>();
        int depth = 0, start = -1;
        for (int i = 0; i < filesArray.length(); i++) {
            char c = filesArray.charAt(i);
            if (c == '{') { if (depth == 0) start = i; depth++; }
            else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    String obj = filesArray.substring(start, i + 1);
                    String path = JsonParser.getString(obj, "path");
                    String hash = JsonParser.getString(obj, "hash");
                    int size = JsonParser.getInt(obj, "size", -1);
                    if (path != null && hash != null && size >= 0) {
                        list.add(new FileEntry(path, hash, size));
                    }
                    start = -1;
                }
            }
        }
        return list;
    }
}
