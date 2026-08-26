package com.zack88604.autoupdater.gui.preset;

import com.zack88604.autoupdater.gui.api.GuiAdapter;
import com.zack88604.autoupdater.gui.api.GuiAdapterContext;
import com.zack88604.autoupdater.gui.api.JavaHelperLaunchSpec;
import com.zack88604.autoupdater.gui.api.JavaHelperProtocol;
import com.zack88604.autoupdater.gui.api.JavaHelperLauncher;
import com.zack88604.autoupdater.gui.api.UiDispatcher;
import com.zack88604.autoupdater.gui.api.UpdateUiState;
import com.zack88604.autoupdater.gui.api.UpdateView;
import com.zack88604.autoupdater.gui.api.UpdateViewActions;
import com.zack88604.autoupdater.gui.swing.SwingGuiAdapterFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A GUI adapter backed by one user-approved, isolated Java helper process.
 *
 * <p>The updater retains all lifecycle decisions. The helper receives only
 * immutable display snapshots and can return only the close-related actions
 * defined by the public protocol.</p>
 */
public final class JavaHelperGuiAdapter implements GuiAdapter {

    private static final long STARTUP_TIMEOUT_SECONDS = 15;

    private final HelperUiDispatcher dispatcher = new HelperUiDispatcher();
    private final Object outputLock = new Object();
    private final GuiAdapterContext context;
    private final Process process;
    private final PrintWriter output;
    private final CountDownLatch ready = new CountDownLatch(1);

    private volatile UpdateViewActions actions;
    private volatile IOException startupFailure;
    private volatile boolean readyReceived;
    private volatile boolean expectedHelperClosure;
    private volatile boolean helperWindowClosed;
    private volatile HelperUpdateView helperView;
    private boolean viewCreated;

    /**
     * Prepare the approved runtime resources and start the helper before the
     * application controller creates its view.
     */
    public JavaHelperGuiAdapter(GuiPreset preset, GuiAdapterContext context,
                                JavaHelperLaunchSpec launchSpec) throws IOException {
        Objects.requireNonNull(preset, "preset");
        this.context = Objects.requireNonNull(context, "context");
        Objects.requireNonNull(launchSpec, "launchSpec");
        if (!preset.usesJavaHelperRuntime()) {
            throw new IllegalArgumentException("Preset does not declare a Java helper runtime");
        }

        JavaHelperRuntimeInstaller.PreparedRuntime runtime =
                JavaHelperRuntimeInstaller.prepare(preset,
                        new File(context.getUpdaterConfigurationDirectory()));
        ensureJavaVersion(runtime.getMinimumJavaVersion());
        ProcessBuilder processBuilder = new ProcessBuilder(buildCommand(preset, runtime, launchSpec));
        process = processBuilder.start();
        output = new PrintWriter(new OutputStreamWriter(process.getOutputStream(),
                StandardCharsets.UTF_8), true);
        startProtocolReader();
        startErrorReader();
        send(JavaHelperProtocol.encodeInit(context));
        awaitReady(preset);
    }

    @Override
    public UiDispatcher dispatcher() {
        return dispatcher;
    }

    @Override
    public synchronized UpdateView create(UpdateViewActions actions) {
        if (viewCreated) {
            throw new IllegalStateException("Java helper adapter can create only one view");
        }
        this.actions = Objects.requireNonNull(actions, "actions");
        viewCreated = true;
        HelperUpdateView created = new HelperUpdateView();
        helperView = created;
        return created;
    }

    private List<String> buildCommand(GuiPreset preset,
                                      JavaHelperRuntimeInstaller.PreparedRuntime runtime,
                                      JavaHelperLaunchSpec launchSpec) throws IOException {
        List<String> command = new ArrayList<String>();
        command.add(javaExecutable());
        command.addAll(launchSpec.getJvmArguments());
        if (!runtime.getModulePath().isEmpty()) {
            command.add("--module-path");
            command.add(joinFiles(runtime.getModulePath()));
        }
        if (!runtime.getAddModules().isEmpty()) {
            command.add("--add-modules");
            command.add(join(runtime.getAddModules(), ","));
        }

        List<File> classPath = new ArrayList<File>();
        classPath.add(coreCodeSource());
        classPath.add(preset.getArchive().getCanonicalFile());
        classPath.addAll(runtime.getClassPath());
        command.add("-cp");
        command.add(joinFiles(classPath));
        command.add(JavaHelperLauncher.class.getName());
        command.add(runtime.getHelperMainClass());
        return command;
    }

