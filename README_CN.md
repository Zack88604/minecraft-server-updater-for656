# Minecraft 自动更新服务

为 Minecraft 客户端提供自包含的自动更新方案，由 Docker 容器（HTTP API）和 Java Agent 组成。运行时无外部脚本或进程调用 — 杀软白名单只需添加一个 JAR。

## 架构

```
  Minecraft 客户端 (Java Agent)              Docker 容器 (Flask API)
  ┌──────────────────────────┐              ┌──────────────────────────┐
  │ -javaagent:UpdateAgent.jar│   HTTP GET   │ /api/version     版本号   │
  │ premain() 阻塞启动        │◄────────────►│ /api/manifest    文件清单 │
  │ GUI 显示进度              │              │ /api/files/*     文件下载 │
  │ 下载更新文件              │              │ /api/generate    触发生成 │
  │ 自动关闭 → MC 启动        │              │                        │
  └──────────────────────────┘              │ 日志 → stdout (docker)   │
                                            └──────────────────────────┘
                                                     ▲
                                                     │ 人工维护
                                            ┌────────┴────────┐
                                            │ generate_manifest│
                                            │ (docker exec     │
                                            │  或 HTTP API)    │
                                            └─────────────────┘
```

## 项目结构

```
656-auto-update/
├── Dockerfile                     # Docker 镜像构建
├── README.md
├── server/                        # 服务端（容器内运行）
│   ├── app.py                     # Flask HTTP API
│   ├── generate_manifest.py       # 资源清单生成工具
│   ├── entrypoint.sh              # 容器启动入口
│   └── requirements.txt           # 仅供参考，Flask 通过 apk 安装
├── agent/                         # 客户端 Java Agent
│   ├── src/UpdateAgent.java       # Agent 源码（纯 Java，无外部依赖）
│   ├── META-INF/MANIFEST.MF       # JAR 清单文件
│   ├── build.sh                   # 编译脚本 (Linux)
│   ├── build.bat                  # 编译脚本 (Windows)
│   ├── setup-agent.sh             # 安装脚本 (Linux)
│   ├── setup-agent.bat            # 安装脚本 (Windows)
│   └── UpdateAgent.jar            # 编译产物
└── data/                          # 数据目录（挂载卷）
    ├── files/                     # 需要分发的资源文件
    ├── update-config.json         # 管理路径配置
    ├── manifest.json              # 生成的资源清单
    └── version.txt                # 当前版本号
```

---

## 服务端部署

### 1. 构建镜像

```bash
# 在项目根目录 (d:\dockerserver) 执行
docker build -t mc-update-service -f 656-auto-update/Dockerfile .
```

### 2. 配置管理路径

在将要挂载为 `/data` 的目录下创建 `update-config.json`：

```json
{
  "managed_paths": [
    "mods/",
    "config/",
    "resourcepacks/",
    "shaderpacks/"
  ]
}
```

规则：
- `mods/` — 以 `/` 结尾表示目录，匹配目录下所有文件
- `options.txt` — 精确匹配单个文件
- `*` — 匹配所有文件（配置文件不存在时的默认行为）

### 3. 启动容器

```bash
docker run -d \
  --name mc-update \
  -p 25565:25565 \
  -v /你的/数据目录:/data \
  mc-update-service
```

首次启动时若不存在清单，会自动生成版本号为 `0` 的默认清单。

### 4. 生成资源清单

更新 `/data/files/` 下的文件后，重新生成清单：

```bash
# 方式一：docker exec
docker exec mc-update python3 /app/generate_manifest.py "1.1"

# 方式二：HTTP API
curl -X POST "http://<IP>:25565/api/generate?version=1.1"

# 方式三：自动使用时间戳
docker exec mc-update python3 /app/generate_manifest.py
```

如需保护生成接口，启动时设置 `GENERATE_TOKEN`：

```bash
docker run -d -e GENERATE_TOKEN=mysecret ...
curl -X POST -H "X-Generate-Token: mysecret" "http://<IP>:25565/api/generate?version=1.1"
```

### 5. 查看日志

```bash
docker logs -f mc-update
```

所有日志直接输出到 stdout，容器内无日志文件。

---

## 客户端部署（Java Agent）

### 1. 编译 Agent

