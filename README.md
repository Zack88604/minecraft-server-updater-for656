# Minecraft Auto Update Service

> Keep Minecraft client resources in sync across machines — via a self-hosted HTTP API and a Java agent.

[中文文档](./README_CN.md)

## How It Works

| Component | Role |
|-----------|------|
| **Server** (Python/Flask, Docker) | Hosts file manifests & resource downloads via REST API. |
| **Agent** (Java, `-javaagent`) | Loaded at Minecraft startup — checks for updates, shows GUI progress, syncs files, then lets the game launch. |

The agent is split into two JARs for safe self-updating:

| JAR | Role |
|-----|------|
| `UpdateAgent.jar` (Launcher) | Thin wrapper loaded by `-javaagent`. Replaces the core JAR at startup, then delegates to it. **Never updated**, so no file-lock issues. |
| `UpdateAgent_core.jar` (Core) | The actual update logic: HTTP sync, GUI, file cleanup. **Can be self-updated** — a new version is downloaded as `.jar.new` and swapped in on next launch. |

```
Minecraft Launch → Launcher → (swap core JAR if .new exists) → Core Agent (GUI) → HTTP → Server → Sync files → Game starts
```

## Quick Start

### Server

```bash
# Build from the parent directory
docker build -t mc-update-service -f Dockerfile .

# Run with file storage mounted
docker run -d -p 25565:25565 -v /path/to/files:/data/files -v /path/to/agent:/data/agent --name mc-update mc-update-service

# Place UpdateAgent_core.jar in the agent directory
cp UpdateAgent_core.jar /path/to/agent/

# Generate manifest after placing files under /data/files
docker exec mc-update python3 /app/generate_manifest.py --dir /data/files --out /data --agent-jar /data/agent/UpdateAgent_core.jar
```

### Agent

```bash
cd agent
./build.sh                              # or build.bat on Windows
./setup-agent.sh ~/.minecraft/versions/1.20.1 http://your-server:25565
```

The setup script writes server configuration to `mc-update.properties` in the game directory, and appends `-javaagent:<path>/UpdateAgent.jar` to the launcher's JVM arguments.

## API

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v2/manifest` | GET | Full file manifest (paths, SHA-256, sizes) |
| `/api/files/<path>` | GET | Download a resource file |
| `/api/agent` | GET | Download the latest `UpdateAgent_core.jar` |
| `/api/config` | GET | Managed paths & excluded paths configuration |
| `/api/generate` | POST | Regenerate manifest (token-protected) |
| `/api/health` | GET | Health check |

## Configuration

### Server (env vars)

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `25565` | HTTP port |
| `GENERATE_TOKEN` | *(empty)* | Protects `/api/generate` |
| `DEBUG` | `false` | Flask debug mode |

### Agent (JVM properties)

Configuration is resolved in this order (normal mode):

1. `mc-update.properties` in the game directory *(written by setup script)*
2. Inline `-javaagent` arguments
3. `-D` system properties
4. Built-in defaults

| Property | Default | Description |
|----------|---------|-------------|
| `mc-update.server` | `http://localhost:25565` | Server URL(s) — comma-separated for **multi-source fallback** |
| `mc-update.game-dir` | `.` | Minecraft directory |
| `mc-update.debug` | `false` | Keep GUI open after sync |

**Recommended: `mc-update.properties`** (written by setup script):
```properties
server=http://cdn1.example.com:25565,http://cdn2.example.com:8443
```

**Inline agent args**:
```
-javaagent:UpdateAgent.jar=server=http://1.2.3.4:25565,game-dir=C:\mc,debug=true
```

**Multi-server fallback** (automatically tries next server on failure):
```
-javaagent:UpdateAgent.jar=server=http://cdn1.example.com:25565,http://cdn2.example.com:8443
```

**Admin mode** (`admin=true`) reverses config priority: agent args > system props > config file — useful for one-off overrides:
```
-javaagent:UpdateAgent.jar=admin=true,server=http://override:25565
```

### Selective Sync (`update-config.json`)

```json
{
  "managed_paths": ["mods/", "config/", "resourcepacks/", "options.txt"],
  "excluded_paths": ["config/secret.cfg", "mods/skip_this/"]
}
```

Paths ending with `/` match directories recursively; bare names match exact files. `excluded_paths` override `managed_paths` — excluded files are neither synced nor cleaned up. Default: `managed_paths: ["*"]`, `excluded_paths: []`.

## Project Structure

```
├── Dockerfile
├── server/
│   ├── app.py                  # Flask API
│   ├── entrypoint.sh           # Container entrypoint
│   ├── generate_manifest.py    # Manifest generator
│   └── requirements.txt
├── agent/
│   ├── src/Launcher.java       # Launcher
│   ├── src/UpdateAgent.java    # Core agent
│   ├── META-INF/MANIFEST.MF
│   ├── build.sh / build.bat    # Builds both JARs
│   └── setup-agent.sh / setup-agent.bat
```

Build output:
- `UpdateAgent.jar` — Launcher JAR (loaded by `-javaagent`)
- `UpdateAgent_core.jar` — Core agent JAR (loaded dynamically)

## License

MIT
