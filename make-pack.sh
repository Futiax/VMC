#!/usr/bin/env bash
# Zip pack/ into src/main/resources/pack.zip (embedded in the jar at build time).
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -f pack/pack.mcmeta ]; then
	echo "pack/pack.mcmeta missing, nothing to zip"
	exit 0
fi

mkdir -p src/main/resources
rm -f src/main/resources/pack.zip
(cd pack && zip -q -r -X ../src/main/resources/pack.zip .)
echo "packed -> src/main/resources/pack.zip ($(du -h src/main/resources/pack.zip | cut -f1))"
