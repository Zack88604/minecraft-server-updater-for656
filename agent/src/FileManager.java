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

/**
 * File operations for the update flow: path-safety checks, SHA-256 hashing,
 * atomic file replacement and stale-file cleanup. Contains no Swing dependency.
 */
class FileManager {

    private final String gameDir;
    private UpdateListener listener;

    FileManager(String gameDir) {
        this.gameDir = gameDir;
    }

    void setListener(UpdateListener listener) {
        this.listener = listener;
    }

    /** Resolve a manifest/config path beneath the game directory. */
    File resolveManagedFile(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) return null;
        String path = relativePath.replace('\\', '/');
        if (path.startsWith("/") || path.matches("^[A-Za-z]:.*")) return null;
        for (String segment : path.split("/")) {
            if ("..".equals(segment)) return null;
        }
        try {
            File base = new File(gameDir).getCanonicalFile();
            File target = new File(base, path.replace('/', File.separatorChar))
                    .getCanonicalFile();
            return target.toPath().startsWith(base.toPath()) ? target : null;
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    String sha256(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) md.update(buf, 0, n);
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    boolean replaceDownloadedFile(File tmpFile, File localFile, String relPath) {
        try {
            Files.move(tmpFile.toPath(), localFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (AtomicMoveNotSupportedException e) {
            try {
                Files.move(tmpFile.toPath(), localFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (IOException fallbackError) {
                log("  [FAIL]  " + relPath + ": cannot replace file ("
                        + fallbackError.getMessage() + ")");
            }
        } catch (IOException e) {
            log("  [FAIL]  " + relPath + ": cannot replace file ("
                    + e.getMessage() + ")");
        }
        return false;
    }

    void cleanStaleFiles(List<FileEntry> manifestFiles, List<String> managedPaths,
                         List<String> excludedPaths) {
        Set<String> manifestSet = new HashSet<>();
        for (FileEntry e : manifestFiles) manifestSet.add(e.path);
        for (String mp : managedPaths) {
            if (mp.equals("*")) continue;
            String pathToResolve = mp.endsWith("/")
                    ? mp.substring(0, mp.length() - 1)
                    : mp;
            File managedFile = resolveManagedFile(pathToResolve);
            if (managedFile == null) {
                log("  [REJECT] " + mp + " (unsafe managed path)");
                continue;
            }
            if (mp.endsWith("/")) {
                // Directory path: recursively clean this directory
                File dir = managedFile;
                if (dir.isDirectory()) {
                    deleteStaleInDir(dir, gameDir, manifestSet, excludedPaths);
                }
            } else {
                // Exact file path: check if this file is in manifest
                File file = managedFile;
                if (file.isFile() && !file.getName().startsWith(".")) {
                    String rel = mp.replace('\\', '/');
                    if (isExcluded(rel, excludedPaths)) {
                        log("  [SKIP]  " + mp + " (excluded)");
                        continue;
                    }
                    if (!manifestSet.contains(rel)) {
                        log("  [DEL]   " + rel + " (not in manifest)");
                        file.delete();
                    }
                }
            }
        }
    }

    private boolean isExcluded(String relPath, List<String> excludedPaths) {
        if (excludedPaths == null || excludedPaths.isEmpty()) return false;
        for (String ep : excludedPaths) {
            if (ep.equals("*")) continue;
            if (ep.endsWith("/")) {
                // Directory exclusion: path starting with this prefix is excluded
                if (relPath.equals(ep.substring(0, ep.length() - 1))
                        || relPath.startsWith(ep)) {
                    return true;
                }
            } else {
                // Exact file exclusion
                if (relPath.equals(ep)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void deleteStaleInDir(File dir, String baseDir, Set<String> manifestSet,
                                  List<String> excludedPaths) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                deleteStaleInDir(child, baseDir, manifestSet, excludedPaths);
            } else if (child.isFile() && !child.getName().startsWith(".")) {
                String rel = child.getAbsolutePath()
                        .substring(new File(baseDir).getAbsolutePath().length() + 1)
                        .replace('\\', '/');
                // Check if excluded
                if (isExcluded(rel, excludedPaths)) {
                    log("  [SKIP]  " + rel + " (excluded)");
                    continue;
                }
                if (!manifestSet.contains(rel)) {
                    log("  [DEL]   " + rel + " (not in manifest)");
                    child.delete();
                }
            }
        }
    }

    private void log(String msg) {
        if (listener != null) listener.onUpdateEvent(new UpdateEvent.LogMessage(msg));
    }
}
