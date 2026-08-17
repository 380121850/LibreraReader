#!/bin/bash
# Restore harmony/entry/libs/<abi>/libmupdf.so from the vendored prebuilt cache
# so the HAP can be built fully offline (no MuPDF cross-compile needed).
# To rebuild MuPDF from source instead: harmony/native/build_mupdf_harmony.sh
set -e
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

for abi in arm64-v8a x86_64; do
    src="$ROOT/prebuilt/harmony/mupdf-1.23.7/$abi/libmupdf.so"
    dst="$ROOT/harmony/entry/libs/$abi/libmupdf.so"
    if [ ! -f "$src" ]; then
        echo "missing $src" >&2
        exit 1
    fi
    mkdir -p "$(dirname "$dst")"
    cp -f "$src" "$dst"
    echo "restored $dst ($(stat -c%s "$dst") bytes)"
done
