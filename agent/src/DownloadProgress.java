/**
 * Immutable per-file download progress snapshot.
 *
 * Computed by the business layer while a download is in progress — including
 * the download speed — and delivered to the UI through
 * {@link UpdateEvent.DownloadProgressChanged} events. The UI never reads this
 * from the business layer; it only receives it.
 */
final class DownloadProgress {

    /** What kind of object is currently being downloaded. */
    enum Kind {
        /** A regular managed file from the update manifest. */
        FILE,
        /** The updater (agent) self-update. */
        UPDATER,
        /** A JavaFX runtime jar downloaded by the background runtime worker. */
        JAVAFX
    }

    final boolean active;
    /** Current download target (file path or updater name); null when inactive. */
    final String path;
    /** Download kind (FILE / UPDATER); null when inactive. */
    final Kind kind;
    final long downloadedBytes;
    final long totalBytes;
    final double bytesPerSecond;

    DownloadProgress(boolean active, String path, Kind kind,
                     long downloadedBytes, long totalBytes, double bytesPerSecond) {
        this.active = active;
        this.path = path;
        this.kind = kind;
        this.downloadedBytes = downloadedBytes;
        this.totalBytes = totalBytes;
        this.bytesPerSecond = bytesPerSecond;
    }

    /** Snapshot for an active download of the given object. */
    static DownloadProgress active(String path, Kind kind,
                                   long downloaded, long total, double speed) {
        return new DownloadProgress(true, path, kind, downloaded, total, speed);
    }

    /** Snapshot meaning "no download in progress". */
    static DownloadProgress inactive() {
        return new DownloadProgress(false, null, null, 0, 0, 0);
    }
}
