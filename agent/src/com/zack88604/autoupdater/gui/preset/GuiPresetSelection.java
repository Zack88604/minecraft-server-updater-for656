package com.zack88604.autoupdater.gui.preset;

import java.util.Objects;

/** A one-launch GUI choice and whether it should become the default. */
public final class GuiPresetSelection {

    private final GuiPreset preset;
    private final boolean remember;

    private GuiPresetSelection(GuiPreset preset, boolean remember) {
        this.preset = preset;
        this.remember = remember;
    }

    /** Select the built-in Swing GUI. */
    public static GuiPresetSelection swing(boolean remember) {
        return new GuiPresetSelection(null, remember);
    }

    /** Select one external GUI preset. */
    public static GuiPresetSelection preset(GuiPreset preset, boolean remember) {
        return new GuiPresetSelection(Objects.requireNonNull(preset, "preset"), remember);
    }

    /** Return whether the built-in Swing GUI was selected. */
    public boolean isSwing() {
        return preset == null;
    }

    /** Return the selected external preset, or {@code null} for Swing. */
    public GuiPreset getPreset() {
        return preset;
    }

    /** Return whether this selection should be used on future launches. */
    public boolean shouldRemember() {
        return remember;
    }
}