```bash
# Windows
cd 656-auto-update\agent
build.bat

# Linux / macOS
cd 656-auto-update/agent
chmod +x build.sh && ./build.sh
```

需要 JDK 8+。输出文件：`UpdateAgent.jar`。

### 2. 添加 JVM 参数

在启动器（HMCL / PCL2 / Prism / 官方启动器）的 JVM 参数中添加：

```
-javaagent:/path/to/UpdateAgent.jar=server=http://192.168.1.100:25565
```

| Agent 参数 | 系统属性 | 默认值 | 说明 |
|-----------|---------|--------|------|
| `server` | `mc-update.server` | `http://localhost:25565` | 更新服务地址 |
| `game-dir` | `mc-update.game-dir` | 游戏目录 | Minecraft 目录 |
| `debug` | `mc-update.debug` | `false` | 检查完成后保持窗口打开 |

多个参数用逗号分隔：

```
-javaagent:UpdateAgent.jar=server=http://1.2.3.4:25565,game-dir=C:\mc,debug=true
```

### 3. 自动安装脚本（可选）

```bash
# Linux
./agent/setup-agent.sh ~/.minecraft http://192.168.1.100:25565

# Windows
agent\setup-agent.bat C:\Users\You\AppData\Roaming\.minecraft http://192.168.1.100:25565
```

脚本会自动将 `-javaagent` 追加到 `user_jvm_args.txt`。

---

## 工作流程

1. **Agent 在 Minecraft 之前加载**（`premain()`）
2. **阻塞 Minecraft 启动**（`CountDownLatch`）
3. **弹出 GUI 窗口**，显示服务器、游戏目录、进度条、日志面板
4. **GET /api/version** → 与本地 `.update_version` 比较
5. 版本不同时：**GET /api/manifest** → 逐文件处理
6. 对每个文件：计算本地 **SHA256** → 缺失或 hash 不一致则下载
7. **清理过期文件**：管理目录内不在清单中的文件将被删除
8. **释放 latch** → Minecraft 以更新后的文件启动
9. 窗口自动关闭（debug 模式下保持打开）

### Debug 模式

```
-javaagent:UpdateAgent.jar=server=...,debug=true
```

开启后窗口在检查完成后不会自动关闭，显示 "Close" 按钮，方便查看完整日志。

---

## API 接口

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/version` | 返回 `{"version":"1.0"}` |
| GET | `/api/manifest` | 返回 `{"version":"1.0","managed_paths":[...],"files":[...]}` |
| GET | `/api/files/<path>` | 下载资源文件 |
| GET | `/api/config` | 服务端配置（managed_paths） |
| GET | `/api/health` | 健康检查 `{"status":"ok","manifest":true,"version":true}` |
| POST | `/api/generate?version=1.0` | 触发清单重新生成 |

---

## 版本生命周期

```
人工更新流程：
  修改 /data/files/ 下的文件
    → docker exec ... generate_manifest.py "1.1"
      → 扫描文件、计算 SHA256、写入 manifest.json + version.txt
        → 客户端检测到新版本 → 下载更新 → 完成

客户端启动流程：
  启动 Minecraft
    → Agent 请求 /api/version
      → 版本一致？直接启动
      → 版本不同？下载清单、逐文件校验 hash、下载更新
        → Agent 窗口关闭 → Minecraft 以最新文件启动
```

---

## 文件管理策略

客户端只操作清单中列出且位于 `managed_paths` 范围内的文件：

- 在 `managed_paths` 内但**不在清单中** → **删除**（清理过期文件）
- 在 `managed_paths` 内且**在清单中** → **校验 hash 并按需更新**
- **不在** `managed_paths` 内 → **完全不动**（如存档、截图等）

---

## 常见问题

| 现象 | 排查方向 |
|------|----------|
| Agent 窗口显示 localhost | 检查 `server=` 参数或 `-Dmc-update.server=` 是否正确 |
| "Manifest contains 0 file(s)" | 确认 manifest 中 `size` 字段为整数（非字符串） |
| 文件下载 404 | 文件路径含特殊字符，检查 URL 编码 |
| renameTo 失败 (Windows) | Agent 已在 rename 前删除目标文件 |
| 窗口一闪而过 | 开启 `debug=true` 模式 |
| 无法连接服务器 | 检查防火墙、端口映射、`docker logs` |
