#!/usr/bin/env bash
#
# bootstrap-gradle.sh — seed the Gradle wrapper distribution cache from the
# vendored zip in prebuilt/gradle/, so a fresh machine can build without
# downloading the 132MB distribution from services.gradle.org.
#
# The wrapper looks for the dist at:
#   ~/.gradle/wrapper/dists/gradle-<ver>-bin/<HASH>/gradle-<ver>-bin.zip
# where <HASH> is derived from distributionUrl by the (committed) wrapper jar.
# If the zip is present there, the wrapper just extracts it (no re-download).
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ZIP="$PROJ_ROOT/prebuilt/gradle/gradle-8.14.5-bin.zip"
PARTS_GLOB="$PROJ_ROOT/prebuilt/gradle/gradle-8.14.5-bin.zip.part*"
DIST_BASE="${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists/gradle-8.14.5-bin"

# The 132MB distribution is stored as <=90MB split parts (so no single file
# exceeds GitHub's 100MB limit and we need no Git LFS, which public forks
# cannot push). Reassemble on demand; fall back to a whole vendored zip.
SEED_ZIP=""
if [ -f "$ZIP" ]; then
    SEED_ZIP="$ZIP"
elif ls $PARTS_GLOB >/dev/null 2>&1; then
    SEED_ZIP="$(mktemp)"
    echo "[bootstrap-gradle] reassembling split parts -> $SEED_ZIP"
    cat $PARTS_GLOB > "$SEED_ZIP"
else
    echo "[bootstrap-gradle] vendored zip not found: $ZIP (and no .part* files)"
    exit 1
fi

# 1. Already extracted? (any hash dir with the unpacked gradle)
if ls -d "$DIST_BASE"/*/gradle-8.14.5/bin/gradle >/dev/null 2>&1; then
    echo "[bootstrap-gradle] gradle-8.14.5 already extracted in wrapper cache — nothing to do."
    exit 0
fi

# 2. Determine the hash dir: prefer an existing one (e.g. from a prior failed
#    download attempt), else fall back to the known hash for THIS project's
#    fixed distributionUrl. (Stable as long as gradle-wrapper.properties /
#    the committed wrapper jar are unchanged.)
KNOWN_HASH="690y85m0j9nfaub7xoiayko8a"
HASH_DIR=""
if ls -d "$DIST_BASE"/*/ >/dev/null 2>&1; then
    HASH_DIR="$(ls -d "$DIST_BASE"/*/ | head -1 | xargs basename)"
    echo "[bootstrap-gradle] reusing existing hash dir: $HASH_DIR"
else
    HASH_DIR="$KNOWN_HASH"
    echo "[bootstrap-gradle] no existing hash dir; using known hash for gradle-8.14.5-bin.zip: $HASH_DIR"
fi

TARGET="$DIST_BASE/$HASH_DIR"
mkdir -p "$TARGET"
cp -n "$SEED_ZIP" "$TARGET/gradle-8.14.5-bin.zip"
# Drop the temp reassembled file; leave a real vendored whole zip untouched.
[ "$SEED_ZIP" != "$ZIP" ] && rm -f "$SEED_ZIP"
echo "[bootstrap-gradle] placed zip at: $TARGET/gradle-8.14.5-bin.zip"
echo "[bootstrap-gradle] the wrapper will extract it on the next ./gradlew run."
echo "[bootstrap-gradle] NOTE: if this hash does not match your wrapper jar,"
echo "    the wrapper will create a new dir and download once; the build still works online."
