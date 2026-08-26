package com.zack88604.autoupdater.gui.api;

import java.util.Objects;

/** One command delivered from the updater process to a Java GUI helper. */
public final class JavaHelperCommand {

    /** Supported commands in the stable helper protocol. */
    public enum Type {
        /** Show the helper window. */
        OPEN,
        /** Render a complete immutable updater snapshot. */
        RENDER,
        /** Close the helper window and finish the helper. */
        CLOSE
    }

    private final Type type;
    private final UpdateUiState state;

    JavaHelperCommand(Type type, UpdateUiState state) {
        this.type = Objects.requireNonNull(type, "type");
        this.state = state;
    }

    public Type getType() {
        return type;
    }

    /** Return the snapshot for {@link Type#RENDER}, otherwise {@code null}. */
    public UpdateUiState getState() {
        return state;
    }
}
