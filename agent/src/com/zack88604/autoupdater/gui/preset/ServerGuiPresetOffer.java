package com.zack88604.autoupdater.gui.preset;

import com.zack88604.autoupdater.infrastructure.json.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Signed metadata for one GUI preset published by an update server.
 *
 * <p>The signature covers {@link #canonicalPayload()}, including the JAR hash
 * and download path. This value object never loads the archive.</p>
 */
public final class ServerGuiPresetOffer {

    private static final String DOWNLOAD_PREFIX = "/api/v2/gui-presets/";
    private static final Pattern SAFE_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern SAFE_ARCHIVE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.jar");
    private static final Pattern SHA256 = Pattern.compile("[0-9A-Fa-f]{64}");

    private final String id;
    private final String version;
    private final String downloadPath;
    private final String sha256;
    private final long size;
    private final String keyId;
    private final String signature;

    private ServerGuiPresetOffer(String id, String version, String downloadPath,
                                 String sha256, long size, String keyId,
                                 String signature) {
        this.id = id;
        this.version = version;
        this.downloadPath = downloadPath;
        this.sha256 = sha256.toLowerCase(Locale.ROOT);
        this.size = size;
        this.keyId = keyId;
        this.signature = signature;
    }

    /** Parse and validate one server descriptor JSON document. */
    public static ServerGuiPresetOffer parse(String json) {
        Objects.requireNonNull(json, "json");
        String id = required(JsonParser.getString(json, "id"), "id");
        String version = required(JsonParser.getString(json, "version"), "version");
        String path = required(JsonParser.getString(json, "path"), "path");
        String hash = required(JsonParser.getString(json, "sha256"), "sha256");
        String keyId = required(JsonParser.getString(json, "key_id"), "key_id");
        String signature = required(JsonParser.getString(json, "signature"), "signature");
        long size = JsonParser.getLong(json, "size", -1);

        if (!SAFE_ID.matcher(id).matches() || !SAFE_ID.matcher(keyId).matches()) {
            throw new IllegalArgumentException("Invalid server GUI preset identifier");
        }
        if (version.length() > 128 || containsControlCharacter(version)) {
            throw new IllegalArgumentException("Invalid server GUI preset version");
        }
        if (!isValidDownloadPath(path)) {
            throw new IllegalArgumentException("Invalid server GUI preset download path");
        }
        if (!SHA256.matcher(hash).matches() || size < 0) {
            throw new IllegalArgumentException("Invalid server GUI preset hash or size");
        }
        try {
            Base64.getDecoder().decode(signature);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid server GUI preset signature", error);
        }
        return new ServerGuiPresetOffer(id, version, path, hash, size, keyId, signature);
    }

    /** Return the stable server-side preset identifier. */
    public String getId() {
        return id;
    }

    /** Return the human-readable preset version. */
    public String getVersion() {
        return version;
    }

    /** Return the signed relative HTTP download path. */
    public String getDownloadPath() {
        return downloadPath;
    }

    /** Return the expected SHA-256 hash in lowercase hexadecimal form. */
    public String getSha256() {
        return sha256;
    }

    /** Return the expected archive size in bytes. */
    public long getSize() {
        return size;
    }

    /** Return the configured signing-key identifier. */
    public String getKeyId() {
        return keyId;
    }

    /** Return the Base64-encoded Ed25519 signature. */
    public String getSignature() {
        return signature;
    }

    /** Return the reserved local archive name used for this server preset. */
    public String getArchiveName() {
        return "server-" + id + ".jar";
    }

    /** Return the bytes that must be signed and verified without alteration. */
    public byte[] canonicalPayload() {
        String payload = id + "\n" + version + "\n" + downloadPath + "\n"
                + sha256 + "\n" + size + "\n" + keyId;
        return payload.getBytes(StandardCharsets.UTF_8);
    }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing server GUI preset " + name);
        }
        return value.trim();
    }

    private static boolean isValidDownloadPath(String path) {
        if (!path.startsWith(DOWNLOAD_PREFIX)) {
            return false;
        }
        String archive = path.substring(DOWNLOAD_PREFIX.length());
        return SAFE_ARCHIVE.matcher(archive).matches();
    }

    private static boolean containsControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }
}
