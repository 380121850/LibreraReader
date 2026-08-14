#!/usr/bin/env bash
#
# vendor-cache.sh — (re)generate the vendored Gradle dependency cache tarball
# under prebuilt/gradle-cache/modules-2.tar.gz from the local Gradle cache.
#
# Run ONCE after a successful ONLINE build so the resolved dependency cache
# (all artifacts + Gradle's own metadata, which handles variant resolution
# correctly) is captured for offline builds on fresh machines. Re-run whenever
# dependency versions change.
#
# We vendor Gradle's NATIVE cache format (not a hand-built Maven repo) because
# modern AndroidX libraries use Gradle Module Metadata with variant artifacts
# that a naive file-Maven-repo cannot resolve (Gradle looks up variant files
# by their declared names). The native cache works with --offline as-is.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SRC="${GRADLE_CACHE:-$HOME/.gradle/caches/modules-2}"
DST="$PROJ_ROOT/prebuilt/gradle-cache"

if [ ! -d "$SRC" ]; then
    echo "[vendor-cache] Gradle cache not found: $SRC"
    echo "[vendor-cache] Run a successful online build first, then re-run this script."
    exit 1
fi

mkdir -p "$DST"
echo "[vendor-cache] source : $SRC"
echo "[vendor-cache] creating prebuilt/gradle-cache/modules-2.tar.gz (excl *.lock) ..."
tar czf "$DST/modules-2.tar.gz" --exclude='*.lock' -C "$(dirname "$SRC")" modules-2
echo "[vendor-cache] wrote: $DST/modules-2.tar.gz ($(ls -lh "$DST/modules-2.tar.gz" | awk '{print $5}'))"
# Split into <=90MB parts so no single file exceeds GitHub's 100MB hard limit
# (the repo uses NO Git LFS — GitHub forbids LFS uploads to public forks).
rm -f "$DST"/modules-2.tar.gz.part*
split -b 90m -d -a 2 "$DST/modules-2.tar.gz" "$DST/modules-2.tar.gz.part"
rm -f "$DST/modules-2.tar.gz"
echo "[vendor-cache] split into:"
ls -lh "$DST"/modules-2.tar.gz.part* | awk '{print "    "$NF" "$5}'
echo "[vendor-cache] done."
