package com.zack88604.autoupdater.gui.api;

/** The explicit, toolkit-neutral phase of an update session. */
public enum UpdatePhase {
    /** Fetching the manifest and checking the updater itself. */
    PREPARING,
    /** Hashing managed local files against the remote manifest. */
    CHECKING,
    /** Downloading a managed resource file. */
    DOWNLOADING,
    /** Removing stale managed files. */
    CLEANING,
    /** The update completed with no failed files. */
    SUCCESS,
    /** The update failed or completed with failed files. */
    ERROR
}
