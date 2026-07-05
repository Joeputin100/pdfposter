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
# or 200% font: button-heavy top bar (main), Compare drawer, model-picker
# modal, the Settings nav-drawer, and the text-dense Getting Started page.
# (low_dpi_modal == model_picker's modal, so it's dropped as a duplicate.)
# rc80: gemini = the Q&A sheet, now launchable with a populated sample reply.
STATES=(main compare model_picker settings getting_started gemini)

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

# RC79: the `with_image` state seeds a real image URI so the FULL main flow
# renders (image info → poster size → Advanced Styling → construction preview).
# Those lower sections are image-gated + below the fold, so we scroll down and
# grab several frames. NOTE: the construction preview animates (assembly cycle),
# so its frame is whatever the animation is on at capture time. Swipe coords are
# calibrated for the font360 360x780 viewport.
capture_scrolled() {  # $1=config
  local cfg="$1"
  echo "=== $cfg / with_image (scrolled) ==="
  adb shell am force-stop "$PKG" || true
  adb shell am start -n "$PKG/com.posterpdf.MainActivity" --es screenshot with_image
  sleep 9  # splash skip + image decode + preview first frame
  adb exec-out screencap -p > "$OUT/$cfg-with_image-1.png"
  for i in 2 3 4 5 6; do                          # extra frames: Advanced Styling is now expanded (longer page)
    adb shell input swipe 180 640 180 160 450   # scroll down
    sleep 2
    adb exec-out screencap -p > "$OUT/$cfg-with_image-$i.png"
  done
  ls -l "$OUT/$cfg-with_image-"*.png
}

# RC79: the assembly preview animates a ~38s cycle that LOOPS, so a single
# screencap rarely lands on the Printing phase (printer framed at the start —
# the #3 fix). Scroll to the preview, then burst-capture across more than a full
# cycle so at least one frame catches the printer at a cycle start.
capture_preview_burst() {  # $1=config
  local cfg="$1"
  echo "=== $cfg / preview printer burst ==="
  adb shell am force-stop "$PKG" || true
  adb shell am start -n "$PKG/com.posterpdf.MainActivity" --es screenshot with_image
  sleep 9
  for i in $(seq 1 8); do adb shell input swipe 180 640 180 160 350; done  # scroll down to the preview
  sleep 2
  for n in $(seq -w 1 14); do                 # 14 × ~4s ≈ 56s > one 38s cycle
    adb exec-out screencap -p > "$OUT/$cfg-preview-burst-$n.png"
    sleep 3
  done
  ls -l "$OUT/$cfg-preview-burst-"*.png
}

