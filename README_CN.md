# Minecraft 自动更新服务

> 通过自托管 HTTP API 与 Java Agent，在多台机器间保持 Minecraft 客户端资源同步更新。

[English](./README.md)

## 工作原理

| 组件 | 职责 |
|------|------|
| **服务端** (Python/Flask, Docker) | 托管文件清单与资源下载的 REST API。 |
| **Agent** (Java, `-javaagent`) | Minecraft 启动时加载 — 检查更新、显示 GUI 进度、同步文件，完成后启动游戏。 |

Agent 分为两个 JAR，实现安全的自更新：

| JAR | 职责 |
|-----|------|
| `UpdateAgent.jar`（启动器） | 由 `-javaagent` 加载的薄封装层。启动时替换核心 JAR，然后委托给核心逻辑。**永不更新**，因此无文件锁问题。 |
| `UpdateAgent_core.jar`（核心） | 实际的更新逻辑：HTTP 同步、GUI、文件清理。**可自更新** — 新版本下载为 `.jar.new`，下次启动时替换。 |

```
Minecraft 启动 → 启动器 → （如有 .new 则替换核心 JAR）→ 核心 Agent (GUI) → HTTP 请求 → 服务器 → 同步文件 → 游戏启动
```

## 快速开始

### 服务端

```bash
# 从上级目录构建
docker build -t mc-update-service -f Dockerfile .

# 挂载文件存储目录运行
docker run -d -p 25565:25565 -v /path/to/files:/data/files -v /path/to/agent:/data/agent --name mc-update mc-update-service

# 将 UpdateAgent_core.jar 放入 agent 目录
cp UpdateAgent_core.jar /path/to/agent/

# 将资源文件放入 /data/files 后生成清单
docker exec mc-update python3 /app/generate_manifest.py --dir /data/files --out /data --agent-jar /data/agent/UpdateAgent_core.jar
```

### Agent

```bash
cd agent
./build.sh                              # Windows 用 build.bat
./setup-agent.sh ~/.minecraft/versions/1.20.1 http://your-server:25565
```

安装脚本会将服务器配置写入游戏目录下的 `mc-update.properties`，并向启动器 JVM 参数追加 `-javaagent:<path>/UpdateAgent.jar`。

## API

| 端点 | 方法 | 描述 |
|------|------|------|
| `/api/v2/manifest` | GET | 完整文件清单（路径、SHA-256、大小） |
| `/api/files/<path>` | GET | 下载指定资源文件 |
| `/api/agent` | GET | 下载最新 `UpdateAgent_core.jar` |
| `/api/config` | GET | 管理路径与排除路径配置 |
| `/api/generate` | POST | 重新生成清单（Token 保护） |
| `/api/health` | GET | 健康检查 |

## 配置

### 服务端（环境变量）

| 变量 | 默认值 | 描述 |
|------|--------|------|
| `PORT` | `25565` | HTTP 端口 |
| `GENERATE_TOKEN` | *(空)* | 保护 `/api/generate` 接口 |
| `DEBUG` | `false` | Flask 调试模式 |

### Agent（JVM 属性）

配置优先级（普通模式）如下：

1. 游戏目录下的 `mc-update.properties` *（由安装脚本写入）*
2. 内联 `-javaagent` 参数
3. `-D` 系统属性
4. 内置默认值

| 属性 | 默认值 | 描述 |
|------|--------|------|
| `mc-update.server` | `http://localhost:25565` | 服务器地址 — 支持**逗号分隔多源**，自动故障转移 |
| `mc-update.game-dir` | `.` | Minecraft 目录 |
| `mc-update.debug` | `false` | 同步完成后保持窗口打开 |
| `mc-update.ui` | `auto` | UI 工具包：`auto`（默认）、`javafx` 或 `swing`。`auto` 在本地 runtime 就绪时使用 JavaFX helper，否则回退 Swing |

**推荐方式：`mc-update.properties`**（由安装脚本写入）：
```properties
server=http://cdn1.example.com:25565,http://cdn2.example.com:8443
```

**内联 agent 参数**：
```
-javaagent:UpdateAgent.jar=server=http://1.2.3.4:25565,game-dir=C:\mc,debug=true
```

**多源故障转移**（某源不可用时自动切换）：
```
-javaagent:UpdateAgent.jar=server=http://cdn1.example.com:25565,http://cdn2.example.com:8443
```

**管理员模式**（`admin=true`）反转配置优先级：agent 参数 > 系统属性 > 配置文件 — 适用于一次性覆盖：
```
-javaagent:UpdateAgent.jar=admin=true,server=http://override:25565
```

