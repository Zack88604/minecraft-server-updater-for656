package com.zack88604.autoupdater.gui.api;

import java.util.Objects;

/** Immutable progress snapshot for the current download, if any. */
public final class DownloadProgress {

    /** What is being downloaded. */
    public enum Kind {
        /** A managed Minecraft resource file. */
        FILE,
        /** A replacement {@code UpdateAgent_core.jar}. */
        UPDATER,
        /** An optional GUI runtime artifact. */
        GUI_RUNTIME
    }

    private static final DownloadProgress INACTIVE =
            new DownloadProgress(false, null, null, 0, 0, 0);

    private final boolean active;
    private final String path;
    private final Kind kind;
    private final long downloadedBytes;
    private final long totalBytes;
    private final double bytesPerSecond;

    private DownloadProgress(boolean active, String path, Kind kind,
                             long downloadedBytes, long totalBytes,
                             double bytesPerSecond) {
        this.active = active;
        this.path = path;
        this.kind = kind;
        this.downloadedBytes = downloadedBytes;
        this.totalBytes = totalBytes;
        this.bytesPerSecond = bytesPerSecond;
    }

    /** Return the shared snapshot representing no active download. */
    public static DownloadProgress inactive() {
        return INACTIVE;
    }

    /**
     * Create an active download snapshot.
     *
     * @param totalBytes total size, or {@code 0} when it is unknown
     */
    public static DownloadProgress active(String path, Kind kind,
                                          long downloadedBytes, long totalBytes,
                                          double bytesPerSecond) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(kind, "kind");
        if (downloadedBytes < 0) {
            throw new IllegalArgumentException("downloadedBytes must be >= 0");
        }
        if (totalBytes < 0) {
            throw new IllegalArgumentException("totalBytes must be >= 0");
        }
        if (bytesPerSecond < 0) {
            throw new IllegalArgumentException("bytesPerSecond must be >= 0");
        }
        return new DownloadProgress(true, path, kind, downloadedBytes,
                totalBytes, bytesPerSecond);
    }

    public boolean isActive() {
        return active;
    }

    public String getPath() {
        return path;
    }

    public Kind getKind() {
        return kind;
    }

    public long getDownloadedBytes() {
        return downloadedBytes;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public double getBytesPerSecond() {
        return bytesPerSecond;
    }
}
