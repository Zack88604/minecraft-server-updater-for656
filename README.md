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
| `mc-update.ui` | `auto` | UI toolkit: `auto` (default), `javafx`, or `swing`. `auto` uses the JavaFX helper when the local runtime is ready, otherwise falls back to Swing |

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

## UI

The update window is rendered by one of two parallel views that implement the
same toolkit-agnostic `UpdateView` contract — **Swing** (default) and **JavaFX**
(experimental). The business layer never touches UI types: it emits
`UpdateEvent`s (phases, progress, logs) and the view renders them, so the two
toolkits are interchangeable.

### Update phases

| Phase | Visual behaviour |
|-------|------------------|
| **Preparing** | Fetching the manifest and running the self-update check — indeterminate bar. The updater self-update is a sub-state shown here. |
| **Checking** | Hashing managed files against the manifest — determinate bar + percentage ("X of Y files checked"). |
| **Downloading** | Downloading managed files; the current-file area shows the path, a per-file bar and the download speed. |
| **Cleaning** | Removing stale files — indeterminate bar. |
| **Success** | Flow completed with no failures — green title, bar at 100%. |
| **Error** | Flow failed (exception or partial failure) — red title, overall bar hidden, Details auto-expanded. |

The phase is carried explicitly by `UpdateEvent.StatusChanged`; the view never
infers it from status text.

### Window layout

- **Status title + description** — a stable headline and re-worded subtitle per
  phase; the raw business status string is never shown verbatim.
- **Overall progress** — thin bar with a percentage next to it (hidden during
  the indeterminate Preparing/Cleaning phases).
- **Current-file area** — the file/JAR currently downloading: path, per-file
  bar, download speed. Hidden while idle.
- **Details** (collapsible) — server URL, game directory and the full log.
  Collapsed by default, auto-expanded on error and in debug mode.
- **Closing the window** — while an update is running the close request asks for
  confirmation ("Quit update?"); in the terminal Success/Error phases it closes
  directly. Debug mode adds a Close button that stays disabled until the flow
  allows it.

### JavaFX view (helper JVM)

The update window can also be rendered with JavaFX instead of Swing. The JavaFX
view lives in `agent/javafx/` and implements the same toolkit-agnostic
`UpdateView` contract. It runs in a **separate helper JVM** (`--module-path
javafx-runtime/<version> --add-modules javafx.controls -cp
UpdateAgent_core.jar JavaFxEntryPoint`); the Minecraft JVM never loads
`javafx.*`. The two processes talk JSONL over stdin/stdout
(`EventCodec`). If the helper cannot be launched or the runtime is missing or
corrupt, the agent silently uses the Swing view instead and the Minecraft
launch is never affected.

**Runtime sourcing is pure client-side.** Each agent release embeds
`/javafx-runtime-spec.json` (version, platform, filenames, sizes, SHA-256) into
the core JAR. `javafx-runtime/` next to the agent JARs is a *locally-rebuildable
cache*: on a clean machine the first launch uses Swing and a background
`javafx-runtime-worker` downloads the missing/corrupt jars from Maven Central
(`org/openjfx/...`), verifying SHA-256 and atomically replacing each file;
the JavaFX view is used from the *next* launch. The server manifest/API is never
involved in the JavaFX runtime.

To use it:

1. **Build** the core JAR (the JavaFX view is always compiled in):
   ```bash
   cd agent
   ./build.sh        # or build.bat on Windows
   ```
   The JavaFX 21 runtime jars (`javafx-base`, `javafx-graphics`,
   `javafx-controls`, `javafx-swing`, win classifier) are auto-downloaded from
   Maven Central into `agent/lib/javafx/` by the build when missing. These jars
   are the *compile-time* dependency and the source for an optional pre-staged
   runtime (`./make-distro.sh --stage-runtime`).
2. **Run**: the default `mc-update.ui=auto` picks JavaFX when
   `javafx-runtime/` is READY (matching the embedded spec) and the helper
   JVM's `java` is found; otherwise it falls back to Swing. Force either way
   with `mc-update.ui=javafx` or `mc-update.ui=swing`. `remove-javafx=true`
   deletes the local runtime cache and forces Swing.

