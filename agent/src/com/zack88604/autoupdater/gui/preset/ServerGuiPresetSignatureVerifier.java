package com.zack88604.autoupdater.gui.preset;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Verifies an Ed25519-signed server GUI-preset descriptor against a pinned key. */
public final class ServerGuiPresetSignatureVerifier {

    private ServerGuiPresetSignatureVerifier() {
    }

    /**
     * Verify an offer using the configured Base64 X.509 SubjectPublicKeyInfo key.
     *
     * <p>Ed25519 is provided by Java 15 and later. On an older JVM this method
     * returns {@code false}, so the updater safely falls back instead of loading
     * a remote preset.</p>
     */
    public static boolean verify(ServerGuiPresetOffer offer, String configuredKeyId,
                                 String encodedPublicKey) {
        if (offer == null || configuredKeyId == null || encodedPublicKey == null
                || !offer.getKeyId().equals(configuredKeyId.trim())) {
            return false;
        }
        try {
            PublicKey publicKey = publicKey(encodedPublicKey);
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(offer.canonicalPayload());
            return verifier.verify(Base64.getDecoder().decode(offer.getSignature()));
        } catch (GeneralSecurityException | IllegalArgumentException ignored) {
            return false;
        }
    }

    /** Return a stable SHA-256 fingerprint for the configured encoded public key. */
    public static String fingerprint(String encodedPublicKey) {
        if (encodedPublicKey == null || encodedPublicKey.trim().isEmpty()) {
            return null;
        }
        try {
            byte[] encoded = Base64.getDecoder().decode(encodedPublicKey.trim());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(encoded));
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            return null;
        }
    }

    private static PublicKey publicKey(String encodedPublicKey)
            throws GeneralSecurityException {
        byte[] encoded = Base64.getDecoder().decode(encodedPublicKey.trim());
        KeyFactory factory = KeyFactory.getInstance("Ed25519");
        return factory.generatePublic(new X509EncodedKeySpec(encoded));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte element : bytes) {
            value.append(Character.forDigit((element >>> 4) & 0x0F, 16));
            value.append(Character.forDigit(element & 0x0F, 16));
        }
        return value.toString();
    }
}
