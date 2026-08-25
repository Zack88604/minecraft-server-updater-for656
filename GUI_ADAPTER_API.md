# GUI Adapter API

The updater exposes a stable, toolkit-neutral GUI boundary in
`com.zack88604.autoupdater.gui.api`. GUI code receives immutable display state
and sends only close-related user intent back to the application controller.

## Implement a factory

Implement `GuiAdapterFactory` for the GUI toolkit you want to use.

```java
public final class MyGuiAdapterFactory implements GuiAdapterFactory {
    @Override
    public GuiAdapter create(GuiAdapterContext context) {
        return new MyGuiAdapter(context);
    }
}
```

The factory class must be public and have a public no-argument constructor.

## Select it

Compile the factory and its adapter into `UpdateAgent_core.jar` alongside the
agent source, then configure its fully qualified class name.

```properties
gui-adapter=com.example.updategui.MyGuiAdapterFactory
```

The same value can be supplied as `mc-update.gui-adapter` or with inline agent
arguments.

```
-javaagent:UpdateAgent.jar=gui-adapter=com.example.updategui.MyGuiAdapterFactory
```

When no factory is configured, the built-in Swing adapter remains the default.

## External GUI presets

When no explicit gui-adapter is configured, the updater uses this fixed location
under the configured Minecraft directory:

~~~text
.mc-update/
  gui-selection.properties    # managed by the updater
  gui-presets/
    my-gui.jar
~~~

mc-update.properties remains in the game root and is not moved. On the first
launch without a saved choice, the updater scans the gui-presets directory. If
it finds a selectable JAR, a trusted Swing dialog lets the user choose that
preset or the built-in Swing GUI. The checkbox saves the selection as the
default for future launches.

A saved Swing choice starts directly. A saved external preset always shows a
risk confirmation before its JAR is loaded. If the user declines, the JAR fails
to load, or its metadata disappears, the updater falls back to Swing. Delete
the gui-selection.properties file to show the chooser again.

### Preset archive contract

A selectable JAR must contain this metadata file:

~~~properties
# META-INF/mc-update-gui.properties
name=Example GUI
version=1.0.0
factory-class=com.example.updategui.MyGuiAdapterFactory
~~~

The factory class must be public, have a public no-argument constructor, and
implement GuiAdapterFactory. Compile against UpdateAgent_core.jar as a provided
dependency; do not bundle updater API or core classes inside the preset.

Discovery reads only this metadata and does not load preset classes. The class
is loaded only after the user confirms the warning. External JARs execute code
in the game process and may read or modify files, access the network, or affect
the game. Install only files from a trusted source.

GuiAdapterContext provides both the game root and the fixed updater
configuration directory for presentation resources.

## Adapter rules

- Implement `GuiAdapter`, `UiDispatcher`, and `UpdateView`; use
  `GuiAdapterContext` only for presentation settings.
- The controller invokes every `UpdateView` method through the adapter's UI
  dispatcher.
- `render(UpdateUiState)` receives a complete replacement snapshot. Do not
  infer business state from prior strings or retain mutable updater state.
- The view must call `UpdateViewActions` for close requests and native-window
  closure. It must not release the launch latch or terminate the JVM itself.
- Do not access updater HTTP, file, or process code from the adapter.

## Public model

The render state is composed from these immutable API types:

- UpdateUiState: phase, message, server, progress, result, and error.
- DownloadProgress: current resource and transfer metrics.
- ClosePolicy: confirmation, successful close, and failure-exit behavior.
- UpdateViewActions: close intent and native-window close notification.
