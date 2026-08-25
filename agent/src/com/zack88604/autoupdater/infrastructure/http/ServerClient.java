package com.zack88604.autoupdater.infrastructure.http;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * HTTP client that keeps an ordered set of update servers and retries requests
 * against the next server when the active one is unavailable.
 */
public final class ServerClient {

    /**
     * Receives infrastructure events without coupling this client to a GUI toolkit.
     */
    public interface Listener {
        void onLog(String message);

        void onServerChanged(List<String> serverUrls, String currentServer);

        void onDownloadProgress(long totalBytes, long downloadedBytes);

        /** Pause or cancel an in-flight transfer at a safe checkpoint. */
        default void checkpoint() {
            // Callers without lifecycle control keep the legacy behavior.
        }
    }

    private final List<String> serverUrls;
    private final Listener listener;
    private int currentServerIndex;

    public ServerClient(List<String> serverUrls, Listener listener) {
        this.serverUrls = Collections.unmodifiableList(new ArrayList<>(serverUrls));
        this.listener = listener;
    }

    public List<String> getServerUrls() {
        return serverUrls;
    }

    public String getCurrentServer() {
        return serverUrls.get(currentServerIndex);
    }

    /** URL-encode each path segment while preserving path separators. */
    public static String encodePath(String relativePath) {
        StringBuilder encoded = new StringBuilder();
        for (String segment : relativePath.split("/")) {
            if (encoded.length() > 0) {
                encoded.append('/');
            }
            encoded.append(URLEncoder.encode(segment, StandardCharsets.UTF_8)
                    .replace("+", "%20"));
        }
        return encoded.toString();
    }

    /** Fetch a UTF-8 response, failing over through each configured server. */
    public String getWithFallback(String path) throws IOException {
        IOException lastException = null;
        int startIndex = currentServerIndex;
        for (int i = 0; i < serverUrls.size(); i++) {
            listener.checkpoint();
            int index = (startIndex + i) % serverUrls.size();
            String server = serverUrls.get(index);
            try {
                if (index != currentServerIndex) {
                    listener.onLog("Trying server: " + server);
                }
                String response = get(server + path);
                switchServerIfNeeded(index);
                return response;
            } catch (IOException e) {
                lastException = e;
                listener.onLog("  [WARN]  Server unreachable: " + server);
            }
        }
        throw lastException != null ? lastException
                : new IOException("All servers unreachable");
    }

    /** Download a file, failing over through each configured server. */
    public boolean downloadWithFallback(String path, File destination) {
        int startIndex = currentServerIndex;
        long[] downloadedBytes = {0};
        for (int i = 0; i < serverUrls.size(); i++) {
            listener.checkpoint();
            int index = (startIndex + i) % serverUrls.size();
            String server = serverUrls.get(index);
            if (index != currentServerIndex) {
                listener.onLog("Trying server: " + server);
            }
            if (download(server + path, destination, downloadedBytes)) {
                switchServerIfNeeded(index);
                return true;
            }
            listener.onLog("  [WARN]  Download failed from: " + server);
        }
        return false;
    }

    private void switchServerIfNeeded(int index) {
        if (index == currentServerIndex) {
            return;
        }
        currentServerIndex = index;
        String server = serverUrls.get(index);
        listener.onLog("Switched to server: " + server);
        listener.onServerChanged(serverUrls, server);
    }

    private String get(String url) throws IOException {
        listener.checkpoint();
        HttpURLConnection connection =
                (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("Accept", "application/json");
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                listener.checkpoint();
                response.append(line);
            }
            return response.toString();
        } finally {
            connection.disconnect();
        }
    }

    private boolean download(String url, File destination, long[] downloadedBytes) {
        try {
            listener.checkpoint();
            HttpURLConnection connection =
                    (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(60000);
            int contentLength = connection.getContentLength();
            if (contentLength > 0) {
                listener.onDownloadProgress(contentLength, downloadedBytes[0]);
            }
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    listener.checkpoint();
                    output.write(buffer, 0, read);
                    downloadedBytes[0] += read;
                    listener.onDownloadProgress(
                            contentLength > 0 ? contentLength : 0, downloadedBytes[0]);
                }
            } finally {
                connection.disconnect();
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
