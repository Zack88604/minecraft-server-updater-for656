package com.zack88604.autoupdater.application;

import com.zack88604.autoupdater.domain.UpdateResult;
import com.zack88604.autoupdater.gui.api.ClosePolicy;
import com.zack88604.autoupdater.gui.api.DownloadProgress;
import com.zack88604.autoupdater.gui.api.UpdatePhase;
import com.zack88604.autoupdater.gui.api.UpdateSummary;
import com.zack88604.autoupdater.gui.api.UpdateUiState;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure reducer that folds one business event into a complete GUI state snapshot.
 *
 * <p>The reducer has no I/O, threading, GUI, or lifecycle side effects. This
 * makes the mapping deterministic and reusable by every GUI adapter.</p>
 */
public final class UpdateStateReducer {

    /** Keep rendering and state reduction bounded during very large cleanups. */
    static final int MAX_LOG_LINES = 250;
    private static final String TRUNCATED_LOG_MARKER =
            "[INFO] Earlier log entries were omitted to keep the updater responsive.";

    private UpdateStateReducer() {
    }

    /** Return the state that results from applying one update event. */
    public static UpdateUiState reduce(UpdateUiState current, UpdateEvent event) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(event, "event");

        UpdateUiState.Builder builder = copyOf(current);
        if (event instanceof UpdateEvent.StatusChanged) {
            UpdateEvent.StatusChanged status = (UpdateEvent.StatusChanged) event;
            builder.phase(status.getPhase())
                    .status(status.getStatus())
                    .description(status.getDescription() != null
                            ? status.getDescription() : "")
                    .overallProgressIndeterminate(status.isIndeterminate());
        } else if (event instanceof UpdateEvent.OverallProgressChanged) {
            builder.overallProgressPercent(
                    ((UpdateEvent.OverallProgressChanged) event).getPercentage())
                    .overallProgressIndeterminate(false);
        } else if (event instanceof UpdateEvent.LogMessage) {
            builder.logLines(appendLog(current.getLogLines(),
                    ((UpdateEvent.LogMessage) event).getMessage()));
        } else if (event instanceof UpdateEvent.ServerChanged) {
            UpdateEvent.ServerChanged server = (UpdateEvent.ServerChanged) event;
            builder.serverUrls(server.getServerUrls())
                    .currentServer(server.getCurrentServer());
        } else if (event instanceof UpdateEvent.DownloadProgressChanged) {
            UpdateEvent.DownloadProgressChanged download =
                    (UpdateEvent.DownloadProgressChanged) event;
            builder.downloadProgress(toGuiDownloadProgress(download));
        } else if (event instanceof UpdateEvent.Completed) {
            applyCompleted(builder, current, ((UpdateEvent.Completed) event).getResult());
        } else if (event instanceof UpdateEvent.Failed) {
            applyFailure(builder, current, (UpdateEvent.Failed) event);
        }

        return builder.build();
    }

    private static UpdateUiState.Builder copyOf(UpdateUiState source) {
        return UpdateUiState.builder()
                .phase(source.getPhase())
                .status(source.getStatus())
                .description(source.getDescription())
                .logLines(source.getLogLines())
                .serverUrls(source.getServerUrls())
                .currentServer(source.getCurrentServer())
                .overallProgressPercent(source.getOverallProgressPercent())
                .overallProgressIndeterminate(source.isOverallProgressIndeterminate())
                .downloadProgress(source.getDownloadProgress())
                .closePolicy(source.getClosePolicy())
                .summary(source.getSummary())
                .errorMessage(source.getErrorMessage());
    }

    private static DownloadProgress toGuiDownloadProgress(
            UpdateEvent.DownloadProgressChanged event) {
        if (!event.isActive()) {
            return DownloadProgress.inactive();
        }
        DownloadProgress.Kind kind = event.getKind() == UpdateEvent.DownloadKind.UPDATER
                ? DownloadProgress.Kind.UPDATER
                : DownloadProgress.Kind.FILE;
        return DownloadProgress.active(event.getResource(), kind, event.getDownloadedBytes(),
                event.getTotalBytes(), event.getBytesPerSecond());
    }

    private static void applyCompleted(UpdateUiState.Builder builder,
                                       UpdateUiState current, UpdateResult result) {
        UpdateSummary summary = new UpdateSummary(result.getUpdatedFiles(), result.getFailedFiles());
        if (result.getFailedFiles() > 0) {
            List<String> logLines = appendLog(current.getLogLines(), "[FATAL] "
                    + result.getFailedFiles()
                    + " file(s) failed to update. Minecraft will not start; close the window to exit.");
            builder.phase(UpdatePhase.ERROR)
                    .status("Update finished with " + result.getFailedFiles() + " error(s)")
                    .description("")
                    .overallProgressIndeterminate(false)
                    .downloadProgress(DownloadProgress.inactive())
                    .closePolicy(ClosePolicy.EXIT_FAILURE)
                    .summary(summary)
                    .errorMessage(null)
                    .logLines(logLines);
            return;
        }

        String status = result.getUpdatedFiles() > 0
                ? "Updated " + result.getUpdatedFiles() + " file(s), launching Minecraft..."
                : "Already up to date, launching Minecraft...";
        builder.phase(UpdatePhase.SUCCESS)
                .status(status)
                .description("")
                .overallProgressPercent(100)
                .overallProgressIndeterminate(false)
                .downloadProgress(DownloadProgress.inactive())
                .closePolicy(ClosePolicy.ALLOW)
                .summary(summary)
                .errorMessage(null);
    }

    private static void applyFailure(UpdateUiState.Builder builder,
                                     UpdateUiState current, UpdateEvent.Failed failure) {
        List<String> logLines = appendLog(current.getLogLines(),
                "[ERROR] " + failure.getMessage());
        logLines = appendLog(logLines,
                "[FATAL] Update failed. Minecraft will not start; close the window to exit.");
        builder.phase(UpdatePhase.ERROR)
                .status("Update failed")
                .description("")
                .overallProgressIndeterminate(false)
                .downloadProgress(DownloadProgress.inactive())
                .closePolicy(ClosePolicy.EXIT_FAILURE)
                .errorMessage(failure.getMessage())
                .logLines(logLines);
    }

    private static List<String> appendLog(List<String> current, String message) {
        String[] incoming = message.split("\\n", -1);
        if (current.size() + incoming.length <= MAX_LOG_LINES) {
            List<String> appended = new ArrayList<>(current.size() + incoming.length);
            appended.addAll(current);
            for (String line : incoming) {
                appended.add(line);
            }
            return appended;
        }

        List<String> retained = new ArrayList<>(MAX_LOG_LINES);
        retained.add(TRUNCATED_LOG_MARKER);
        int incomingToKeep = Math.min(incoming.length, MAX_LOG_LINES - 1);
        int currentToKeep = MAX_LOG_LINES - 1 - incomingToKeep;
        int firstRetained = Math.max(0, current.size() - currentToKeep);
        for (int index = firstRetained; index < current.size(); index++) {
            retained.add(current.get(index));
        }
        for (int index = incoming.length - incomingToKeep; index < incoming.length; index++) {
            retained.add(incoming[index]);
        }
        return retained;
    }
}
