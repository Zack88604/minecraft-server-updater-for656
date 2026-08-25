package com.zack88604.autoupdater.bootstrap;

import com.zack88604.autoupdater.application.UpdateController;
import com.zack88604.autoupdater.application.UpdateService;
import com.zack88604.autoupdater.config.AgentConfig;
import com.zack88604.autoupdater.gui.swing.SwingGuiAdapter;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Java-agent composition root for the Minecraft client updater.
 *
 * <p>This class retains the legacy agent entry point and system-property
 * compatibility. Update flow and user-interface work are assembled through
 * {@link UpdateController}, {@link UpdateService}, and the Swing adapter.</p>
 */
public final class AgentBootstrap {

    private static final String PROP_SERVER = AgentConfig.PROP_SERVER;
    private static final String PROP_GAME_DIR = AgentConfig.PROP_GAME_DIR;
    private static final String PROP_DEBUG = AgentConfig.PROP_DEBUG;

    private AgentBootstrap() {
    }

    /** Start the update flow before allowing the Minecraft client to launch. */
    public static void premain(String args, Instrumentation inst) {
        AgentConfig config = AgentConfig.resolve(args);

        // Keep properties populated for existing launcher and client integrations.
        System.setProperty(PROP_GAME_DIR, config.getGameDir());
        System.setProperty(PROP_SERVER, config.getServer());
        if (config.isDebug()) {
            System.setProperty(PROP_DEBUG, "true");
        }

        CountDownLatch launchLatch = new CountDownLatch(1);
        UpdateService service = new UpdateService(config.getGameDir(),
                parseServerList(config.getServer()));
        UpdateController controller = new UpdateController(service,
                new SwingGuiAdapter(config.getGameDir(), config.isDebug()),
                launchLatch, config.isDebug());
        controller.start();

        try {
            launchLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Parse comma-separated server URLs with the legacy normalization rules. */
    private static List<String> parseServerList(String raw) {
        List<String> servers = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            return servers;
        }
        for (String token : raw.split(",")) {
            String url = token.trim();
            if (url.isEmpty()) {
                continue;
            }
            while (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
            servers.add(url);
        }
        return servers;
    }
}
