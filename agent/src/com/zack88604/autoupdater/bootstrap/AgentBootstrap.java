package com.zack88604.autoupdater.bootstrap;

/**
 * Minecraft Client Auto-Update Java Agent
 *
 * Loaded via -javaagent JVM argument at Minecraft client startup:
 * 1. Check for updates via HTTP API
 * 2. Show GUI window with status and progress
 * 3. Block Minecraft launch until update check completes
 *
 * System properties (or agent args):
 *   -Dmc-update.server=http://192.168.1.100:25565
 *   -Dmc-update.game-dir=C:\\path\\to\\.minecraft
 *
 * Compile:
 *   javac -d build src/UpdateAgent.java
 *   cd build && jar cfm ../UpdateAgent.jar ../META-INF/MANIFEST.MF *.class
 */

import com.zack88604.autoupdater.config.AgentConfig;
import com.zack88604.autoupdater.domain.FileEntry;
import com.zack88604.autoupdater.domain.UpdateResult;
import com.zack88604.autoupdater.infrastructure.files.FileManager;
import com.zack88604.autoupdater.infrastructure.http.ServerClient;
import com.zack88604.autoupdater.infrastructure.json.JsonParser;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public final class AgentBootstrap {

    private static final String PROP_SERVER = AgentConfig.PROP_SERVER;
    private static final String PROP_GAME_DIR = AgentConfig.PROP_GAME_DIR;
    private static final String PROP_DEBUG = AgentConfig.PROP_DEBUG;

    // ── Agent entry point ────────────────────────────────────────

    public static void premain(String args, Instrumentation inst) {
        AgentConfig config = AgentConfig.resolve(args);

        // Keep these legacy properties populated until the remaining Swing
        // updater code is moved out of this bootstrap.
        System.setProperty(PROP_GAME_DIR, config.getGameDir());
        System.setProperty(PROP_SERVER, config.getServer());
        if (config.isDebug()) {
            System.setProperty(PROP_DEBUG, "true");
        }

        // Block premain until update check finishes, then allow Minecraft to start.
        CountDownLatch latch = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> new UpdateGUI(latch, config.isDebug()));

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Utility: get own JAR path ──────────────────────────────

    private static String getMyJarPath() {
        try {
            String path = AgentBootstrap.class.getProtectionDomain()
                    .getCodeSource().getLocation().getPath();
            return URLDecoder.decode(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  GUI
    // ═══════════════════════════════════════════════════════════════

    static class UpdateGUI extends JFrame {

        private final JLabel     lblStatus   = new JLabel("Checking for updates...");
        private final JProgressBar progressBar = new JProgressBar(0, 100);
        private final JTextArea  logArea     = new JTextArea(8, 50);
        private final JButton    btnClose    = new JButton("Close");

        // Per-file download progress bar (below overall bar)
        private final JProgressBar dlProgressBar = new JProgressBar(0, 100);
        private final JLabel       lblDlSpeed    = new JLabel(" ");

        // Download tracking — written by worker thread, read by Swing Timer on EDT
        private volatile long dlTotalBytes      = 0;
        private volatile long dlDownloadedBytes = 0;
        private volatile boolean dlActive       = false;

        // Per-file download UI refresh timer (500ms)
        private final javax.swing.Timer dlRefreshTimer = new javax.swing.Timer(500, e -> refreshDownloadUI());
        private long dlLastBytes = 0;
        private long dlLastTime  = 0;

        private String gameDir;
        private FileManager fileManager;
        private ServerClient serverClient;
        private final CountDownLatch latch;
        private final boolean debug;
        private JLabel serverLabel;
        private UpdateWorker updateWorker;

        private enum UiEventType {
            STATUS, LOG, OVERALL_PROGRESS, RESET_DOWNLOAD_PROGRESS,
            SERVER_LABEL, STOP_DOWNLOAD_REFRESH
        }

        /** A background-to-EDT message. UI components are only changed in process(). */
        private static final class UiEvent {
            final UiEventType type;
            final String text;
            final boolean indeterminate;
            final int progress;

            private UiEvent(UiEventType type, String text, boolean indeterminate, int progress) {
                this.type = type;
                this.text = text;
                this.indeterminate = indeterminate;
                this.progress = progress;
            }

            static UiEvent status(String text, boolean indeterminate) {
                return new UiEvent(UiEventType.STATUS, text, indeterminate, 0);
            }

            static UiEvent log(String text) {
                return new UiEvent(UiEventType.LOG, text, false, 0);
            }

            static UiEvent overallProgress(int progress) {
                return new UiEvent(UiEventType.OVERALL_PROGRESS, null, false, progress);
            }

            static UiEvent serverLabel(String text) {
                return new UiEvent(UiEventType.SERVER_LABEL, text, false, 0);
            }

            static UiEvent resetDownloadProgress() {
                return new UiEvent(UiEventType.RESET_DOWNLOAD_PROGRESS, null, false, 0);
            }

            static UiEvent stopDownloadRefresh() {
                return new UiEvent(UiEventType.STOP_DOWNLOAD_REFRESH, null, false, 0);
            }
        }



        UpdateGUI(CountDownLatch latch, boolean debug) {
            this.latch = latch;
            this.debug = debug;
            initConfig();
            initUI();
            setVisible(true);
            startUpdate();
        }

        private void initConfig() {
            // gameDir: system property (set by premain) > user.dir
            gameDir = System.getProperty(PROP_GAME_DIR);
            if (gameDir == null || gameDir.isEmpty()) {
                gameDir = System.getProperty("user.dir", ".");
            }

            // The bootstrap has already resolved configuration and populated this property.
            List<String> configuredServers = parseServerList(
                    System.getProperty(PROP_SERVER, AgentConfig.DEFAULT_SERVER));
            fileManager = new FileManager(new File(gameDir));
            serverClient = new ServerClient(configuredServers, new ServerClient.Listener() {
                @Override
                public void onLog(String message) {
                    log(message);
                }

                @Override
                public void onServerChanged(List<String> serverUrls, String currentServer) {
                    updateServerLabel();
                }

                @Override
                public void onDownloadProgress(long totalBytes, long downloadedBytes) {
                    if (totalBytes > 0) {
                        dlTotalBytes = totalBytes;
                    }
                    dlDownloadedBytes = downloadedBytes;
                }
            });
        }

        /** Parse comma-separated server URLs, trimming whitespace from each. */
        private static List<String> parseServerList(String raw) {
            List<String> list = new ArrayList<>();
            if (raw == null || raw.trim().isEmpty()) return list;
            for (String token : raw.split(",")) {
                String url = token.trim();
                if (!url.isEmpty()) {
                    // Remove trailing slash for consistency
                    while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
                    list.add(url);
                }
            }
            return list;
        }

        /** Get the currently active server URL. */
        private String currentServer() {
            return serverClient.getCurrentServer();
        }

        /** Update the server label in the top panel. */
        private void updateServerLabel() {
            List<String> serverUrls = serverClient.getServerUrls();
            String display = serverUrls.size() <= 1
                    ? "Server: " + currentServer()
                    : "Servers (" + serverUrls.size() + "): " + currentServer();
            dispatchUiEvent(UiEvent.serverLabel(display));
        }

        /** Run a UI mutation on Swing's Event Dispatch Thread. */
        private void runOnEdt(Runnable action) {
            if (SwingUtilities.isEventDispatchThread()) {
                action.run();
            } else {
                SwingUtilities.invokeLater(action);
            }
        }

        /** Deliver an event through SwingWorker when called from its worker thread. */
        private void dispatchUiEvent(UiEvent event) {
            UpdateWorker worker = updateWorker;
            if (worker != null && !SwingUtilities.isEventDispatchThread() && !worker.isDone()) {
                worker.emit(event);
            } else {
                runOnEdt(() -> applyUiEvent(event));
            }
        }

        /** Apply a UI event. This method must run on the EDT. */
        private void applyUiEvent(UiEvent event) {
            switch (event.type) {
                case STATUS:
                    lblStatus.setText(event.text);
                    progressBar.setIndeterminate(event.indeterminate);
                    break;
                case LOG:
                    logArea.append(event.text + "\n");
                    logArea.setCaretPosition(logArea.getDocument().getLength());
                    break;
                case OVERALL_PROGRESS:
                    progressBar.setIndeterminate(false);
                    progressBar.setValue(Math.max(0, Math.min(100, event.progress)));
                    break;
                case RESET_DOWNLOAD_PROGRESS:
                    dlProgressBar.setValue(0);
                    dlProgressBar.setString("");
                    lblDlSpeed.setText(" ");
                    break;
                case SERVER_LABEL:
                    serverLabel.setText(event.text);
                    break;
                case STOP_DOWNLOAD_REFRESH:
                    dlRefreshTimer.stop();
                    break;
            }
        }

        /** Set the overall progress bar to a determinate percentage. */
        private void setOverallProgress(int value) {
            dispatchUiEvent(UiEvent.overallProgress(value));
        }

        /** Reset the per-file download progress bar and speed label. */
        private void resetDownloadProgress() {
            dispatchUiEvent(UiEvent.resetDownloadProgress());
        }

        /** Stop the Swing refresh timer from the EDT. */
        private void stopDownloadRefreshTimer() {
            dispatchUiEvent(UiEvent.stopDownloadRefresh());
        }

        private void initUI() {
            setTitle("Minecraft Update Check");
            setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            setSize(520, 420);
            setLocationRelativeTo(null);
            setResizable(false);

            // Release latch on window close so Minecraft can start
            addWindowListener(new java.awt.event.WindowAdapter() {
                public void windowClosed(java.awt.event.WindowEvent e) {
                    latch.countDown();
                }
            });

            // Root panel
            JPanel root = new JPanel(new BorderLayout(8, 8));
            root.setBorder(new EmptyBorder(12, 12, 12, 12));
            setContentPane(root);

            // Top info
            JPanel topPanel = new JPanel(new GridLayout(2, 1, 4, 4));
            serverLabel = new JLabel();
            updateServerLabel();
            topPanel.add(serverLabel);
            topPanel.add(new JLabel("Game dir: " + gameDir));
            root.add(topPanel, BorderLayout.NORTH);

            // Center: progress area + log
            JPanel center = new JPanel(new BorderLayout(6, 6));

            // Progress panel: status + overall bar + per-file bar + speed
            JPanel progressPanel = new JPanel();
            progressPanel.setLayout(new BoxLayout(progressPanel, BoxLayout.Y_AXIS));

            progressBar.setIndeterminate(true);
            progressBar.setStringPainted(true);
            progressBar.setAlignmentX(Component.LEFT_ALIGNMENT);

            dlProgressBar.setStringPainted(true);
            dlProgressBar.setValue(0);
            dlProgressBar.setString("");
            dlProgressBar.setAlignmentX(Component.LEFT_ALIGNMENT);

            lblDlSpeed.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            lblDlSpeed.setForeground(new Color(120, 120, 120));
            lblDlSpeed.setAlignmentX(Component.LEFT_ALIGNMENT);

            progressPanel.add(lblStatus);
            progressPanel.add(Box.createVerticalStrut(4));
            progressPanel.add(progressBar);
            progressPanel.add(Box.createVerticalStrut(2));
            progressPanel.add(dlProgressBar);
            progressPanel.add(Box.createVerticalStrut(2));
            progressPanel.add(lblDlSpeed);

            center.add(progressPanel, BorderLayout.NORTH);

            logArea.setEditable(false);
            logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            logArea.setBackground(new Color(30, 30, 30));
            logArea.setForeground(new Color(200, 200, 200));
            JScrollPane scroll = new JScrollPane(logArea);
            scroll.setBorder(BorderFactory.createTitledBorder("Update log"));
            center.add(scroll, BorderLayout.CENTER);
            root.add(center, BorderLayout.CENTER);

            // Close button (only shown in debug mode; otherwise window auto-closes)
            if (debug) {
                JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
                btnClose.setEnabled(false);
                btnClose.addActionListener(e -> {
                    latch.countDown();
                    dispose();
                });
                bottom.add(btnClose);
                root.add(bottom, BorderLayout.SOUTH);
            }
        }

        // ── Per-file download UI refresh ──────────────────────────

        private void refreshDownloadUI() {
            if (!dlActive) {
                dlProgressBar.setValue(0);
                dlProgressBar.setString("");
                lblDlSpeed.setText(" ");
                return;
            }
            long total = dlTotalBytes;
            long done  = dlDownloadedBytes;
            if (total > 0) {
                int pct = (int) (done * 100 / total);
                if (pct > 100) pct = 100;
                dlProgressBar.setValue(pct);
                dlProgressBar.setString(pct + "%");
                dlProgressBar.setIndeterminate(false);
            } else {
                dlProgressBar.setIndeterminate(true);
                dlProgressBar.setString("");
            }
            long now = System.currentTimeMillis();
            long elapsed = now - dlLastTime;
            if (elapsed >= 400) {
                long bytesDelta = done - dlLastBytes;
                double speed = elapsed > 0 ? bytesDelta * 1000.0 / elapsed : 0;
                lblDlSpeed.setText(formatSpeed(speed));
                dlLastBytes = done;
                dlLastTime  = now;
            }
        }

        private static String formatSpeed(double bytesPerSec) {
            if (bytesPerSec < 0) bytesPerSec = 0;
            if (bytesPerSec >= 1_000_000_000) return String.format("%.1f GB/s", bytesPerSec / 1_000_000_000);
            if (bytesPerSec >= 1_000_000)     return String.format("%.1f MB/s", bytesPerSec / 1_000_000);
            if (bytesPerSec >= 1_000)         return String.format("%.0f KB/s", bytesPerSec / 1_000);
            return String.format("%.0f B/s", bytesPerSec);
        }

        // ── update flow (pure Java HTTP, no external scripts) ─────

        private void startUpdate() {
            dlRefreshTimer.start();
            updateWorker = new UpdateWorker();
            updateWorker.execute();
        }

        /** Performs blocking network and file work; no Swing components are touched here. */
        private UpdateResult performUpdate() throws Exception {
            List<String> serverUrls = serverClient.getServerUrls();
            log("Servers (" + serverUrls.size() + "):");
            for (int i = 0; i < serverUrls.size(); i++) {
                log("  [" + (i + 1) + "] " + serverUrls.get(i));
            }
            log("Game dir: " + gameDir);

            // 1. fetch manifest (with multi-server fallback)
            setStatus("Checking for updates...", true);
            log("Fetching manifest...");
            String manifestJson = serverClient.getWithFallback("/api/v2/manifest");

            // 0. self-update check — must happen before regular file sync
            checkSelfUpdate(manifestJson);

            String filesArray = JsonParser.getArray(manifestJson, "files");
            if (filesArray == null) {
                throw new IOException("Cannot parse manifest");
            }

            List<FileEntry> manifestFiles = parseFileEntries(filesArray);
            log("Manifest contains " + manifestFiles.size() + " file(s)");

            String managedArray = JsonParser.getArray(manifestJson, "managed_paths");
            List<String> managedPaths = JsonParser.parseStringArray(
                    managedArray != null ? managedArray : "");
            log("Managed paths:");
            for (String p : managedPaths) log("  - " + p);

            String excludedArray = JsonParser.getArray(manifestJson, "excluded_paths");
            List<String> excludedPaths = JsonParser.parseStringArray(
                    excludedArray != null ? excludedArray : "");
            if (!excludedPaths.isEmpty()) {
                log("Excluded paths:");
                for (String p : excludedPaths) log("  - " + p);
            }

            // 2. check and download each file
            setOverallProgress(0);
            int total = manifestFiles.size();
            int checked = 0;
            int updated = 0;
            int failed = 0;

            for (FileEntry entry : manifestFiles) {
                checked++;
                String relPath = entry.getPath();

                File localFile = fileManager.resolveManagedFile(relPath);
                if (localFile == null) {
                    log("  [REJECT] " + relPath + " (unsafe manifest path)");
                    failed++;
                    setStatus("Rejected unsafe path: " + checked + "/" + total, false);
                    setOverallProgress(total > 0 ? checked * 95 / total : 100);
                    continue;
                }
                boolean needDownload = false;

                if (!localFile.isFile()) {
                    log("  [MISS]  " + relPath);
                    needDownload = true;
                } else {
                    String localHash = fileManager.sha256(localFile);
                    if (localHash == null) {
                        log("  [WARN]  " + relPath + " (cannot read, re-downloading)");
                        needDownload = true;
                    } else if (!localHash.equals(entry.getSha256())) {
                        log("  [DIFF]  " + relPath + " (hash mismatch)");
                        needDownload = true;
                    } else {
                        log("  [OK]    " + relPath);
                    }
                }

                if (needDownload) {
                    setStatus("Downloading: " + relPath, false);
                    log("         -> Downloading " + relPath + "...");
                    File parent = localFile.getParentFile();
                    if (parent != null && !parent.isDirectory()) parent.mkdirs();
                    File tmpFile = new File(localFile.getPath() + ".tmp");

                    // Track per-file download progress
                    dlTotalBytes = entry.getSize();
                    dlDownloadedBytes = 0;
                    dlActive = true;
                    dlLastBytes = 0;
                    dlLastTime = System.currentTimeMillis();
                    long dlStart = dlLastTime;

                    // URL-encode each path segment for the download URL
                    String encodedPath = ServerClient.encodePath(relPath);
                    boolean ok = serverClient.downloadWithFallback(
                            "/api/files/" + encodedPath, tmpFile);
                    dlActive = false;

                    if (ok) {
                        String dlHash = fileManager.sha256(tmpFile);
                        if (dlHash != null && dlHash.equals(entry.getSha256())) {
                            try {
                                fileManager.replaceDownloadedFile(tmpFile, localFile);
                                long dlElapsed = System.currentTimeMillis() - dlStart;
                                double avgSpeed = dlElapsed > 0 ? entry.getSize() * 1000.0 / dlElapsed : 0;
                                log("         -> Done (" + entry.getSize() + " bytes, " + formatSpeed(avgSpeed) + ")");
                                updated++;
                            } catch (IOException e) {
                                log("  [FAIL]  " + relPath + ": cannot replace file ("
                                        + e.getMessage() + ")");
                                log("  [FAIL]  " + relPath + ": cannot move file");
                                tmpFile.delete();
                                failed++;
                            }
                        } else {
                            log("  [FAIL]  " + relPath + ": hash mismatch after download");
                            tmpFile.delete();
                            failed++;
                        }
                    } else {
                        log("  [FAIL]  " + relPath + ": download failed");
                        tmpFile.delete();
                        failed++;
                    }

                    // Reset per-file progress bar immediately
                    resetDownloadProgress();
                }

                setStatus("Checked: " + checked + "/" + total, false);
                setOverallProgress(total > 0 ? checked * 95 / total : 100);
            }

            // 3. clean stale files
            log("Cleaning stale files...");
            fileManager.cleanStaleFiles(manifestFiles, managedPaths, excludedPaths, this::log);

            return new UpdateResult(updated, failed);
        }

        /** Runs the update off the EDT and applies published UI events on it. */
        private final class UpdateWorker extends SwingWorker<UpdateResult, UiEvent> {
            void emit(UiEvent event) {
                publish(event);
            }

            @Override
            protected UpdateResult doInBackground() throws Exception {
                return performUpdate();
            }

            @Override
            protected void process(List<UiEvent> events) {
                for (UiEvent event : events) applyUiEvent(event);
            }

            @Override
            protected void done() {
                stopDownloadRefreshTimer();
                try {
                    UpdateResult result = get();
                    setOverallProgress(100);
                    if (result.getFailedFiles() > 0) {
                        setStatus("Update finished with " + result.getFailedFiles() + " error(s)", false);
                        log("[FATAL] " + result.getFailedFiles() + " file(s) failed to update, killing Minecraft process...");
                        new javax.swing.Timer(2000, ev -> System.exit(1)).start();
                    } else if (result.getUpdatedFiles() > 0) {
                        setStatus("Updated " + result.getUpdatedFiles() + " file(s), launching Minecraft...", false);
                        autoClose(2000);
                    } else {
                        setStatus("Already up to date, launching Minecraft...", false);
                        autoClose(1000);
                    }
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    showError("Update error: " + cause.getMessage());
                    cause.printStackTrace();
                }
            }
        }

        // ═══════════════════════════════════════════════════════════
        //   Manifest mapping
        // ═══════════════════════════════════════════════════════════

        private static List<FileEntry> parseFileEntries(String filesArray) {
            List<FileEntry> list = new ArrayList<>();
            // Split top-level JSON objects
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

        // ═══════════════════════════════════════════════════════════
        //   Auto close
        // ═══════════════════════════════════════════════════════════

        private void autoClose(int delayMs) {
            runOnEdt(() -> {
                progressBar.setIndeterminate(false);
                if (debug) {
                    // Debug mode: release latch so Minecraft starts, but keep window open
                    latch.countDown();
                    btnClose.setEnabled(true);
                    log("[DEBUG] Update check done. Window stays open for inspection.");
                } else {
                    new javax.swing.Timer(delayMs, e -> {
                        latch.countDown();
                        dispose();
                    }).start();
                }
            });
        }


        // ── Self-update ─────────────────────────────────────────

        /** Check manifest for agent update; download if newer. */
        private void checkSelfUpdate(String manifestJson) {
            String agentObj = JsonParser.getObject(manifestJson, "agent");
            if (agentObj == null) {
                log("  [SKIP]  No agent info in manifest");
                return;
            }
            String agentHash = JsonParser.getString(agentObj, "hash");
            long agentSize = JsonParser.getLong(agentObj, "size", -1);
            if (agentHash == null || agentSize <= 0) {
                log("  [SKIP]  Incomplete agent info in manifest");
                return;
            }

            String myJarPath = getMyJarPath();
            if (myJarPath == null) {
                log("  [SKIP]  Cannot determine agent JAR path");
                return;
            }

            File myJar = new File(myJarPath);
            if (!myJar.isFile()) {
                log("  [SKIP]  Agent JAR not found at: " + myJarPath);
                return;
            }

            log("Checking agent update...");
            log("  My path:    " + myJarPath);
            String myHash = fileManager.sha256(myJar);
            if (myHash == null) {
                log("  [SKIP]  Cannot compute local agent hash");
                return;
            }
            if (myHash.equals(agentHash)) {
                log("  [OK]    Agent is up to date");
                return;
            }

            log("  [UPDATE] New agent version available!");
            log("  Remote: " + agentHash);
            log("  Local:  " + myHash);
            setStatus("Downloading agent update...", false);

            File newJar = new File(myJarPath + ".new");
            if (newJar.exists()) newJar.delete();

            dlTotalBytes = agentSize;
            dlDownloadedBytes = 0;
            dlActive = true;
            dlLastBytes = 0;
            dlLastTime = System.currentTimeMillis();

            boolean ok = serverClient.downloadWithFallback("/api/agent", newJar);
            dlActive = false;
            resetDownloadProgress();

            if (!ok) {
                log("  [FAIL]  Agent download failed");
                newJar.delete();
                return;
            }

            String dlHash = fileManager.sha256(newJar);
            if (dlHash == null || !dlHash.equals(agentHash)) {
                log("  [FAIL]  Agent hash mismatch after download");
                newJar.delete();
                return;
            }

            log("  [OK]    Agent downloaded, will replace on next restart");
        }

        // ── GUI helpers ──────────────────────────────────────────

        private void setStatus(String text, boolean indeterminate) {
            dispatchUiEvent(UiEvent.status(text, indeterminate));
        }

        private void log(String msg) {
            dispatchUiEvent(UiEvent.log(msg));
        }

        private void showError(String msg) {
            runOnEdt(() -> {
                dlRefreshTimer.stop();
                resetDownloadProgress();
                log("[ERROR] " + msg);
                setStatus("Update failed", false);
                progressBar.setIndeterminate(false);
                progressBar.setValue(0);
                JOptionPane.showMessageDialog(this,
                        msg, "Update Error", JOptionPane.ERROR_MESSAGE);
                // Terminate the JVM before Minecraft's main() ever runs.
                // Delay exit by 1s so the fatal log message is painted and visible.
                log("[FATAL] Killing Minecraft process...");
                new javax.swing.Timer(1000, ev -> System.exit(1)).start();
            });
        }
    }
}
