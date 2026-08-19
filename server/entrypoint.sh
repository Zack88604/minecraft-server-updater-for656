#!/bin/sh
set -e

# ── Minecraft Auto-Update Service ── Container Entrypoint ──────────

# Generate timestamp for log filename
STARTUP_TS=$(date -u +'%Y%m%d_%H%M%S')
export STARTUP_TS

# Ensure log directory exists
mkdir -p /data/logs
echo "[update-service] Log file: /data/logs/${STARTUP_TS}.log"

# Auto-generate default manifest if missing
if [ ! -f /data/manifest.json ]; then
    echo "[update-service] WARNING: No manifest found. Generating default manifest..."
    AGENT_ARG=""
    if [ -f /data/agent/UpdateAgent_core.jar ]; then
        AGENT_ARG="--agent-jar /data/agent/UpdateAgent_core.jar"
    fi
    python3 /app/generate_manifest.py --dir /data/files --out /data $AGENT_ARG
fi

echo "[update-service] Starting update service..."
echo "[update-service] Files:   $(python3 -c "import json; d=json.load(open('/data/manifest.json')); print(len(d.get('files',[])))")"

# Start Flask API service
exec python3 /app/app.py
