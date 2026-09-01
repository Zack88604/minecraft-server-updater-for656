package com.zack88604.autoupdater.config;

/**
 * Local policy for a GUI preset offered by the configured update server.
 *
 * <p>Remote GUI loading is disabled unless the user enables one of the
 * server-backed modes.</p>
 */
public enum ServerGuiMode {
    /** Ignore any server GUI-preset offer. */
    DISABLED,
    /** Use a server preset only when no local default takes precedence. */
    RECOMMENDED,
    /** Prefer a server preset over a remembered local GUI choice. */
    REQUIRED;

    /** Parse the persisted setting, falling back to the safe disabled mode. */
    static ServerGuiMode parse(String value) {
        if (value == null) {
            return DISABLED;
        }
        for (ServerGuiMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        return DISABLED;
    }
}
