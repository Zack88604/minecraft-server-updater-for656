package com.zack88604.autoupdater.infrastructure.files;

import com.zack88604.autoupdater.domain.FileEntry;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * File-system operations used by the updater, rooted at one game directory.
 */
public final class FileManager {

    private final File gameDirectory;

    public FileManager(File gameDirectory) {
        this.gameDirectory = gameDirectory;
    }

    /** Resolve a manifest path only when it remains inside the managed game directory. */
    public File resolveManagedFile(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            return null;
        }
        String path = relativePath.replace('\\', '/');
        if (path.startsWith("/") || path.matches("^[A-Za-z]:.*")) {
            return null;
        }
        for (String segment : path.split("/")) {
            if ("..".equals(segment)) {
                return null;
            }
        }
        try {
            File base = gameDirectory.getCanonicalFile();
            File target = new File(base, path.replace('/', File.separatorChar))
                    .getCanonicalFile();
            return target.toPath().startsWith(base.toPath()) ? target : null;
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    /** Return the file SHA-256 in lowercase hexadecimal, or null if unreadable. */
    public String sha256(File file) {
        return sha256(file, null);
    }

    /**
     * Return the file SHA-256 while invoking an optional pause/cancellation
     * checkpoint between read chunks.
     */
    public String sha256(File file, Runnable checkpoint) {
        try (FileInputStream input = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (checkpoint != null) {
                    checkpoint.run();
                }
                digest.update(buffer, 0, read);
            }
            StringBuilder hash = new StringBuilder();
            for (byte value : digest.digest()) {
                hash.append(String.format("%02x", value));
            }
            return hash.toString();
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception e) {
            return null;
        }
    }

    /** Atomically replace a managed file where supported, otherwise use a normal replacement move. */
    public void replaceDownloadedFile(File temporaryFile, File localFile) throws IOException {
        replaceDownloadedFile(temporaryFile, localFile, null);
    }

    /**
     * Replace a file after capturing its original state in the active
     * transaction. If capture fails, the target is left unchanged.
     */
    public void replaceDownloadedFile(File temporaryFile, File localFile,
                                      FileTransaction transaction) throws IOException {
        if (transaction != null) {
            transaction.capture(localFile);
        }
        try {
            Files.move(temporaryFile.toPath(), localFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporaryFile.toPath(), localFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Delete managed files that are not present in the manifest. */
    public void cleanStaleFiles(List<FileEntry> manifestFiles, List<String> managedPaths,
                                List<String> excludedPaths, Consumer<String> log) {
        cleanStaleFiles(manifestFiles, managedPaths, excludedPaths, log,
                new Runnable() {
                    @Override
                    public void run() {
                        // No pause or cancellation control was supplied.
                    }
                }, null);
    }

    /**
     * Delete stale managed files while allowing cooperative pause/cancellation
     * and rollback capture.
     */
    public void cleanStaleFiles(List<FileEntry> manifestFiles, List<String> managedPaths,
                                List<String> excludedPaths, Consumer<String> log,
                                Runnable checkpoint, FileTransaction transaction) {
        Set<String> manifestSet = new HashSet<String>();
        for (FileEntry entry : manifestFiles) {
            manifestSet.add(entry.getPath());
        }
        for (String managedPath : managedPaths) {
            checkpoint.run();
            if (managedPath.equals("*")) {
                continue;
            }
            String pathToResolve = managedPath.endsWith("/")
                    ? managedPath.substring(0, managedPath.length() - 1)
                    : managedPath;
            File managedFile = resolveManagedFile(pathToResolve);
            if (managedFile == null) {
                log.accept("  [REJECT] " + managedPath + " (unsafe managed path)");
                continue;
            }
            if (managedPath.endsWith("/")) {
                if (managedFile.isDirectory()) {
                    deleteStaleInDirectory(managedFile, manifestSet, excludedPaths, log,
                            checkpoint, transaction);
                }
            } else if (managedFile.isFile() && !managedFile.getName().startsWith(".")) {
                String relativePath = managedPath.replace('\\', '/');
                if (isExcluded(relativePath, excludedPaths)) {
                    log.accept("  [SKIP]  " + managedPath + " (excluded)");
                    continue;
                }
                if (!manifestSet.contains(relativePath)) {
                    deleteStaleFile(managedFile, relativePath, log, transaction);
                }
            }
        }
    }

    private boolean isExcluded(String relativePath, List<String> excludedPaths) {
        if (excludedPaths == null || excludedPaths.isEmpty()) {
            return false;
        }
        for (String excludedPath : excludedPaths) {
            if (excludedPath.equals("*")) {
                continue;
            }
            if (excludedPath.endsWith("/")) {
                if (relativePath.equals(excludedPath.substring(0, excludedPath.length() - 1))
                        || relativePath.startsWith(excludedPath)) {
                    return true;
                }
            } else if (relativePath.equals(excludedPath)) {
                return true;
            }
        }
        return false;
    }

    private void deleteStaleInDirectory(File directory, Set<String> manifestSet,
                                        List<String> excludedPaths, Consumer<String> log,
                                        Runnable checkpoint, FileTransaction transaction) {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            checkpoint.run();
            if (child.isDirectory()) {
                deleteStaleInDirectory(child, manifestSet, excludedPaths, log,
                        checkpoint, transaction);
            } else if (child.isFile() && !child.getName().startsWith(".")) {
                String relativePath = child.getAbsolutePath()
                        .substring(gameDirectory.getAbsolutePath().length() + 1)
                        .replace('\\', '/');
                if (isExcluded(relativePath, excludedPaths)) {
                    log.accept("  [SKIP]  " + relativePath + " (excluded)");
                    continue;
                }
                if (!manifestSet.contains(relativePath)) {
                    deleteStaleFile(child, relativePath, log, transaction);
                }
            }
        }
    }

    private static void deleteStaleFile(File file, String relativePath, Consumer<String> log,
                                        FileTransaction transaction) {
        try {
            if (transaction != null) {
                transaction.capture(file);
            }
            if (Files.deleteIfExists(file.toPath())) {
                log.accept("  [DEL]   " + relativePath + " (not in manifest)");
            }
        } catch (IOException exception) {
            log.accept("  [FAIL]  " + relativePath + ": cannot delete stale file ("
                    + exception.getMessage() + ")");
        }
    }
}
