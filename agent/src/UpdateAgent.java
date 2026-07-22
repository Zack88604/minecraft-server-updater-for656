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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UpdateAgent {

    private static final String PROP_SERVER  = "mc-update.server";
    private static final String PROP_GAME_DIR = "mc-update.game-dir";
    private static final String PROP_DEBUG    = "mc-update.debug";

    // ── Agent entry point ────────────────────────────────────────

    public static void premain(String args, Instrumentation inst) {
        boolean debug = false;

        // Parse key=value pairs from -javaagent args (comma-separated)
        // e.g. -javaagent:UpdateAgent.jar=server=http://1.2.3.4:25565,game-dir=C:\mc,debug=true
        if (args != null && !args.isEmpty()) {
            for (String token : args.split(",")) {
                String[] kv = token.split("=", 2);
                if (kv.length == 2) {
                    String key = kv[0].trim();
                    String value = kv[1].trim();
                    switch (key) {
                        case "server":
                            System.setProperty(PROP_SERVER, value);
                            break;
                        case "game-dir":
                            System.setProperty(PROP_GAME_DIR, value);
                            break;
                        case "debug":
                            debug = "true".equalsIgnoreCase(value) || "1".equals(value);
                            break;
                    }
                    if (key.equals(PROP_SERVER)) System.setProperty(PROP_SERVER, value);
                    if (key.equals(PROP_GAME_DIR)) System.setProperty(PROP_GAME_DIR, value);
                }
            }
        }

        // Also check system property for debug
        if (!debug) {
            String dbg = System.getProperty(PROP_DEBUG);
            debug = "true".equalsIgnoreCase(dbg) || "1".equals(dbg);
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

    // ═══════════════════════════════════════════════════════════════
    //  GUI
    // ═══════════════════════════════════════════════════════════════

    static class UpdateGUI extends JFrame {

        private final JLabel     lblStatus   = new JLabel("Checking for updates...");
        private final JProgressBar progressBar = new JProgressBar(0, 100);
        private final JTextArea  logArea     = new JTextArea(8, 50);
        private final JButton    btnClose    = new JButton("Close");

        private String gameDir;
        private String serverUrl;
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
            serverUrl = System.getProperty(PROP_SERVER, "http://localhost:25565");
            gameDir   = System.getProperty(PROP_GAME_DIR, System.getProperty("user.dir", "."));
        }

        private void initUI() {
            setTitle("Minecraft Update Check");
            setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            setSize(520, 380);
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
            topPanel.add(new JLabel("Server: " + serverUrl));
            topPanel.add(new JLabel("Game dir: " + gameDir));
            root.add(topPanel, BorderLayout.NORTH);

            // Center: progress bar + log
            JPanel center = new JPanel(new BorderLayout(6, 6));
            progressBar.setIndeterminate(true);
            progressBar.setStringPainted(true);
            center.add(lblStatus, BorderLayout.NORTH);
            center.add(progressBar, BorderLayout.CENTER);

            logArea.setEditable(false);
            logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            logArea.setBackground(new Color(30, 30, 30));
            logArea.setForeground(new Color(200, 200, 200));
            JScrollPane scroll = new JScrollPane(logArea);
            scroll.setBorder(BorderFactory.createTitledBorder("Update log"));
            center.add(scroll, BorderLayout.SOUTH);
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

        // ── update flow (pure Java HTTP, no external scripts) ─────

        private void startUpdate() {
            new Thread(this::doUpdate, "mc-update-worker").start();
        }

        private void doUpdate() {
            try {
                log("Server:   " + serverUrl);
                log("Game dir: " + gameDir);

                // 1. check version
                setStatus("Checking for updates...", true);
                log("Fetching remote version...");
                String versionJson = httpGet(serverUrl + "/api/version");
                String remoteVersion = jsonGetString(versionJson, "version");
                if (remoteVersion == null) {
                    showError("Cannot get remote version");
                    return;
                }
                log("Remote version: " + remoteVersion);

                String localVersion = readLocalVersion();
                log("Local version:  " + (localVersion.isEmpty() ? "<none>" : localVersion));

                if (remoteVersion.equals(localVersion)) {
                    SwingUtilities.invokeLater(() -> {
                        setStatus("Already up to date, launching Minecraft...", false);
                        progressBar.setIndeterminate(false);
                        progressBar.setValue(100);
                        autoClose(800);
                    });
                    return;
                }

                // 2. fetch manifest
                log("Fetching manifest...");
                String manifestJson = httpGet(serverUrl + "/api/manifest");
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

                // 3. check and download each file
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

                        // URL-encode each path segment for the download URL
                        String encodedPath = encodePath(relPath);
                        boolean ok = httpDownload(serverUrl + "/api/files/" + encodedPath, tmpFile);
                        if (ok) {
                            String dlHash = sha256(tmpFile);
                            if (dlHash.equals(entry.hash)) {
                                // delete target first (Windows renameTo does not overwrite)
                                if (localFile.exists()) localFile.delete();
                                if (tmpFile.renameTo(localFile)) {
                                    log("         -> Done (" + entry.size + " bytes)");
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
                    }

                    setStatus("Checked: " + checked + "/" + total, false);
                    progressBar.setValue(total > 0 ? checked * 95 / total : 100);
                }

                // 4. clean stale files
                log("Cleaning stale files...");
                cleanStaleFiles(manifestFiles, managedPaths, excludedPaths);

                // 5. done — update happened before Minecraft launch, no restart needed
                final int finalUpdated = updated;
                final int finalFailed = failed;
                progressBar.setValue(100);
                writeLocalVersion(remoteVersion);

                if (finalFailed > 0) {
                    SwingUtilities.invokeLater(() -> {
                        setStatus("Update finished with " + finalFailed + " error(s)", false);
                        autoClose(3000);
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
                showError("Update error: " + e.getMessage());
                e.printStackTrace();
                latch.countDown();
            }
        }

        // ═══════════════════════════════════════════════════════════
        //  Network utilities
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

        private boolean httpDownload(String urlStr, File dest) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(60000);
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
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

        private String readLocalVersion() {
            File f = new File(gameDir, ".update_version");
            if (!f.isFile()) return "";
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                return r.readLine().trim();
            } catch (IOException e) {
                return "";
            }
        }

        private void writeLocalVersion(String version) {
            File f = new File(gameDir, ".update_version");
            try (FileWriter w = new FileWriter(f)) {
                w.write(version);
            } catch (IOException ignored) {}
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
                    // 目录路径：递归清理该目录
                    File dir = new File(gameDir, mp);
                    if (dir.isDirectory()) {
                        deleteStaleInDir(dir, gameDir, manifestSet, excludedPaths);
                    }
                } else {
                    // 精确文件路径：检查该文件是否在 manifest 中
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
                    // 目录排除：路径以该目录开头则排除
                    if (relPath.equals(ep.substring(0, ep.length() - 1))
                            || relPath.startsWith(ep)) {
                        return true;
                    }
                } else {
                    // 精确文件排除
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
                    // 检查是否在排除列表中
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
            SwingUtilities.invokeLater(() -> {
                log("[ERROR] " + msg);
                setStatus("Update failed", false);
                progressBar.setIndeterminate(false);
                progressBar.setValue(0);
                JOptionPane.showMessageDialog(this,
                        msg, "Update Error", JOptionPane.ERROR_MESSAGE);
                latch.countDown();
                dispose();
            });
        }
    }
}
