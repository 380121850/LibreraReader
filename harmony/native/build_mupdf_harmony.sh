#!/bin/bash
# Cross-compile MuPDF 1.23.7 for HarmonyOS (OHOS) using the native SDK toolchain.
# Usage: bash build_mupdf_harmony.sh [abi]   (default: arm64-v8a)
set -e

ABI="${1:-arm64-v8a}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OHOS_SDK_NATIVE="${OHOS_SDK_NATIVE:-/docker/opt/deveco-sdk/command-line-tools/sdk/default/openharmony/native}"
CMAKE_BIN="$OHOS_SDK_NATIVE/build-tools/cmake/bin"
export PATH="$CMAKE_BIN:$PATH"

if [ ! -f "$OHOS_SDK_NATIVE/build/cmake/ohos.toolchain.cmake" ]; then
    echo "ERROR: ohos.toolchain.cmake not found under $OHOS_SDK_NATIVE" >&2
    exit 1
fi

BUILD_DIR="$SCRIPT_DIR/build-$ABI"

"$CMAKE_BIN/cmake" -G Ninja \
    -DOHOS_STL=c++_shared \
    -DOHOS_ARCH=$ABI \
    -DOHOS_PLATFORM=OHOS \
    -DOHOS_SDK_NATIVE=$OHOS_SDK_NATIVE \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$OHOS_SDK_NATIVE/build/cmake/ohos.toolchain.cmake" \
    -S "$SCRIPT_DIR" \
    -B "$BUILD_DIR"

"$CMAKE_BIN/cmake" --build "$BUILD_DIR" -j "$(nproc)"

SO_FILE="$BUILD_DIR/libmupdf.so"
if [ -f "$SO_FILE" ]; then
    echo ""
    echo "=== BUILD OK: $SO_FILE ==="
    ls -lh "$SO_FILE"
    file "$SO_FILE" 2>/dev/null || true
    # Stage the .so for the HAP build: entry/libs/<abi>/ is packed by hvigor
    # and linked against by entry/src/main/cpp/CMakeLists.txt.
    STAGED="$SCRIPT_DIR/../entry/libs/$ABI"
    mkdir -p "$STAGED"
    cp -f "$SO_FILE" "$STAGED/libmupdf.so"
    # Drop what little debug info the release build carries; the bulk of the
    # .so is embedded font data (.rodata) which cannot be stripped.
    "$OHOS_SDK_NATIVE/llvm/bin/llvm-strip" --strip-debug "$STAGED/libmupdf.so"
    echo "=== STAGED: $STAGED/libmupdf.so ==="
else
    echo "ERROR: libmupdf.so not produced" >&2
    exit 1
fi
