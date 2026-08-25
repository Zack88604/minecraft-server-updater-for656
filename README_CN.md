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

## GUI Adapter 开发

GUI 开发者可以在不修改更新逻辑与生命周期控制的情况下接入其他工具包。
内建 Swing adapter 仍为默认实现；自定义 adapter 的实现与配置方式见 [GUI Adapter API 中文说明](GUI_ADAPTER_API_CN.md)。

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
| `mc-update.gui-adapter` | *（内建 Swing）* | `GuiAdapterFactory` 的完整类名 |

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

## 项目结构

```
├── Dockerfile
├── server/
│   ├── app.py                  # Flask API
│   ├── entrypoint.sh           # 容器入口
│   ├── generate_manifest.py    # 清单生成器
│   └── requirements.txt
├── agent/
│   ├── src/Launcher.java       # 启动器
│   ├── src/UpdateAgent.java    # 核心 Agent
│   ├── META-INF/MANIFEST.MF
│   ├── build.sh / build.bat    # 构建两个 JAR
│   └── setup-agent.sh / setup-agent.bat
```

构建产物：
- `UpdateAgent.jar` — 启动器 JAR（由 `-javaagent` 加载）
- `UpdateAgent_core.jar` — 核心 Agent JAR（动态加载）

## 许可证

MIT
