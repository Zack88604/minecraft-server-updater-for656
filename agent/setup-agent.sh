#!/bin/bash
# ── Minecraft Client Auto-Update Agent Setup Script (Linux/macOS) ──
# Creates mc-update.properties in the instance directory and appends
# a minimal -javaagent JVM argument (no inline parameters).
#
# Usage:
#   setup-agent.sh <minecraft-instance-dir> [update-server-url]
#
# Example:
#   setup-agent.sh ~/.minecraft http://192.168.1.100:25565
# ──────────────────────────────────────────────────────────────────

set -euo pipefail

if [ $# -lt 1 ]; then
    echo "Usage: $0 <minecraft-instance-dir> [update-server-url]"
    echo "Example: $0 ~/.minecraft/versions/1.20.1 http://192.168.1.100:25565"
    exit 1
fi

INSTANCE_DIR="$1"
SERVER_URL="${2:-http://localhost:25565}"

# Determine Agent JAR path (same directory as this script)
AGENT_JAR="$(cd "$(dirname "$0")" && pwd)/UpdateAgent.jar"

if [ ! -f "$AGENT_JAR" ]; then
    echo "[setup] ERROR: UpdateAgent.jar not found at $AGENT_JAR"
    echo "[setup] Run build.sh first to generate the JAR."
    exit 1
fi

# ---- Write persistent config file ----
CONFIG_FILE="$INSTANCE_DIR/mc-update.properties"
cat > "$CONFIG_FILE" << EOF
# Minecraft Update Agent Configuration
server=$SERVER_URL
EOF
echo "[setup] Config written: $CONFIG_FILE"

# ---- Add -javaagent JVM argument (JAR path only, no inline params) ----
JVM_ARGS_FILE=""
if [ -f "$INSTANCE_DIR/user_jvm_args.txt" ]; then
    JVM_ARGS_FILE="$INSTANCE_DIR/user_jvm_args.txt"
elif [ -f "$INSTANCE_DIR/options.txt" ]; then
    JVM_ARGS_FILE="$INSTANCE_DIR/options.txt"
else
    JVM_ARGS_FILE="$INSTANCE_DIR/user_jvm_args.txt"
    touch "$JVM_ARGS_FILE"
fi

AGENT_ARG="-javaagent:${AGENT_JAR}"

# Check if already configured
if grep -q "UpdateAgent" "$JVM_ARGS_FILE" 2>/dev/null; then
    echo "[setup] Agent already configured in $JVM_ARGS_FILE"
    echo "[setup] To update config, edit: $CONFIG_FILE"
else
    echo "$AGENT_ARG" >> "$JVM_ARGS_FILE"
    echo "[setup] Added agent to $JVM_ARGS_FILE"
fi

echo "[setup] Done!"
echo "[setup] Agent JAR: $AGENT_JAR"
echo "[setup] Config:    $CONFIG_FILE"
echo "[setup] Server:    $SERVER_URL"
echo ""
echo "Next time you launch Minecraft, updates will be checked automatically."