#### Status illustrations & layout

The header reserves a 64×64 slot for a transparent PNG that switches with the
update phase — one art per phase (`preparing`, `checking`, `downloading`,
`cleaning`, `success`, `error`), plus a separate `updater` art for the
self-update sub-state of Preparing. Images are JAR resources under
`/images/*.png`, sourced from `agent/images/` and bundled into the core JAR by
the `--javafx` build; until the real art is provided they act as placeholders.
A missing or corrupt image simply hides the slot — it never affects the layout
or the update flow.

The window height is content-driven: expanding Details (error state or debug
mode) grows the window to fit its content rather than jumping to a fixed height,
so expanded states leave no dead space at the bottom. The Details expand arrow
is de-emphasised (accent only on hover/focus), and in debug mode the Close
button sits in its own footer row above a separator line.

### Screenshots

Every visual state is captured in [`screenshots/`](screenshots/), rendered
off-screen by the dev harness `agent/devtools/UiScreenshotHarness.java`:

| File | State |
|------|-------|
| `01_preparing.png` | Preparing |
| `02_updater_download.png` | Updater self-update (sub-state of Preparing) |
| `03_checking.png` | Checking |
| `04_downloading.png` | Downloading |
| `05_cleaning.png` | Cleaning |
| `06_success.png` | Success |
| `07_partial_failure.png` | Partial failure (Error state) |
| `08_error.png` | Exception failure (Error state) |
| `09_debug_close_disabled.png` | Debug window, close disabled during the flow |
| `10_debug_close_enabled.png` | Debug window, close enabled after completion |
| `11_quit_alert.png` | "Quit update?" confirmation dialog |

> **Regenerating the placeholder art and screenshots** (from `agent/`):
> ```bash
> # 1. (re)generate the placeholder status illustrations into agent/images/
> javac -encoding UTF-8 -d build-harness devtools/GenImages.java
> java -cp build-harness GenImages
>
> # 2. render every UI state to screenshots/*.png
> javac -encoding UTF-8 --module-path lib/javafx --add-modules javafx.controls,javafx.swing \
>       -cp "lib/javafx/*" -d build-harness src/*.java javafx/*.java devtools/*.java
> cp javafx/ui.css build-harness/
> java --module-path lib/javafx --add-modules javafx.controls,javafx.swing \
>       -cp "build-harness;.;lib/javafx/*" UiScreenshotHarness
> ```
> The `.` entry on the classpath lets the view resolve `/images/*.png` from
> `agent/images/`; the shipped build gets them from the JAR instead.

## Project Structure

