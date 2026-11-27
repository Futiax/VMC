#!/usr/bin/env bash
# Build mcdata: bump version, compile with Maven, zip the resource pack into the jar.
set -euo pipefail
cd "$(dirname "$0")"

# --- bump patch version in pom.xml ---
VER=$(grep -m1 '<version>' pom.xml | sed -E 's/.*<version>([^<]+)<\/version>.*/\1/')
MAJOR=$(echo "$VER" | cut -d. -f1)
MINOR=$(echo "$VER" | cut -d. -f2)
PATCH=$(echo "$VER" | cut -d. -f3)
NEWPATCH=$((PATCH + 1))
NEWVER="$MAJOR.$MINOR.$NEWPATCH"
sed -i -E "0,/<version>[^<]+<\/version>/s|<version>[^<]+</version>|<version>$NEWVER</version>|" pom.xml
echo "version: $VER -> $NEWVER"

# --- build the resource pack zip (if pack/ has content) ---
if [ -f pack/pack.mcmeta ]; then
	./make-pack.sh
else
	echo "no pack/ directory, skipping pack.zip"
	rm -f src/main/resources/pack.zip
fi

# --- compile ---
mvn -q -DskipTests package
echo "built target/mcdata-$NEWVER.jar"
