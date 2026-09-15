package com.zack88604.autoupdater.infrastructure.security;

import com.zack88604.autoupdater.infrastructure.json.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Verifies a v3 manifest against an administrator-pinned Ed25519 public key. */
public final class ManifestSignatureVerifier {
    private static final long MAX_CLOCK_SKEW_SECONDS = 300L;
    private static final long MAX_LIFETIME_SECONDS = 31L * 24L * 60L * 60L;
    private static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;

    private final PublicKey publicKey;
    private final String expectedKeyId;

    public ManifestSignatureVerifier(String encodedPublicKey, String expectedKeyId)
            throws IOException {
        if (encodedPublicKey == null || encodedPublicKey.trim().isEmpty()) {
            throw new IOException("No Ed25519 manifest public key is configured");
        }
        this.publicKey = decodePublicKey(encodedPublicKey);
        this.expectedKeyId = trim(expectedKeyId);
    }

    /** Authenticate an envelope and return its exact signed manifest JSON. */
    public String verifyEnvelope(String envelope) throws IOException {
        if (!"mc-update-signed-manifest-v1".equals(JsonParser.getString(envelope, "format"))
                || !"Ed25519".equals(JsonParser.getString(envelope, "algorithm"))) {
            throw new IOException("Update server returned an unsupported signed manifest");
        }
        String keyId = JsonParser.getString(envelope, "key_id");
        String payloadText = JsonParser.getString(envelope, "payload");
        String signatureText = JsonParser.getString(envelope, "signature");
        if (keyId == null || payloadText == null || signatureText == null) {
            throw new IOException("Update server returned an incomplete signed manifest");
        }
        if (expectedKeyId != null && !expectedKeyId.equals(keyId)) {
            throw new IOException("Signed manifest key id does not match the configured key id");
        }
        byte[] payload = decode(payloadText, "signed manifest payload");
        if (payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) {
            throw new IOException("Signed manifest payload has an invalid size");
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(payload);
            if (!verifier.verify(decode(signatureText, "signed manifest signature"))) {
                throw new IOException("Signed manifest signature is invalid");
            }
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Ed25519 verification requires Java 15 or later", error);
        }
        return validateClaims(new String(payload, StandardCharsets.UTF_8));
    }

    private static PublicKey decodePublicKey(String encodedPublicKey) throws IOException {
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(
                    Base64.getDecoder().decode(encodedPublicKey.trim())));
        } catch (Exception error) {
            throw new IOException("Configured Ed25519 manifest public key is invalid; Java 15 or later is required", error);
        }
    }

    private static String validateClaims(String payload) throws IOException {
        if (!"mc-update-manifest-v1".equals(JsonParser.getString(payload, "format"))) {
            throw new IOException("Signed manifest has an unsupported claims format");
        }
        long issuedAt = JsonParser.getLong(payload, "issued_at", -1L);
        long expiresAt = JsonParser.getLong(payload, "expires_at", -1L);
        long now = System.currentTimeMillis() / 1000L;
        if (issuedAt < 0 || expiresAt < issuedAt || expiresAt < now
                || issuedAt > now + MAX_CLOCK_SKEW_SECONDS
                || expiresAt - issuedAt > MAX_LIFETIME_SECONDS) {
            throw new IOException("Signed manifest is expired or has invalid timestamps");
        }
        String manifestJson = extractObject(payload, "manifest");
        String expectedHash = JsonParser.getString(payload, "manifest_sha256");
        if (manifestJson == null || expectedHash == null || !expectedHash.matches("[0-9a-fA-F]{64}")) {
            throw new IOException("Signed manifest claims are incomplete");
        }
        if (!sha256(manifestJson.getBytes(StandardCharsets.UTF_8)).equalsIgnoreCase(expectedHash)) {
            throw new IOException("Signed manifest content hash is invalid");
        }
        return manifestJson;
    }

    private static byte[] decode(String value, String name) throws IOException {
        try { return Base64.getDecoder().decode(value); }
        catch (IllegalArgumentException error) { throw new IOException("Invalid " + name, error); }
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder output = new StringBuilder(digest.length * 2);
            for (byte value : digest) output.append(String.format("%02x", value & 0xff));
            return output.toString();
        } catch (Exception error) { throw new IOException("Cannot hash signed manifest", error); }
    }

    /** Extract an object while ignoring braces inside quoted JSON strings. */
    private static String extractObject(String json, String key) {
        int keyIndex = json.indexOf('"' + key + '"');
        int start = keyIndex < 0 ? -1 : json.indexOf('{', keyIndex);
        if (start < 0) return null;
        boolean quoted = false, escaped = false;
        int depth = 0;
        for (int index = start; index < json.length(); index++) {
            char value = json.charAt(index);
            if (quoted) {
                if (escaped) escaped = false;
                else if (value == '\\') escaped = true;
                else if (value == '"') quoted = false;
            } else if (value == '"') quoted = true;
            else if (value == '{') depth++;
            else if (value == '}' && --depth == 0) return json.substring(start, index + 1);
        }
        return null;
    }

    private static String trim(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }
}
