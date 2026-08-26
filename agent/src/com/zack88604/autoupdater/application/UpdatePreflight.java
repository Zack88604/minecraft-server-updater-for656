package com.zack88604.autoupdater.application;

/**
 * Optional work the controller runs before the update use case starts.
 *
 * <p>A preflight runs on the update-worker thread, after the initial render and
 * before {@code UpdateService.run}. It is purely optional GUI infrastructure: it
 * must never throw, and its result must never fail or gate the Minecraft update
 * flow — {@link UpdateController} treats any escaping {@link Throwable} as a
 * runtime problem to log and ignore, not as an updater error.</p>
 *
 * <p>The interface is deliberately toolkit-independent: it depends only on
 * {@link UpdateListener} and the application event model, never on Swing,
 * JavaFX, Maven, or a runtime manager. The controller knows only that an
 * optional preflight can run before the real update.</p>
 */
@FunctionalInterface
public interface UpdatePreflight {

    /** Run the preflight, emitting progress through {@code listener}. */
    void run(UpdateListener listener);
}
