package com.zack88604.autoupdater.gui.api;

/**
 * The application controller's current policy for a user close request.
 * GUI implementations may decide how to present confirmation, but must defer
 * the resulting lifecycle decision to {@link UpdateViewActions}.
 */
public enum ClosePolicy {
    /** The update is in progress and a close request should require confirmation. */
    CONFIRM,
    /** The update completed successfully and closing is allowed. */
    ALLOW,
    /** The update failed; closing exits without launching Minecraft. */
    EXIT_FAILURE
}
