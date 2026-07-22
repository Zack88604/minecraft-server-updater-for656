#!/bin/sh
set -e

# ── Minecraft 自动更新服务 ── 容器启动入口 ────────────────────────

# 生成本次启动的时间戳作为日志文件名
STARTUP_TS=$(date -u +'%Y%m%d_%H%M%S')
export STARTUP_TS

# 确保日志目录存在
mkdir -p /data/logs
echo "[update-service] Log file: /data/logs/${STARTUP_TS}.log"

# 如果 manifest 不存在，自动生成一个默认清单
if [ ! -f /data/manifest.json ]; then
    echo "[update-service] WARNING: No manifest found. Generating default manifest..."
    python3 /app/generate_manifest.py --dir /data/files --out /data
fi

echo "[update-service] Starting update service..."
echo "[update-service] Files:   $(python3 -c "import json; d=json.load(open('/data/manifest.json')); print(len(d.get('files',[])))")"

# 启动 Flask API 服务
exec python3 /app/app.py
