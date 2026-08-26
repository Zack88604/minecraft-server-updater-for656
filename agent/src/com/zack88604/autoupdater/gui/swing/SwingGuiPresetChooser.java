package com.zack88604.autoupdater.gui.swing;

import com.zack88604.autoupdater.gui.preset.GuiPreset;
import com.zack88604.autoupdater.gui.preset.GuiPresetSelection;
import com.zack88604.autoupdater.gui.preset.ServerGuiPresetOffer;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Minimal trusted Swing UI used to choose and warn about external GUI presets.
 */
public final class SwingGuiPresetChooser {

    private static final String SWING_LABEL = "Built-in Swing GUI (recommended)";

    private SwingGuiPresetChooser() {
    }

    /**
     * Ask the user to select a GUI preset. Cancellation keeps Swing for this
     * launch and does not save a default.
     */
    public static GuiPresetSelection choose(List<GuiPreset> presets) {
        if (presets == null || presets.isEmpty() || GraphicsEnvironment.isHeadless()) {
            return GuiPresetSelection.swing(false);
        }
        return onEventThread(new Callable<GuiPresetSelection>() {
            @Override
            public GuiPresetSelection call() {
                return showSelectionDialog(presets);
            }
        }, GuiPresetSelection.swing(false));
    }


    /**
     * Ask for explicit trust before a verified server-published preset runs.
     * The result is persisted by the bootstrap for this exact preset identity.
     */
    public static boolean confirmServerPreset(final ServerGuiPresetOffer offer,
                                              final String serverUrl) {
        if (GraphicsEnvironment.isHeadless()) {
            return false;
        }
        return onEventThread(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return showServerRiskDialog(offer, serverUrl);
            }
        }, false);
    }

    /** Tell the user that an approved external preset could not be loaded. */
    public static void showLoadFailure(final GuiPreset preset) {
        showMessage("Unable to load external GUI preset \"" + preset.getSelectionLabel()
                        + "\". The built-in Swing GUI will be used instead.",
                "External GUI preset", JOptionPane.ERROR_MESSAGE);
    }

    /** Tell the user that the updater could not read or persist preset settings. */
    public static void showStorageFailure() {
        showMessage("GUI preset settings could not be read or saved. "
                        + "The built-in Swing GUI will be used for this launch.",
                "GUI preset settings", JOptionPane.WARNING_MESSAGE);
    }

    private static GuiPresetSelection showSelectionDialog(List<GuiPreset> presets) {
        JComboBox<String> choices = new JComboBox<String>();
        choices.addItem(SWING_LABEL);
        for (GuiPreset preset : presets) {
            choices.addItem(preset.getSelectionLabel());
        }

        JCheckBox remember = new JCheckBox(
                "Use this selection as the default GUI on future launches", true);

        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.add(new JLabel("Choose the GUI used by the updater:"), BorderLayout.NORTH);

        JPanel center = new JPanel(new GridLayout(2, 1, 0, 6));
        center.add(choices);
        center.add(remember);
        panel.add(center, BorderLayout.CENTER);

        int result = JOptionPane.showConfirmDialog(null, panel, "Choose updater GUI",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (result != JOptionPane.OK_OPTION || choices.getSelectedIndex() == 0) {
            return GuiPresetSelection.swing(result == JOptionPane.OK_OPTION
                    && remember.isSelected());
        }

        GuiPreset preset = presets.get(choices.getSelectedIndex() - 1);
        if (!showRiskDialog(preset)) {
            return GuiPresetSelection.swing(false);
        }
        return GuiPresetSelection.preset(preset, remember.isSelected());
    }

    private static boolean showServerRiskDialog(ServerGuiPresetOffer offer,
                                                 String serverUrl) {
        String message = "The update server offers a signed external GUI preset:\\n\\n"
                + offer.getId() + " (" + offer.getVersion() + ")\\n"
                + "Server: " + serverUrl + "\\n\\n"
                + "The archive hash and signature were verified against the public key "
                + "configured on this client. Loading it still executes external Java code, "
                + "which may read or modify files, access the network, or affect the game "
                + "process. Only trust a server and publisher you recognize.\\n\\n"
                + "Trust this preset identity and load it?";
        Object[] options = {"Trust and load server GUI", "Use built-in Swing"};
        return JOptionPane.showOptionDialog(null, message, "Server GUI security warning",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options,
                options[1]) == 0;
    }

    private static boolean showRiskDialog(GuiPreset preset) {
        String message = "The selected GUI preset is an external Java archive:\n\n"
                + preset.getArchive().getAbsolutePath() + "\n\n"
                + "Loading it executes code supplied by the preset. It may read or modify "
                + "your files, access the network, or affect the game process. Only continue "
                + "if you trust the file and its source.";
        Object[] options = {"Load external GUI", "Use built-in Swing"};
        return JOptionPane.showOptionDialog(null, message, "External GUI security warning",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options,
                options[1]) == 0;
    }

    private static void showMessage(final String message, final String title, final int type) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        onEventThread(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                JOptionPane.showMessageDialog(null, message, title, type);
                return true;
            }
        }, false);
    }

    private static <T> T onEventThread(Callable<T> action, T fallback) {
        if (SwingUtilities.isEventDispatchThread()) {
            try {
                return action.call();
            } catch (Exception ignored) {
                return fallback;
            }
        }

        final Result<T> result = new Result<T>(fallback);
        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    try {
                        result.value = action.call();
                    } catch (Exception ignored) {
                        // Use the safe fallback value.
                    }
                }
            });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException ignored) {
            // Use the safe fallback value.
        }
        return result.value;
    }

    private static final class Result<T> {
        private T value;

        private Result(T value) {
            this.value = value;
        }
    }
}