# --- rc83: STORE_MODE — clean Play-listing captures at a modern phone
# resolution (1080x2340 @440dpi ≈ Pixel 7 class), baseline font, portrait.
# Captures the eight-shot story from docs/screenshots/README.md as far as
# launch-state hooks allow, then exits (skips the QA matrix entirely).
if [ "${STORE_MODE:-0}" = "1" ]; then
  adb shell wm size 1080x2340
  adb shell wm density 440
  sleep 2
  for s in main model_picker compare settings gemini getting_started; do
    capture store "$s"
  done
  # with_image: top frame + scrolled frames (coords scaled for 1080x2340).
  adb shell am force-stop "$PKG" || true
  adb shell am start -n "$PKG/com.posterpdf.MainActivity" --es screenshot with_image
  sleep 9
  adb exec-out screencap -p > "$OUT/store-with_image-1.png"
  for i in 2 3 4; do
    adb shell input swipe 540 1900 540 500 450
    sleep 2
    adb exec-out screencap -p > "$OUT/store-with_image-$i.png"
  done
  # Construction-cycle burst: scroll to the animated preview, then sample
  # past a full ~38s cycle so some frame lands on the photogenic phases.
  # Scroll pattern proven by the with_image-4 framing (3 slow swipes) plus
  # one nudge to center the tile grid — fast 350ms swipes fling unreliably.
  for i in 1 2 3; do adb shell input swipe 540 1800 540 700 900; sleep 1; done
  adb shell input swipe 540 1500 540 1000 600
  sleep 2
  for n in $(seq -w 1 14); do
    adb exec-out screencap -p > "$OUT/store-construction-$n.png"
    sleep 3
  done
  # rc83: product-video raw footage — DISABLED BY DEFAULT (STORE_VIDEO=1 to
  # try). BOTH recorders crash this runner's emulator: device-side
  # screenrecord needs a hw h264 encoder swiftshader lacks, and even the
  # emulator-side `adb emu screenrecord` kills it (runs 28654310187,
  # 28729212356 — "no frame!" spam then "error: closed"). The product video
  # is assembled OFF-CI from the construction burst frames instead
  # (1 fps × 45 s at 10 fps playback ≈ the 6×-accelerated loop the
  # docs/screenshots README always wanted).
  if [ "${STORE_VIDEO:-0}" = "1" ]; then
  vstart() { adb emu screenrecord start "$OUT/$1.webm" || echo "WARN: emu recorder start failed for $1"; }
  vstop()  { adb emu screenrecord stop || true; sleep 1; }
  vstate() {  # $1=name $2=seconds $3=state — static screen segment
    echo "=== video: $1 ($3, ${2}s) ==="
    adb shell am force-stop "$PKG" || true
    adb shell am start -n "$PKG/com.posterpdf.MainActivity" --es screenshot "$3"
    sleep 8
    vstart "$1"; sleep "$2"; vstop
  }
  vstate vid-main 10 main
  # with_image + slow scroll through the full page during recording
  adb shell am force-stop "$PKG" || true
  adb shell am start -n "$PKG/com.posterpdf.MainActivity" --es screenshot with_image
  sleep 9
  vstart vid-scroll
  for i in 1 2 3 4; do adb shell input swipe 540 1800 540 700 900; sleep 3; done
  sleep 2; vstop
  # construction cycle: scroll to the preview, record a full ~40s loop
  adb shell am force-stop "$PKG" || true
  adb shell am start -n "$PKG/com.posterpdf.MainActivity" --es screenshot with_image
  sleep 9
  for i in $(seq 1 6); do adb shell input swipe 540 1900 540 500 350; done
  sleep 2
  vstart vid-cycle; sleep 45; vstop
  vstate vid-picker 10 model_picker
  vstate vid-gemini 10 gemini
  fi  # STORE_VIDEO
  echo "STORE_MODE captures done:"; ls -l "$OUT"
  exit 0
fi

# --- baseline ---
run_config baseline

# --- font360: ~360dp width + Android max font scale (200%) ---
adb shell wm size 360x780
adb shell wm density 160
adb shell settings put system font_scale 2.0
echo "effective font_scale: $(adb shell settings get system font_scale)"
run_config font360
capture_scrolled font360
capture_preview_burst font360
adb shell settings put system font_scale 1.0
adb shell wm size reset
adb shell wm density reset

# --- cutout config relocated below (now dead-last + guarded). `cmd overlay
# enable` has repeatedly crashed the emulator (device goes offline), which used
# to abort the whole job AFTER the important captures were already done. ---

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

# --- cutout (LAST + guarded): display-cutout emulation is flaky — `cmd overlay
# enable` has crashed the emulator repeatedly. Run it dead-last so the important
# captures above are already done + uploaded, and wrap the whole block in a
# subshell + `|| echo` so a crash can't fail the job. The only step after this
# is the local `ls`, which doesn't need the emulator. ---
(
  CUTOUT=$(adb shell cmd overlay list 2>/dev/null | grep -oE 'com.android.internal.display.cutout.emulation.[a-z]+' | head -1)
  if [ -n "$CUTOUT" ]; then
    echo "Enabling cutout overlay: $CUTOUT"
    adb shell cmd overlay enable "$CUTOUT" || true
    sleep 2
    run_config cutout
    adb shell cmd overlay disable "$CUTOUT" || true
  else
    echo "WARN: no cutout emulation overlay available; skipping cutout config"
  fi
) || echo "WARN: cutout config failed (flaky emulator) — continuing; earlier captures already uploaded"

echo "All screenshots captured:"
ls -l "$OUT"
