#!/usr/bin/env bash
# RC69: drive the debug APK to named UI states and screencap each.
# Runs inside reactivecircus/android-emulator-runner (adb is on PATH,
# emulator already booting). The debug-only intent hook in MainActivity
# reads `--es screenshot <state>` and opens the matching screen; for
# model_picker it also seeds a bundled test image so the picker has
# content to render.
#
# RC77: capture each dense screen under a CONFIG MATRIX (baseline / font360
# = 360dp width + 200% font / cutout = display-cutout emulation), output
# named "<config>-<state>.png", plus one in-app predictive-back video. Used
# for Play Store editorial-review edge-case review (capture only; fixes
# follow from what the artifacts reveal).
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

# Curated dense screen set — the screens most likely to break at 360dp width
# or 200% font (button-heavy top bar, drawers, modals, settings, Gemini sheet).
STATES=(main compare model_picker settings low_dpi_modal gemini getting_started)

capture() {  # $1=config $2=state
  local cfg="$1" state="$2"
  echo "=== $cfg / $state ==="
  adb shell am force-stop "$PKG" || true
  adb shell am start -n "$PKG/com.posterpdf.MainActivity" --es screenshot "$state"
  sleep 8  # splash video + first composition + drawer slide-in
  adb exec-out screencap -p > "$OUT/$cfg-$state.png"
  ls -l "$OUT/$cfg-$state.png"
}

run_config() { local cfg="$1"; for s in "${STATES[@]}"; do capture "$cfg" "$s"; done; }

# --- baseline ---
run_config baseline

# --- font360: ~360dp width + Android max font scale (200%) ---
adb shell wm size 360x780
adb shell wm density 160
adb shell settings put system font_scale 2.0
echo "effective font_scale: $(adb shell settings get system font_scale)"
run_config font360
adb shell settings put system font_scale 1.0
adb shell wm size reset
adb shell wm density reset

# --- cutout: enable a built-in display-cutout emulation overlay ---
CUTOUT=$(adb shell cmd overlay list 2>/dev/null | grep -oE 'com.android.internal.display.cutout.emulation.[a-z]+' | head -1)
if [ -n "$CUTOUT" ]; then
  echo "Enabling cutout overlay: $CUTOUT"
  adb shell cmd overlay enable "$CUTOUT" || true
  sleep 2
  run_config cutout
  adb shell cmd overlay disable "$CUTOUT" || true
else
  echo "WARN: no cutout emulation overlay available on this image; skipping cutout config"
fi

# --- predictive-back in-app video (RC77) ---
echo "=== recording in-app predictive-back video ==="
adb shell screenrecord --time-limit 20 /sdcard/back.mp4 &
REC=$!
adb shell am force-stop "$PKG" || true
adb shell am start -n "$PKG/com.posterpdf.MainActivity" --es screenshot compare; sleep 5
adb shell input keyevent KEYCODE_BACK; sleep 2          # dismiss the Compare drawer
adb shell am start -n "$PKG/com.posterpdf.MainActivity" --es screenshot getting_started; sleep 4
adb shell input keyevent KEYCODE_BACK; sleep 2          # screen-swap back
wait $REC || true
adb pull /sdcard/back.mp4 "$OUT/back.mp4" || true
ls -l "$OUT/back.mp4" || true

echo "All screenshots captured:"
ls -l "$OUT"
