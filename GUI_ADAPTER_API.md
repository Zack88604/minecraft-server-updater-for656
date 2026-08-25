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
