package com.zack88604.autoupdater.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable parsed update manifest.
 *
 * <p>JSON parsing remains in the current bootstrap for this incremental step;
 * a later infrastructure extraction will construct this model there.</p>
 */
public final class Manifest {

    private final List<FileEntry> files;
    private final List<String> managedPaths;
    private final List<String> excludedPaths;
    private final AgentArtifact agentArtifact;
    private final boolean fileListPresent;
    private final boolean agentSectionPresent;

    public Manifest(List<FileEntry> files, List<String> managedPaths,
                    List<String> excludedPaths, AgentArtifact agentArtifact) {
        this(files, managedPaths, excludedPaths, agentArtifact, true, agentArtifact != null);
    }

    public Manifest(List<FileEntry> files, List<String> managedPaths,
                    List<String> excludedPaths, AgentArtifact agentArtifact,
                    boolean fileListPresent, boolean agentSectionPresent) {
        this.files = immutableCopy(files, "files");
        this.managedPaths = immutableCopy(managedPaths, "managedPaths");
        this.excludedPaths = immutableCopy(excludedPaths, "excludedPaths");
        this.agentArtifact = agentArtifact;
        this.fileListPresent = fileListPresent;
        this.agentSectionPresent = agentSectionPresent;
    }

    public List<FileEntry> getFiles() {
        return files;
    }

    public List<String> getManagedPaths() {
        return managedPaths;
    }

    public List<String> getExcludedPaths() {
        return excludedPaths;
    }

    /** Return the optional self-update artifact, or {@code null} when absent. */
    public AgentArtifact getAgentArtifact() {
        return agentArtifact;
    }

    /** Whether the source JSON contained a top-level {@code files} array. */
    public boolean isFileListPresent() {
        return fileListPresent;
    }

    /** Whether the source JSON contained an {@code agent} metadata object. */
    public boolean isAgentSectionPresent() {
        return agentSectionPresent;
    }

    private static <T> List<T> immutableCopy(List<T> source, String name) {
        if (source == null) {
            throw new NullPointerException(name);
        }
        List<T> copy = new ArrayList<>(source.size());
        for (T value : source) {
            if (value == null) {
                throw new NullPointerException(name + " entry");
            }
            copy.add(value);
        }
        return Collections.unmodifiableList(copy);
    }
}