### 选择性同步 (`update-config.json`)

```json
{
  "managed_paths": ["mods/", "config/", "resourcepacks/", "options.txt"],
  "excluded_paths": ["config/secret.cfg", "mods/skip_this/"]
}
```

以 `/` 结尾匹配目录（递归），否则精确匹配文件。`excluded_paths` 优先级高于 `managed_paths` — 被排除的文件既不同步也不清理。默认值：`managed_paths: ["*"]`，`excluded_paths: []`。

## 界面

更新窗口由两个并行的视图之一渲染，它们实现同一个与工具包无关的 `UpdateView`
契约 — **Swing**（默认）与 **JavaFX**（实验性）。业务层从不接触 UI 类型：它只
发出 `UpdateEvent`（阶段、进度、日志），由视图负责渲染，因此两种工具包可以互换。

### 更新阶段

| 阶段 | 视觉表现 |
|------|----------|
| **Preparing（准备中）** | 获取清单并执行自更新检查 — 不确定状态进度条。更新器自更新是该阶段的子状态。 |
| **Checking（检查中）** | 对照清单对受管文件进行哈希校验 — 确定性进度条 + 百分比（“X of Y files checked”）。 |
| **Downloading（下载中）** | 下载受管文件；当前文件区域显示路径、单文件进度条与下载速度。 |
| **Cleaning（清理中）** | 移除过期文件 — 不确定状态进度条。 |
| **Success（成功）** | 流程完成且无失败 — 绿色标题，进度条 100%。 |
| **Error（失败）** | 流程失败（异常或部分失败）— 红色标题，隐藏总进度条，自动展开 Details。 |

阶段由 `UpdateEvent.StatusChanged` 显式携带；视图不会从状态文本中推断阶段。

### 窗口布局

- **状态标题 + 描述** — 每个阶段对应稳定的标题与重新措辞的副标题；原始业务状态字符串不会逐字显示。
- **总体进度** — 细进度条 + 旁边的百分比（在 Preparing/Cleaning 不确定阶段隐藏）。
- **当前文件区域** — 正在下载的文件/JAR：路径、单文件进度条、下载速度；空闲时隐藏。
- **Details（可折叠）** — 服务器地址、游戏目录与完整日志；默认折叠，出错与调试模式下自动展开。
- **关闭窗口** — 更新进行中关闭会弹窗确认（“Quit update?”）。终态阶段：成功会短暂延迟后自动关闭；失败（错误状态）则保持窗口打开，直到用户手动关闭——关闭错误窗口后进程退出、不启动 Minecraft。调试模式额外提供一个 Close 按钮，流程允许前保持禁用。

### JavaFX 视图（独立 helper JVM）

更新窗口也可以使用 JavaFX 而非 Swing 渲染。JavaFX 视图位于 `agent/javafx/`，
实现与工具包无关的同一个 `UpdateView` 契约。它运行在**独立的 helper JVM** 中
（`--module-path javafx-runtime/<version> --add-modules javafx.controls -cp
UpdateAgent_core.jar JavaFxEntryPoint`）；Minecraft JVM 永不加载 `javafx.*`。
两个进程通过 stdin/stdout 上的 JSONL 通信（`EventCodec`）。helper 无法启动、
runtime 缺失或损坏时，agent 静默改用 Swing 视图，Minecraft 启动永不受影响。

**runtime 来源完全在客户端。** 每个发行版本把
`/javafx-runtime-spec.json`（版本、平台、文件名、大小、SHA-256）内置进核心
JAR。agent jar 旁的 `javafx-runtime/` 只是**可自动重建的本地缓存**：干净机器
首次启动用 Swing，后台 `javafx-runtime-worker` 从 Maven Central
（`org/openjfx/...`）下载缺失/损坏的 jar，校验 SHA-256 后原子替换；
JavaFX 视图从**下次**启动开始使用。服务端 manifest/API 完全不参与 JavaFX
runtime。

使用方式：

1. **构建**核心 JAR（JavaFX 视图始终编译进去）：
   ```bash
   cd agent
   ./build.sh        # Windows 用 build.bat
   ```
   JavaFX 21 运行时 jar（`javafx-base`、`javafx-graphics`、
   `javafx-controls`、`javafx-swing`，win 分类器）缺失时，构建脚本会自动从
   Maven Central 下载到 `agent/lib/javafx/`。这些 jar 是编译期依赖，也是
   可选预置 runtime（`./make-distro.sh --stage-runtime`）的来源。
