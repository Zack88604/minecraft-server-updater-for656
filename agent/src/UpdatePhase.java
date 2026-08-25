/**
 * The six main visual phases of the update flow.
 *
 * Toolkit-agnostic: both the Swing and the JavaFX views render from this same
 * enum, so the UI never has to infer its phase from status strings.
 *
 * {@link UpdateEvent.StatusChanged} carries one of the four mid-flow phases
 * (PREPARING, CHECKING, DOWNLOADING, CLEANING); the two terminal phases
 * (SUCCESS, ERROR) are set by the view's completion / failure callbacks.
 *
 * The updater self-update is deliberately <em>not</em> a phase of its own. It is
 * a download sub-state shown while the flow is {@code PREPARING}, distinguished
 * by {@link DownloadProgress.Kind#UPDATER} and rendered through the
 * current-file area.
 */
enum UpdatePhase {
    /** Preparing: fetching the manifest, running the self-update check and
     *  repairing the local JavaFX runtime. */
    PREPARING,
    /** Checking managed files against the manifest. */
    CHECKING,
    /** Downloading a regular managed file. */
    DOWNLOADING,
    /** Removing stale files. */
    CLEANING,
    /** The update flow completed successfully (terminal). */
    SUCCESS,
    /** The update flow failed (terminal). */
    ERROR
}
