#!/bin/bash
# Build using the standard Android/Gradle environment supplied by the caller.
# Set ANDROID_HOME and JAVA_HOME before running; GRADLE may point to an explicit
# Gradle executable when gradle is not on PATH.
set -euo pipefail
cd "$(dirname "$0")"

if [ -n "${ANDROID_HOME:-}" ]; then export ANDROID_HOME; fi
if [ -n "${JAVA_HOME:-}" ]; then export JAVA_HOME; fi
GRADLE="${GRADLE:-gradle}"

"$GRADLE" --offline --console=plain :app:assembleDebug 2>&1 | grep -E "BUILD|\.java:[0-9]+|error:" | head -10
if [ ! -f app/build/outputs/apk/debug/app-debug.apk ]; then echo "NO APK"; exit 1; fi
ls -l app/build/outputs/apk/debug/app-debug.apk | awk '{print "apk:", $5, "bytes", $8}'

echo "--- tests ---"
bash run-host-tests.sh 2>&1 | grep -E "AssertionError|All [0-9]+ host" | head -5
