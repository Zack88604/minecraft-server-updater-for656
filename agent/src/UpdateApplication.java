import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Composition root for the update check.
 *
 * Wires the business layer ({@link UpdateService}) and the flow layer
 * ({@link UpdateController}) together and starts the flow, either with the
 * Swing view ({@link UpdateGUI}, {@link #start()}) or through the JavaFX helper
 * JVM ({@link #startHelperFlow(String, List, boolean, CountDownLatch)}).
 * Holds no flow decisions — when to start the update, how to handle success and
 * failure, when to open and close the view, delays and releasing the latch are
 * all owned by the {@link UpdateController}. Implements nothing: user actions
 * from the view go straight to the controller through {@link UpdateViewListener}.
 * Contains no Swing dependency.
 */
class UpdateApplication {

    private final String gameDir;
    private final List<String> serverUrls;
    private final boolean debug;
    private final CountDownLatch latch;

    UpdateApplication(String gameDir, String serverConfig, boolean debug, CountDownLatch latch) {
        this.gameDir = gameDir;
        this.serverUrls = parseServerList(serverConfig);
        this.debug = debug;
        this.latch = latch;
    }

    /** Create the service, view and controller and start the flow. */
    void start() {
        UpdateService service = new UpdateService(gameDir, serverUrls);
        UiDispatcher dispatcher = new SwingUiDispatcher();
        UpdateController controller = new UpdateController(service, dispatcher, latch, debug);
        UpdateView view = new UpdateGUI(controller, new UiModel(gameDir, debug));
        controller.attach(view);
        controller.start();
        // Best-effort background worker: when the local JavaFX runtime is
        // missing/corrupt relative to the embedded spec, repair it from Maven
        // Central so a later helper launch finds a fresh runtime. Runs on every
        // Swing launch; never blocks the update flow.
        JavaFxRuntimeManager.startWorker(controller);
    }

    /**
     * Helper-JVM composition root. Wires the business layer and controller with a
     * {@link DirectUiDispatcher} + {@link RemoteUpdateView}, launches the JavaFX
     * helper process and starts the update flow through it. If the helper cannot
     * be launched at all, the {@link RemoteUpdateView} immediately engages the
     * Swing fallback — the same controller keeps driving, nothing is lost.
     */
    static void startHelperFlow(String gameDir, List<String> serverUrls,
                                boolean debug, CountDownLatch latch) {
        UpdateService service = new UpdateService(gameDir, serverUrls);
        UpdateController controller = new UpdateController(
                service, new DirectUiDispatcher(), latch, debug);
        UiModel model = new UiModel(gameDir, debug);
        UiSnapshot snapshot = new UiSnapshot();
        RemoteUpdateView view = new RemoteUpdateView(controller, model, snapshot);
        JavaFxHelperProcess helper =
                JavaFxHelperProcess.launch(controller, model, view, snapshot);
        if (helper == null) {
            System.err.println("[UpdateAgent] Helper launch failed — using Swing UI.");
            view.engageSwingFallback();
        } else {
            view.setSender(helper::send);
            view.setHelper(helper);
            controller.attach(view);
        }
        controller.start();
    }

    /** Parse comma-separated server URLs, trimming whitespace from each.
     *  Package-private so the JavaFX composition root ({@link JavaFxEntryPoint})
     *  reuses the same parsing. */
    static List<String> parseServerList(String raw) {
        List<String> list = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return list;
        for (String token : raw.split(",")) {
            String url = token.trim();
            if (!url.isEmpty()) {
                // Remove trailing slash for consistency
                while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
                list.add(url);
            }
        }
        return list;
    }
}
