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

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.*;
import java.lang.instrument.Instrumentation;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class UpdateAgent {

    private static final String PROP_SERVER  = "mc-update.server";
    private static final String PROP_GAME_DIR = "mc-update.game-dir";
    private static final String PROP_DEBUG    = "mc-update.debug";
    private static final String CONFIG_FILE   = "mc-update.properties";
    private static final String DEFAULT_SERVER = "http://localhost:25565";

    // ── Agent entry point ────────────────────────────────────────

    public static void premain(String args, Instrumentation inst) {
        // 1. Parse agent args into a map (don't set system properties yet)
        Map<String, String> agentArgs = parseAgentArgs(args);
        boolean admin = "true".equalsIgnoreCase(agentArgs.get("admin"));

        // 2. Resolve game directory: agent arg > -D system property > user.dir
        String gameDir = coalesce(
            agentArgs.get("game-dir"),
            System.getProperty(PROP_GAME_DIR),
            System.getProperty("user.dir", ".")
        );
        System.setProperty(PROP_GAME_DIR, gameDir);

        // 3. Load persistent config from game directory
        Properties fileConfig = loadConfigFile(new File(gameDir));

        // 4. Merge config with mode-dependent priority
        //    Normal:  file config > agent args > -D system props > defaults
        //    Admin:   agent args  > -D system props > file config  > defaults (original)
        String server;
        boolean debug;

        if (admin) {
            server = coalesce(
                agentArgs.get("server"),
                System.getProperty(PROP_SERVER),
                fileConfig.getProperty("server"),
                DEFAULT_SERVER
            );
            String debugStr = coalesce(
                agentArgs.get("debug"),
                System.getProperty(PROP_DEBUG),
                fileConfig.getProperty("debug"),
                "false"
            );
            debug = "true".equalsIgnoreCase(debugStr) || "1".equals(debugStr);
        } else {
            server = coalesce(
                fileConfig.getProperty("server"),
                agentArgs.get("server"),
                System.getProperty(PROP_SERVER),
                DEFAULT_SERVER
            );
            String debugStr = coalesce(
                fileConfig.getProperty("debug"),
                agentArgs.get("debug"),
                System.getProperty(PROP_DEBUG),
                "false"
            );
            debug = "true".equalsIgnoreCase(debugStr) || "1".equals(debugStr);
        }

        System.setProperty(PROP_SERVER, server);
        if (debug) {
            System.setProperty(PROP_DEBUG, "true");
        }

        // Block premain until update check finishes, then allow Minecraft to start
        CountDownLatch latch = new CountDownLatch(1);
        final boolean finalDebug = debug;
        SwingUtilities.invokeLater(() -> new UpdateGUI(latch, finalDebug));

        try {
            latch.await();  // block until update check completes
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Agent args parser ─────────────────────────────────────

    /** Parse comma-separated key=value pairs from -javaagent args. Never returns null. */
    private static Map<String, String> parseAgentArgs(String args) {
        Map<String, String> map = new LinkedHashMap<>();
        if (args != null && !args.isEmpty()) {
            for (String token : args.split(",")) {
                String[] kv = token.split("=", 2);
                if (kv.length == 2) {
                    map.put(kv[0].trim(), kv[1].trim());
                }
            }
        }
        return map;
    }

    // ── Value coalescing ──────────────────────────────────────

    /** Return the first non-null, non-empty value from the given candidates. */
    private static String coalesce(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    // ── Persistent config file ─────────────────────────────────

    /** Load mc-update.properties from the given directory. Never returns null. */
    static Properties loadConfigFile(File dir) {
        Properties props = new Properties();
        File configFile = new File(dir, CONFIG_FILE);
        if (configFile.isFile()) {
            try (FileInputStream fis = new FileInputStream(configFile)) {
                props.load(fis);
            } catch (IOException ignored) {}
        }
        return props;
    }

    // ── Utility: get own JAR path ──────────────────────────────

    private static String getMyJarPath() {
        try {
            String path = UpdateAgent.class.getProtectionDomain()
                    .getCodeSource().getLocation().getPath();
            return URLDecoder.decode(path, "UTF-8");
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
        private List<String> serverUrls;
        private int currentServerIndex = 0;
        private final CountDownLatch latch;
        private final boolean debug;

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

            // serverUrls: parse comma-separated list from system property or config file
            String serverProp = System.getProperty(PROP_SERVER);
            if (serverProp == null || serverProp.isEmpty()) {
                Properties fc = loadConfigFile(new File(gameDir));
                serverProp = fc.getProperty("server");
            }
            if (serverProp == null || serverProp.isEmpty()) {
                serverProp = DEFAULT_SERVER;
            }
            serverUrls = parseServerList(serverProp);
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
            return serverUrls.get(currentServerIndex);
        }

        /** Try the next server in the list; returns true if there is another to try. */
        private boolean tryNextServer() {
            if (currentServerIndex + 1 < serverUrls.size()) {
                currentServerIndex++;
                log("Switching to fallback server: " + currentServer());
                SwingUtilities.invokeLater(() -> {
                    // Update server display in top panel
                    Component topPanel = ((JPanel) getContentPane().getComponent(0));
                    if (topPanel instanceof JPanel) {
                        JLabel serverLabel = (JLabel) ((JPanel) topPanel).getComponent(0);
                        serverLabel.setText("Server: " + currentServer());
                    }
                });
                return true;
            }
            return false;
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
            String serverDisplay = serverUrls.size() <= 1
                    ? "Server: " + currentServer()
                    : "Servers (" + serverUrls.size() + "): " + currentServer();
            topPanel.add(new JLabel(serverDisplay));
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
            new Thread(this::doUpdate, "mc-update-worker").start();
        }

        private void doUpdate() {
            try {
                log("Servers (" + serverUrls.size() + "):");
                for (int i = 0; i < serverUrls.size(); i++) {
                    log("  [" + (i + 1) + "] " + serverUrls.get(i));
                }
                log("Game dir: " + gameDir);

                // 1. fetch manifest (with multi-server fallback)
                setStatus("Checking for updates...", true);
                log("Fetching manifest...");
                String manifestJson = httpGetWithFallback("/api/v2/manifest");

                // 0. self-update check — must happen before regular file sync
                checkSelfUpdate(manifestJson);

                String filesArray = jsonGetArray(manifestJson, "files");
                if (filesArray == null) {
                    showError("Cannot parse manifest");
                    return;
                }

                List<FileEntry> manifestFiles = parseFileEntries(filesArray);
                log("Manifest contains " + manifestFiles.size() + " file(s)");

                String managedArray = jsonGetArray(manifestJson, "managed_paths");
                List<String> managedPaths = parseStringArray(managedArray != null ? managedArray : "");
                log("Managed paths:");
                for (String p : managedPaths) log("  - " + p);

                String excludedArray = jsonGetArray(manifestJson, "excluded_paths");
                List<String> excludedPaths = parseStringArray(excludedArray != null ? excludedArray : "");
                if (!excludedPaths.isEmpty()) {
                    log("Excluded paths:");
                    for (String p : excludedPaths) log("  - " + p);
                }

                // 2. check and download each file
                progressBar.setIndeterminate(false);
                progressBar.setValue(0);
                int total = manifestFiles.size();
                int checked = 0;
                int updated = 0;
                int failed = 0;

                for (FileEntry entry : manifestFiles) {
                    checked++;
                    String relPath = entry.path;
                    // normalize separators for current OS
                    String osPath = relPath.replace('/', File.separatorChar);
                    File localFile = new File(gameDir, osPath);
                    boolean needDownload = false;

                    if (!localFile.isFile()) {
                        log("  [MISS]  " + relPath);
                        needDownload = true;
                    } else {
                        String localHash = sha256(localFile);
                        if (!localHash.equals(entry.hash)) {
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
                        dlTotalBytes = entry.size;
                        dlDownloadedBytes = 0;
                        dlActive = true;
                        dlLastBytes = 0;
                        dlLastTime = System.currentTimeMillis();
                        long dlStart = dlLastTime;

                        // URL-encode each path segment for the download URL
                        String encodedPath = encodePath(relPath);
                        boolean ok = httpDownloadWithFallback("/api/files/" + encodedPath, tmpFile);
                        dlActive = false;

                        if (ok) {
                            String dlHash = sha256(tmpFile);
                            if (dlHash.equals(entry.hash)) {
                                // delete target first (Windows renameTo does not overwrite)
                                if (localFile.exists()) localFile.delete();
                                if (tmpFile.renameTo(localFile)) {
                                    long dlElapsed = System.currentTimeMillis() - dlStart;
                                    double avgSpeed = dlElapsed > 0 ? entry.size * 1000.0 / dlElapsed : 0;
                                    log("         -> Done (" + entry.size + " bytes, " + formatSpeed(avgSpeed) + ")");
                                    updated++;
                                } else {
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
                        SwingUtilities.invokeLater(() -> {
                            dlProgressBar.setValue(0);
                            dlProgressBar.setString("");
                            lblDlSpeed.setText(" ");
                        });
                    }

                    setStatus("Checked: " + checked + "/" + total, false);
                    progressBar.setValue(total > 0 ? checked * 95 / total : 100);
                }

                // 3. clean stale files
                log("Cleaning stale files...");
                cleanStaleFiles(manifestFiles, managedPaths, excludedPaths);

                // 4. done — update happened before Minecraft launch, no restart needed
                dlRefreshTimer.stop();
                final int finalUpdated = updated;
                final int finalFailed = failed;
                progressBar.setValue(100);

                if (finalFailed > 0) {
                    SwingUtilities.invokeLater(() -> {
                        setStatus("Update finished with " + finalFailed + " error(s)", false);
                        log("[FATAL] " + finalFailed + " file(s) failed to update, killing Minecraft process...");
                        new javax.swing.Timer(2000, ev -> System.exit(1)).start();
                    });
                } else if (finalUpdated > 0) {
                    SwingUtilities.invokeLater(() -> {
                        setStatus("Updated " + finalUpdated + " file(s), launching Minecraft...", false);
                        autoClose(2000);
                    });
                } else {
                    SwingUtilities.invokeLater(() -> {
                        setStatus("Already up to date, launching Minecraft...", false);
                        autoClose(1000);
                    });
                }

            } catch (Exception e) {
                dlRefreshTimer.stop();
                showError("Update error: " + e.getMessage());
                e.printStackTrace();
                // showError will System.exit() — do NOT release the latch
            }
        }

        // ═══════════════════════════════════════════════════════════
        //  Network utilities (multi-server with fallback)
        // ═══════════════════════════════════════════════════════════

        /** URL-encode each segment of a path (e.g. "mods/my mod.jar" -> "mods/my%20mod.jar") */
        private static String encodePath(String relPath) {
            StringBuilder sb = new StringBuilder();
            for (String seg : relPath.split("/")) {
                if (sb.length() > 0) sb.append('/');
                try {
                    sb.append(URLEncoder.encode(seg, "UTF-8").replace("+", "%20"));
                } catch (UnsupportedEncodingException e) {
                    sb.append(seg);
                }
            }
            return sb.toString();
        }

        /** HTTP GET with fallback: try each server in order until one succeeds. */
        private String httpGetWithFallback(String path) throws IOException {
            IOException lastException = null;
            int startIndex = currentServerIndex;
            // Try from current server through the end, then wrap around
            for (int i = 0; i < serverUrls.size(); i++) {
                int idx = (startIndex + i) % serverUrls.size();
                String url = serverUrls.get(idx) + path;
                try {
                    if (idx != currentServerIndex) {
                        log("Trying server: " + serverUrls.get(idx));
                    }
                    String result = httpGet(url);
                    // Success — switch to this server for subsequent requests
                    if (idx != currentServerIndex) {
                        log("Switched to server: " + serverUrls.get(idx));
                        currentServerIndex = idx;
                        SwingUtilities.invokeLater(() -> {
                            Component topPanel = ((JPanel) getContentPane().getComponent(0));
                            if (topPanel instanceof JPanel) {
                                JLabel serverLabel = (JLabel) ((JPanel) topPanel).getComponent(0);
                                String display = serverUrls.size() <= 1
                                        ? "Server: " + currentServer()
                                        : "Servers (" + serverUrls.size() + "): " + currentServer();
                                serverLabel.setText(display);
                            }
                        });
                    }
                    return result;
                } catch (IOException e) {
                    lastException = e;
                    log("  [WARN]  Server unreachable: " + serverUrls.get(idx));
                }
            }
            throw lastException != null ? lastException
                    : new IOException("All servers unreachable");
        }

        private String httpGet(String urlStr) throws IOException {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Accept", "application/json");
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                return sb.toString();
            } finally {
                conn.disconnect();
            }
        }

        /** HTTP download with fallback: try each server in order until one succeeds. */
        private boolean httpDownloadWithFallback(String path, File dest) {
            int startIndex = currentServerIndex;
            for (int i = 0; i < serverUrls.size(); i++) {
                int idx = (startIndex + i) % serverUrls.size();
                String url = serverUrls.get(idx) + path;
                if (idx != currentServerIndex) {
                    log("Trying server: " + serverUrls.get(idx));
                }
                if (httpDownload(url, dest)) {
                    // Success — switch to this server for subsequent requests
                    if (idx != currentServerIndex) {
                        log("Switched to server: " + serverUrls.get(idx));
                        currentServerIndex = idx;
                    }
                    return true;
                }
                log("  [WARN]  Download failed from: " + serverUrls.get(idx));
            }
            return false;
        }

        private boolean httpDownload(String urlStr, File dest) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(60000);
                // Use Content-Length from server if available (more accurate)
                int contentLength = conn.getContentLength();
                if (contentLength > 0) dlTotalBytes = contentLength;
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        dlDownloadedBytes += n;
                    }
                } finally {
                    conn.disconnect();
                }
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        // ═══════════════════════════════════════════════════════════
        //   File utilities
        // ═══════════════════════════════════════════════════════════

        private String sha256(File file) {
            try (FileInputStream fis = new FileInputStream(file)) {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) != -1) md.update(buf, 0, n);
                byte[] digest = md.digest();
                StringBuilder sb = new StringBuilder();
                for (byte b : digest) sb.append(String.format("%02x", b));
                return sb.toString();
            } catch (Exception e) {
                return "";
            }
        }

        // ═══════════════════════════════════════════════════════════
        //   Lightweight JSON parser (no external deps)
        // ═══════════════════════════════════════════════════════════

        private static String jsonGetString(String json, String key) {
            Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"");
            Matcher m = p.matcher(json);
            return m.find() ? m.group(1) : null;
        }

        /** Extract an integer value for a key (unquoted number) */
        private static int jsonGetInt(String json, String key, int defaultVal) {
            Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)");
            Matcher m = p.matcher(json);
            if (m.find()) {
                try { return Integer.parseInt(m.group(1)); }
                catch (NumberFormatException ignored) {}
            }
            return defaultVal;
        }

        /** Extract a long integer value for a key */
        private static long jsonGetLong(String json, String key, long defaultVal) {
            Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)");
            Matcher m = p.matcher(json);
            if (m.find()) {
                try { return Long.parseLong(m.group(1)); }
                catch (NumberFormatException ignored) {}
            }
            return defaultVal;
        }

        /** Extract a JSON object value for a key (e.g. "agent": {...}) */
        private static String jsonGetObject(String json, String key) {
            int k = json.indexOf("\"" + key + "\"");
            if (k < 0) return null;
            int start = json.indexOf('{', k);
            if (start < 0) return null;
            int depth = 1, i = start + 1;
            while (i < json.length() && depth > 0) {
                char c = json.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') depth--;
                i++;
            }
            return json.substring(start, i);
        }

        private static String jsonGetArray(String json, String key) {
            int k = json.indexOf("\"" + key + "\"");
            if (k < 0) return null;
            int start = json.indexOf('[', k);
            if (start < 0) return null;
            int depth = 1, i = start + 1;
            while (i < json.length() && depth > 0) {
                char c = json.charAt(i);
                if (c == '[') depth++;
                else if (c == ']') depth--;
                i++;
            }
            return json.substring(start + 1, i - 1).trim();
        }

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
                        String path = jsonGetString(obj, "path");
                        String hash = jsonGetString(obj, "hash");
                        int size = jsonGetInt(obj, "size", -1);
                        if (path != null && hash != null && size >= 0) {
                            list.add(new FileEntry(path, hash, size));
                        }
                        start = -1;
                    }
                }
            }
            return list;
        }

        private static List<String> parseStringArray(String arrayStr) {
            List<String> list = new ArrayList<>();
            if (arrayStr.isEmpty()) return list;
            Pattern p = Pattern.compile("\"([^\"]*)\"");
            Matcher m = p.matcher(arrayStr);
            while (m.find()) list.add(m.group(1));
            // handle bare '*' wildcard
            if (list.isEmpty() && !arrayStr.isEmpty()) list.add("*");
            return list;
        }

        // ═══════════════════════════════════════════════════════════
        //  Cleanup
        // ═══════════════════════════════════════════════════════════

        private void cleanStaleFiles(List<FileEntry> manifestFiles, List<String> managedPaths,
                                      List<String> excludedPaths) {
            Set<String> manifestSet = new HashSet<>();
            for (FileEntry e : manifestFiles) manifestSet.add(e.path);
            for (String mp : managedPaths) {
                if (mp.equals("*")) continue;
                if (mp.endsWith("/")) {
                    // Directory path: recursively clean this directory
                    File dir = new File(gameDir, mp);
                    if (dir.isDirectory()) {
                        deleteStaleInDir(dir, gameDir, manifestSet, excludedPaths);
                    }
                } else {
                    // Exact file path: check if this file is in manifest
                    String normalizedPath = mp.replace('/', File.separatorChar);
                    File file = new File(gameDir, normalizedPath);
                    if (file.isFile() && !file.getName().startsWith(".")) {
                        String rel = mp.replace('\\', '/');
                        if (isExcluded(rel, excludedPaths)) {
                            log("  [SKIP]  " + mp + " (excluded)");
                            continue;
                        }
                        if (!manifestSet.contains(rel)) {
                            log("  [DEL]   " + rel + " (not in manifest)");
                            file.delete();
                        }
                    }
                }
            }
        }

        private boolean isExcluded(String relPath, List<String> excludedPaths) {
            if (excludedPaths == null || excludedPaths.isEmpty()) return false;
            for (String ep : excludedPaths) {
                if (ep.equals("*")) continue;
                if (ep.endsWith("/")) {
                    // Directory exclusion: path starting with this prefix is excluded
                    if (relPath.equals(ep.substring(0, ep.length() - 1))
                            || relPath.startsWith(ep)) {
                        return true;
                    }
                } else {
                    // Exact file exclusion
                    if (relPath.equals(ep)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private void deleteStaleInDir(File dir, String baseDir, Set<String> manifestSet,
                                       List<String> excludedPaths) {
            File[] children = dir.listFiles();
            if (children == null) return;
            for (File child : children) {
                if (child.isDirectory()) {
                    deleteStaleInDir(child, baseDir, manifestSet, excludedPaths);
                } else if (child.isFile() && !child.getName().startsWith(".")) {
                    String rel = child.getAbsolutePath()
                            .substring(new File(baseDir).getAbsolutePath().length() + 1)
                            .replace('\\', '/');
                    // Check if excluded
                    if (isExcluded(rel, excludedPaths)) {
                        log("  [SKIP]  " + rel + " (excluded)");
                        continue;
                    }
                    if (!manifestSet.contains(rel)) {
                        log("  [DEL]   " + rel + " (not in manifest)");
                        child.delete();
                    }
                }
            }
        }

        // ═══════════════════════════════════════════════════════════
        //   Auto close
        // ═══════════════════════════════════════════════════════════

        private void autoClose(int delayMs) {
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
        }

        // ── Data class ────────────────────────────────────────────

        static class FileEntry {
            final String path, hash;
            final int size;
            FileEntry(String path, String hash, int size) {
                this.path = path; this.hash = hash; this.size = size;
            }
        }

        // ── Self-update ─────────────────────────────────────────

        /** Check manifest for agent update; download if newer; schedule post-exit replace. */
        private void checkSelfUpdate(String manifestJson) {
            String agentObj = jsonGetObject(manifestJson, "agent");
            if (agentObj == null) {
                log("  [SKIP]  No agent info in manifest");
                return;
            }
            String agentHash = jsonGetString(agentObj, "hash");
            long agentSize = jsonGetLong(agentObj, "size", -1);
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
            String myHash = sha256(myJar);
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

            boolean ok = httpDownloadWithFallback("/api/agent", newJar);
            dlActive = false;
            SwingUtilities.invokeLater(() -> {
                dlProgressBar.setValue(0);
                dlProgressBar.setString("");
                lblDlSpeed.setText(" ");
            });

            if (!ok) {
                log("  [FAIL]  Agent download failed");
                newJar.delete();
                return;
            }

            String dlHash = sha256(newJar);
            if (!dlHash.equals(agentHash)) {
                log("  [FAIL]  Agent hash mismatch after download");
                newJar.delete();
                return;
            }

            log("  [OK]    Agent downloaded, will replace on next restart");
            scheduleSelfReplace(myJarPath, newJar.getAbsolutePath());
        }

        /** Register a shutdown hook that extracts a helper class from our own JAR and
         *  spawns a detached Java process to replace the agent JAR after JVM exit.
         *  This avoids cmd.exe / batch scripts that may trigger antivirus on Windows. */
        private void scheduleSelfReplace(String oldJarPath, String newJarPath) {
            try {
                // Extract ReplaceHelper.class from our own JAR into a temp dir
                File tempDir = new File(System.getProperty("java.io.tmpdir"), "mc-update-helper");
                if (!tempDir.isDirectory()) tempDir.mkdirs();
                extractReplaceHelper(oldJarPath, tempDir);

                // Find java executable from the same JRE that is running us
                String javaHome = System.getProperty("java.home");
                boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
                String javaExe = javaHome + File.separator + "bin" + File.separator
                        + (isWindows ? "java.exe" : "java");

                // On Windows, prefer javaw.exe (no console window) if available
                if (isWindows) {
                    File javawExe = new File(javaHome + File.separator + "bin" + File.separator
                            + "javaw.exe");
                    if (javawExe.isFile()) javaExe = javawExe.getAbsolutePath();
                }

                String[] cmd = new String[]{
                        javaExe, "-cp", tempDir.getAbsolutePath(),
                        "ReplaceHelper", oldJarPath, newJarPath
                };

                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        new ProcessBuilder(cmd).inheritIO().start();
                    } catch (IOException ignored) {}
                }, "mc-agent-replace-hook"));

                log("  [INFO]  Replace scheduled via: " + javaExe);
            } catch (Exception e) {
                log("  [WARN]  Failed to schedule agent replacement: " + e.getMessage());
            }
        }

        /** Extract ReplaceHelper.class from our JAR into tempDir so it can run independently
         *  (reading from a JAR that is about to be replaced would fail on Windows). */
        private void extractReplaceHelper(String jarPath, File tempDir) {
            try (JarFile jar = new JarFile(jarPath)) {
                JarEntry entry = jar.getJarEntry("ReplaceHelper.class");
                if (entry == null) {
                    log("  [WARN]  ReplaceHelper.class not found in JAR");
                    return;
                }
                File outFile = new File(tempDir, "ReplaceHelper.class");
                try (InputStream in = jar.getInputStream(entry);
                     FileOutputStream out = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
            } catch (IOException e) {
                log("  [WARN]  Cannot extract ReplaceHelper: " + e.getMessage());
            }
        }

        // ── GUI helpers ──────────────────────────────────────────

        private void setStatus(String text, boolean indeterminate) {
            SwingUtilities.invokeLater(() -> {
                lblStatus.setText(text);
                progressBar.setIndeterminate(indeterminate);
            });
        }

        private void log(String msg) {
            SwingUtilities.invokeLater(() -> {
                logArea.append(msg + "\n");
                logArea.setCaretPosition(logArea.getDocument().getLength());
            });
        }

        private void showError(String msg) {
            dlRefreshTimer.stop();
            SwingUtilities.invokeLater(() -> {
                dlProgressBar.setValue(0);
                dlProgressBar.setString("");
                lblDlSpeed.setText(" ");
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
