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

## 外部 GUI 预设

未显式配置 gui-adapter 时，更新器会使用游戏根目录下的固定位置：

~~~text
.mc-update/
  gui-selection.properties    # 由更新器管理
  gui-presets/
    my-gui.jar
~~~

根目录的 mc-update.properties 保持原位置，不会被移动。首次启动且尚未保存选择时，
更新器会扫描 gui-presets 目录；发现可选择的 JAR 后，会由受信任的 Swing 窗口让用户
选择外部预设或内建 Swing GUI。窗口中的复选框决定是否把本次选择保存为后续启动的默认
GUI。

已保存的 Swing 默认选择会直接启动。已保存的外部预设在每次加载 JAR 前都仍会显示风险
确认；拒绝确认、加载失败或预设元数据消失时，更新器会回退到 Swing。删除
gui-selection.properties 即可再次显示选择窗口。

### 预设 JAR 契约

可选择的 JAR 必须包含以下元数据文件：

~~~properties
# META-INF/mc-update-gui.properties
name=Example GUI
version=1.0.0
factory-class=com.example.updategui.MyGuiAdapterFactory
~~~

工厂类必须为 public、具有 public 无参构造函数，并实现 GuiAdapterFactory。请以
UpdateAgent_core.jar 作为 provided 依赖编译，不要把更新器 API 或 core 类再次打包进
预设 JAR。

扫描时只读取元数据，不会加载预设类；只有用户确认风险后才会加载工厂类。外部 JAR 会在
游戏进程中执行代码，可能读取或修改文件、访问网络、影响游戏进程；只能安装来自可信来源
的文件。

GuiAdapterContext 提供游戏根目录以及固定的更新器配置目录，供展示资源使用。

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
