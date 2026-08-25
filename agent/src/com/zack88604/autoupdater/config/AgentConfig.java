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

    private static final String CONFIG_FILE = "mc-update.properties";
    public static final String DEFAULT_SERVER = "http://localhost:25565";

    private final String gameDir;
    private final String server;
    private final boolean debug;
    private final boolean admin;

    private AgentConfig(String gameDir, String server, boolean debug, boolean admin) {
        this.gameDir = gameDir;
        this.server = server;
        this.debug = debug;
        this.admin = admin;
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
        }

        return new AgentConfig(gameDir, server, isTrue(debugValue), admin);
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
