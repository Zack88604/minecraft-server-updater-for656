package com.zack88604.autoupdater.domain;

/** Immutable outcome of one resource update run. */
public final class UpdateResult {

    private final int updatedFiles;
    private final int failedFiles;

    public UpdateResult(int updatedFiles, int failedFiles) {
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

    public boolean isSuccessful() {
        return failedFiles == 0;
    }
}
