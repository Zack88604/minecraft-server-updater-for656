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

import com.zack88604.autoupdater.application.UpdateEvent;
import com.zack88604.autoupdater.application.UpdateService;
import com.zack88604.autoupdater.config.AgentConfig;
import com.zack88604.autoupdater.domain.UpdateResult;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.lang.instrument.Instrumentation;
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

        // Keep legacy properties populated for the compatible launcher entry point.
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
        private UpdateService updateService;
        private List<String> serverUrls;
        private String currentServer;
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
            updateService = new UpdateService(gameDir, configuredServers);
            serverUrls = updateService.getServerUrls();
            currentServer = updateService.getCurrentServer();
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
            return currentServer;
        }

        /** Update the server label in the top panel. */
        private void updateServerLabel() {
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

        /** Translate toolkit-neutral use-case events into the existing Swing event stream. */
        private void onUpdateEvent(UpdateEvent event) {
            if (event instanceof UpdateEvent.StatusChanged) {
                UpdateEvent.StatusChanged status = (UpdateEvent.StatusChanged) event;
                setStatus(status.getStatus(), status.isIndeterminate());
            } else if (event instanceof UpdateEvent.LogMessage) {
                log(((UpdateEvent.LogMessage) event).getMessage());
            } else if (event instanceof UpdateEvent.OverallProgressChanged) {
                setOverallProgress(((UpdateEvent.OverallProgressChanged) event).getPercentage());
            } else if (event instanceof UpdateEvent.ServerChanged) {
                UpdateEvent.ServerChanged server = (UpdateEvent.ServerChanged) event;
                serverUrls = server.getServerUrls();
                currentServer = server.getCurrentServer();
                updateServerLabel();
            } else if (event instanceof UpdateEvent.DownloadProgressChanged) {
                UpdateEvent.DownloadProgressChanged progress =
                        (UpdateEvent.DownloadProgressChanged) event;
                if (!progress.isActive()) {
                    dlActive = false;
                    resetDownloadProgress();
                    return;
                }
                boolean starting = !dlActive;
                dlTotalBytes = progress.getTotalBytes();
                dlDownloadedBytes = progress.getDownloadedBytes();
                dlActive = true;
                if (starting) {
                    dlLastBytes = 0;
                    dlLastTime = System.currentTimeMillis();
                }
            }
        }

        private void startUpdate() {
            dlRefreshTimer.start();
            updateWorker = new UpdateWorker();
            updateWorker.execute();
        }

        /** Runs the update off the EDT and applies published UI events on it. */
        private final class UpdateWorker extends SwingWorker<UpdateResult, UiEvent> {
            void emit(UiEvent event) {
                publish(event);
            }

            @Override
            protected UpdateResult doInBackground() throws Exception {
                return updateService.run(UpdateGUI.this::onUpdateEvent);
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
