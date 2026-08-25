package com.zack88604.autoupdater.application;

import com.zack88604.autoupdater.domain.AgentArtifact;
import com.zack88604.autoupdater.domain.FileEntry;
import com.zack88604.autoupdater.domain.Manifest;
import com.zack88604.autoupdater.domain.UpdateResult;
import com.zack88604.autoupdater.gui.api.UpdatePhase;
import com.zack88604.autoupdater.infrastructure.files.FileManager;
import com.zack88604.autoupdater.infrastructure.http.ServerClient;
import com.zack88604.autoupdater.infrastructure.json.ManifestParser;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Synchronous update use case.
 *
 * <p>This class coordinates manifest retrieval, agent self-update, managed
 * file synchronization, verification, replacement, and stale-file cleanup.
 * It never depends on a GUI toolkit; callers receive progress exclusively as
 * {@link UpdateEvent}s through the supplied {@link UpdateListener}.</p>
 */
public final class UpdateService {

    private final String gameDirectory;
    private final List<String> serverUrls;
    private final FileManager fileManager;

    public UpdateService(String gameDirectory, List<String> serverUrls) {
        this.gameDirectory = Objects.requireNonNull(gameDirectory, "gameDirectory");
        Objects.requireNonNull(serverUrls, "serverUrls");
        this.serverUrls = Collections.unmodifiableList(new ArrayList<>(serverUrls));
        this.fileManager = new FileManager(new File(gameDirectory));
    }

    /** Return configured server URLs in failover priority order. */
    public List<String> getServerUrls() {
        return serverUrls;
    }

    /** Return the initially selected server. */
    public String getCurrentServer() {
        return serverUrls.get(0);
    }

    /**
     * Run the full update flow on the caller's thread.
     *
     * @return the completed file-update result
     * @throws Exception if a manifest cannot be retrieved or parsed
     */
    public UpdateResult run(UpdateListener listener) throws Exception {
        Objects.requireNonNull(listener, "listener");
        EventRelay relay = new EventRelay(listener);
        ServerClient serverClient = new ServerClient(serverUrls, relay);

        relay.emit(new UpdateEvent.ServerChanged(serverUrls, serverClient.getCurrentServer()));
        relay.log("Servers (" + serverUrls.size() + "):");
        for (int index = 0; index < serverUrls.size(); index++) {
            relay.log("  [" + (index + 1) + "] " + serverUrls.get(index));
        }
        relay.log("Game dir: " + gameDirectory);

        relay.status(UpdatePhase.PREPARING, "Checking for updates...", null, true);
        relay.log("Fetching manifest...");
        String manifestJson = serverClient.getWithFallback("/api/v2/manifest");
        Manifest manifest = ManifestParser.parse(manifestJson);

        checkSelfUpdate(relay, serverClient, manifest);

        if (!manifest.isFileListPresent()) {
            throw new IOException("Cannot parse manifest");
        }

        List<FileEntry> manifestFiles = manifest.getFiles();
        relay.log("Manifest contains " + manifestFiles.size() + " file(s)");
        relay.log("Managed paths:");
        for (String path : manifest.getManagedPaths()) {
            relay.log("  - " + path);
        }
        if (!manifest.getExcludedPaths().isEmpty()) {
            relay.log("Excluded paths:");
            for (String path : manifest.getExcludedPaths()) {
                relay.log("  - " + path);
            }
        }

        relay.overallProgress(0);
        int total = manifestFiles.size();
        int checked = 0;
        int updated = 0;
        int failed = 0;

        for (FileEntry entry : manifestFiles) {
            checked++;
            String relativePath = entry.getPath();
            File localFile = fileManager.resolveManagedFile(relativePath);
            if (localFile == null) {
                relay.log("  [REJECT] " + relativePath + " (unsafe manifest path)");
                failed++;
                relay.status(UpdatePhase.CHECKING,
                        "Rejected unsafe path: " + checked + "/" + total, null, false);
                relay.overallProgress(total > 0 ? checked * 95 / total : 100);
                continue;
            }

            boolean needsDownload = needsDownload(relay, localFile, entry);
            if (needsDownload) {
                if (updateFile(relay, serverClient, localFile, entry)) {
                    updated++;
                } else {
                    failed++;
                }
            }

            relay.status(UpdatePhase.CHECKING,
                    "Checked: " + checked + "/" + total, null, false);
            relay.overallProgress(total > 0 ? checked * 95 / total : 100);
        }

        relay.log("Cleaning stale files...");
        relay.status(UpdatePhase.CLEANING, "Cleaning up…",
                "Removing files that are no longer needed", true);
        fileManager.cleanStaleFiles(manifestFiles, manifest.getManagedPaths(),
                manifest.getExcludedPaths(), relay::log);

        return new UpdateResult(updated, failed);
    }

