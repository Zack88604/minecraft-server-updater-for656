import java.util.List;

/**
 * Unified event model for the update business layer.
 *
 * The update service and its collaborators emit {@link UpdateEvent}s to a
 * single {@link UpdateListener}; the UI layer subscribes and translates each
 * event into its own presentation. No Swing types appear anywhere in this
 * model, so any UI toolkit (Swing, JavaFX, ...) can consume it.
 */
abstract class UpdateEvent {

    enum Type {
        STATUS_CHANGED,
        OVERALL_PROGRESS_CHANGED,
        DOWNLOAD_PROGRESS_CHANGED,
        LOG_MESSAGE,
        SERVER_CHANGED,
        COMPLETED,
        FAILED
    }

    final Type type;

    private UpdateEvent(Type type) {
        this.type = type;
    }

    /** Status text changed, with an optional secondary description, plus
     *  whether the overall bar is indeterminate. Carries the {@link UpdatePhase}
     *  explicitly so the UI never has to infer its phase from the status text. */
    static final class StatusChanged extends UpdateEvent {
        final UpdatePhase phase;
        final String status;
        final String description;   // optional subtitle; may be null
        final boolean indeterminate;

        StatusChanged(UpdatePhase phase, String status, String description, boolean indeterminate) {
            super(Type.STATUS_CHANGED);
            this.phase = phase;
            this.status = status;
            this.description = description;
            this.indeterminate = indeterminate;
        }
    }

    /** Overall progress percentage (0-100). */
    static final class OverallProgressChanged extends UpdateEvent {
        final int percent;

        OverallProgressChanged(int percent) {
            super(Type.OVERALL_PROGRESS_CHANGED);
            this.percent = percent;
        }
    }

    /** Per-file / agent download progress snapshot, including speed. */
    static final class DownloadProgressChanged extends UpdateEvent {
        final DownloadProgress progress;

        DownloadProgressChanged(DownloadProgress progress) {
            super(Type.DOWNLOAD_PROGRESS_CHANGED);
            this.progress = progress;
        }
    }

    /** One log line produced by the update flow. */
    static final class LogMessage extends UpdateEvent {
        final String message;

        LogMessage(String message) {
            super(Type.LOG_MESSAGE);
            this.message = message;
        }
    }

    /** The active server or the server list changed. */
    static final class ServerChanged extends UpdateEvent {
        final List<String> serverUrls;
        final String currentServer;

        ServerChanged(List<String> serverUrls, String currentServer) {
            super(Type.SERVER_CHANGED);
            this.serverUrls = serverUrls;
            this.currentServer = currentServer;
        }
    }

    /** The update flow completed with a result. */
    static final class Completed extends UpdateEvent {
        final UpdateResult result;

        Completed(UpdateResult result) {
            super(Type.COMPLETED);
            this.result = result;
        }
    }

    /** The update flow failed with an exception. */
    static final class Failed extends UpdateEvent {
        final String message;
        final Throwable cause;

        Failed(String message, Throwable cause) {
            super(Type.FAILED);
            this.message = message;
            this.cause = cause;
        }
    }
}
