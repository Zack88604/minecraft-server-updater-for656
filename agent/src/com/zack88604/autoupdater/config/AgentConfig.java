package com.zack88604.autoupdater.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Immutable configuration resolved for one updater launch.
 *
 * <p>Normal mode gives the persistent {@code mc-update.properties} file
 * precedence. Passing {@code admin=true} in the agent arguments restores the
 * administrator override order: agent arguments, system properties, then the
 * persistent file. The resolved object has no side effects; the bootstrap owns
 * copying its values into JVM system properties for legacy consumers.</p>
 */
public final class AgentConfig {

    public static final String PROP_SERVER = "mc-update.server";
    public static final String PROP_GAME_DIR = "mc-update.game-dir";
    public static final String PROP_DEBUG = "mc-update.debug";
    public static final String PROP_GUI_ADAPTER = "mc-update.gui-adapter";
    public static final String PROP_SERVER_GUI_MODE = "mc-update.server-gui";
    public static final String PROP_SERVER_GUI_KEY_ID = "mc-update.server-gui-key-id";
    public static final String PROP_SERVER_GUI_PUBLIC_KEY = "mc-update.server-gui-public-key";

    private static final String CONFIG_FILE = "mc-update.properties";
    public static final String DEFAULT_SERVER = "http://localhost:25565";

    private final String gameDir;
    private final String server;
    private final boolean debug;
    private final boolean admin;
    private final String guiAdapterFactoryClassName;
    private final ServerGuiMode serverGuiMode;
    private final String serverGuiKeyId;
    private final String serverGuiPublicKey;

    private AgentConfig(String gameDir, String server, boolean debug, boolean admin,
                        String guiAdapterFactoryClassName, ServerGuiMode serverGuiMode,
                        String serverGuiKeyId, String serverGuiPublicKey) {
        this.gameDir = gameDir;
        this.server = server;
        this.debug = debug;
        this.admin = admin;
        this.guiAdapterFactoryClassName = guiAdapterFactoryClassName;
        this.serverGuiMode = serverGuiMode;
        this.serverGuiKeyId = serverGuiKeyId;
        this.serverGuiPublicKey = serverGuiPublicKey;
    }

    /** Resolve configuration from the current JVM and the agent argument string. */
    public static AgentConfig resolve(String agentArguments) {
        return resolve(agentArguments, System.getProperties(),
                System.getProperty("user.dir", "."), null);
    }

    /**
     * Resolve configuration with supplied inputs.
     *
     * <p>This package-visible overload exists for deterministic tests. When
     * {@code persistentConfig} is {@code null}, the configuration file is loaded
     * from the resolved game directory.</p>
     */
    static AgentConfig resolve(String agentArguments, Properties systemProperties,
                               String workingDirectory, Properties persistentConfig) {
        Properties system = systemProperties == null ? new Properties() : systemProperties;
        Map<String, String> arguments = parseAgentArguments(agentArguments);
        boolean admin = "true".equalsIgnoreCase(arguments.get("admin"));

        String gameDir = coalesce(
                arguments.get("game-dir"),
                system.getProperty(PROP_GAME_DIR),
                workingDirectory == null || workingDirectory.isEmpty() ? "." : workingDirectory
        );

        Properties fileConfig = persistentConfig == null
                ? loadConfigFile(new File(gameDir))
                : copyOf(persistentConfig);

        String server;
        String debugValue;
        String guiAdapterFactory;
        String serverGuiModeValue;
        String serverGuiKeyId;
        String serverGuiPublicKey;
        if (admin) {
            server = coalesce(
                    arguments.get("server"),
                    system.getProperty(PROP_SERVER),
                    fileConfig.getProperty("server"),
                    DEFAULT_SERVER
            );
            debugValue = coalesce(
                    arguments.get("debug"),
                    system.getProperty(PROP_DEBUG),
                    fileConfig.getProperty("debug"),
                    "false"
            );
            guiAdapterFactory = coalesce(
                    arguments.get("gui-adapter"),
                    system.getProperty(PROP_GUI_ADAPTER),
                    fileConfig.getProperty("gui-adapter")
            );
            serverGuiModeValue = coalesce(
                    arguments.get("server-gui"),
                    system.getProperty(PROP_SERVER_GUI_MODE),
                    fileConfig.getProperty("server-gui"),
                    ServerGuiMode.DISABLED.name()
            );
            serverGuiKeyId = coalesce(
                    arguments.get("server-gui-key-id"),
                    system.getProperty(PROP_SERVER_GUI_KEY_ID),
                    fileConfig.getProperty("server-gui-key-id")
            );
            serverGuiPublicKey = coalesce(
                    arguments.get("server-gui-public-key"),
                    system.getProperty(PROP_SERVER_GUI_PUBLIC_KEY),
                    fileConfig.getProperty("server-gui-public-key")
            );
        } else {
            server = coalesce(
                    fileConfig.getProperty("server"),
                    arguments.get("server"),
                    system.getProperty(PROP_SERVER),
                    DEFAULT_SERVER
            );
            debugValue = coalesce(
                    fileConfig.getProperty("debug"),
                    arguments.get("debug"),
                    system.getProperty(PROP_DEBUG),
                    "false"
            );
            guiAdapterFactory = coalesce(
                    fileConfig.getProperty("gui-adapter"),
                    arguments.get("gui-adapter"),
                    system.getProperty(PROP_GUI_ADAPTER)
            );
            serverGuiModeValue = coalesce(
                    fileConfig.getProperty("server-gui"),
                    arguments.get("server-gui"),
                    system.getProperty(PROP_SERVER_GUI_MODE),
                    ServerGuiMode.DISABLED.name()
            );
            serverGuiKeyId = coalesce(
                    fileConfig.getProperty("server-gui-key-id"),
                    arguments.get("server-gui-key-id"),
                    system.getProperty(PROP_SERVER_GUI_KEY_ID)
            );
            serverGuiPublicKey = coalesce(
                    fileConfig.getProperty("server-gui-public-key"),
                    arguments.get("server-gui-public-key"),
                    system.getProperty(PROP_SERVER_GUI_PUBLIC_KEY)
            );
        }

        return new AgentConfig(gameDir, server, isTrue(debugValue), admin,
                guiAdapterFactory, ServerGuiMode.parse(serverGuiModeValue),
                serverGuiKeyId, serverGuiPublicKey);
    }

