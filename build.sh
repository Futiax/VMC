#!/usr/bin/env bash
# Build the plugin, auto-bumping the version in pom.xml first.
# plugin.yml gets the version automatically at build time (Maven filtering),
# and NativeInstaller re-extracts the bundled mcmm on the server whenever the
# version changes — so every build ships its natives.
#
#   ./build.sh              bump the PATCH number   (0.2.3 -> 0.2.4) and build
#   ./build.sh minor        bump the MINOR number   (0.2.3 -> 0.3.0) and build
#   ./build.sh major        bump the MAJOR number   (0.2.3 -> 1.0.0) and build
#   ./build.sh set 1.4.2    set an explicit version and build
#   ./build.sh keep         build WITHOUT bumping (rebuild the current version)
set -euo pipefail
cd "$(dirname "$0")"

POM=pom.xml

# Current project version = the first <version> tag in the pom (the project's
# own; dependency versions come later in the file).
current=$(grep -m1 -oP '<version>\K[0-9]+\.[0-9]+\.[0-9]+' "$POM")
if [[ -z "$current" ]]; then
    echo "error: could not read an X.Y.Z <version> from $POM" >&2
    exit 1
fi
IFS=. read -r major minor patch <<< "$current"

mode="${1:-patch}"
case "$mode" in
    patch)  new="$major.$minor.$((patch + 1))" ;;
    minor)  new="$major.$((minor + 1)).0" ;;
    major)  new="$((major + 1)).0.0" ;;
    set)
        new="${2:-}"
        if [[ ! "$new" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
            echo "usage: ./build.sh set <X.Y.Z>" >&2
            exit 1
        fi
        ;;
    keep)   new="$current" ;;
    -h|--help|help)
        sed -n '2,12p' "$0"
        exit 0
        ;;
    *)
        echo "unknown option '$mode' (use: patch [default] | minor | major | set X.Y.Z | keep)" >&2
        exit 1
        ;;
esac

if [[ "$new" != "$current" ]]; then
    # Replace only the FIRST <version> occurrence (the project's own).
    sed -i "0,/<version>$current<\/version>/s//<version>$new<\/version>/" "$POM"
    echo "version: $current -> $new"
else
    echo "version: $current (unchanged)"
fi

mvn -q clean package
echo "built: target/minecraftvideo-plugin-$new.jar"
