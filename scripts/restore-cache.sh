#!/usr/bin/env bash
#
# restore-cache.sh — restore the vendored Gradle dependency cache and
# distribution into ~/.gradle so a FRESH machine (empty ~/.gradle) can build
# fully offline, with no network access at all.
#
# Run once after a fresh checkout. Idempotent: safe to re-run (files are
# merged; existing artifacts are not clobbered). After this, build with:
#     ./gradlew --offline :app:assembleFdroidDebug
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TAR="$PROJ_ROOT/prebuilt/gradle-cache/modules-2.tar.gz"
PARTS_GLOB="$PROJ_ROOT/prebuilt/gradle-cache/modules-2.tar.gz.part*"
GRADLE_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
DEST="$GRADLE_HOME/caches"

# The 158MB cache tarball is stored as <=90MB split parts (under GitHub's
# 100MB/file limit, no Git LFS — public forks cannot push LFS). Stream the
# reassembled tarball straight into tar; fall back to a whole vendored tarball.
mkdir -p "$DEST"
# Keep any existing cache; tar merges into it.
if [ -f "$TAR" ]; then
    echo "[restore-cache] extracting modules-2 cache -> $DEST"
    tar xzf "$TAR" -C "$DEST"
elif ls $PARTS_GLOB >/dev/null 2>&1; then
    echo "[restore-cache] reassembling split parts -> tar -> $DEST"
    cat $PARTS_GLOB | tar xzf - -C "$DEST"
else
    echo "[restore-cache] vendored cache not found: $TAR (and no .part* files)"
    exit 1
fi
echo "[restore-cache] dependency cache restored ($(du -sh "$DEST/modules-2" | cut -f1))."

# Seed the Gradle distribution from the vendored zip (best-effort).
if [ -f "$SCRIPT_DIR/bootstrap-gradle.sh" ]; then
    bash "$SCRIPT_DIR/bootstrap-gradle.sh" || true
fi

echo "[restore-cache] done. Build offline with:  ./gradlew --offline ..."
