#!/usr/bin/env bash
# Builds a portable (statically-linked) linux-x64 mcmm and stages it, along
# with the color palette, into the plugin resources so `mvn package` bundles
# them. The plugin extracts them into its data folder on first start.
#
# Windows: cross-compilation is not done here. To bundle a Windows binary,
# build mcmm.exe on Windows (or with a mingw toolchain) and drop it at
#   src/main/resources/natives/mcmm-windows-x64.exe
#
# Run this from the plugin/ directory before `mvn package`.
set -e
cd "$(dirname "$0")"

REPO_ROOT="$(cd .. && pwd)"
CDIR="$REPO_ROOT/c version"
NATIVES_DIR="src/main/resources/natives"

mkdir -p "$NATIVES_DIR"

echo "Building static linux-x64 mcmm..."
cmake -S "$CDIR" -B "$CDIR/build-static" -DCMAKE_BUILD_TYPE=Release -DMCMM_STATIC=ON >/dev/null
cmake --build "$CDIR/build-static" >/dev/null
cp "$CDIR/build-static/mcmm" "$NATIVES_DIR/mcmm-linux-x64"
echo "  -> $NATIVES_DIR/mcmm-linux-x64"

# The palette is a committed resource (vanilla_map_colors.json), so it does not
# need staging here — only the native binary does.

echo "Done. Now run: mvn package"
