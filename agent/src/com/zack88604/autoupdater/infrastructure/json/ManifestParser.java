package com.zack88604.autoupdater.infrastructure.json;

import com.zack88604.autoupdater.domain.AgentArtifact;
import com.zack88604.autoupdater.domain.FileEntry;
import com.zack88604.autoupdater.domain.Manifest;

import java.util.ArrayList;
import java.util.List;

/** Parses the updater manifest protocol into immutable domain data. */
public final class ManifestParser {

    private static final String AGENT_DOWNLOAD_PATH = "/api/agent";

    private ManifestParser() {
    }

    /** Parse a manifest JSON document using the updater's lightweight JSON protocol parser. */
    public static Manifest parse(String json) {
        String agentObject = JsonParser.getObject(json, "agent");
        boolean agentSectionPresent = agentObject != null;
        AgentArtifact agentArtifact = parseAgentArtifact(agentObject);

        String filesArray = JsonParser.getArray(json, "files");
        boolean fileListPresent = filesArray != null;
        List<FileEntry> files = fileListPresent
                ? parseFileEntries(filesArray)
                : new ArrayList<FileEntry>();

        String managedArray = JsonParser.getArray(json, "managed_paths");
        List<String> managedPaths = JsonParser.parseStringArray(
                managedArray != null ? managedArray : "");

        String excludedArray = JsonParser.getArray(json, "excluded_paths");
        List<String> excludedPaths = JsonParser.parseStringArray(
                excludedArray != null ? excludedArray : "");

        return new Manifest(files, managedPaths, excludedPaths, agentArtifact,
                fileListPresent, agentSectionPresent);
    }

    private static AgentArtifact parseAgentArtifact(String agentObject) {
        if (agentObject == null) {
            return null;
        }
        String hash = JsonParser.getString(agentObject, "hash");
        long size = JsonParser.getLong(agentObject, "size", -1);
        if (hash == null || size < 0) {
            return null;
        }
        return new AgentArtifact(AGENT_DOWNLOAD_PATH, hash, size);
    }

    private static List<FileEntry> parseFileEntries(String filesArray) {
        List<FileEntry> entries = new ArrayList<>();
        int depth = 0;
        int start = -1;
        for (int index = 0; index < filesArray.length(); index++) {
            char character = filesArray.charAt(index);
            if (character == '{') {
                if (depth == 0) {
                    start = index;
                }
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    String object = filesArray.substring(start, index + 1);
                    String path = JsonParser.getString(object, "path");
                    String hash = JsonParser.getString(object, "hash");
                    int size = JsonParser.getInt(object, "size", -1);
                    if (path != null && hash != null && size >= 0) {
                        entries.add(new FileEntry(path, hash, size));
                    }
                    start = -1;
                }
            }
        }
        return entries;
    }
}
