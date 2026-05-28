#!/usr/bin/env bash
# RC69: drive the debug APK to named UI states and screencap each.
# Runs inside reactivecircus/android-emulator-runner (adb is on PATH,
# emulator already booting). The debug-only intent hook in MainActivity
# reads `--es screenshot <state>` and opens the matching screen; for
# model_picker it also seeds a bundled test image so the picker has
# content to render.
set -euo pipefail

PKG=com.posterpdf
OUT=/tmp/shots
mkdir -p "$OUT"

echo "Waiting for device + boot…"
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  sleep 2
done
adb shell input keyevent 82 || true  # dismiss keyguard

APK=$(find app/build/outputs/apk/debug -name "*.apk" | head -1)
if [ -z "$APK" ]; then echo "No debug APK found"; exit 1; fi
echo "Installing $APK"
adb install -r "$APK"

# Pre-grant POST_NOTIFICATIONS so the runtime permission dialog never
# pops over the UI we're trying to screenshot. (-g grants all at install
# on API 23+, but be explicit for the one that auto-prompts on launch.)
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

capture() {
  local state="$1"
  echo "=== capturing: $state ==="
  adb shell am force-stop "$PKG" || true
  # screenshot extra is read by the debug-only hook in MainActivity.
  adb shell am start -n "$PKG/com.posterpdf.MainActivity" --es screenshot "$state"
  # Generous settle time: splash video + first composition + drawer slide-in.
  sleep 8
  adb exec-out screencap -p > "$OUT/$state.png"
  ls -l "$OUT/$state.png"
}

capture main
capture compare
capture model_picker

echo "All screenshots captured:"
ls -l "$OUT"
