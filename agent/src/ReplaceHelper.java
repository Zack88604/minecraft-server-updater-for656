/**
 * Minecraft UpdateAgent self-replace helper.
 *
 * Spawned as a detached Java process by the agent's shutdown hook.
 * Waits for the JVM to fully exit (releasing the file lock on Windows),
 * then replaces the old agent JAR with the newly downloaded version.
 *
 * Usage: java ReplaceHelper <oldJarPath> <newJarPath>
 */

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class ReplaceHelper {

    private static final int MAX_RETRIES = 30;
    private static final int RETRY_DELAY_MS = 1000;
    private static final int INITIAL_DELAY_MS = 2000;

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: ReplaceHelper <oldJar> <newJar>");
            System.exit(1);
            return;
        }

        File oldFile = new File(args[0]);
        File newFile = new File(args[1]);

        if (!newFile.isFile()) {
            System.err.println("New JAR not found: " + newFile.getAbsolutePath());
            System.exit(2);
            return;
        }

        // Initial delay — give the parent JVM time to fully terminate
        sleep(INITIAL_DELAY_MS);

        // Poll until the old JAR can be deleted (file lock released)
        boolean oldGone = waitForUnlock(oldFile, MAX_RETRIES, RETRY_DELAY_MS);

        if (oldGone) {
            try {
                Files.move(newFile.toPath(), oldFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[ReplaceHelper] OK: " + oldFile.getName() + " replaced");
            } catch (IOException e) {
                System.err.println("[ReplaceHelper] Move failed: " + e.getMessage());
                newFile.delete();
                System.exit(3);
                return;
            }
        } else {
            // Timeout: old JAR still locked. Clean up and let next startup retry.
            System.err.println("[ReplaceHelper] Timeout: cannot replace " + oldFile.getName()
                    + " (still locked)");
            newFile.delete();
            System.exit(4);
            return;
        }

        // Self-cleanup: delete own class directory from temp
        cleanupSelf();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean waitForUnlock(File file, int maxRetries, int delayMs) {
        for (int i = 0; i < maxRetries; i++) {
            if (file.exists()) {
                try {
                    file.delete();
                } catch (SecurityException ignored) {
                    // security manager denied
                }
            }
            if (!file.exists()) {
                return true;
            }
            sleep(delayMs);
        }
        return false;
    }

    /** Attempt to remove our own temporary class dir. Best-effort, not critical. */
    private static void cleanupSelf() {
        try {
            String myPath = URLDecoder.decode(
                    ReplaceHelper.class.getProtectionDomain()
                            .getCodeSource().getLocation().getPath(),
                    StandardCharsets.UTF_8);
            File me = new File(myPath);
            // If we are a .class file in a temp dir, delete the .class and its parent dir
            if (me.isFile() && me.getName().endsWith(".class")) {
                me.delete();
                File parent = me.getParentFile();
                if (parent != null && parent.getAbsolutePath().contains(
                        System.getProperty("java.io.tmpdir"))) {
                    parent.delete(); // best-effort
                }
            }
        } catch (Exception ignored) {
            // not critical
        }
    }
}
