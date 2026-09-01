package com.zack88604.autoupdater.infrastructure.http;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Small bootstrap-time HTTP transport for a server GUI-preset offer.
 *
 * <p>It is deliberately independent of the update progress transport: GUI
 * selection happens before the normal update controller and its view exist.</p>
 */
public final class ServerGuiPresetTransport {

    private static final int CONNECT_TIMEOUT_MILLIS = 5000;
    private static final int READ_TIMEOUT_MILLIS = 15000;
    private static final int MAX_DESCRIPTOR_BYTES = 64 * 1024;

    /** Fetch the first available descriptor response from the configured mirrors. */
    public Response fetchDescriptor(List<String> serverUrls, String endpoint) {
        if (serverUrls == null) {
            return null;
        }
        for (String rawServer : serverUrls) {
            String server = normalizeServer(rawServer);
            if (server == null) {
                continue;
            }
            try {
                return new Response(server, getUtf8(server + endpoint));
            } catch (IOException ignored) {
                // A missing optional offer or unavailable mirror is not fatal.
            }
        }
        return null;
    }

    /** Download from the same mirror that supplied the descriptor. */
    public boolean download(Response response, String relativePath, File destination,
                            long expectedSize) {
        if (response == null || relativePath == null || destination == null) {
            return false;
        }
        HttpURLConnection connection = null;
        try {
            connection = open(response.getServerUrl() + relativePath);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return false;
            }
            long contentLength = connection.getContentLengthLong();
            if (contentLength >= 0 && contentLength != expectedSize) {
                return false;
            }
            long written = 0;
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    written += read;
                    if (written > expectedSize) {
                        return false;
                    }
                    output.write(buffer, 0, read);
                }
            }
            return written == expectedSize;
        } catch (IOException | IllegalArgumentException ignored) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String normalizeServer(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String server = value.trim();
        while (server.endsWith("/")) {
            server = server.substring(0, server.length() - 1);
        }
        try {
            URI uri = URI.create(server);
            String scheme = uri.getScheme();
            if (uri.getHost() == null || (!"http".equalsIgnoreCase(scheme)
                    && !"https".equalsIgnoreCase(scheme))) {
                return null;
            }
            return server;
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static String getUtf8(String url) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = open(url);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("Unexpected HTTP status");
            }
            long length = connection.getContentLengthLong();
            if (length > MAX_DESCRIPTOR_BYTES) {
                throw new IOException("GUI preset descriptor is too large");
            }
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (output.size() + read > MAX_DESCRIPTOR_BYTES) {
                        throw new IOException("GUI preset descriptor is too large");
                    }
                    output.write(buffer, 0, read);
                }
                return output.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection =
                (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        connection.setRequestProperty("Accept", "application/json");
        return connection;
    }

    /** A descriptor body paired with the exact mirror that provided it. */
    public static final class Response {
        private final String serverUrl;
        private final String body;

        private Response(String serverUrl, String body) {
            this.serverUrl = serverUrl;
            this.body = body;
        }

        public String getServerUrl() {
            return serverUrl;
        }

        public String getBody() {
            return body;
        }
    }
}
