import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Core update service.
 *
 * Fetches and parses the update manifest, checks each managed file, downloads
 * and SHA-256 verifies those that differ, replaces them atomically, cleans
 * stale files and performs the agent self-update.
 *
 * Runs synchronously on the caller's thread and reports every state change,
 * progress update and log line as an {@link UpdateEvent} delivered to an
 * {@link UpdateListener}. Contains no Swing dependency and never touches the
 * UI — it only produces business events.
 */
class UpdateService {

    private final String gameDir;
    private final List<String> serverUrls;
    private final ServerClient client;
    private final FileManager fileManager;

    UpdateService(String gameDir, List<String> serverUrls) {
        this.gameDir = gameDir;
        this.serverUrls = serverUrls;
        this.client = new ServerClient(serverUrls);
        this.fileManager = new FileManager(gameDir);
    }

    /**
     * Run the full update flow. Every step is reported to the listener as an
     * {@link UpdateEvent}; a successful run ends with a
     * {@link UpdateEvent.Completed}, an unexpected failure with a
     * {@link UpdateEvent.Failed}. Never throws.
     */
    UpdateResult run(UpdateListener listener) {
        client.setListener(listener);
        fileManager.setListener(listener);

        try {
            UpdateResult result = runFlow(listener);
            emit(listener, new UpdateEvent.Completed(result));
            return result;
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.toString();
            emit(listener, new UpdateEvent.Failed(message, e));
            return null;
        }
    }

    private UpdateResult runFlow(UpdateListener listener) throws Exception {
        emit(listener, new UpdateEvent.ServerChanged(serverUrls, client.getCurrentServer()));
        log(listener, "Servers (" + serverUrls.size() + "):");
        for (int i = 0; i < serverUrls.size(); i++) {
            log(listener, "  [" + (i + 1) + "] " + serverUrls.get(i));
        }
        log(listener, "Game dir: " + gameDir);

        // 1. fetch manifest (with multi-server fallback)
        emit(listener, new UpdateEvent.StatusChanged(UpdatePhase.PREPARING,
                "Checking for updates...", null, true));
        log(listener, "Fetching manifest...");
        String manifestJson = client.httpGetWithFallback("/api/v2/manifest");

        Manifest manifest = Manifest.parse(manifestJson);

        // 0. self-update check — must happen before regular file sync
        checkSelfUpdate(listener, manifest);

        // 0.5. JavaFX runtime — same stage as the self-update check. Blocks
        // until the local runtime is complete (or the repair finishes, success
        // or failure), then the flow continues regardless of the outcome.
        JavaFxRuntimeManager.ensureReady(listener);

        if (!manifest.hasFiles) {
            throw new IOException("Cannot parse manifest");
        }
        List<FileEntry> manifestFiles = manifest.files;
        log(listener, "Manifest contains " + manifestFiles.size() + " file(s)");
        log(listener, "Managed paths:");
        for (String p : manifest.managedPaths) log(listener, "  - " + p);
        if (!manifest.excludedPaths.isEmpty()) {
            log(listener, "Excluded paths:");
            for (String p : manifest.excludedPaths) log(listener, "  - " + p);
        }

        // 2. check and download each file
        emit(listener, new UpdateEvent.OverallProgressChanged(0));
        int total = manifestFiles.size();
        int checked = 0;
        int updated = 0;
        int failed = 0;

        for (FileEntry entry : manifestFiles) {
            checked++;
            String relPath = entry.path;

            File localFile = fileManager.resolveManagedFile(relPath);
            if (localFile == null) {
                log(listener, "  [REJECT] " + relPath + " (unsafe manifest path)");
                failed++;
                emit(listener, new UpdateEvent.StatusChanged(UpdatePhase.CHECKING,
                        "Rejected unsafe path: " + checked + "/" + total, null, false));
                emit(listener, new UpdateEvent.OverallProgressChanged(total > 0 ? checked * 95 / total : 100));
                continue;
            }
            boolean needDownload = false;

            if (!localFile.isFile()) {
                log(listener, "  [MISS]  " + relPath);
                needDownload = true;
            } else {
                String localHash = fileManager.sha256(localFile);
                if (localHash == null) {
                    log(listener, "  [WARN]  " + relPath + " (cannot read, re-downloading)");
                    needDownload = true;
                } else if (!localHash.equals(entry.hash)) {
                    log(listener, "  [DIFF]  " + relPath + " (hash mismatch)");
                    needDownload = true;
                } else {
                    log(listener, "  [OK]    " + relPath);
                }
            }

            if (needDownload) {
                emit(listener, new UpdateEvent.StatusChanged(UpdatePhase.DOWNLOADING,
                        "Downloading: " + relPath, null, false));
                log(listener, "         -> Downloading " + relPath + "...");
                File parent = localFile.getParentFile();
                if (parent != null && !parent.isDirectory()) parent.mkdirs();
                File tmpFile = new File(localFile.getPath() + ".tmp");

                // Report the start of a per-file download; the speed is computed
                // while streaming and delivered through progress events.
                emit(listener, new UpdateEvent.DownloadProgressChanged(
                        DownloadProgress.active(relPath, DownloadProgress.Kind.FILE, 0, entry.size, 0)));
                long dlStart = System.currentTimeMillis();

                // URL-encode each path segment for the download URL
                String encodedPath = ServerClient.encodePath(relPath);
                boolean ok = client.httpDownloadWithFallback("/api/files/" + encodedPath, tmpFile,
                        relPath, DownloadProgress.Kind.FILE);

                // Reset per-file progress bar immediately
                emit(listener, new UpdateEvent.DownloadProgressChanged(DownloadProgress.inactive()));

                if (ok) {
                    String dlHash = fileManager.sha256(tmpFile);
                    if (dlHash != null && dlHash.equals(entry.hash)) {
                        if (fileManager.replaceDownloadedFile(tmpFile, localFile, relPath)) {
                            long dlElapsed = System.currentTimeMillis() - dlStart;
                            double avgSpeed = dlElapsed > 0 ? entry.size * 1000.0 / dlElapsed : 0;
                            log(listener, "         -> Done (" + entry.size + " bytes, "
                                    + FormatUtil.formatSpeed(avgSpeed) + ")");
                            updated++;
                        } else {
                            log(listener, "  [FAIL]  " + relPath + ": cannot move file");
                            tmpFile.delete();
                            failed++;
                        }
                    } else {
                        log(listener, "  [FAIL]  " + relPath + ": hash mismatch after download");
                        tmpFile.delete();
                        failed++;
                    }
                } else {
                    log(listener, "  [FAIL]  " + relPath + ": download failed");
                    tmpFile.delete();
                    failed++;
                }
            }

            emit(listener, new UpdateEvent.StatusChanged(UpdatePhase.CHECKING,
                    "Checked: " + checked + "/" + total, null, false));
            emit(listener, new UpdateEvent.OverallProgressChanged(total > 0 ? checked * 95 / total : 100));
        }

        // 3. clean stale files
        log(listener, "Cleaning stale files...");
        emit(listener, new UpdateEvent.StatusChanged(UpdatePhase.CLEANING,
                "Cleaning up…",
                "Removing files that are no longer needed", true));
        fileManager.cleanStaleFiles(manifestFiles, manifest.managedPaths, manifest.excludedPaths);

        return new UpdateResult(updated, failed);
    }