    private boolean needsDownload(EventRelay relay, File localFile, FileEntry entry) {
        String relativePath = entry.getPath();
        if (!localFile.isFile()) {
            relay.log("  [MISS]  " + relativePath);
            return true;
        }

        String localHash = fileManager.sha256(localFile);
        if (localHash == null) {
            relay.log("  [WARN]  " + relativePath + " (cannot read, re-downloading)");
            return true;
        }
        if (!localHash.equals(entry.getSha256())) {
            relay.log("  [DIFF]  " + relativePath + " (hash mismatch)");
            return true;
        }
        relay.log("  [OK]    " + relativePath);
        return false;
    }

    private boolean updateFile(EventRelay relay, ServerClient serverClient,
                               File localFile, FileEntry entry) {
        String relativePath = entry.getPath();
        relay.status(UpdatePhase.DOWNLOADING, "Downloading: " + relativePath, null, false);
        relay.log("         -> Downloading " + relativePath + "...");
        File parent = localFile.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            parent.mkdirs();
        }
        File temporaryFile = new File(localFile.getPath() + ".tmp");

        relay.startDownload(relativePath, UpdateEvent.DownloadKind.MANAGED_FILE, entry.getSize());
        long downloadStartedAt = System.currentTimeMillis();
        boolean downloaded = serverClient.downloadWithFallback(
                "/api/files/" + ServerClient.encodePath(relativePath), temporaryFile);

        boolean updated = false;
        if (downloaded) {
            String downloadedHash = fileManager.sha256(temporaryFile);
            if (downloadedHash != null && downloadedHash.equals(entry.getSha256())) {
                try {
                    fileManager.replaceDownloadedFile(temporaryFile, localFile);
                    long elapsed = System.currentTimeMillis() - downloadStartedAt;
                    double averageSpeed = elapsed > 0
                            ? entry.getSize() * 1000.0 / elapsed : 0;
                    relay.log("         -> Done (" + entry.getSize() + " bytes, "
                            + formatSpeed(averageSpeed) + ")");
                    updated = true;
                } catch (IOException e) {
                    relay.log("  [FAIL]  " + relativePath + ": cannot replace file ("
                            + e.getMessage() + ")");
                    relay.log("  [FAIL]  " + relativePath + ": cannot move file");
                    temporaryFile.delete();
                }
            } else {
                relay.log("  [FAIL]  " + relativePath + ": hash mismatch after download");
                temporaryFile.delete();
            }
        } else {
            relay.log("  [FAIL]  " + relativePath + ": download failed");
            temporaryFile.delete();
        }

