# Minecraft Auto Update Service

> Keep Minecraft client resources in sync across machines — via a self-hosted HTTP API and a Java agent.

[中文文档](./README_CN.md)

## How It Works

| Component | Role |
|-----------|------|
| **Server** (Python/Flask, Docker) | Hosts file manifests & resource downloads via REST API. |
| **Agent** (Java, `-javaagent`) | Loaded at Minecraft startup — checks for updates, shows GUI progress, syncs files, then lets the game launch. |

```
Minecraft Launch → UpdateAgent (GUI) → HTTP → Server → Sync files → Game starts
```

## Quick Start

### Server

```bash
docker build -t mc-update-service -f Dockerfile .
docker run -d -p 25565:25565 -v /path/to/files:/data/files --name mc-update mc-update-service

# Generate manifest after placing files under /data/files
docker exec mc-update python3 /app/generate_manifest.py "1.0.0"
```

### Agent

```bash
cd agent
./build.sh                              # or build.bat on Windows
./setup-agent.sh ~/.minecraft/versions/1.20.1 http://your-server:25565
```

The setup script appends `-javaagent:UpdateAgent.jar=server=...,game-dir=...` to the launcher's JVM arguments.

## API

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/manifest` | GET | Full file manifest (paths, SHA-256, sizes) |
| `/api/files/<path>` | GET | Download a resource file |
| `/api/config` | GET | Managed paths configuration |
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

| Property | Default | Description |
|----------|---------|-------------|
| `mc-update.server` | `http://localhost:25565` | Server URL |
| `mc-update.game-dir` | `.` | Minecraft directory |
| `mc-update.debug` | `false` | Keep GUI open after sync |

Example: `-javaagent:UpdateAgent.jar=server=http://1.2.3.4:25565,game-dir=C:\mc,debug=true`

### Selective Sync (`update-config.json`)

```json
{
  "managed_paths": ["mods/", "config/", "resourcepacks/", "options.txt"]
}
```

Paths ending with `/` match directories recursively; bare names match exact files. Default: `["*"]` (all files).

## Project Structure

```
├── Dockerfile
├── server/
│   ├── app.py                  # Flask API
│   ├── entrypoint.sh           # Container entrypoint
│   ├── generate_manifest.py    # Manifest generator
│   └── requirements.txt
├── agent/
│   ├── src/UpdateAgent.java    # Java agent (pure JDK, no deps)
│   ├── META-INF/MANIFEST.MF
│   ├── build.sh / build.bat
│   └── setup-agent.sh / setup-agent.bat
```

## License

MIT
