/**
 * Minecraft Client Auto-Update Java Agent — Launcher
 *
 * Loaded via -javaagent JVM argument at Minecraft client startup.
 * This is a thin wrapper that:
 * 1. Replaces the core JAR (UpdateAgent_core.jar) with a new version
 *    if UpdateAgent_core.jar.new is present — since the core JAR is
 *    not yet loaded, there is no file lock, so this works even if
 *    the previous Minecraft process was killed forcefully.
 * 2. Dynamically loads the core JAR via URLClassLoader and delegates
 *    to UpdateAgent.premain().
 *
 * This launcher JAR is never updated, so the file lock on it is
 * irrelevant.
 */

import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class Launcher {

    // ── Agent entry point ────────────────────────────────────────

    public static void premain(String args, Instrumentation inst) {
        try {
            // 1. Find launcher JAR directory (core JAR lives next to us)
            String myPath = getMyJarPath();
            if (myPath == null) {
                System.err.println("[Launcher] FATAL: Cannot determine JAR location");
                System.exit(1);
                return;
            }
            File launcherJar = new File(myPath);
            File jarDir = launcherJar.getParentFile();

            // 2. If a new core JAR was downloaded, replace the old one
            //    (core JAR is not loaded yet, so no file lock — always succeeds)
            File coreJar = new File(jarDir, "UpdateAgent_core.jar");
            File newCore = new File(jarDir, "UpdateAgent_core.jar.new");
            if (newCore.isFile()) {
                System.out.println("[Launcher] New core JAR found, replacing...");
                if (coreJar.exists()) {
                    if (!coreJar.delete()) {
                        System.out.println("[Launcher] WARNING: Cannot delete old core JAR");
                    }
                }
                try {
                    Files.move(newCore.toPath(), coreJar.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("[Launcher] Core JAR replaced successfully");
                } catch (IOException e) {
                    System.out.println("[Launcher] WARNING: Core JAR move failed: "
                            + e.getMessage());
                    // Fall through — try to use whatever core JAR exists
                }
            }

            if (!coreJar.isFile()) {
                System.err.println("[Launcher] FATAL: UpdateAgent_core.jar not found at "
                        + coreJar.getAbsolutePath());
                System.exit(1);
                return;
            }

            // 3. Load core JAR and delegate to UpdateAgent.premain()
            // NOTE: do NOT close this classloader. AgentBootstrap starts a daemon
            // background thread (JavaFX runtime repair) that outlives premain()
            // and still needs this loader to resolve classes/resources (the core
            // JAR's embedded javafx-runtime-spec.json, RepairResult, ...). Closing
            // it here would race the repair thread — if a download runs longer
            // than the update flow, the repair crashes mid-install and the runtime
            // is never committed. The loader is intentionally leaked; the JVM
            // reclaims it on exit.
            URLClassLoader cl = new URLClassLoader(
                    new URL[]{coreJar.toURI().toURL()},
                    Launcher.class.getClassLoader()
            );
            try {
                Class<?> agentClass = cl.loadClass("UpdateAgent");
                Method premain = agentClass.getMethod("premain", String.class,
                        Instrumentation.class);
                premain.invoke(null, args, inst);
            } catch (Exception e) {
                cl.close();
                throw e;
            }

        } catch (Exception e) {
            System.err.println("[Launcher] FATAL: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    // ── Utility: get own JAR path ──────────────────────────────

    private static String getMyJarPath() {
        try {
            String path = Launcher.class.getProtectionDomain()
                    .getCodeSource().getLocation().getPath();
            return URLDecoder.decode(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}