        relay.finishDownload();
        return updated;
    }

    private void checkSelfUpdate(EventRelay relay, ServerClient serverClient, Manifest manifest) {
        if (!manifest.isAgentSectionPresent()) {
            relay.log("  [SKIP]  No agent info in manifest");
            return;
        }

        AgentArtifact agent = manifest.getAgentArtifact();
        if (agent == null || agent.getSize() <= 0) {
            relay.log("  [SKIP]  Incomplete agent info in manifest");
            return;
        }

        String jarPath = getMyJarPath();
        if (jarPath == null) {
            relay.log("  [SKIP]  Cannot determine agent JAR path");
            return;
        }

        File currentJar = new File(jarPath);
        if (!currentJar.isFile()) {
            relay.log("  [SKIP]  Agent JAR not found at: " + jarPath);
            return;
        }

        relay.log("Checking agent update...");
        relay.log("  My path:    " + jarPath);
        String localHash = fileManager.sha256(currentJar);
        if (localHash == null) {
            relay.log("  [SKIP]  Cannot compute local agent hash");
            return;
        }
        if (localHash.equals(agent.getSha256())) {
            relay.log("  [OK]    Agent is up to date");
            return;
        }

        relay.log("  [UPDATE] New agent version available!");
        relay.log("  Remote: " + agent.getSha256());
        relay.log("  Local:  " + localHash);
        relay.status(UpdatePhase.PREPARING, "Downloading agent update...", null, false);

        File newJar = new File(jarPath + ".new");
        if (newJar.exists()) {
            newJar.delete();
        }

        relay.startDownload(currentJar.getName(), UpdateEvent.DownloadKind.UPDATER, agent.getSize());
        boolean downloaded = serverClient.downloadWithFallback(agent.getPath(), newJar);
        relay.finishDownload();

        if (!downloaded) {
            relay.log("  [FAIL]  Agent download failed");
            newJar.delete();
            return;
        }

        String downloadedHash = fileManager.sha256(newJar);
        if (downloadedHash == null || !downloadedHash.equals(agent.getSha256())) {
            relay.log("  [FAIL]  Agent hash mismatch after download");
            newJar.delete();
            return;
        }

        relay.log("  [OK]    Agent downloaded, will replace on next restart");
    }

    private static String getMyJarPath() {
        try {
            String path = UpdateService.class.getProtectionDomain()
                    .getCodeSource().getLocation().getPath();
            return URLDecoder.decode(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static String formatSpeed(double bytesPerSecond) {
        if (bytesPerSecond < 0) {
            bytesPerSecond = 0;
        }
        if (bytesPerSecond >= 1_000_000_000) {
            return String.format("%.1f GB/s", bytesPerSecond / 1_000_000_000);
        }
        if (bytesPerSecond >= 1_000_000) {
            return String.format("%.1f MB/s", bytesPerSecond / 1_000_000);
        }
        if (bytesPerSecond >= 1_000) {
            return String.format("%.0f KB/s", bytesPerSecond / 1_000);
        }
        return String.format("%.0f B/s", bytesPerSecond);
    }

    private static final class EventRelay implements ServerClient.Listener {
        private final UpdateListener listener;
        private String resource;
        private UpdateEvent.DownloadKind downloadKind;
        private long expectedTotalBytes;
        private long lastDownloadBytes;
        private long lastDownloadTime;

        private EventRelay(UpdateListener listener) {
            this.listener = listener;
        }

        void emit(UpdateEvent event) {
            listener.onUpdateEvent(event);
        }

        void log(String message) {
            emit(new UpdateEvent.LogMessage(message));
        }

        void status(UpdatePhase phase, String status, String description,
                    boolean indeterminate) {
            emit(new UpdateEvent.StatusChanged(phase, status, description, indeterminate));
        }

        void overallProgress(int percentage) {
            emit(new UpdateEvent.OverallProgressChanged(percentage));
        }

        void startDownload(String resource, UpdateEvent.DownloadKind kind, long expectedTotalBytes) {
            this.resource = resource;
            this.downloadKind = kind;
            this.expectedTotalBytes = expectedTotalBytes;
            this.lastDownloadBytes = 0;
            this.lastDownloadTime = System.currentTimeMillis();
            emit(UpdateEvent.DownloadProgressChanged.active(
                    resource, kind, expectedTotalBytes, 0, 0));
        }

        void finishDownload() {
            emit(UpdateEvent.DownloadProgressChanged.inactive());
            resource = null;
            downloadKind = null;
            expectedTotalBytes = 0;
            lastDownloadBytes = 0;
            lastDownloadTime = 0;
        }

        @Override
        public void onLog(String message) {
            log(message);
        }

        @Override
        public void onServerChanged(List<String> serverUrls, String currentServer) {
            emit(new UpdateEvent.ServerChanged(serverUrls, currentServer));
        }

        @Override
        public void onDownloadProgress(long totalBytes, long downloadedBytes) {
            long effectiveTotal = totalBytes > 0 ? totalBytes : expectedTotalBytes;
            long now = System.currentTimeMillis();
            long elapsed = now - lastDownloadTime;
            long bytesDelta = downloadedBytes - lastDownloadBytes;
            double bytesPerSecond = elapsed > 0
                    ? Math.max(0, bytesDelta) * 1000.0 / elapsed : 0;
            lastDownloadBytes = downloadedBytes;
            lastDownloadTime = now;
            emit(UpdateEvent.DownloadProgressChanged.active(
                    resource, downloadKind, effectiveTotal, downloadedBytes, bytesPerSecond));
        }
    }
}