    public String getGameDir() {
        return gameDir;
    }

    /**
     * Return the configured comma-separated server list exactly as it should be
     * passed to the existing updater flow.
     */
    public String getServer() {
        return server;
    }

    public boolean isDebug() {
        return debug;
    }

    /**
     * Return a configured {@code GuiAdapterFactory} class name, or {@code null}
     * when the built-in Swing adapter should be used.
     */
    public String getGuiAdapterFactoryClassName() {
        return guiAdapterFactoryClassName;
    }

    /** Return the local policy for a signed GUI preset offered by the server. */
    public ServerGuiMode getServerGuiMode() {
        return serverGuiMode;
    }

    /** Return the expected server signing-key identifier, or {@code null}. */
    public String getServerGuiKeyId() {
        return serverGuiKeyId;
    }

    /** Return the Base64-encoded X.509 Ed25519 public key trusted by this client. */
    public String getServerGuiPublicKey() {
        return serverGuiPublicKey;
    }

    public boolean isAdmin() {
        return admin;
    }

    /**
     * Parse comma-separated {@code key=value} agent arguments.
     *
     * <p>A token without an equals sign continues the preceding value. This
     * preserves documented multi-server input such as
     * {@code server=http://cdn-a,http://cdn-b}.</p>
     */
    static Map<String, String> parseAgentArguments(String agentArguments) {
        Map<String, String> values = new LinkedHashMap<>();
        if (agentArguments == null || agentArguments.isEmpty()) {
            return values;
        }

        String lastKey = null;
        for (String token : agentArguments.split(",")) {
            String[] keyValue = token.split("=", 2);
            if (keyValue.length == 2) {
                lastKey = keyValue[0].trim();
                values.put(lastKey, keyValue[1].trim());
            } else if (lastKey != null) {
                values.put(lastKey, values.get(lastKey) + "," + token.trim());
            }
        }
        return values;
    }

    private static Properties loadConfigFile(File gameDir) {
        Properties properties = new Properties();
        File configFile = new File(gameDir, CONFIG_FILE);
        if (!configFile.isFile()) {
            return properties;
        }
        try (FileInputStream input = new FileInputStream(configFile)) {
            properties.load(input);
        } catch (IOException ignored) {
            // Keep the previous best-effort behaviour: use lower-priority values.
        }
        return properties;
    }

    private static Properties copyOf(Properties source) {
        Properties copy = new Properties();
        copy.putAll(source);
        return copy;
    }

    private static String coalesce(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private static boolean isTrue(String value) {
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }
}
