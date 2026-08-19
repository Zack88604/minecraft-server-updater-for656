#!/bin/bash
# ── Minecraft Client Update Java Agent Build Script (Linux/macOS) ──
# Usage: ./build.sh
# Output: UpdateAgent.jar (launcher) + UpdateAgent_core.jar (core)
# ──────────────────────────────────────────────────────────────────

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$SCRIPT_DIR/src"
BUILD_DIR="$SCRIPT_DIR/build"
LAUNCHER_JAR="$SCRIPT_DIR/UpdateAgent.jar"
CORE_JAR="$SCRIPT_DIR/UpdateAgent_core.jar"

echo "[build] Compiling..."
mkdir -p "$BUILD_DIR"
javac -d "$BUILD_DIR" "$SRC_DIR/Launcher.java" "$SRC_DIR/UpdateAgent.java"

echo "[build] Packaging launcher JAR..."
cd "$BUILD_DIR"
jar cfm "$LAUNCHER_JAR" "$SCRIPT_DIR/META-INF/MANIFEST.MF" Launcher.class Launcher\$*.class 2>/dev/null || true

echo "[build] Packaging core JAR..."
# Temporarily exclude Launcher classes from core JAR
for f in Launcher.class Launcher\$*.class; do
    [ -f "$f" ] && mv "$f" "$f.exclude"
done
jar cf "$CORE_JAR" *.class
# Restore Launcher classes
for f in *.class.exclude; do
    [ -f "$f" ] && mv "$f" "${f%.exclude}"
done

echo "[build] Done!"
echo "[build] Launcher: $LAUNCHER_JAR"
echo "[build] Core:     $CORE_JAR"

# Clean up temp class files
cd "$SCRIPT_DIR"
rm -rf "$BUILD_DIR"
