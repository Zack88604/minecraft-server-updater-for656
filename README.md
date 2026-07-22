# Minecraft Auto-Update Service

A self-contained auto-update system for Minecraft clients, consisting of a Docker-based HTTP server and a Java Agent. No external scripts or processes at runtime — single JAR whitelist for antivirus.

## Architecture

```
  Minecraft Client (Java Agent)              Docker Container (Flask API)
  ┌──────────────────────────┐              ┌──────────────────────────┐
  │ -javaagent:UpdateAgent.jar│   HTTP GET   │ /api/version     version │
  │ premain() blocks launch   │◄────────────►│ /api/manifest    files   │
  │ GUI shows progress        │              │ /api/files/*     downloads│
  │ Downloads updated files   │              │ /api/generate    trigger │
  │ Auto-closes → MC starts   │              │                        │
  └──────────────────────────┘              │ Logs → stdout (docker)   │
                                            └──────────────────────────┘
                                                     ▲
                                                     │ manual
                                            ┌────────┴────────┐
                                            │ generate_manifest│
                                            │    (via docker   │
                                            │     exec or API) │
                                            └─────────────────┘
```

## Project Structure

```
656-auto-update/
├── Dockerfile                     # Docker image build
├── README.md
├── server/                        # Server (inside Docker container)
│   ├── app.py                     # Flask HTTP API
│   ├── generate_manifest.py       # Manifest generator CLI
│   ├── entrypoint.sh              # Container entrypoint
│   └── requirements.txt           # (reference only, Flask via apk)
├── agent/                         # Client Java Agent
│   ├── src/UpdateAgent.java       # Agent source (pure Java, no deps)
│   ├── META-INF/MANIFEST.MF       # JAR manifest
│   ├── build.sh                   # Build script (Linux)
│   ├── build.bat                  # Build script (Windows)
│   ├── setup-agent.sh             # Install agent to MC instance (Linux)
│   ├── setup-agent.bat            # Install agent to MC instance (Windows)
│   └── UpdateAgent.jar            # Pre-built agent (after build)
└── data/                          # Data dir (volume mount)
    ├── files/                     # Resource files to distribute
    ├── update-config.json         # Managed paths config
    ├── manifest.json              # Generated manifest
    └── version.txt                # Current version
```

---

## Server Setup

### 1. Build Docker Image

```bash
# From project root (d:\dockerserver)
docker build -t mc-update-service -f 656-auto-update/Dockerfile .
```

### 2. Configure Managed Paths

Create `data/update-config.json` in the directory you'll mount as `/data`:

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

Rules:
- `mods/` — directory (trailing `/`), matches all files under it
- `options.txt` — exact file match
- `*` — all files (used when config is absent)

### 3. Run Container

```bash
docker run -d \
  --name mc-update \
  -p 25565:25565 \
  -v /path/to/your/data:/data \
  mc-update-service
```

The container starts and auto-generates a default manifest (version `0`) if none exists.

### 4. Generate Manifest

After updating files in `/data/files/`, trigger manifest regeneration:

```bash
# Option A: docker exec
docker exec mc-update python3 /app/generate_manifest.py "1.1"

# Option B: HTTP API
curl -X POST "http://<ip>:25565/api/generate?version=1.1"

# Option C: Auto timestamp version
docker exec mc-update python3 /app/generate_manifest.py
```

To protect the generate endpoint, set `GENERATE_TOKEN` env var:

```bash
docker run -d -e GENERATE_TOKEN=mysecret ...
curl -X POST -H "X-Generate-Token: mysecret" "http://<ip>:25565/api/generate?version=1.1"
```

### 5. View Logs

```bash
docker logs -f mc-update
```

All logs go to stdout — no log files inside the container.

---

## Client Setup (Java Agent)

### 1. Build the Agent

```bash
# Windows
cd 656-auto-update\agent
build.bat

# Linux / macOS
cd 656-auto-update/agent
chmod +x build.sh && ./build.sh
```

Requires JDK 8+. Output: `UpdateAgent.jar`.

### 2. Add to Minecraft Launcher

Add this JVM argument in your launcher (HMCL / PCL2 / Prism / official):

```
-javaagent:/path/to/UpdateAgent.jar=server=http://192.168.1.100:25565
```

| Agent arg | System property | Default | Description |
|-----------|----------------|---------|-------------|
| `server` | `mc-update.server` | `http://localhost:25565` | Update server URL |
| `game-dir` | `mc-update.game-dir` | `.minecraft` dir | Game directory |
| `debug` | `mc-update.debug` | `false` | Keep window open after check |

Multiple args separated by comma:

```
-javaagent:UpdateAgent.jar=server=http://1.2.3.4:25565,game-dir=C:\mc,debug=true
```

### 3. Auto-Install Script (Optional)

```bash
# Linux
./agent/setup-agent.sh ~/.minecraft http://192.168.1.100:25565

# Windows
agent\setup-agent.bat C:\Users\You\AppData\Roaming\.minecraft http://192.168.1.100:25565
```

This appends the `-javaagent` line to `user_jvm_args.txt`.

---

## How It Works

1. **Agent loads** before Minecraft via `premain()`
2. **Blocks Minecraft launch** with `CountDownLatch`
3. **GUI opens** showing server, game dir, progress bar, log panel
4. **GET /api/version** → compare with local `.update_version`
5. If different: **GET /api/manifest** → iterate files
6. For each file: compare **SHA256** → download if missing or mismatched
7. **Clean stale files** in managed directories (not in manifest → delete)
8. **Release latch** → Minecraft launches with updated files
9. Window auto-closes (or stays open in debug mode)

### Debug Mode

```
-javaagent:UpdateAgent.jar=server=...,debug=true
```

In debug mode, the window stays open after completion with a "Close" button, allowing log inspection.

---

## API Reference

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/version` | `{"version":"1.0"}` |
| GET | `/api/manifest` | `{"version":"1.0","managed_paths":[...],"files":[...]}` |
| GET | `/api/files/<path>` | Download a resource file |
| GET | `/api/config` | Server config (managed_paths) |
| GET | `/api/health` | `{"status":"ok","manifest":true,"version":true}` |
| POST | `/api/generate?version=1.0` | Trigger manifest regeneration |

---

## Version Lifecycle

```
Manual update flow:
  Edit files in /data/files/
    → docker exec ... generate_manifest.py "1.1"
      → Scans files, computes SHA256, writes manifest.json + version.txt
        → Clients see new version → download updated files → done

Client startup flow:
  Launch Minecraft
    → Agent checks /api/version
      → Same as local? Launch immediately
      → Different? Download manifest, compare hashes, download changed files
        → Agent closes → Minecraft starts with updated files
```

---

## Files Managed by the Client

The client only touches files listed in the manifest and within `managed_paths`:

- Files **in** `managed_paths` but **not in manifest** → **deleted** (stale cleanup)
- Files **in managed_paths** and **in manifest** → **hash-checked and updated**
- Files **outside** `managed_paths` → **never touched** (e.g. saves, screenshots)

---

## Troubleshooting

| Symptom | Check |
|---------|-------|
| Agent window shows localhost | Set `server=` in agent args or `-Dmc-update.server=` |
| "Manifest contains 0 files" | Check `jsonGetInt` fix; ensure manifest `size` is integer |
| Download 404 | Path encoding; check special characters in filenames |
| renameTo fails (Windows) | Agent now deletes target before rename |
| Window closes too fast | Enable `debug=true` mode |
| Can't connect to server | Check firewall, port mapping, container logs |
