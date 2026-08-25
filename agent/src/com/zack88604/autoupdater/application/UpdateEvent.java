package com.zack88604.autoupdater.application;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable event emitted by the update use case.
 *
 * <p>Events contain no GUI toolkit, network, file-system, or process types.
 * A presentation adapter is responsible for marshalling them onto its UI
 * thread and rendering them.</p>
 */
public abstract class UpdateEvent {

    private UpdateEvent() {
    }

    /** A textual status and its progress-bar mode changed. */
    public static final class StatusChanged extends UpdateEvent {
        private final String status;
        private final boolean indeterminate;

        public StatusChanged(String status, boolean indeterminate) {
            this.status = Objects.requireNonNull(status, "status");
            this.indeterminate = indeterminate;
        }

        public String getStatus() {
            return status;
        }

        public boolean isIndeterminate() {
            return indeterminate;
        }
    }

    /** Overall progress changed to a percentage from 0 through 100. */
    public static final class OverallProgressChanged extends UpdateEvent {
        private final int percentage;

        public OverallProgressChanged(int percentage) {
            if (percentage < 0 || percentage > 100) {
                throw new IllegalArgumentException("percentage must be 0..100");
            }
            this.percentage = percentage;
        }

        public int getPercentage() {
            return percentage;
        }
    }

    /** A log line was produced by the update flow or an infrastructure adapter. */
    public static final class LogMessage extends UpdateEvent {
        private final String message;

        public LogMessage(String message) {
            this.message = Objects.requireNonNull(message, "message");
        }

        public String getMessage() {
            return message;
        }
    }

    /** The configured server list or active server changed. */
    public static final class ServerChanged extends UpdateEvent {
        private final List<String> serverUrls;
        private final String currentServer;

        public ServerChanged(List<String> serverUrls, String currentServer) {
            Objects.requireNonNull(serverUrls, "serverUrls");
            this.serverUrls = Collections.unmodifiableList(new ArrayList<>(serverUrls));
            this.currentServer = Objects.requireNonNull(currentServer, "currentServer");
        }

        public List<String> getServerUrls() {
            return serverUrls;
        }

        public String getCurrentServer() {
            return currentServer;
        }
    }

    /** Whether an updater artifact or managed file is being downloaded. */
    public enum DownloadKind {
        MANAGED_FILE,
        UPDATER
    }

    /** A snapshot of the active download, or its completion. */
    public static final class DownloadProgressChanged extends UpdateEvent {
        private final String resource;
        private final DownloadKind kind;
        private final boolean active;
        private final long totalBytes;
        private final long downloadedBytes;

        private DownloadProgressChanged(String resource, DownloadKind kind, boolean active,
                                        long totalBytes, long downloadedBytes) {
            this.resource = resource;
            this.kind = kind;
            this.active = active;
            this.totalBytes = totalBytes;
            this.downloadedBytes = downloadedBytes;
        }

        public static DownloadProgressChanged active(String resource, DownloadKind kind,
                                                     long totalBytes, long downloadedBytes) {
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(kind, "kind");
            if (totalBytes < 0 || downloadedBytes < 0) {
                throw new IllegalArgumentException("download byte counts must be >= 0");
            }
            return new DownloadProgressChanged(resource, kind, true, totalBytes, downloadedBytes);
        }

        public static DownloadProgressChanged inactive() {
            return new DownloadProgressChanged(null, null, false, 0, 0);
        }

        public String getResource() {
            return resource;
        }

        public DownloadKind getKind() {
            return kind;
        }

        public boolean isActive() {
            return active;
        }

        public long getTotalBytes() {
            return totalBytes;
        }

        public long getDownloadedBytes() {
            return downloadedBytes;
        }
    }
}
