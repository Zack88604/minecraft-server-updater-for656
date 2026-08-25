/**
 * Compatibility entry point for {@code UpdateAgent_core.jar}.
 *
 * <p>{@link Launcher} is intentionally never self-updated and loads this class
 * by the literal name {@code "UpdateAgent"}. Keep this default-package facade
 * even as the implementation moves into named packages; otherwise an already
 * deployed launcher could not load a newer core JAR.</p>
 */

import com.zack88604.autoupdater.bootstrap.AgentBootstrap;
import java.lang.instrument.Instrumentation;

public final class UpdateAgent {

    private UpdateAgent() {
        // Entry-point class; do not instantiate.
    }

    /** Delegate the Java agent entry point to the packaged implementation. */
    public static void premain(String args, Instrumentation inst) {
        AgentBootstrap.premain(args, inst);
    }
}
