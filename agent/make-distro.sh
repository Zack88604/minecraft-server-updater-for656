#!/bin/bash
# ── Build a distribution bundle (optional) ──
# Runs build.sh first, then copies the two agent JARs into <agent>/distro/.
#
# Per v3改动说明 the JavaFX runtime is a LOCAL, rebuildable cache — it is NOT
# bundled by default: on a clean machine the agent uses Swing once and a
# background worker repairs the runtime from Maven Central for the next launch.
#
# Pass --stage-runtime to additionally pre-stage the runtime from ./lib/javafx
# into distro/javafx-runtime/<version>/ and write runtime.json + .installed
# (hashes recomputed from the actual jars, consistent with the embedded spec).
# No policy.json is ever written (no server policy in the pure client design).
# ──────────────────────────────────────────────────────────────────

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

STAGE_RUNTIME=0
if [[ "${1:-}" == "--stage-runtime" ]]; then
    STAGE_RUNTIME=1
fi

echo "[distro] Building agent JARs..."
bash "$SCRIPT_DIR/build.sh"

DISTRO_DIR="$SCRIPT_DIR/distro"
rm -rf "$DISTRO_DIR"
mkdir -p "$DISTRO_DIR"

echo "[distro] Copying agent JARs..."
cp "$SCRIPT_DIR/UpdateAgent.jar" "$DISTRO_DIR/"
cp "$SCRIPT_DIR/UpdateAgent_core.jar" "$DISTRO_DIR/"

if [[ $STAGE_RUNTIME == 1 ]]; then
    SPEC="$SCRIPT_DIR/javafx/javafx-runtime-spec.json"
    JAVAFX_LIB_DIR="$SCRIPT_DIR/lib/javafx"
    VERSION=$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$SPEC" | head -1)
    if [[ -z "$VERSION" ]]; then
        echo "[distro] ERROR: cannot read version from $SPEC"
        exit 1
    fi
    echo "[distro] Staging runtime $VERSION (win)..."
    RUNTIME_DIR="$DISTRO_DIR/javafx-runtime/$VERSION"
    mkdir -p "$RUNTIME_DIR"
    cp "$JAVAFX_LIB_DIR"/*.jar "$RUNTIME_DIR/"
    {
        echo "{"
        echo "  \"version\": \"$VERSION\","
        echo "  \"classifier\": \"win\","
        echo "  \"min_jdk\": 17,"
        echo "  \"module_path\": \"$VERSION\","
        echo "  \"artifacts\": ["
        n=0
        for jar in "$RUNTIME_DIR"/javafx-{base,graphics,controls}-*.jar; do
            [ -f "$jar" ] || continue
            name=$(basename "$jar")
            module=$(echo "$name" | sed 's/-[0-9].*//')                 # javafx-base-... -> javafx-base
            classifier=$(echo "$name" | sed -E 's/^[^-]+-[^-]+-[^-]+-([^.]*)\.jar$/\1/')
            hash=$(sha256sum "$jar" | awk '{print $1}')
            size=$(wc -c < "$jar")
            prefix="    "
            [ $n -gt 0 ] && prefix="    ,"
            echo "$prefix{ \"module\": \"$module\", \"classifier\": \"$classifier\", \"file\": \"$name\", \"size\": $size, \"hash\": \"$hash\" }"
            n=$((n+1))
        done
        echo "  ]"
        echo "}"
    } > "$DISTRO_DIR/javafx-runtime/runtime.json"
    touch "$DISTRO_DIR/javafx-runtime/.installed"
    echo "[distro] Staged runtime: $RUNTIME_DIR"
fi

echo "[distro] Done! Distribution in: $DISTRO_DIR"
