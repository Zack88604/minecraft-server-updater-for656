/**
 * Immutable read-only view model handed to the UI layer.
 *
 * Carries display-only configuration (game directory, debug mode) so the UI
 * does not depend on business configuration objects directly.
 */
final class UiModel {

    final String gameDir;
    final boolean debug;

    UiModel(String gameDir, boolean debug) {
        this.gameDir = gameDir;
        this.debug = debug;
    }
}
