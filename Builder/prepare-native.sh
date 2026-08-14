#!/usr/bin/env bash
#
# prepare-native.sh — ensure the prebuilt MuPDF native libraries exist.
#
# Uses the committed .so under prebuilt/native/ by default. Only falls back to
# a full source clone + ndk-build (Builder/link_to_mupdf_1.23.7.sh) if any of
# the 8 expected files are missing.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"   # Builder/
PROJ_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PREBUILT="$PROJ_ROOT/prebuilt/native/mupdf-1.23.7"
ABIS=(armeabi-v7a arm64-v8a x86 x86_64)
LIBS=(libMuPDF.so liblame.so)

missing=0
for abi in "${ABIS[@]}"; do
    for lib in "${LIBS[@]}"; do
        if [ ! -f "$PREBUILT/$abi/$lib" ]; then
            echo "[prepare-native] MISSING: $PREBUILT/$abi/$lib"
            missing=1
        fi
    done
done

if [ "$missing" -eq 0 ]; then
    echo "[prepare-native] all prebuilt MuPDF libs present — using cache."
    exit 0
fi

echo "[prepare-native] prebuilt libs missing — building MuPDF from source..."
echo "[prepare-native] (requires network: git clone ghostscript/mupdf + NDK)"
cd "$SCRIPT_DIR"
bash link_to_mupdf_1.23.7.sh
