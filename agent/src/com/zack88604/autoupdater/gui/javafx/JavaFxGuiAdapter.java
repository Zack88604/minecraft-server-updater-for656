package com.zack88604.autoupdater.gui.javafx;

import com.zack88604.autoupdater.gui.api.GuiAdapter;
import com.zack88604.autoupdater.gui.api.GuiAdapterContext;
import com.zack88604.autoupdater.gui.api.UiDispatcher;
import com.zack88604.autoupdater.gui.api.UpdateView;
import com.zack88604.autoupdater.gui.api.UpdateViewActions;
import com.zack88604.autoupdater.gui.swing.SwingGuiAdapterFactory;

import java.util.Objects;

/**
 * JavaFX adapter for the update GUI.
 *
 * <p>The Minecraft JVM never loads {@code javafx.*}; the window lives in a separate
 * helper JVM. So {@link #dispatcher()} returns a <b>direct</b> dispatcher — there is
 * no JavaFX UI thread in this JVM, and every view call only enqueues a JSONL message
 * (thread-safe, non-blocking). The helper JVM runs {@code Platform.runLater} itself.</p>
 *
 * <p>The Swing fallback is built through the public creation path
 * ({@code SwingGuiAdapterFactory → SwingGuiAdapter → create + dispatcher}, 第一阶段
 * §强制约束 4) and handed to {@link RemoteJavaFxUpdateView}, which switches to it
 * transparently when the helper cannot start or dies.</p>
 */
public final class JavaFxGuiAdapter implements GuiAdapter {

    private final GuiAdapterContext context;
    private final GuiAdapter swingFallback;
    private final UiDispatcher directDispatcher = task -> task.run();

    public JavaFxGuiAdapter(GuiAdapterContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.swingFallback = new SwingGuiAdapterFactory().create(context);
    }

    @Override
    public UiDispatcher dispatcher() {
        return directDispatcher;
    }

    @Override
    public UpdateView create(UpdateViewActions actions) {
        Objects.requireNonNull(actions, "actions");
        RemoteJavaFxUpdateView view = new RemoteJavaFxUpdateView(actions, swingFallback);
        JavaFxHelperProcess.launch(context, view);
        return view;
    }
}
