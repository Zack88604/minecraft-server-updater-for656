package com.zack88604.autoupdater.gui.swing;

import com.zack88604.autoupdater.gui.api.ClosePolicy;
import com.zack88604.autoupdater.gui.api.DownloadProgress;
import com.zack88604.autoupdater.gui.api.UpdateUiState;
import com.zack88604.autoupdater.gui.api.UpdateView;
import com.zack88604.autoupdater.gui.api.UpdateViewActions;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.Objects;

/** Renders one immutable {@link UpdateUiState} snapshot with Swing components. */
final class SwingUpdateView implements UpdateView {

    private final UpdateViewActions actions;
    private final boolean debug;
    private final JFrame frame = new JFrame("Minecraft Update Check");
    private final JLabel serverLabel = new JLabel("Server: checking...");
    private final JLabel statusLabel = new JLabel("Checking for updates...");
    private final JProgressBar overallProgress = new JProgressBar(0, 100);
    private final JProgressBar downloadProgress = new JProgressBar(0, 100);
    private final JLabel downloadSpeedLabel = new JLabel(" ");
    private final JTextArea logArea = new JTextArea(8, 50);
    private final JButton closeButton = new JButton("Close");

    private UpdateUiState currentState = UpdateUiState.initial();
    private String shownError;

    SwingUpdateView(UpdateViewActions actions, String gameDirectory, boolean debug) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.debug = debug;
        configureFrame(gameDirectory);
    }

    @Override
    public void open() {
        frame.setVisible(true);
    }

    @Override
    public void render(UpdateUiState state) {
        currentState = Objects.requireNonNull(state, "state");
        renderServer(state);
        renderStatus(state);
        renderOverallProgress(state);
        renderDownloadProgress(state.getDownloadProgress());
        renderLog(state.getLogLines());
        renderClosePolicy(state.getClosePolicy());
        showErrorIfNeeded(state.getErrorMessage());
    }

    @Override
    public void close() {
        frame.dispose();
    }

    private void configureFrame(String gameDirectory) {
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setSize(520, 420);
        frame.setLocationRelativeTo(null);
        frame.setResizable(false);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                requestWindowClose();
            }

            @Override
            public void windowClosed(WindowEvent event) {
                actions.notifyWindowClosed();
            }
        });

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(12, 12, 12, 12));
        frame.setContentPane(root);

        JPanel topPanel = new JPanel(new GridLayout(2, 1, 4, 4));
        topPanel.add(serverLabel);
        topPanel.add(new JLabel("Game dir: " + gameDirectory));
        root.add(topPanel, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(6, 6));
        center.add(createProgressPanel(), BorderLayout.NORTH);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        logArea.setBackground(new Color(30, 30, 30));
        logArea.setForeground(new Color(200, 200, 200));
        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Update log"));
        center.add(scrollPane, BorderLayout.CENTER);
        root.add(center, BorderLayout.CENTER);

        if (debug) {
            JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            closeButton.setEnabled(false);
            closeButton.addActionListener(event -> requestWindowClose());
            bottom.add(closeButton);
            root.add(bottom, BorderLayout.SOUTH);
        }
    }

    private JPanel createProgressPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        overallProgress.setIndeterminate(true);
        overallProgress.setStringPainted(true);
        overallProgress.setAlignmentX(Component.LEFT_ALIGNMENT);

        downloadProgress.setStringPainted(true);
        downloadProgress.setValue(0);
        downloadProgress.setString("");
        downloadProgress.setAlignmentX(Component.LEFT_ALIGNMENT);

        downloadSpeedLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        downloadSpeedLabel.setForeground(new Color(120, 120, 120));
        downloadSpeedLabel.setHorizontalAlignment(SwingConstants.LEFT);
        downloadSpeedLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        panel.add(statusLabel);
        panel.add(Box.createVerticalStrut(4));
        panel.add(overallProgress);
        panel.add(Box.createVerticalStrut(2));
        panel.add(downloadProgress);
        panel.add(Box.createVerticalStrut(2));
        panel.add(downloadSpeedLabel);
        return panel;
    }

    private void requestWindowClose() {
        if (currentState.getClosePolicy() == ClosePolicy.CONFIRM) {
            actions.beginCloseConfirmation();
            int choice = JOptionPane.showConfirmDialog(frame,
                    "The update is paused. Close, restore this update's changed files, "
                            + "and launch Minecraft?",
                    "Update in progress", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) {
                actions.cancelCloseConfirmation();
                return;
            }
        }
        actions.requestClose();
    }

    private void renderServer(UpdateUiState state) {
        List<String> servers = state.getServerUrls();
        String currentServer = state.getCurrentServer();
        if (servers.isEmpty() || currentServer == null) {
            serverLabel.setText("Server: checking...");
        } else if (servers.size() == 1) {
            serverLabel.setText("Server: " + currentServer);
        } else {
            serverLabel.setText("Servers (" + servers.size() + "): " + currentServer);
        }
    }

    private void renderStatus(UpdateUiState state) {
        statusLabel.setText(state.getStatus());
    }

    private void renderOverallProgress(UpdateUiState state) {
        overallProgress.setIndeterminate(state.isOverallProgressIndeterminate());
        if (!state.isOverallProgressIndeterminate()) {
            overallProgress.setValue(state.getOverallProgressPercent());
        }
    }

    private void renderDownloadProgress(DownloadProgress progress) {
        if (!progress.isActive()) {
            downloadProgress.setIndeterminate(false);
            downloadProgress.setValue(0);
            downloadProgress.setString("");
            downloadSpeedLabel.setText(" ");
            return;
        }

        long totalBytes = progress.getTotalBytes();
        long downloadedBytes = progress.getDownloadedBytes();
        if (totalBytes > 0) {
            int percentage = (int) Math.min(100, downloadedBytes * 100 / totalBytes);
            downloadProgress.setIndeterminate(false);
            downloadProgress.setValue(percentage);
            downloadProgress.setString(percentage + "%");
        } else {
            downloadProgress.setIndeterminate(true);
            downloadProgress.setString("");
        }
        downloadSpeedLabel.setText(formatSpeed(progress.getBytesPerSecond()));
    }

    private void renderLog(List<String> logLines) {
        StringBuilder text = new StringBuilder();
        for (String line : logLines) {
            text.append(line).append('\n');
        }
        logArea.setText(text.toString());
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void renderClosePolicy(ClosePolicy closePolicy) {
        if (debug) {
            closeButton.setEnabled(closePolicy != ClosePolicy.CONFIRM);
        }
    }

    private void showErrorIfNeeded(String errorMessage) {
        if (errorMessage != null && !errorMessage.equals(shownError)) {
            shownError = errorMessage;
            JOptionPane.showMessageDialog(frame, errorMessage,
                    "Update Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static String formatSpeed(double bytesPerSecond) {
        if (bytesPerSecond < 0) {
            bytesPerSecond = 0;
        }
        if (bytesPerSecond >= 1_000_000_000) {
            return String.format("%.1f GB/s", bytesPerSecond / 1_000_000_000);
        }
        if (bytesPerSecond >= 1_000_000) {
            return String.format("%.1f MB/s", bytesPerSecond / 1_000_000);
        }
        if (bytesPerSecond >= 1_000) {
            return String.format("%.0f KB/s", bytesPerSecond / 1_000);
        }
        return String.format("%.0f B/s", bytesPerSecond);
    }
}
