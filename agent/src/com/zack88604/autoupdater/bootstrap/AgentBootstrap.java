package com.zack88604.autoupdater.bootstrap;

import com.zack88604.autoupdater.application.UpdateController;
import com.zack88604.autoupdater.application.UpdateService;
import com.zack88604.autoupdater.config.AgentConfig;
import com.zack88604.autoupdater.config.ServerGuiMode;
import com.zack88604.autoupdater.gui.api.GuiAdapter;
import com.zack88604.autoupdater.gui.api.GuiAdapterContext;
import com.zack88604.autoupdater.gui.api.GuiAdapterFactory;
import com.zack88604.autoupdater.gui.api.JavaHelperGuiPresetFactory;
import com.zack88604.autoupdater.gui.api.JavaHelperLaunchSpec;
import com.zack88604.autoupdater.gui.preset.ExternalGuiAdapterFactoryLoader;
import com.zack88604.autoupdater.gui.preset.GuiPreset;
import com.zack88604.autoupdater.gui.preset.GuiPresetSelection;
import com.zack88604.autoupdater.gui.preset.GuiPresetStore;
import com.zack88604.autoupdater.gui.preset.JavaHelperGuiAdapter;
import com.zack88604.autoupdater.gui.preset.ServerGuiPresetManager;
import com.zack88604.autoupdater.gui.preset.ServerGuiPresetTrust;
import com.zack88604.autoupdater.gui.swing.SwingGuiAdapterFactory;
import com.zack88604.autoupdater.gui.swing.SwingGuiPresetChooser;

import java.io.IOException;
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
        GuiPresetStore presetStore = new GuiPresetStore(config.getGameDir());
        GuiAdapterContext context = new GuiAdapterContext(config.getGameDir(),
                presetStore.getConfigurationDirectory().getAbsolutePath(), config.isDebug());

        // Preserve the established explicit factory setting for adapters already
        // compiled into the updater core.
        String factoryClassName = config.getGuiAdapterFactoryClassName();
        if (factoryClassName != null) {
            return createConfiguredAdapter(factoryClassName, context);
        }

        try {
            List<GuiPreset> presets = presetStore.findLoadablePresets();
            GuiPresetSelection selection = presetStore.readDefault(presets);
            selection = resolveServerPresetSelection(config, presetStore, selection);
            if (selection == null) {
                selection = SwingGuiPresetChooser.choose(presets);
            }

            if (selection.isSwing()) {
                persistSelection(presetStore, selection);
                return createBuiltInAdapter(context);
            }

            GuiPreset preset = selection.getPreset();
            try {
                GuiAdapter adapter = createPresetAdapter(preset, context);
                persistSelection(presetStore, selection);
                return adapter;
            } catch (IOException | RuntimeException | LinkageError error) {
                clearSelection(presetStore);
                SwingGuiPresetChooser.showLoadFailure(preset);
                return createBuiltInAdapter(context);
            }
        } catch (IOException error) {
            System.err.println("Unable to read GUI preset settings: " + error.getMessage());
            SwingGuiPresetChooser.showStorageFailure();
            return createBuiltInAdapter(context);
        }
    }

    /**
     * Resolve an optional server preset before any external GUI classes load.
     *
     * <p>A remembered local Swing or local-preset selection wins in recommended
     * mode. A remembered server preset is refreshed when its originating server
     * remains configured, and can continue from its locally verified cache when
     * that server is temporarily unavailable.</p>
     */
    private static GuiPresetSelection resolveServerPresetSelection(AgentConfig config,
                                                                    GuiPresetStore presetStore,
                                                                    GuiPresetSelection selection)
            throws IOException {
        ServerGuiMode mode = config.getServerGuiMode();
        if (mode == ServerGuiMode.DISABLED) {
            return selection;
        }

        List<String> servers = parseServerList(config.getServer());
        ServerGuiPresetTrust trust = presetStore.readServerTrust();
        boolean selectedServerPreset = presetStore.isServerPresetSelection(selection, trust);
        if (selectedServerPreset && trust != null && !servers.contains(trust.getServerUrl())) {
            // A cached server archive is never carried over to a different configured server.
            return GuiPresetSelection.swing(false);
        }
        if (selectedServerPreset && trust == null) {
            // Migrate old key-bound records by requiring a fresh server approval.
            selection = null;
        }
        if (mode == ServerGuiMode.RECOMMENDED && selection != null
                && !selectedServerPreset) {
            return selection;
        }

        ServerGuiPresetManager.InstalledPreset installed = new ServerGuiPresetManager().install(
                servers, presetStore);
        if (installed == null) {
            return mode == ServerGuiMode.REQUIRED || selectedServerPreset
                    ? GuiPresetSelection.swing(false) : selection;
        }

        boolean trusted = trust != null
                && trust.matches(installed.getOffer(), installed.getServerUrl());
        if (!trusted) {
            if (!SwingGuiPresetChooser.confirmServerPreset(installed.getOffer(),
                    installed.getServerUrl())) {
                return mode == ServerGuiMode.REQUIRED || selection == null
                        ? GuiPresetSelection.swing(false) : selection;
            }
            try {
                presetStore.saveServerTrust(installed.toTrustRecord());
            } catch (IOException error) {
                // The approved JAR may run once, but approval is not retained.
                System.err.println("Unable to save server GUI trust: " + error.getMessage());
                return GuiPresetSelection.preset(installed.getPreset(), false);
            }
        }
        return GuiPresetSelection.preset(installed.getPreset(), true);
    }

    private static GuiAdapter createPresetAdapter(GuiPreset preset, GuiAdapterContext context)
            throws IOException {
        ExternalGuiAdapterFactoryLoader loader = new ExternalGuiAdapterFactoryLoader();
        if (preset.usesJavaHelperRuntime()) {
            JavaHelperGuiPresetFactory factory = loader.loadJavaHelper(preset);
            JavaHelperLaunchSpec launchSpec = Objects.requireNonNull(factory.create(context),
                    "JavaHelperGuiPresetFactory returned null launch specification");
            return new JavaHelperGuiAdapter(preset, context, launchSpec);
        }
        GuiAdapterFactory factory = loader.load(preset);
        return Objects.requireNonNull(factory.create(context),
                "GuiAdapterFactory returned null adapter");
    }

    private static GuiAdapter createConfiguredAdapter(String factoryClassName,
                                                       GuiAdapterContext context) {
        try {
            Class<?> candidate = Class.forName(factoryClassName, true,
                    AgentBootstrap.class.getClassLoader());
            Class<? extends GuiAdapterFactory> factoryType =
                    candidate.asSubclass(GuiAdapterFactory.class);
            GuiAdapterFactory factory = factoryType.getDeclaredConstructor().newInstance();
            return Objects.requireNonNull(factory.create(context),
                    "GuiAdapterFactory returned null adapter");
        } catch (ReflectiveOperationException | ClassCastException error) {
            throw new IllegalStateException("Unable to create GUI adapter factory: "
                    + factoryClassName, error);
        }
    }

    private static GuiAdapter createBuiltInAdapter(GuiAdapterContext context) {
        return new SwingGuiAdapterFactory().create(context);
    }

    private static void persistSelection(GuiPresetStore presetStore,
                                         GuiPresetSelection selection) {
        try {
            presetStore.saveDefault(selection);
        } catch (IOException error) {
            System.err.println("Unable to save GUI preset selection: " + error.getMessage());
        }
    }

    private static void clearSelection(GuiPresetStore presetStore) {
        try {
            presetStore.clearDefault();
        } catch (IOException error) {
            System.err.println("Unable to clear GUI preset selection: " + error.getMessage());
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
