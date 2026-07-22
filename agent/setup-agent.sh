#!/bin/bash
# ── Minecraft 客户端自动更新 Agent 安装脚本 (Linux/macOS) ─────────
# 为 Minecraft 客户端的启动参数添加 -javaagent 配置
#
# 用法:
#   ./setup-agent.sh <minecraft-instance-dir> [update-server-url]
#
# 示例:
#   ./setup-agent.sh ~/.minecraft/versions/1.20.1 http://192.168.1.100:25565
#
# 该脚本会在实例目录的 user_jvm_args.txt 或 vmoptions 文件中追加
# -javaagent 参数。如果文件不存在，会创建一个新的。
# ──────────────────────────────────────────────────────────────────

set -euo pipefail

if [ $# -lt 1 ]; then
    echo "Usage: $0 <minecraft-instance-dir> [update-server-url]"
    echo "Example: $0 ~/.minecraft/versions/1.20.1 http://192.168.1.100:25565"
    exit 1
fi

INSTANCE_DIR="$1"
SERVER_URL="${2:-http://localhost:25565}"

# 确定 Agent JAR 路径（与脚本同目录）
AGENT_JAR="$(cd "$(dirname "$0")" && pwd)/UpdateAgent.jar"

if [ ! -f "$AGENT_JAR" ]; then
    echo "[setup] ERROR: UpdateAgent.jar not found at $AGENT_JAR"
    echo "[setup] Run build.sh first to generate the JAR."
    exit 1
fi

# 寻找 JVM 参数文件
JVM_ARGS_FILE=""
if [ -f "$INSTANCE_DIR/user_jvm_args.txt" ]; then
    JVM_ARGS_FILE="$INSTANCE_DIR/user_jvm_args.txt"
elif [ -f "$INSTANCE_DIR/options.txt" ]; then
    JVM_ARGS_FILE="$INSTANCE_DIR/options.txt"
else
    # 创建 user_jvm_args.txt
    JVM_ARGS_FILE="$INSTANCE_DIR/user_jvm_args.txt"
    touch "$JVM_ARGS_FILE"
fi

AGENT_ARG="-javaagent:${AGENT_JAR}=server=${SERVER_URL}"

# 检查是否已经添加过
if grep -q "UpdateAgent" "$JVM_ARGS_FILE" 2>/dev/null; then
    echo "[setup] Agent already configured in $JVM_ARGS_FILE"
    echo "[setup] To update, edit the line manually:"
    echo "       $AGENT_ARG"
else
    echo "$AGENT_ARG" >> "$JVM_ARGS_FILE"
    echo "[setup] Added agent to $JVM_ARGS_FILE"
fi

echo "[setup] Done!"
echo "[setup] Agent JAR: $AGENT_JAR"
echo "[setup] Server:    $SERVER_URL"
echo ""
echo "下次启动 Minecraft 客户端时，将自动检查更新。"
