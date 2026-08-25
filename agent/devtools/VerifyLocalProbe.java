import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dev-only probe: exercises JavaFxRuntimeManager.verifyLocal() against the
 * agentDir it derives from its own location (the build-harness/ dir here, so it
 * will look for javafx-runtime/ relative to CWD's parent — see JavaFxRuntimeManager).
 * Stages the three states expected in the v3 matrix: MISSING (no marker),
 * READY (valid runtime.json + .installed), CORRUPTED (tampered jar).
 *
 * Run from agent/ with the runtime pre-staged in javafx-runtime/21.0.4/.
 */
public final class VerifyLocalProbe {

    public static void main(String[] args) throws Exception {
        // JavaFxRuntimeManager derives agentDir from its own classpath location.
        // In build-harness/ that's <agent>/build-harness; agentDir() is used for
        // runtimeDir(). Print the resolved dir so the probe is self-explanatory.
        System.out.println("[probe] agentDir = " + JavaFxRuntimeManager.agentDir());
        System.out.println("[probe] runtimeDir = " + JavaFxRuntimeManager.runtimeDir());

        System.out.println("[probe] state 1 (no marker) => "
                + JavaFxRuntimeManager.verifyLocal());

        File runtimeDir = JavaFxRuntimeManager.runtimeDir();
        File runtimeJson = new File(runtimeDir, "runtime.json");
        File installed = new File(runtimeDir, ".installed");
        Files.writeString(runtimeJson.toPath(), "{\"version\":\"21.0.4\",\"classifier\":\"win\","
                + "\"min_jdk\":17,\"module_path\":\"21.0.4\",\"artifacts\":["
                + "{\"module\":\"javafx-base\",\"classifier\":\"win\","
                + "\"file\":\"javafx-base-21.0.4-win.jar\",\"size\":753983,"
                + "\"hash\":\"daedb2fe921bf1c03c43f032078f01f6201d5ffa86ce353f4bac77b0d7eae346\"},"
                + "{\"module\":\"javafx-graphics\",\"classifier\":\"win\","
                + "\"file\":\"javafx-graphics-21.0.4-win.jar\",\"size\":5941675,"
                + "\"hash\":\"f0a0e80d0a4c75966070823d92bf9c051d34c1fa75525e9ef7687d15a918799c\"},"
                + "{\"module\":\"javafx-controls\",\"classifier\":\"win\","
                + "\"file\":\"javafx-controls-21.0.4-win.jar\",\"size\":2585128,"
                + "\"hash\":\"1a958b25299bfda612475c6062b98002178124cab3cc5f76e82b6ead21cc7a6d\"}]}");
        Files.writeString(installed.toPath(), "");
        System.out.println("[probe] state 2 (valid marker) => "
                + JavaFxRuntimeManager.verifyLocal());

        // Tamper with one jar -> CORRUPTED.
        File versionDir = JavaFxRuntimeManager.runtimeVersionDir();
        File controls = new File(versionDir, "javafx-controls-21.0.4-win.jar");
        byte[] original = Files.readAllBytes(controls.toPath());
        Files.write(controls.toPath(), "tampered".getBytes());
        System.out.println("[probe] state 3 (tampered jar) => "
                + JavaFxRuntimeManager.verifyLocal());
        Files.write(controls.toPath(), original); // restore

        System.out.println("[probe] state 4 (restored) => "
                + JavaFxRuntimeManager.verifyLocal());
    }
}
