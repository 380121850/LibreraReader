#!/usr/bin/env bash
# Build script for Librera Reader HarmonyOS HAP.
# Always source ~/.bashrc first to load configured environment variables.
set -e
source ~/.bashrc

cd /docker/opt/librera/LibreraReader/harmony

echo "=== Step 0: Stop stale daemon ==="
hvigorw --stop-daemon 2>/dev/null || true

echo "=== Step 1: Build HAP (assembleHap) ==="
hvigorw assembleHap --mode module -p product=default -p buildMode=debug 2>&1

echo "=== Build finished ==="
