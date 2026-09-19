package com.zack88604.autoupdater.gui.api;

/**
 * Machine-readable category for an updater failure presented to a GUI.
 *
 * <p>The accompanying {@link UpdateUiState#getErrorMessage()} remains the
 * display-safe, human-readable explanation. Adapters should use this code for
 * presentation decisions rather than parsing that message.</p>
 */
public enum UpdateErrorCode {
    /** An update server could not be reached or its connection timed out. */
    NETWORK,
    /** A signed manifest or its trusted signing key could not be authenticated. */
    MANIFEST_AUTHENTICATION,
    /** The updater configuration is missing or invalid. */
    CONFIGURATION,
    /** A local file-system operation could not be completed safely. */
    FILESYSTEM,
    /** The updater could not classify the failure more precisely. */
    UNKNOWN
}