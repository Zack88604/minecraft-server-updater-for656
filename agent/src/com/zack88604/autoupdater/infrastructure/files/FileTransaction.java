package com.zack88604.autoupdater.infrastructure.files;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Retains original files for one update session so a user cancellation can
 * restore every replacement, creation, and managed-file deletion.
 */
public final class FileTransaction {

    private final File backupDirectory;
    private final Map<String, Entry> entries = new LinkedHashMap<String, Entry>();
    private boolean finished;

    /** Create an isolated temporary directory for rollback copies. */
    public FileTransaction() throws IOException {
        this.backupDirectory = Files.createTempDirectory("mc-update-rollback-").toFile();
    }

    /**
     * Capture a file's original state before the updater modifies or deletes it.
     * Repeated captures of the same canonical path are ignored.
     */
    public synchronized void capture(File target) throws IOException {
        requireActive();
        File canonical = target.getCanonicalFile();
        String key = canonical.getPath();
        if (entries.containsKey(key)) {
            return;
        }

        boolean existed = canonical.exists();
        if (existed && !canonical.isFile()) {
            throw new IOException("Cannot roll back non-file target: " + canonical);
        }

        File backup = null;
        if (existed) {
            backup = new File(backupDirectory, Integer.toString(entries.size()) + ".bak");
            Files.copy(canonical.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        entries.put(key, new Entry(canonical, backup));
    }

    /** Restore all captured targets to their state before this update began. */
    public synchronized void rollback() throws IOException {
        requireActive();
        IOException failure = null;
        List<Entry> reversed = new ArrayList<Entry>(entries.values());
        for (int index = reversed.size() - 1; index >= 0; index--) {
            Entry entry = reversed.get(index);
            try {
                if (entry.backup == null) {
                    Files.deleteIfExists(entry.target.toPath());
                } else {
                    File parent = entry.target.getParentFile();
                    if (parent != null && !parent.isDirectory()
                            && !parent.mkdirs() && !parent.isDirectory()) {
                        throw new IOException("Unable to recreate directory: " + parent);
                    }
                    Files.copy(entry.backup.toPath(), entry.target.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        finished = true;
        deleteRecursively(backupDirectory);
        if (failure != null) {
            throw failure;
        }
    }

    /** Discard retained backups after a completed or failed normal update. */
    public synchronized void commit() throws IOException {
        requireActive();
        finished = true;
        deleteRecursively(backupDirectory);
    }

    private void requireActive() {
        if (finished) {
            throw new IllegalStateException("File transaction has already finished");
        }
    }

    private static void deleteRecursively(File file) throws IOException {
        if (!file.exists()) {
            return;
        }
        File[] children = file.isDirectory() ? file.listFiles() : null;
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        Files.deleteIfExists(file.toPath());
    }

    private static final class Entry {
        private final File target;
        private final File backup;

        private Entry(File target, File backup) {
            this.target = target;
            this.backup = backup;
        }
    }
}
