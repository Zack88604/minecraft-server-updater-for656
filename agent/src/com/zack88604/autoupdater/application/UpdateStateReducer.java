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
            List<String> logLines = new ArrayList<>(current.getLogLines());
            logLines.add(((UpdateEvent.LogMessage) event).getMessage());
            builder.logLines(logLines);
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
            List<String> logLines = new ArrayList<>(current.getLogLines());
            logLines.add("[FATAL] " + result.getFailedFiles()
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
        List<String> logLines = new ArrayList<>(current.getLogLines());
        logLines.add("[ERROR] " + failure.getMessage());
        logLines.add("[FATAL] Update failed. Minecraft will not start; close the window to exit.");
        builder.phase(UpdatePhase.ERROR)
                .status("Update failed")
                .description("")
                .overallProgressIndeterminate(false)
                .downloadProgress(DownloadProgress.inactive())
                .closePolicy(ClosePolicy.EXIT_FAILURE)
                .errorMessage(failure.getMessage())
                .logLines(logLines);
    }
}
