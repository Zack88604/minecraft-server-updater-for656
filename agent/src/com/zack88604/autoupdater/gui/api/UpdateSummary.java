package com.zack88604.autoupdater.gui.api;

/** Immutable terminal summary of a completed update flow. */
public final class UpdateSummary {

    private final int updatedFiles;
    private final int failedFiles;

    public UpdateSummary(int updatedFiles, int failedFiles) {
        if (updatedFiles < 0 || failedFiles < 0) {
            throw new IllegalArgumentException("file counts must be >= 0");
        }
        this.updatedFiles = updatedFiles;
        this.failedFiles = failedFiles;
    }

    public int getUpdatedFiles() {
        return updatedFiles;
    }

    public int getFailedFiles() {
        return failedFiles;
    }

    /** Whether every managed file completed successfully. */
    public boolean isSuccessful() {
        return failedFiles == 0;
    }
}
