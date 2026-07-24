# Minecraft 自动更新服务

> 通过自托管 HTTP API 与 Java Agent，在多台机器间保持 Minecraft 客户端资源同步更新。

[English](./README.md)

## 工作原理

| 组件 | 职责 |
|------|------|
| **服务端** (Python/Flask, Docker) | 托管文件清单与资源下载的 REST API。 |
| **Agent** (Java, `-javaagent`) | Minecraft 启动时加载 — 检查更新、显示 GUI 进度、同步文件，完成后启动游戏。 |

```
Minecraft 启动 → UpdateAgent (GUI) → HTTP 请求 → 服务器 → 同步文件 → 游戏启动
```

## 快速开始

### 服务端

```bash
docker build -t mc-update-service -f Dockerfile .
docker run -d -p 25565:25565 -v /path/to/files:/data/files --name mc-update mc-update-service

# 将资源文件放入 /data/files 后生成清单
docker exec mc-update python3 /app/generate_manifest.py "1.0.0"
```

### Agent

```bash
cd agent
./build.sh                              # Windows 用 build.bat
./setup-agent.sh ~/.minecraft/versions/1.20.1 http://your-server:25565
```

安装脚本会自动将 `-javaagent:UpdateAgent.jar=server=...,game-dir=...` 追加到启动器 JVM 参数中。

## API

| 端点 | 方法 | 描述 |
|------|------|------|
| `/api/manifest` | GET | 完整文件清单（路径、SHA-256、大小） |
| `/api/files/<path>` | GET | 下载指定资源文件 |
| `/api/config` | GET | 管理路径配置 |
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

| 属性 | 默认值 | 描述 |
|------|--------|------|
| `mc-update.server` | `http://localhost:25565` | 服务器地址 |
| `mc-update.game-dir` | `.` | Minecraft 目录 |
| `mc-update.debug` | `false` | 同步完成后保持窗口打开 |

示例：`-javaagent:UpdateAgent.jar=server=http://1.2.3.4:25565,game-dir=C:\mc,debug=true`

### 选择性同步 (`update-config.json`)

```json
{
  "managed_paths": ["mods/", "config/", "resourcepacks/", "options.txt"]
}
```

以 `/` 结尾匹配目录（递归），否则精确匹配文件。默认 `["*"]`（全部文件）。

## 项目结构

```
├── Dockerfile
├── server/
│   ├── app.py                  # Flask API
│   ├── entrypoint.sh           # 容器入口
│   ├── generate_manifest.py    # 清单生成器
│   └── requirements.txt
├── agent/
│   ├── src/UpdateAgent.java    # Java Agent（纯 JDK，无外部依赖）
│   ├── META-INF/MANIFEST.MF
│   ├── build.sh / build.bat
│   └── setup-agent.sh / setup-agent.bat
```

## 许可证

MIT
