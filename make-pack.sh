#!/usr/bin/env bash
# Zippe pack/ en src/main/resources/pack.zip, le resource pack que le jar embarque et que
# ResourcePackService pousse aux joueurs. Appele par ./build.sh. Les README* de pack/ ne partent pas dans le zip.
#
# Zip DETERMINISTE (-X, entrees triees, mtimes normalisees sur une copie) : le client cache le pack
# par son SHA-1, qui ne doit bouger que si le contenu bouge.
set -euo pipefail
cd "$(dirname "$0")"

PACK_RES=src/main/resources/pack.zip
rm -f "$PACK_RES"

if [ ! -d pack ] || [ -z "$(find pack -type f ! -name 'README*' -print -quit)" ]; then
    echo "pack: aucun contenu dans pack/, le jar partira sans resource pack"
    exit 0
fi

command -v zip >/dev/null || { echo "pack: 'zip' introuvable, impossible de construire le pack" >&2; exit 1; }

staged=$(mktemp -d)
trap 'rm -rf "$staged"' EXIT

( cd pack && find . -type f ! -name 'README*' -exec cp --parents {} "$staged/" \; )
find "$staged" -exec touch -t 198001010000 {} +
( cd "$staged" && find . -type f | LC_ALL=C sort | zip -q -X -9 "$OLDPWD/$PACK_RES" -@ )

echo "pack: $PACK_RES ($(find "$staged" -type f | wc -l) fichiers, sha1 $(sha1sum "$PACK_RES" | cut -d' ' -f1))"
