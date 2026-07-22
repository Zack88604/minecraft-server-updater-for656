#!/bin/bash
# ── Minecraft 客户端更新 Java Agent 编译脚本 (Linux/macOS) ────────
# 用法: ./build.sh
# 输出: UpdateAgent.jar
# ──────────────────────────────────────────────────────────────────

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$SCRIPT_DIR/src"
BUILD_DIR="$SCRIPT_DIR/build"
OUTPUT_JAR="$SCRIPT_DIR/UpdateAgent.jar"

echo "[build] Compiling UpdateAgent.java..."
mkdir -p "$BUILD_DIR"
javac -d "$BUILD_DIR" "$SRC_DIR/UpdateAgent.java"

echo "[build] Packaging into JAR..."
cd "$BUILD_DIR"
jar cfm "$OUTPUT_JAR" "$SCRIPT_DIR/META-INF/MANIFEST.MF" *.class

echo "[build] Done! Created: $OUTPUT_JAR"

# 清理临时 class 文件
rm -rf "$BUILD_DIR"
