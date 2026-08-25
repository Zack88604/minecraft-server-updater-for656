/**
 * Pure rendering translation: turns one {@link UpdateEvent} into the equivalent
 * {@link UpdateView} calls.
 *
 * Extracted from {@link UpdateController#apply} so the same rendering can be
 * reused on both sides of the helper-JVM split (the helper renders a decoded
 * event into its JavaFX view; the agent renders a buffered event into a Swing
 * fallback). It contains <b>no flow side-effects</b> — completing a run, killing
 * the process and releasing the latch all stay in the controller, so a replay
 * of a snapshot or a buffered tail never double-fires them.
 */
final class ViewApplier {

    private ViewApplier() {}

    /** Apply the view-rendering part of an event to the given view. */
    static void apply(UpdateView view, UpdateEvent event) {
        switch (event.type) {
            case STATUS_CHANGED: {
                UpdateEvent.StatusChanged e = (UpdateEvent.StatusChanged) event;
                view.showStatus(e.phase, e.status, e.description, e.indeterminate);
                break;
            }
            case OVERALL_PROGRESS_CHANGED:
                view.showOverallProgress(((UpdateEvent.OverallProgressChanged) event).percent);
                break;
            case DOWNLOAD_PROGRESS_CHANGED:
                view.showDownloadProgress(((UpdateEvent.DownloadProgressChanged) event).progress);
                break;
            case LOG_MESSAGE:
                view.showLog(((UpdateEvent.LogMessage) event).message);
                break;
            case SERVER_CHANGED: {
                UpdateEvent.ServerChanged e = (UpdateEvent.ServerChanged) event;
                view.showServer(e.serverUrls, e.currentServer);
                break;
            }
            case COMPLETED:
                view.showCompleted(((UpdateEvent.Completed) event).result);
                break;
            case FAILED:
                view.showError(((UpdateEvent.Failed) event).message, ((UpdateEvent.Failed) event).cause);
                break;
        }
    }
}
