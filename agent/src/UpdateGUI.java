import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;

/**
 * Swing UI for the update check. Pure View: it creates and lays out the
 * components, renders presentation from {@link UpdateView} callbacks and
 * forwards user actions (window close, close button) to a
 * {@link UpdateViewListener} — it never references the application that owns
 * the flow. It makes no application-flow decisions — delays, releasing the
 * latch, closing the window and JVM exit are all owned by the application —
 * and owns no threads.
 *
 * Implements the toolkit-agnostic {@link UpdateView} contract. All methods must
 * be called on the Event Dispatch Thread; the {@link UpdateController}
 * guarantees this by marshalling through a {@link UiDispatcher}. It holds no
 * reference to the {@link UpdateService} and never queries business state —
 * everything displayed arrives through the view callbacks.
 */
class UpdateGUI extends JFrame implements UpdateView {

    private final JLabel     lblStatus    = new JLabel("Checking for updates...");
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JTextArea  logArea      = new JTextArea(8, 50);
    private final JButton    btnClose     = new JButton("Close");

    // Per-file download progress bar (below overall bar)
    private final JProgressBar dlProgressBar = new JProgressBar(0, 100);
    private final JLabel       lblDlSpeed    = new JLabel(" ");

    private final JLabel serverLabel = new JLabel();
    private final UpdateViewListener listener;
    private final boolean debug;

    UpdateGUI(UpdateViewListener listener, UiModel model) {
        this.listener = listener;
        this.debug = model.debug;
        initUI(model);
    }

    // ── UpdateView ────────────────────────────────────────────────

    /**
     * Update the status text and whether the overall bar is indeterminate.
     * The carried {@link UpdatePhase} and the optional description are not
     * rendered by the single-label Swing fallback; it keeps its previous
     * behaviour unchanged.
     */
    @Override
    public void showStatus(UpdatePhase phase, String status, String description, boolean indeterminate) {
        lblStatus.setText(status);
        progressBar.setIndeterminate(indeterminate);
    }

    /** Append one log line. */
    @Override
    public void showLog(String message) {
        appendLog(message);
    }

    /** Set the overall progress percentage (0-100). */
    @Override
    public void showOverallProgress(int percent) {
        progressBar.setIndeterminate(false);
        progressBar.setValue(clamp(percent));
    }

    /** Present a per-file download snapshot. */
    @Override
    public void showDownloadProgress(DownloadProgress progress) {
        if (!progress.active) {
            resetDownloadProgressBar();
            return;
        }
        if (progress.totalBytes > 0) {
            int pct = clamp((int) (progress.downloadedBytes * 100 / progress.totalBytes));
            dlProgressBar.setValue(pct);
            dlProgressBar.setString(pct + "%");
            dlProgressBar.setIndeterminate(false);
        } else {
            dlProgressBar.setIndeterminate(true);
            dlProgressBar.setString("");
        }
        lblDlSpeed.setText(FormatUtil.formatSpeed(progress.bytesPerSecond));
    }

    /** Present the server state carried by the event. */
    @Override
    public void showServer(List<String> serverUrls, String currentServer) {
        serverLabel.setText(serverUrls.size() <= 1
                ? "Server: " + currentServer
                : "Servers (" + serverUrls.size() + "): " + currentServer);
    }

    /**
     * The update completed — render the final state. Flow control after
     * completion is the application's job.
     */
    @Override
    public void showCompleted(UpdateResult result) {
        progressBar.setIndeterminate(false);
        progressBar.setValue(100);
        resetDownloadProgressBar();

        if (result.failed > 0) {
            lblStatus.setText("Update finished with " + result.failed + " error(s)");
        } else {
            lblStatus.setText(result.updated > 0
                    ? "Updated " + result.updated + " file(s), launching Minecraft..."
                    : "Already up to date, launching Minecraft...");
            if (debug) {
                setCloseEnabled(true);
                appendLog("[DEBUG] Update check done. Window stays open for inspection.");
            }
        }
    }

    /**
     * The update failed — render the error and show a dialog. Flow control
     * after the failure is the application's job.
     */
    @Override
    public void showError(String message, Throwable cause) {
        lblStatus.setText("Update failed");
        progressBar.setIndeterminate(false);
        progressBar.setValue(0);
        resetDownloadProgressBar();
        appendLog("[ERROR] " + message);
        JOptionPane.showMessageDialog(this, message, "Update Error", JOptionPane.ERROR_MESSAGE);
    }

    /** Enable or disable the close button (used in debug mode). */
    @Override
    public void setCloseEnabled(boolean enabled) {
        btnClose.setEnabled(enabled);
    }

    /** Open the window. Must be called on the EDT. */
    @Override
    public void open() {
        setVisible(true);
    }

    /** Close the window. Must be called on the EDT. */
    @Override
    public void close() {
        dispose();
    }

    // ── Helpers ───────────────────────────────────────────────────

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private void resetDownloadProgressBar() {
        dlProgressBar.setValue(0);
        dlProgressBar.setString("");
        lblDlSpeed.setText(" ");
    }

    private void appendLog(String msg) {
        logArea.append(msg + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void initUI(UiModel model) {
        setTitle("Minecraft Update Check");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(520, 420);
        setLocationRelativeTo(null);
        setResizable(false);

        // Forward the close to the flow controller so Minecraft can start
        addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosed(java.awt.event.WindowEvent e) {
                listener.onWindowClosed();
            }
        });

        // Root panel
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(12, 12, 12, 12));
        setContentPane(root);

        // Top info
        JPanel topPanel = new JPanel(new GridLayout(2, 1, 4, 4));
        topPanel.add(serverLabel);
        topPanel.add(new JLabel("Game dir: " + model.gameDir));
        root.add(topPanel, BorderLayout.NORTH);

        // Center: progress area + log
        JPanel center = new JPanel(new BorderLayout(6, 6));

        // Progress panel: status + overall bar + per-file bar + speed
        JPanel progressPanel = new JPanel();
        progressPanel.setLayout(new BoxLayout(progressPanel, BoxLayout.Y_AXIS));

        progressBar.setIndeterminate(true);
        progressBar.setStringPainted(true);
        progressBar.setAlignmentX(Component.LEFT_ALIGNMENT);

        dlProgressBar.setStringPainted(true);
        dlProgressBar.setValue(0);
        dlProgressBar.setString("");
        dlProgressBar.setAlignmentX(Component.LEFT_ALIGNMENT);

        lblDlSpeed.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        lblDlSpeed.setForeground(new Color(120, 120, 120));
        lblDlSpeed.setAlignmentX(Component.LEFT_ALIGNMENT);

        progressPanel.add(lblStatus);
        progressPanel.add(Box.createVerticalStrut(4));
        progressPanel.add(progressBar);
        progressPanel.add(Box.createVerticalStrut(2));
        progressPanel.add(dlProgressBar);
        progressPanel.add(Box.createVerticalStrut(2));
        progressPanel.add(lblDlSpeed);

        center.add(progressPanel, BorderLayout.NORTH);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        logArea.setBackground(new Color(30, 30, 30));
        logArea.setForeground(new Color(200, 200, 200));
        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(BorderFactory.createTitledBorder("Update log"));
        center.add(scroll, BorderLayout.CENTER);
        root.add(center, BorderLayout.CENTER);

        // Close button (only shown in debug mode; otherwise window auto-closes)
        if (debug) {
            JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            setCloseEnabled(false);
            btnClose.addActionListener(e -> listener.onCloseRequested());
            bottom.add(btnClose);
            root.add(bottom, BorderLayout.SOUTH);
        }
    }
}
