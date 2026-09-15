package com.zack88604.autoupdater.infrastructure.cache;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Properties;

/** Stores only an already authenticated server-signed manifest envelope. */
public final class SignedManifestCacheStore {
    private static final String VERSION = "1";
    private static final long MAX_CACHE_BYTES = 24L * 1024L * 1024L;
    private final File directory;
    private final File cacheFile;

    public SignedManifestCacheStore(File gameDirectory) {
        directory = new File(gameDirectory, ".mc-update");
        cacheFile = new File(directory, "signed-manifest-cache.properties");
    }

    public void save(String serverIdentity, String envelope) throws IOException {
        if (serverIdentity == null || serverIdentity.isEmpty() || envelope == null) {
            throw new IOException("Cannot cache an incomplete signed manifest");
        }
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Unable to create updater cache directory");
        }
        Properties values = new Properties();
        values.setProperty("version", VERSION);
        values.setProperty("server", encode(serverIdentity));
        values.setProperty("envelope", encode(envelope));
        File temporary = File.createTempFile("signed-manifest-", ".tmp", directory);
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                values.store(output, "Minecraft updater signed manifest cache");
                output.flush();
                output.getFD().sync();
            }
            try {
                Files.move(temporary.toPath(), cacheFile.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary.toPath(), cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    public String load(String expectedServerIdentity) throws IOException {
        if (!cacheFile.isFile()) {
            throw new IOException("No verified signed manifest cache is available; complete an update first");
        }
        if (cacheFile.length() > MAX_CACHE_BYTES) {
            throw new IOException("Signed manifest cache is too large");
        }
        Properties values = new Properties();
        try (InputStream input = new FileInputStream(cacheFile)) { values.load(input); }
        if (!VERSION.equals(values.getProperty("version"))) {
            throw new IOException("Signed manifest cache has an unsupported format");
        }
        if (!expectedServerIdentity.equals(decode(values.getProperty("server"), "server identity"))) {
            throw new IOException("Signed manifest cache belongs to different update servers");
        }
        return decode(values.getProperty("envelope"), "manifest envelope");
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    private static String decode(String value, String name) throws IOException {
        if (value == null) throw new IOException("Signed manifest cache is missing " + name);
        try { return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException error) { throw new IOException("Invalid cached " + name, error); }
    }
}
