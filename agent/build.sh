#!/bin/bash
# ── Minecraft Client Update Java Agent Build Script (Linux/macOS) ──
# Phase 1 (JavaFX helper JVM) on the latest-upstream baseline: compiles the
# gui/javafx package against the bundled JavaFX 21 build jars and produces two
# JARs:
#   UpdateAgent.jar        launcher (javaagent bootstrap, never updated)
#   UpdateAgent_core.jar   core + gui/javafx helper classes + embedded
#                          /ui.css + /images + /javafx-runtime-spec.json
# The helper JVM runs on the core JAR (its -cp). JavaFX 21 runtime jars
# (javafx-base/graphics/controls/swing, win classifier) are auto-downloaded
# into ./lib/javafx/ from Maven Central when missing. The Minecraft JVM never
# loads javafx.*: only JavaFxEntryPoint / JavaFxUpdateView import JavaFX, and
# those are only loaded inside the helper JVM.
# Output dir: <script dir>/  (UpdateAgent.jar + UpdateAgent_core.jar)
# ──────────────────────────────────────────────────────────────────

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"   # resolve relative paths below regardless of invocation directory
SRC_DIR="$SCRIPT_DIR/src"
JAVAFX_RES_DIR="$SCRIPT_DIR/javafx"      # ui.css + javafx-runtime-spec.json
JAVAFX_LIB_DIR="$SCRIPT_DIR/lib/javafx"
BUILD_DIR="$SCRIPT_DIR/build"
LAUNCHER_JAR="$SCRIPT_DIR/UpdateAgent.jar"
CORE_JAR="$SCRIPT_DIR/UpdateAgent_core.jar"
RUNTIME_SPEC="$JAVAFX_RES_DIR/javafx-runtime-spec.json"

# Auto-download the JavaFX 21 build jars from Maven Central if missing
# (keeps lib/javafx out of the repo; fresh clones build without manual steps).
JAVAFX_VERSION="21.0.4"
JAVAFX_CLASSIFIER="win"
JAVAFX_MAVEN="https://repo1.maven.org/maven2/org/openjfx"

mkdir -p "$JAVAFX_LIB_DIR"
for module in javafx-base javafx-graphics javafx-controls javafx-swing; do
    file="$module-$JAVAFX_VERSION-$JAVAFX_CLASSIFIER.jar"
    if [[ ! -f "$JAVAFX_LIB_DIR/$file" ]]; then
        echo "[build] Downloading $file from Maven Central..."
        if ! curl -fSL -o "$JAVAFX_LIB_DIR/$file" "$JAVAFX_MAVEN/$module/$JAVAFX_VERSION/$file"; then
            echo "[build] ERROR: failed to download $file"
            echo "[build] Get it manually from: $JAVAFX_MAVEN/$module/$JAVAFX_VERSION/"
            exit 1
        fi
    fi
done
if [[ ! -f "$RUNTIME_SPEC" ]]; then
    echo "[build] ERROR: missing embedded runtime spec: $RUNTIME_SPEC"
    exit 1
fi

echo "[build] Compiling core + gui/javafx helper..."
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"
find "$SRC_DIR" -type f -name '*.java' -print0 \
    | xargs -0 javac --release 17 -encoding UTF-8 -cp "lib/javafx/*" -d "$BUILD_DIR"
# Bundle the stylesheet + illustrations + runtime spec so the helper can load them.
cp "$JAVAFX_RES_DIR/ui.css" "$BUILD_DIR/ui.css"
cp -r "$SCRIPT_DIR/images" "$BUILD_DIR/images"
cp "$RUNTIME_SPEC" "$BUILD_DIR/javafx-runtime-spec.json"

echo "[build] Packaging launcher JAR..."
cd "$BUILD_DIR"
jar cfm "$LAUNCHER_JAR" "$SCRIPT_DIR/META-INF/MANIFEST.MF" Launcher.class

echo "[build] Packaging core JAR (everything except the launcher) + css/images/runtime spec..."
# Keep the launcher out of the self-updatable core, but include the default
# package UpdateAgent compatibility facade and all named-package classes.
jar cf "$CORE_JAR" UpdateAgent.class com ui.css images javafx-runtime-spec.json

cd "$SCRIPT_DIR"
rm -rf "$BUILD_DIR"

echo "[build] Done!"
echo "[build] Launcher: $LAUNCHER_JAR"
echo "[build] Core:     $CORE_JAR"
