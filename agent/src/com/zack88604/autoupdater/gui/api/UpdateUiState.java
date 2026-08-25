package com.zack88604.autoupdater.gui.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, complete snapshot rendered by {@link UpdateView}.
 *
 * <p>This is the public GUI data contract. It deliberately contains no Swing,
 * JavaFX, file-system, network, exception, or process types. A controller builds
 * a new snapshot for each state transition; a view must not retain mutable
 * updater state or infer phases from display strings.</p>
 */
public final class UpdateUiState {

    private final UpdatePhase phase;
    private final String status;
    private final String description;
    private final List<String> logLines;
    private final List<String> serverUrls;
    private final String currentServer;
    private final int overallProgressPercent;
    private final boolean overallProgressIndeterminate;
    private final DownloadProgress downloadProgress;
    private final ClosePolicy closePolicy;
    private final UpdateSummary summary;
    private final String errorMessage;

    private UpdateUiState(Builder builder) {
        phase = builder.phase;
        status = builder.status;
        description = builder.description;
        logLines = immutableCopy(builder.logLines);
        serverUrls = immutableCopy(builder.serverUrls);
        currentServer = builder.currentServer;
        overallProgressPercent = builder.overallProgressPercent;
        overallProgressIndeterminate = builder.overallProgressIndeterminate;
        downloadProgress = builder.downloadProgress;
        closePolicy = builder.closePolicy;
        summary = builder.summary;
        errorMessage = builder.errorMessage;
    }

    /** Return the initial Preparing snapshot for a new update session. */
    public static UpdateUiState initial() {
        return builder().build();
    }

    /** Create a builder with the API's safe initial values. */
    public static Builder builder() {
        return new Builder();
    }

    public UpdatePhase getPhase() {
        return phase;
    }

    public String getStatus() {
        return status;
    }

    public String getDescription() {
        return description;
    }

    /** Return an immutable chronological log snapshot. */
    public List<String> getLogLines() {
        return logLines;
    }

    /** Return configured servers in priority order. */
    public List<String> getServerUrls() {
        return serverUrls;
    }

    /** Return the selected server, or {@code null} before one is selected. */
    public String getCurrentServer() {
        return currentServer;
    }

    public int getOverallProgressPercent() {
        return overallProgressPercent;
    }

    public boolean isOverallProgressIndeterminate() {
        return overallProgressIndeterminate;
    }

    public DownloadProgress getDownloadProgress() {
        return downloadProgress;
    }

    public ClosePolicy getClosePolicy() {
        return closePolicy;
    }

    /** Return the terminal summary, or {@code null} while the flow is running. */
    public UpdateSummary getSummary() {
        return summary;
    }

    /** Return a display-safe failure description, or {@code null} when absent. */
    public String getErrorMessage() {
        return errorMessage;
    }

    private static List<String> immutableCopy(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    /** Builder for a complete {@link UpdateUiState} snapshot. */
    public static final class Builder {

        private UpdatePhase phase = UpdatePhase.PREPARING;
        private String status = "Preparing update...";
        private String description = "";
        private List<String> logLines = Collections.emptyList();
        private List<String> serverUrls = Collections.emptyList();
        private String currentServer;
        private int overallProgressPercent;
        private boolean overallProgressIndeterminate = true;
        private DownloadProgress downloadProgress = DownloadProgress.inactive();
        private ClosePolicy closePolicy = ClosePolicy.CONFIRM;
        private UpdateSummary summary;
        private String errorMessage;

        private Builder() {
        }

        public Builder phase(UpdatePhase value) {
            phase = Objects.requireNonNull(value, "phase");
            return this;
        }

        public Builder status(String value) {
            status = Objects.requireNonNull(value, "status");
            return this;
        }

        public Builder description(String value) {
            description = Objects.requireNonNull(value, "description");
            return this;
        }

        public Builder logLines(List<String> value) {
            logLines = copyNonNull(value, "logLines");
            return this;
        }

        public Builder serverUrls(List<String> value) {
            serverUrls = copyNonNull(value, "serverUrls");
            return this;
        }

        public Builder currentServer(String value) {
            currentServer = value;
            return this;
        }

        public Builder overallProgressPercent(int value) {
            if (value < 0 || value > 100) {
                throw new IllegalArgumentException("overallProgressPercent must be 0..100");
            }
            overallProgressPercent = value;
            return this;
        }

        public Builder overallProgressIndeterminate(boolean value) {
            overallProgressIndeterminate = value;
            return this;
        }

        public Builder downloadProgress(DownloadProgress value) {
            downloadProgress = Objects.requireNonNull(value, "downloadProgress");
            return this;
        }

        public Builder closePolicy(ClosePolicy value) {
            closePolicy = Objects.requireNonNull(value, "closePolicy");
            return this;
        }

        public Builder summary(UpdateSummary value) {
            summary = value;
            return this;
        }

        public Builder errorMessage(String value) {
            errorMessage = value;
            return this;
        }

        /** Build an immutable state snapshot. */
        public UpdateUiState build() {
            return new UpdateUiState(this);
        }

        private static List<String> copyNonNull(List<String> values, String name) {
            Objects.requireNonNull(values, name);
            List<String> copy = new ArrayList<>(values.size());
            for (String value : values) {
                copy.add(Objects.requireNonNull(value, name + " entry"));
            }
            return copy;
        }
    }
}