    /** Check manifest for agent update; download if newer. */
    private void checkSelfUpdate(UpdateListener listener, Manifest manifest) {
        if (!manifest.hasAgent) {
            log(listener, "  [SKIP]  No agent info in manifest");
            return;
        }
        String agentHash = manifest.agentHash;
        long agentSize = manifest.agentSize;
        if (agentHash == null || agentSize <= 0) {
            log(listener, "  [SKIP]  Incomplete agent info in manifest");
            return;
        }

        String myJarPath = getMyJarPath();
        if (myJarPath == null) {
            log(listener, "  [SKIP]  Cannot determine agent JAR path");
            return;
        }

        File myJar = new File(myJarPath);
        if (!myJar.isFile()) {
            log(listener, "  [SKIP]  Agent JAR not found at: " + myJarPath);
            return;
        }

        log(listener, "Checking agent update...");
        log(listener, "  My path:    " + myJarPath);
        String myHash = fileManager.sha256(myJar);
        if (myHash == null) {
            log(listener, "  [SKIP]  Cannot compute local agent hash");
            return;
        }
        if (myHash.equals(agentHash)) {
            log(listener, "  [OK]    Agent is up to date");
            return;
        }

        log(listener, "  [UPDATE] New agent version available!");
        log(listener, "  Remote: " + agentHash);
        log(listener, "  Local:  " + myHash);
        emit(listener, new UpdateEvent.StatusChanged(UpdatePhase.PREPARING,
                "Downloading agent update...", null, false));

        File newJar = new File(myJarPath + ".new");
        if (newJar.exists()) newJar.delete();

        String agentName = myJar.getName();
        emit(listener, new UpdateEvent.DownloadProgressChanged(
                DownloadProgress.active(agentName, DownloadProgress.Kind.UPDATER, 0, agentSize, 0)));
        boolean ok = client.httpDownloadWithFallback("/api/agent", newJar,
                agentName, DownloadProgress.Kind.UPDATER);
        emit(listener, new UpdateEvent.DownloadProgressChanged(DownloadProgress.inactive()));

        if (!ok) {
            log(listener, "  [FAIL]  Agent download failed");
            newJar.delete();
            return;
        }

        String dlHash = fileManager.sha256(newJar);
        if (dlHash == null || !dlHash.equals(agentHash)) {
            log(listener, "  [FAIL]  Agent hash mismatch after download");
            newJar.delete();
            return;
        }

        log(listener, "  [OK]    Agent downloaded, will replace on next restart");
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

    private static void emit(UpdateListener listener, UpdateEvent event) {
        listener.onUpdateEvent(event);
    }

    private static void log(UpdateListener listener, String msg) {
        emit(listener, new UpdateEvent.LogMessage(msg));
    }
}
