#!/usr/bin/env bash
# CI-only: baut app/libs/onnxruntime-genai-android-0.6.0.aar aus den Stubs in ai/onnxruntime/genai/.
# Entfaellt, sobald die Download-Quelle in app/build.gradle repariert ist (siehe README.txt).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
W="${RUNNER_TEMP:-/tmp}/ortstub"
OUT="$HERE/../../android/app/libs/onnxruntime-genai-android-0.6.0.aar"
rm -rf "$W"; mkdir -p "$W/out" "$W/aar" "$(dirname "$OUT")"
javac --release 8 -d "$W/out" "$HERE"/ai/onnxruntime/genai/*.java
jar cf "$W/aar/classes.jar" -C "$W/out" .
echo '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="ai.onnxruntime.genai"/>' > "$W/aar/AndroidManifest.xml"
(cd "$W/aar" && jar cf "$(cd "$(dirname "$OUT")" && pwd)/$(basename "$OUT")" AndroidManifest.xml classes.jar)
