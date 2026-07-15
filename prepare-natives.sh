#!/usr/bin/env bash
# Rebuilds the portable (statically-linked) linux-x64 mcmm binary and stages it
# into src/main/resources/natives/ so `mvn package` bundles it. The plugin
# extracts it into its data folder on first start.
#
# The mcmm C source lives in a SEPARATE repository (MinecraftVideo, "c version/").
# Point MCMM_SRC at that "c version" directory, or place the two repos side by
# side so the default works:
#   /home/you/GitHub/MinecraftVideo/c version   <- C source
#   /home/you/GitHub/VMC                         <- this plugin
#
# The committed binary already works; re-run this only after changing mcmm.c.
#
# Windows: cross-compilation is not done here. Build mcmm.exe on Windows (or
# with a mingw toolchain) and drop it at
#   src/main/resources/natives/mcmm-windows-x64.exe
set -e
cd "$(dirname "$0")"

CDIR="${MCMM_SRC:-../MinecraftVideo/c version}"
NATIVES_DIR="src/main/resources/natives"

if [ ! -f "$CDIR/mcmm.c" ]; then
    echo "Error: mcmm C source not found at: $CDIR" >&2
    echo "Set MCMM_SRC to the MinecraftVideo repo's 'c version' directory, e.g.:" >&2
    echo "  MCMM_SRC='/path/to/MinecraftVideo/c version' ./prepare-natives.sh" >&2
    exit 1
fi

mkdir -p "$NATIVES_DIR"

echo "Building static linux-x64 mcmm from: $CDIR"
cmake -S "$CDIR" -B "$CDIR/build-static" -DCMAKE_BUILD_TYPE=Release -DMCMM_STATIC=ON >/dev/null
cmake --build "$CDIR/build-static" >/dev/null
cp "$CDIR/build-static/mcmm" "$NATIVES_DIR/mcmm-linux-x64"
echo "  -> $NATIVES_DIR/mcmm-linux-x64"

# The palette is a committed resource (vanilla_map_colors.json), so it does not
# need staging here — only the native binary does.

echo "Done. Now run: mvn package"
