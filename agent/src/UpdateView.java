import java.util.List;

/**
 * Toolkit-agnostic contract for the update UI.
 *
 * Implemented by {@link UpdateGUI} (Swing) today and by a JavaFX view in the
 * future. No Swing or JavaFX types appear here — parameters use only plain
 * business types, so any UI toolkit can implement it.
 *
 * All methods must be invoked on the UI thread of the implementing toolkit.
 * The {@link UpdateController} guarantees this by marshalling every call
 * through a {@link UiDispatcher}; the view itself manages no threads and owns
 * no update-task state.
 */
interface UpdateView {

    /** Status text changed. The {@link UpdatePhase} is carried explicitly by
     *  the business layer so the view never has to infer it from the text.
     *  Also carries an optional secondary description and whether the overall
     *  bar is indeterminate. */
    void showStatus(UpdatePhase phase, String status, String description, boolean indeterminate);

    /** Append one log line to the view. */
    void showLog(String message);

    /** Overall progress percentage (0-100). */
    void showOverallProgress(int percent);

    /** Per-file / agent download progress snapshot, including speed. */
    void showDownloadProgress(DownloadProgress progress);

    /** The active server or the server list changed. */
    void showServer(List<String> serverUrls, String currentServer);

    /** The update flow completed with a result. */
    void showCompleted(UpdateResult result);

    /** The update flow failed with an exception. */
    void showError(String message, Throwable cause);

    /** Enable or disable the user's close affordance. */
    void setCloseEnabled(boolean enabled);

    /** Open the view window. Must be called on the UI thread. */
    void open();

    /** Close the view window. Must be called on the UI thread. */
    void close();
}
