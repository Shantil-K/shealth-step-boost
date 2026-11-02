#!/usr/bin/env bash
# Builds the hook APK, then a flashable KernelSU/Magisk zip with the WebUI and the APK inside.
set -euo pipefail
cd "$(dirname "$0")"
./build.sh
rm -rf build/zip && mkdir -p build/zip
cp -r webui/. build/zip/
cp build/StepBoost.apk build/zip/StepBoost.apk
(cd build/zip && zip -qr ../shealth_step_boost.zip .)
echo "built build/shealth_step_boost.zip"
