import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * HTTP client for the update server with multi-server fallback.
 *
 * Tracks the currently active server and reports log lines, server switches
 * and per-download progress through {@link UpdateListener} events. Contains no
 * Swing dependency.
 */
class ServerClient {

    /** Emit a download-progress event at most this often while streaming. */
    private static final long PROGRESS_EMIT_INTERVAL_MS = 500;

    private final List<String> serverUrls;
    private UpdateListener listener;
    private int currentServerIndex = 0;

    ServerClient(List<String> serverUrls) {
        this.serverUrls = serverUrls;
    }

    void setListener(UpdateListener listener) {
        this.listener = listener;
    }

    List<String> getServerUrls() {
        return serverUrls;
    }

    /** Get the currently active server URL. */
    String getCurrentServer() {
        return serverUrls.get(currentServerIndex);
    }

    /** HTTP GET with fallback: try each server in order until one succeeds. */
    String httpGetWithFallback(String path) throws IOException {
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
                    switchToServer(idx);
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
        HttpURLConnection conn = (HttpURLConnection) URI.create(urlStr).toURL().openConnection();
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
    boolean httpDownloadWithFallback(String path, File dest,
                                     String displayPath, DownloadProgress.Kind kind) {
        int startIndex = currentServerIndex;
        for (int i = 0; i < serverUrls.size(); i++) {
            int idx = (startIndex + i) % serverUrls.size();
            String url = serverUrls.get(idx) + path;
            if (idx != currentServerIndex) {
                log("Trying server: " + serverUrls.get(idx));
            }
            if (httpDownload(url, dest, displayPath, kind)) {
                // Success — switch to this server for subsequent requests
                if (idx != currentServerIndex) {
                    switchToServer(idx);
                }
                return true;
            }
            log("  [WARN]  Download failed from: " + serverUrls.get(idx));
        }
        return false;
    }

    /** Download from an absolute URL (our servers, or Maven Central for the
     *  JavaFX runtime worker) into {@code dest}, emitting progress events.
     *  Package-private so {@link JavaFxRuntimeManager} can reuse the same
     *  streaming/progress logic for runtime jars. */
    boolean httpDownload(String urlStr, File dest,
                         String displayPath, DownloadProgress.Kind kind) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(urlStr).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(60000);
            // Use Content-Length from server if available (more accurate)
            long total = conn.getContentLength();
            long downloaded = 0;
            long lastEmitAt = System.currentTimeMillis();
            long lastBytes = 0;
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    downloaded += n;
                    long now = System.currentTimeMillis();
                    if (now - lastEmitAt >= PROGRESS_EMIT_INTERVAL_MS) {
                        emitProgress(displayPath, kind, downloaded, total,
                                (downloaded - lastBytes) * 1000.0 / (now - lastEmitAt));
                        lastEmitAt = now;
                        lastBytes = downloaded;
                    }
                }
            } finally {
                conn.disconnect();
            }
            // Final snapshot so the UI sees 100% / total
            long now = System.currentTimeMillis();
            double finalSpeed = (now - lastEmitAt) > 0
                    ? (downloaded - lastBytes) * 1000.0 / (now - lastEmitAt)
                    : 0;
            emitProgress(displayPath, kind, downloaded, total, finalSpeed);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Emit a download-progress event carrying the current object and speed. */
    private void emitProgress(String displayPath, DownloadProgress.Kind kind,
                              long downloaded, long total, double speed) {
        if (listener != null) {
            listener.onUpdateEvent(new UpdateEvent.DownloadProgressChanged(
                    DownloadProgress.active(displayPath, kind, downloaded, total, speed)));
        }
    }

    /** Record a fallback switch and tell listeners about the new server state. */
    private void switchToServer(int newIndex) {
        log("Switched to server: " + serverUrls.get(newIndex));
        currentServerIndex = newIndex;
        if (listener != null) {
            listener.onUpdateEvent(new UpdateEvent.ServerChanged(serverUrls, getCurrentServer()));
        }
    }

    /** URL-encode each segment of a path (e.g. "mods/my mod.jar" -> "mods/my%20mod.jar") */
    static String encodePath(String relPath) {
        StringBuilder sb = new StringBuilder();
        for (String seg : relPath.split("/")) {
            if (sb.length() > 0) sb.append('/');
            sb.append(URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    private void log(String msg) {
        if (listener != null) listener.onUpdateEvent(new UpdateEvent.LogMessage(msg));
    }
}
