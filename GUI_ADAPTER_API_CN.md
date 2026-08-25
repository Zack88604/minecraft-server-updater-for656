# GUI Adapter API（中文）

更新器在 `com.zack88604.autoupdater.gui.api` 中提供稳定、与 GUI 工具包无关的
接口边界。GUI 代码接收不可变的展示状态，并且只能将关闭相关的用户意图传回
应用层 controller。

## 实现工厂

为要使用的 GUI 工具包实现 `GuiAdapterFactory`。

```java
public final class MyGuiAdapterFactory implements GuiAdapterFactory {
    @Override
    public GuiAdapter create(GuiAdapterContext context) {
        return new MyGuiAdapter(context);
    }
}
```

工厂类必须是 `public`，并具有 `public` 的无参构造函数。

## 选择工厂

将工厂和 adapter 与 agent 源码一起编译到 `UpdateAgent_core.jar`，然后配置其
完整类名。

```properties
gui-adapter=com.example.updategui.MyGuiAdapterFactory
```

也可以通过 `mc-update.gui-adapter` 系统属性或内联 agent 参数传入同一值。

```
-javaagent:UpdateAgent.jar=gui-adapter=com.example.updategui.MyGuiAdapterFactory
```

未配置工厂时，仍会使用内建的 Swing adapter。

## Adapter 规则

- 实现 `GuiAdapter`、`UiDispatcher` 与 `UpdateView`；`GuiAdapterContext` 仅用于
  获取展示相关的启动设置。
- controller 会通过 adapter 的 UI dispatcher 调用全部 `UpdateView` 方法。
- `render(UpdateUiState)` 收到的是完整替换状态。不要根据旧的展示字符串推断
  业务状态，也不要保留可变的更新器状态。
- 视图必须通过 `UpdateViewActions` 上报关闭请求和原生窗口已经关闭的事件；不得
  自行释放启动锁或终止 JVM。
- adapter 不得直接访问更新器的 HTTP、文件或进程代码。

## 公开数据模型

渲染状态由以下不可变 API 类型组成：

- `UpdateUiState`：阶段、消息、服务器、进度、最终结果与错误。
- `DownloadProgress`：当前资源及传输指标。
- `ClosePolicy`：确认关闭、成功关闭或失败退出策略。
- `UpdateViewActions`：用户关闭意图与原生窗口关闭通知。
