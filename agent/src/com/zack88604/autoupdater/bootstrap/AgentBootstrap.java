package com.zack88604.autoupdater.bootstrap;

import com.zack88604.autoupdater.application.UpdateController;
import com.zack88604.autoupdater.application.UpdateService;
import com.zack88604.autoupdater.config.AgentConfig;
import com.zack88604.autoupdater.gui.api.GuiAdapter;
import com.zack88604.autoupdater.gui.api.GuiAdapterContext;
import com.zack88604.autoupdater.gui.api.GuiAdapterFactory;
import com.zack88604.autoupdater.gui.swing.SwingGuiAdapterFactory;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

/**
 * Java-agent composition root for the Minecraft client updater.
 *
 * <p>This class retains the legacy agent entry point and system-property
 * compatibility. Update flow and user-interface work are assembled through
 * {@link UpdateController}, {@link UpdateService}, and a GUI adapter.</p>
 */
public final class AgentBootstrap {

    private static final String PROP_SERVER = AgentConfig.PROP_SERVER;
    private static final String PROP_GAME_DIR = AgentConfig.PROP_GAME_DIR;
    private static final String PROP_DEBUG = AgentConfig.PROP_DEBUG;
    private static final String PROP_GUI_ADAPTER = AgentConfig.PROP_GUI_ADAPTER;

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
        if (config.getGuiAdapterFactoryClassName() != null) {
            System.setProperty(PROP_GUI_ADAPTER, config.getGuiAdapterFactoryClassName());
        }

        CountDownLatch launchLatch = new CountDownLatch(1);
        UpdateService service = new UpdateService(config.getGameDir(),
                parseServerList(config.getServer()));
        UpdateController controller = new UpdateController(service,
                createGuiAdapter(config),
                launchLatch, config.isDebug());
        controller.start();

        try {
            launchLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static GuiAdapter createGuiAdapter(AgentConfig config) {
        GuiAdapterContext context = new GuiAdapterContext(
                config.getGameDir(), config.isDebug());
        String factoryClassName = config.getGuiAdapterFactoryClassName();
        GuiAdapterFactory factory = new SwingGuiAdapterFactory();
        if (factoryClassName != null) {
            try {
                Class<?> candidate = Class.forName(factoryClassName, true,
                        AgentBootstrap.class.getClassLoader());
                Class<? extends GuiAdapterFactory> factoryType =
                        candidate.asSubclass(GuiAdapterFactory.class);
                factory = factoryType.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException | ClassCastException e) {
                throw new IllegalStateException("Unable to create GUI adapter factory: "
                        + factoryClassName, e);
            }
        }
        return Objects.requireNonNull(factory.create(context),
                "GuiAdapterFactory returned null adapter");
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
