/**
 * {@link UiDispatcher} for the helper-JVM flow.
 *
 * The "UI thread" of the remote view is the agent side of the stdin pipe: every
 * {@code invoke} simply runs the action inline on the caller's (worker) thread.
 * The view call then writes one JSONL line into the helper's bounded writer
 * queue, so this is both thread-safe and non-blocking. The helper is a separate
 * process that marshals onto its own JavaFX Application Thread via
 * {@code Platform.runLater}; no dispatching is needed on this side.
 */
final class DirectUiDispatcher implements UiDispatcher {

    @Override
    public void invoke(Runnable action) {
        action.run();
    }
}
