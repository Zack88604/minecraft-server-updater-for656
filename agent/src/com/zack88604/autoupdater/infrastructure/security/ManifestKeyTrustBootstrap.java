package com.zack88604.autoupdater.infrastructure.security;

import com.zack88604.autoupdater.infrastructure.http.ServerClient;
import com.zack88604.autoupdater.infrastructure.json.JsonParser;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

/** Performs one explicit, user-approved first trust of a server Ed25519 key. */
public final class ManifestKeyTrustBootstrap {
    private ManifestKeyTrustBootstrap() { }

    public static ManifestSignatureVerifier resolve(File gameDirectory, ServerClient serverClient,
                                                    String configuredKey, String configuredKeyId)
            throws IOException {
        if (configuredKey != null && !configuredKey.trim().isEmpty()) {
            return new ManifestSignatureVerifier(configuredKey, configuredKeyId);
        }
        String descriptor = serverClient.getWithFallback("/api/v3/manifest-public-key");
        String algorithm = JsonParser.getString(descriptor, "algorithm");
        String keyId = JsonParser.getString(descriptor, "key_id");
        String publicKey = JsonParser.getString(descriptor, "public_key");
        if (!"Ed25519".equals(algorithm) || keyId == null || publicKey == null) {
            throw new IOException("Update server returned an invalid Ed25519 public-key descriptor");
        }
        String fingerprint = fingerprint(publicKey);
        if (!confirm(serverClient.getCurrentServer(), keyId, fingerprint)) {
            throw new IOException("The server Ed25519 public key was not accepted; Minecraft will not start");
        }
        saveTrust(gameDirectory, publicKey, keyId);
        return new ManifestSignatureVerifier(publicKey, keyId);
    }

    private static String fingerprint(String encodedKey) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    Base64.getDecoder().decode(encodedKey));
            StringBuilder result = new StringBuilder(digest.length * 3 - 1);
            for (int index = 0; index < digest.length; index++) {
                if (index > 0) result.append(':');
                result.append(String.format("%02X", digest[index] & 0xff));
            }
            return result.toString();
        } catch (Exception error) {
            throw new IOException("Cannot calculate server public-key fingerprint", error);
        }
    }

    private static boolean confirm(String server, String keyId, String fingerprint)
            throws IOException {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IOException("Cannot confirm a first-use public key in a headless environment");
        }
        String message = "This is the first connection to this update server.\n\n"
                + "Server: " + server + "\n"
                + "Key ID: " + keyId + "\n"
                + "Ed25519 SHA-256 fingerprint:\n" + fingerprint + "\n\n"
                + "Verify this fingerprint with the server administrator before accepting.\n"
                + "Accept this key and continue updating?";
        AtomicReference<Integer> result = new AtomicReference<Integer>();
        Runnable prompt = new Runnable() {
            @Override public void run() {
                result.set(JOptionPane.showConfirmDialog(null, message, "Trust update server key",
                        JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE));
            }
        };
        try {
            if (SwingUtilities.isEventDispatchThread()) prompt.run();
            else SwingUtilities.invokeAndWait(prompt);
        } catch (Exception error) {
            throw new IOException("Unable to show server-key confirmation", error);
        }
        return Integer.valueOf(JOptionPane.YES_OPTION).equals(result.get());
    }

    private static void saveTrust(File gameDirectory, String publicKey, String keyId)
            throws IOException {
        File configuration = new File(gameDirectory, "mc-update.properties");
        Properties values = new Properties();
        if (configuration.isFile()) {
            try (FileInputStream input = new FileInputStream(configuration)) { values.load(input); }
        }
        // This routine is called only when no key is configured; never replace an existing trust root.
        String existingKey = values.getProperty("manifest-public-key");
        if (existingKey != null && !existingKey.trim().isEmpty()) {
            throw new IOException("A manifest public key already exists; refusing to replace it");
        }
        values.setProperty("manifest-public-key", publicKey);
        values.setProperty("manifest-key-id", keyId);
        File parent = configuration.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Unable to create game configuration directory");
        }
        File temporary = File.createTempFile("mc-update-", ".tmp", parent);
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                values.store(output, "Minecraft Update Agent Configuration");
                output.flush();
                output.getFD().sync();
            }
            try {
                Files.move(temporary.toPath(), configuration.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary.toPath(), configuration.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }
}