2. **运行**：默认 `mc-update.ui=auto` 在 `javafx-runtime/` READY（与内置 spec
   一致）且能找到 helper JVM 的 `java` 时使用 JavaFX，否则回退 Swing。
   `mc-update.ui=javafx` / `mc-update.ui=swing` 可强制指定；
   `remove-javafx=true` 会删除本地 runtime 缓存并强制 Swing。

#### 状态插图与布局

标题栏左侧预留了一个 **64×64 的状态插图槽位** —— 透明 PNG，随更新阶段切换：
每个阶段一张插图（`preparing` / `checking` / `downloading` / `cleaning` /
`success` / `error`），PREPARING 的自更新子状态另有独立的 `updater` 插图。
图片作为 JAR 资源 `/images/*.png` 加载，源文件在 `agent/images/` 下，由
构建脚本打包进核心 JAR；正式插图到位前作为占位图。**图片缺失或损坏时
槽位自动隐藏**，不影响布局与更新流程。

窗口高度为**内容驱动**：Details 展开（出错或调试模式）时窗口按内容撑开，而
不是跳到固定高度，展开状态不再留底部空白。Details 展开箭头做了弱化（仅悬停
/聚焦时高亮），调试模式的 Close 按钮位于独立底栏行（上方带分隔细线）。

### 截图

每一种界面状态都保存在 [`screenshots/`](screenshots/) 中 — 由开发工具
`agent/devtools/UiScreenshotHarness.java` 离屏渲染生成：

| 文件 | 状态 |
|------|------|
| `01_preparing.png` | 准备中 |
| `02_updater_download.png` | 更新器自更新（准备中的子状态） |
| `03_checking.png` | 检查中 |
| `04_downloading.png` | 下载中 |
| `05_cleaning.png` | 清理中 |
| `06_success.png` | 成功 |
| `07_partial_failure.png` | 部分失败（失败状态） |
| `08_error.png` | 异常失败（失败状态） |
| `09_debug_close_disabled.png` | 调试窗口，流程进行中关闭按钮禁用 |
| `10_debug_close_enabled.png` | 调试窗口，流程完成后关闭按钮可用 |
| `11_quit_alert.png` | “Quit update?” 退出确认弹窗 |

> **重新生成占位插图与截图**（在 `agent/` 目录下）：
> ```bash
> # 1. 重新生成占位状态插图（写入 agent/images/）
> javac -encoding UTF-8 -d build-harness devtools/GenImages.java
> java -cp build-harness GenImages
>
> # 2. 重新渲染每种界面状态到 screenshots/*.png
> javac -encoding UTF-8 --module-path lib/javafx --add-modules javafx.controls,javafx.swing \
>       -cp "lib/javafx/*" -d build-harness src/*.java javafx/*.java devtools/*.java
> cp javafx/ui.css build-harness/
> java --module-path lib/javafx --add-modules javafx.controls,javafx.swing \
>       -cp "build-harness;.;lib/javafx/*" UiScreenshotHarness
> ```
> 类路径里的 `.`（即 `agent/` 目录）让视图能解析 `agent/images/` 下的
> `/images/*.png`；正式构建改为从 JAR 内加载。

## 项目结构