```
├── Dockerfile
├── LICENSE
├── README.md
├── README_CN.md
├── screenshots/              # UI screenshots of every state, generated by the dev harness
├── server/
│   ├── app.py                  # Flask API (manifest, files, agent, config, health)
│   ├── entrypoint.sh           # Container entrypoint
│   ├── generate_manifest.py    # Scans files, computes SHA-256, writes manifest JSON
│   └── requirements.txt
└── agent/
    ├── META-INF/MANIFEST.MF   # Premain-Class: Launcher
    ├── src/                   # Business layer + Swing view + JavaFX helper-JVM bridge (always compiled)
    │   ├── Launcher.java           # -javaagent entry; swaps core JAR from .new, then loads it
    │   ├── UpdateAgent.java        # Core entry (premain): config resolution + update flow
    │   ├── UpdateApplication.java  # Composition root: wires service + view + controller; holds no flow decisions
    │   ├── UpdateController.java   # Controller/flow layer: coordinates service + view + app flow; decides start/success/failure/close/delay/latch release
    │   ├── UpdateService.java      # Update logic: manifest, hashing, download, cleanup, self-update; emits UpdateEvents
    │   ├── UpdateEvent.java        # Unified business event model (no Swing dependency)
    │   ├── UpdatePhase.java        # Shared visual phase enum for the update flow (toolkit-agnostic)
    │   ├── UpdateListener.java     # Business→UI event callback interface (no Swing dependency)
    │   ├── UpdateView.java         # Toolkit-agnostic UI contract (open/close/status/...; no Swing/JavaFX types)
    │   ├── UpdateViewListener.java # View→controller user-action callback (window close / debug close)
    │   ├── UpdateGUI.java          # Swing UI (status, progress, log, speed); implements UpdateView
    │   ├── UiModel.java            # Immutable display data handed to the UI
    │   ├── UiSnapshot.java         # Immutable UI-state snapshot used by the view (incl. the Swing fallback)
    │   ├── ViewApplier.java        # Applies UpdateEvents to the current view state
    │   ├── RemoteUpdateView.java   # Agent-side UpdateView proxying to the JavaFX helper JVM over JSONL
    │   ├── UiDispatcher.java       # "Run on UI thread" abstraction over the UI toolkit
    │   ├── SwingUiDispatcher.java  # UiDispatcher backed by Swing's EDT
    │   ├── DirectUiDispatcher.java # UiDispatcher running on the JavaFX platform thread inside the helper JVM
    │   ├── JavaFxHelperProcess.java    # Spawns/manages the JavaFX helper JVM subprocess
    │   ├── JavaFxRuntimeManager.java   # Verifies/downloads the local javafx-runtime/ cache against the embedded spec
    │   ├── UpdateResult.java       # Update outcome: updated / failed counts
    │   ├── ServerClient.java       # HTTP client with multi-server fallback
    │   ├── FileManager.java        # Path-safety, SHA-256, atomic replace, stale-file cleanup
    │   ├── Manifest.java           # Parsed manifest model (files + managed/excluded paths + agent)
    │   ├── FileEntry.java          # Single manifest file entry (path, hash, size)
    │   ├── DownloadProgress.java   # Per-file download progress snapshot (worker ↔ UI)
    │   ├── JsonParser.java         # Lightweight JSON parsing helpers (no external deps)
    │   ├── EventCodec.java         # JSONL IPC codec between the Minecraft JVM and the JavaFX helper JVM
    │   └── FormatUtil.java         # Formatting helpers (e.g. download speed)
    ├── images/                 # Status illustrations for the JavaFX view (one per phase); bundled into the core JAR
    ├── javafx/                 # JavaFX view — parallel impl of UpdateView, always compiled; runs in the helper JVM
    │   ├── JavaFxEntryPoint.java    # Helper-JVM main: reads JSONL on stdin, renders the view, replies on stdout
    │   ├── JavaFxUpdateView.java    # JavaFX view implementing UpdateView (six phases, status-illustration slot, /ui.css styling)
    │   ├── ui.css                   # Dark flat visual system shared by the window and dialogs
    │   └── javafx-runtime-spec.json # Embedded pure-client spec: version/platform/artifact SHA-256 for the local runtime cache
    ├── devtools/               # Dev-only tools — never shipped in the agent JARs
    │   ├── UiScreenshotHarness.java  # Off-screen harness; renders every UI state to screenshots/*.png
    │   ├── GenImages.java            # Generates the placeholder status illustrations into images/
    │   ├── ImageCheck.java           # Verifies generated illustrations (bounds, glyph, transparency)
    │   ├── ScreenshotProbe.java      # Verifies each screenshot shows its phase illustration
    │   ├── PhaseSwitchTest.java      # Rapid consecutive phase-switch test for the JavaFX view
    │   ├── WindowBoundsCheck.java    # Verifies the window stays centred/on-screen when Details expands
    │   └── VerifyLocalProbe.java     # Exercises JavaFxRuntimeManager.verifyLocal() across MISSING/READY/CORRUPTED
    ├── lib/javafx/             # JavaFX 21 runtime jars (javafx-base/-graphics/-controls/-swing, win) — compile-time dep + pre-stage source
    ├── build.sh / build.bat    # Compile + package both JARs (always includes the JavaFX view, ui.css, images/, embedded spec)
    ├── make-distro.sh          # Optional distro bundle; --stage-runtime pre-stages javafx-runtime/<ver>/ + runtime.json (no policy.json)
    └── setup-agent.sh / setup-agent.bat  # Write config + append -javaagent to JVM args
```

Build output:
- `UpdateAgent.jar` — Launcher JAR (loaded by `-javaagent`)
- `UpdateAgent_core.jar` — Core agent JAR (loaded dynamically)

## License

MIT
