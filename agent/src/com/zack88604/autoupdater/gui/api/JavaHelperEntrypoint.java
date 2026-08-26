package com.zack88604.autoupdater.gui.api;

/**
 * Main implementation executed inside a preset's isolated helper JVM.
 *
 * <p>The entry point calls {@link JavaHelperSession#signalReady()} after its
 * toolkit is ready, then consumes render commands until it receives a close
 * command or standard input closes.</p>
 */
@FunctionalInterface
public interface JavaHelperEntrypoint {

    /** Start the helper UI and service commands from the updater process. */
    void run(JavaHelperSession session) throws Exception;
}
