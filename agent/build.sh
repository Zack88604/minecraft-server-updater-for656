#!/bin/bash
# ── Minecraft Client Update Java Agent Build Script (Linux/macOS) ──
# Usage: ./build.sh
# Output: UpdateAgent.jar
# ──────────────────────────────────────────────────────────────────

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$SCRIPT_DIR/src"
BUILD_DIR="$SCRIPT_DIR/build"
OUTPUT_JAR="$SCRIPT_DIR/UpdateAgent.jar"

echo "[build] Compiling UpdateAgent.java..."
mkdir -p "$BUILD_DIR"
javac -d "$BUILD_DIR" "$SRC_DIR/UpdateAgent.java" "$SRC_DIR/ReplaceHelper.java"

echo "[build] Packaging into JAR..."
cd "$BUILD_DIR"
jar cfm "$OUTPUT_JAR" "$SCRIPT_DIR/META-INF/MANIFEST.MF" *.class

echo "[build] Done! Created: $OUTPUT_JAR"

# Clean up temp class files
rm -rf "$BUILD_DIR"