    private void awaitReady(GuiPreset preset) throws IOException {
        try {
            if (!ready.await(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                stopProcess();
                throw new IOException("Timed out while starting Java helper preset "
                        + preset.getSelectionLabel());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            stopProcess();
            throw new IOException("Interrupted while starting Java helper preset", exception);
        }
        if (!readyReceived) {
            stopProcess();
            throw startupFailure == null
                    ? new IOException("Java helper preset exited before becoming ready")
                    : startupFailure;
        }
    }

    private void startProtocolReader() {
        Thread reader = new Thread(() -> {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    JavaHelperProtocol.HelperAction action = JavaHelperProtocol.decodeAction(line);
                    if (action == null) {
                        System.err.println("[GUI helper] Ignored invalid protocol message");
                    } else if (action == JavaHelperProtocol.HelperAction.READY) {
                        readyReceived = true;
                        ready.countDown();
                    } else {
                        forwardAction(action);
                    }
                }
            } catch (IOException exception) {
                startupFailure = exception;
            } finally {
                if (!readyReceived) {
                    if (startupFailure == null) {
                        startupFailure = new IOException("Java helper protocol closed during startup");
                    }
                    ready.countDown();
                } else if (!expectedHelperClosure && !helperWindowClosed) {
                    HelperUpdateView view = helperView;
                    if (view != null) {
                        view.activateFallback();
                    }
                }
            }
        }, "gui-helper-protocol-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void startErrorReader() {
        Thread reader = new Thread(() -> {
            try (BufferedReader errors = new BufferedReader(new InputStreamReader(
                    process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = errors.readLine()) != null) {
                    System.err.println("[GUI helper] " + line);
                }
            } catch (IOException ignored) {
                // The helper process is terminating.
            }
        }, "gui-helper-error-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void forwardAction(JavaHelperProtocol.HelperAction action) {
        UpdateViewActions currentActions = actions;
        if (currentActions == null) {
            return;
        }
        switch (action) {
            case BEGIN_CLOSE_CONFIRMATION:
                currentActions.beginCloseConfirmation();
                break;
            case CANCEL_CLOSE_CONFIRMATION:
                currentActions.cancelCloseConfirmation();
                break;
            case REQUEST_CLOSE:
                currentActions.requestClose();
                break;
            case WINDOW_CLOSED:
                helperWindowClosed = true;
                currentActions.notifyWindowClosed();
                break;
            default:
                break;
        }
    }

    private void send(String message) {
        synchronized (outputLock) {
            output.println(message);
            if (output.checkError()) {
                HelperUpdateView view = helperView;
                if (view != null) {
                    view.activateFallback();
                }
                throw new IllegalStateException("Java helper process is no longer available");
            }
        }
    }

    private void stopProcess() {
        output.close();
        if (process.isAlive()) {
            process.destroy();
        }
    }

    private static void ensureJavaVersion(int minimum) throws IOException {
        int actual = javaMajorVersion(System.getProperty("java.specification.version", ""));
        if (actual < minimum) {
            throw new IOException("Java helper requires Java " + minimum + "+, but updater runs on "
                    + actual);
        }
    }

    private static int javaMajorVersion(String version) {
        String value = version.startsWith("1.") ? version.substring(2) : version;
        int separator = value.indexOf('.');
        String first = separator >= 0 ? value.substring(0, separator) : value;
        try {
            return Integer.parseInt(first);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static String javaExecutable() {
        String extension = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? ".exe" : "";
        File executable = new File(new File(System.getProperty("java.home"), "bin"),
                "java" + extension);
        return executable.isFile() ? executable.getPath() : "java";
    }

    private static File coreCodeSource() throws IOException {
        try {
            CodeSource source = JavaHelperLauncher.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                throw new IOException("Cannot locate updater core for Java helper");
            }
            return new File(source.getLocation().toURI()).getCanonicalFile();
        } catch (URISyntaxException exception) {
            throw new IOException("Cannot locate updater core for Java helper", exception);
        }
    }

    private static String joinFiles(List<File> files) {
        List<String> paths = new ArrayList<String>();
        for (File file : files) {
            paths.add(file.getPath());
        }
        return join(paths, File.pathSeparator);
    }

    private static String join(List<String> values, String separator) {
        StringBuilder joined = new StringBuilder();
        for (String value : values) {
            if (joined.length() > 0) {
                joined.append(separator);
            }
            joined.append(value);
        }
        return joined.toString();
    }

    private final class HelperUpdateView implements UpdateView {
        private UpdateUiState latestState = UpdateUiState.initial();
        private GuiAdapter fallbackAdapter;
        private UpdateView fallbackView;
        private boolean opened;
        private boolean closed;

        @Override
        public void open() {
            UpdateView fallback;
            synchronized (this) {
                opened = true;
                fallback = fallbackView;
            }
            if (fallback != null) {
                dispatchFallbackOpen(fallback);
                return;
            }
            try {
                send(JavaHelperProtocol.encodeOpen());
            } catch (IllegalStateException ignored) {
                activateFallback();
            }
        }

        @Override
        public void render(UpdateUiState state) {
            Objects.requireNonNull(state, "state");
            UpdateView fallback;
            synchronized (this) {
                latestState = state;
                fallback = fallbackView;
            }
            if (fallback != null) {
                dispatchFallbackRender(fallback, state);
                return;
            }
            try {
                send(JavaHelperProtocol.encodeRender(state));
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to encode Java helper render state", exception);
            } catch (IllegalStateException ignored) {
                activateFallback();
            }
        }

        @Override
        public void close() {
            UpdateView fallback;
            synchronized (this) {
                closed = true;
                fallback = fallbackView;
            }
            expectedHelperClosure = true;
            if (fallback != null) {
                dispatchFallbackClose(fallback);
                return;
            }
            try {
                send(JavaHelperProtocol.encodeClose());
            } catch (IllegalStateException ignored) {
                // A failed helper has no visible window left to close.
            }
        }

        private void activateFallback() {
            GuiAdapter adapter;
            UpdateView view;
            UpdateUiState state;
            boolean shouldOpen;
            synchronized (this) {
                if (fallbackView != null || closed || actions == null) {
                    return;
                }
                adapter = new SwingGuiAdapterFactory().create(context);
                view = adapter.create(actions);
                fallbackAdapter = adapter;
                fallbackView = view;
                state = latestState;
                shouldOpen = opened;
            }
            final UpdateView target = view;
            final UpdateUiState snapshot = state;
            final boolean openTarget = shouldOpen;
            adapter.dispatcher().dispatch(() -> {
                if (openTarget) {
                    target.open();
                }
                target.render(snapshot);
            });
        }

        private void dispatchFallbackOpen(final UpdateView fallback) {
            GuiAdapter adapter;
            synchronized (this) {
                adapter = fallbackAdapter;
            }
            adapter.dispatcher().dispatch(fallback::open);
        }

        private void dispatchFallbackRender(final UpdateView fallback,
                                            final UpdateUiState state) {
            GuiAdapter adapter;
            synchronized (this) {
                adapter = fallbackAdapter;
            }
            adapter.dispatcher().dispatch(() -> fallback.render(state));
        }

        private void dispatchFallbackClose(final UpdateView fallback) {
            GuiAdapter adapter;
            synchronized (this) {
                adapter = fallbackAdapter;
            }
            adapter.dispatcher().dispatch(fallback::close);
        }
    }

    private static final class HelperUiDispatcher implements UiDispatcher {
        private final java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "gui-helper-dispatcher");
                    thread.setDaemon(true);
                    return thread;
                });

        @Override
        public void dispatch(Runnable task) {
            executor.execute(Objects.requireNonNull(task, "task"));
        }
    }
}