```
├── Dockerfile
├── LICENSE
├── README.md
├── README_CN.md
├── screenshots/              # 每种界面状态的截图，由开发工具生成
├── server/
│   ├── app.py                  # Flask API（清单、文件、Agent、配置、健康检查）
│   ├── entrypoint.sh           # 容器入口
│   ├── generate_manifest.py    # 扫描文件、计算 SHA-256、生成清单 JSON
│   └── requirements.txt
└── agent/
    ├── META-INF/MANIFEST.MF   # Premain-Class: Launcher
    ├── src/                   # 业务层 + Swing 视图 + JavaFX helper-JVM 桥（始终编译）
    │   ├── Launcher.java           # -javaagent 入口；用 .new 替换核心 JAR 并动态加载
    │   ├── UpdateAgent.java        # 核心入口（premain）：配置解析 + 更新流程
    │   ├── UpdateApplication.java  # 组合根：串联服务、视图与控制器；不持有任何流程决策
    │   ├── UpdateController.java   # 控制器/流程层：协调服务、视图与应用流程；决定启动/成功/失败/关闭/延迟/释放 latch
    │   ├── UpdateService.java      # 更新逻辑：清单、哈希校验、下载、清理、自更新；发出 UpdateEvent
    │   ├── UpdateEvent.java        # 统一的业务事件模型（不依赖 Swing）
    │   ├── UpdatePhase.java        # 更新流程共享的视觉阶段枚举（与 UI 工具包无关）
    │   ├── UpdateListener.java     # 业务层 → 界面事件回调接口（不依赖 Swing）
    │   ├── UpdateView.java         # 与 UI 工具包无关的视图契约（open/close/状态等；不含 Swing/JavaFX 类型）
    │   ├── UpdateViewListener.java # 视图 → 控制器的用户操作回调（关闭窗口 / 调试关闭按钮）
    │   ├── UpdateGUI.java          # Swing 界面（状态、进度、日志、速度）；实现 UpdateView
    │   ├── UiModel.java            # 传给界面的不可变展示数据
    │   ├── UiSnapshot.java         # 传给视图的界面状态快照（含 Swing 回退路径）
    │   ├── ViewApplier.java        # 将 UpdateEvent 应用到当前视图状态
    │   ├── RemoteUpdateView.java   # 经 JSONL 代理到 JavaFX helper JVM 的 agent 侧 UpdateView
    │   ├── UiDispatcher.java       # 对 UI 工具包「在 UI 线程执行」的抽象
    │   ├── SwingUiDispatcher.java  # 基于 Swing EDT 的 UiDispatcher 实现
    │   ├── DirectUiDispatcher.java # 在 helper JVM 内直接运行于 JavaFX 平台线程的 UiDispatcher
    │   ├── JavaFxHelperProcess.java    # 启动/管理 JavaFX helper JVM 子进程
    │   ├── JavaFxRuntimeManager.java   # 依据内置 spec 校验/下载本地 javafx-runtime/ 缓存
    │   ├── UpdateResult.java       # 更新结果：updated / failed 计数
    │   ├── ServerClient.java       # 支持多源故障转移的 HTTP 客户端
    │   ├── FileManager.java        # 路径安全检查、SHA-256、原子替换、过期文件清理
    │   ├── Manifest.java           # 解析后的清单模型（文件 + 管理/排除路径 + Agent）
    │   ├── FileEntry.java          # 清单中的单个文件条目（路径、哈希、大小）
    │   ├── DownloadProgress.java   # 单文件下载进度快照（工作线程 ↔ 界面）
    │   ├── JsonParser.java         # 轻量 JSON 解析辅助（无外部依赖）
    │   ├── EventCodec.java         # Minecraft JVM 与 helper JVM 之间的 JSONL IPC 编解码
    │   └── FormatUtil.java         # 格式化辅助（如下载速度）
    ├── images/                 # JavaFX 视图的状态插图（每阶段一张）；打包进核心 JAR
    ├── javafx/                 # JavaFX 视图 — UpdateView 的并行实现，始终编译；运行于 helper JVM
    │   ├── JavaFxEntryPoint.java    # helper-JVM 入口：读 stdin JSONL、渲染视图、经 stdout 应答
    │   ├── JavaFxUpdateView.java    # 实现 UpdateView 的 JavaFX 视图（六种状态 + 状态插图槽位，由 /ui.css 提供样式）
    │   ├── ui.css                   # 窗口与对话框共用的深色扁平视觉系统
    │   └── javafx-runtime-spec.json # 内置纯客户端 spec：本地 runtime 缓存的版本/平台/artifact SHA-256
    ├── devtools/               # 仅开发用工具 — 不打包进 Agent JAR
    │   ├── UiScreenshotHarness.java  # 离屏渲染每种界面状态到 screenshots/*.png
    │   ├── GenImages.java            # 生成占位状态插图到 images/
    │   ├── ImageCheck.java           # 校验生成的插图（边界、图标、透明）
    │   ├── ScreenshotProbe.java      # 校验每张截图是否显示了对应阶段插图
    │   ├── PhaseSwitchTest.java      # JavaFX 视图快速连续阶段切换测试
    │   ├── WindowBoundsCheck.java    # 校验 Details 展开时窗口居中/不越界
    │   └── VerifyLocalProbe.java     # 覆盖 MISSING/READY/CORRUPTED 校验 JavaFxRuntimeManager.verifyLocal()
    ├── lib/javafx/             # JavaFX 21 运行时 jar（javafx-base/-graphics/-controls/-swing，win）— 编译期依赖 + 预置 runtime 来源
    ├── build.sh / build.bat    # 编译并打包两个 JAR（始终包含 JavaFX 视图、ui.css、images/、内置 spec）
    ├── make-distro.sh          # 可选发行包；--stage-runtime 预置 javafx-runtime/<ver>/ + runtime.json（不写 policy.json）
    └── setup-agent.sh / setup-agent.bat  # 写入配置并追加 -javaagent 到 JVM 参数
```

构建产物：
- `UpdateAgent.jar` — 启动器 JAR（由 `-javaagent` 加载）
- `UpdateAgent_core.jar` — 核心 Agent JAR（动态加载）

## 许可证

MIT
