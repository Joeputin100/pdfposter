# UX Edge-Case Verification — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Produce CI artifacts that show how PosterPDF's UI behaves at 360dp+max-font, under a display cutout, on a real flip foldable, and under predictive back — so we can see (and later fix) editorial-review edge cases.

**Architecture:** Extend the existing emulator screenshot pipeline with a runtime config matrix (font/density/cutout); add a real-flip FTL screenshot test + sibling workflow; produce a predictive-back manifest/code audit + one in-app video. **Capture + audit only — fixes are a follow-up RC.**

**Tech Stack:** bash + `adb` (wm size/density, `settings`, `cmd overlay`, `screenrecord`, `screencap`), Kotlin androidTest + UiAutomator (`UiDevice.takeScreenshot`), GitHub Actions, Firebase Test Lab (`--test-targets`, `--directories-to-pull`).

**Reference spec:** `docs/superpowers/specs/2026-05-28-ux-edge-case-verification-design.md`

---

## File Structure

**Create:**
- `app/src/androidTest/kotlin/com/posterpdf/UxScreenshotTest.kt` — per-state `UiDevice.takeScreenshot` into `/sdcard/ux-shots/`, for the real-flip FTL run.
- `.github/workflows/ftl-ux-screenshots.yml` — sibling of `ftl-upscale-test.yml`; runs `UxScreenshotTest` on a device + pulls `/sdcard/ux-shots`.
- `docs/ux/predictive-back-audit.md` — the generated audit checklist (committed deliverable).

**Modify:**
- `app/src/main/kotlin/com/posterpdf/MainActivity.kt` — add `--es screenshot` states for any dense screen not already wired (`settings`, `low_dpi_modal`, `gemini`); confirm `getting_started` exists.
- `.github/scripts/capture-screenshots.sh` — config matrix (`baseline`/`font360`/`cutout`) × expanded screen set + the `screenrecord` predictive-back video.

---

## Task 1: Add missing screenshot hook states

**Files:** Modify `app/src/main/kotlin/com/posterpdf/MainActivity.kt` (the `intent.getStringExtra("screenshot")` handling, ~line 138 + the state→screen mapping ~317-359).

- [ ] **Step 1: Read the existing screenshot-state handler** (`getStringExtra("screenshot")` at ~138; the `when`/`if` that maps `"main"`/`"compare"`/`"model_picker"`/`"getting_started"` to opening those screens, ~317-359). Note the exact pattern used (which state opens which drawer/screen/modal).

- [ ] **Step 2: Add cases for the dense screens not already wired**, following that exact pattern. Required states the capture matrix will drive: `settings` (open the settings drawer), `low_dpi_modal` (open `LowDpiUpgradeModal` — seed a low-DPI test image first, mirroring how `model_picker` seeds its image), `gemini` (open the Gemini Q&A sheet). If any already exists, skip it. Each must be gated by the same `BuildConfig.ENABLE_TEST_HOOKS` guard the existing states use.

- [ ] **Step 3: Commit** (CI verifies compile + the screenshot run exercises the states).
```bash
git add app/src/main/kotlin/com/posterpdf/MainActivity.kt
git commit -m "feat(rc77): screenshot hook states for settings/low_dpi_modal/gemini (UX edge capture)"
```

> No local gradle: compile is verified by `build-android` on push. Correct-screen-opens is verified by Task 6's screenshot artifacts.

## Task 2: Emulator config matrix in capture-screenshots.sh

**Files:** Modify `.github/scripts/capture-screenshots.sh`.

- [ ] **Step 1: Replace the flat capture list with a config × state loop.** Keep the existing boot/install/grant preamble (lines 1-29). Replace the `capture()` calls (43-45) with:
```bash
# Curated dense screen set (most likely to break at 360dp / 200% font).
STATES=(main compare model_picker settings low_dpi_modal gemini getting_started)

capture() {  # $1=config $2=state
  local cfg="$1" state="$2"
  echo "=== $cfg / $state ==="
  adb shell am force-stop "$PKG" || true
  adb shell am start -n "$PKG/com.posterpdf.MainActivity" --es screenshot "$state"
  sleep 8
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
  adb shell cmd overlay enable "$CUTOUT" || true
  sleep 2
  run_config cutout
  adb shell cmd overlay disable "$CUTOUT" || true
else
  echo "WARN: no cutout emulation overlay available on this image; skipping cutout config"
fi
```

- [ ] **Step 2: Commit.**
```bash
git add .github/scripts/capture-screenshots.sh
git commit -m "ci(rc77): screenshot config matrix — baseline/font360/cutout × dense screens"
```

> Verified in Task 6 by the `screenshots.yml` run producing `<config>-<state>.png` artifacts. (`screenshots.yml` auto-runs on push to `feat/md3e-redesign` touching `app/src/main/**` or the workflow; this script change is under `.github/` — Task 6 dispatches it manually.)

## Task 3: UxScreenshotTest for the real flip (FTL)

**Files:** Create `app/src/androidTest/kotlin/com/posterpdf/UxScreenshotTest.kt`.

- [ ] **Step 1: Write the test** (mirrors `UpscalePdfDeviceTest`'s launch pattern; captures each state to a pullable dir):
```kotlin
package com.posterpdf

import android.content.Intent
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * RC77 — captures one screenshot per dense screen for odd-form-factor review
 * (run on a real flip via FTL). Saves to /sdcard/ux-shots/, pulled by
 * ftl-ux-screenshots.yml via --directories-to-pull. Not a pass/fail test of
 * layout (human reviews the images); it asserts only that each capture wrote
 * a non-empty file, so a black/again-missing screen surfaces as a failure.
 */
@RunWith(AndroidJUnit4::class)
class UxScreenshotTest {
    private val states = listOf(
        "main", "compare", "model_picker", "settings",
        "low_dpi_modal", "gemini", "getting_started",
    )

    @Test
    fun captureDenseScreens() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val outDir = File(Environment.getExternalStorageDirectory(), "ux-shots").apply { mkdirs() }
        for (state in states) {
            ctx.startActivity(
                ctx.packageManager.getLaunchIntentForPackage("com.posterpdf")!!
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .putExtra("screenshot", state),
            )
            device.waitForIdle()
            Thread.sleep(6_000L) // splash + first composition + drawer slide-in
            val f = File(outDir, "$state.png")
            device.takeScreenshot(f)
            check(f.length() > 0) { "empty screenshot for state=$state" }
        }
    }
}
```

- [ ] **Step 2: Commit.**
```bash
git add app/src/androidTest/kotlin/com/posterpdf/UxScreenshotTest.kt
git commit -m "test(rc77): UxScreenshotTest — per-screen capture for FTL flip review"
```

> Compile of androidTest is verified when the FTL workflow builds `:app:assembleDebugAndroidTest` (Task 6).

## Task 4: ftl-ux-screenshots.yml workflow

**Files:** Create `.github/workflows/ftl-ux-screenshots.yml` (copy `ftl-upscale-test.yml` structure: Java17 + SDK + gradle 8.11.1 + decode keystore/google-services + build `:app:assembleBenchmark :app:assembleDebugAndroidTest` + WIF auth + setup-gcloud).

- [ ] **Step 1: Author the workflow.** Inputs `device` (default `arcfox`), `version` (default `34`). The gcloud run step (mirroring the pipefail + APK-locate steps of `ftl-upscale-test.yml`):
```yaml
        run: |
          set -eo pipefail
          gcloud firebase test android run \
            --type=instrumentation \
            --app="${{ steps.apks.outputs.app_apk }}" \
            --test="${{ steps.apks.outputs.test_apk }}" \
            --device="model=${DEVICE},version=${VERSION},locale=en,orientation=portrait" \
            --test-targets="class com.posterpdf.UxScreenshotTest" \
            --directories-to-pull=/sdcard/ux-shots \
            --timeout=20m \
            --results-bucket="${FTL_BUCKET}" \
            --results-dir="ux-shots-${{ github.run_id }}" \
            --project="${GCP_PROJECT}" 2>&1 | tee /tmp/ftl.txt
```
Reuse the exact `env:` (GCP_PROJECT, FTL_BUCKET), `DEVICE`/`VERSION` env vars, APK-locate step (benchmark app APK + debug androidTest APK), and permissions block from `ftl-upscale-test.yml`.

- [ ] **Step 2: Commit + sync to master** (workflow_dispatch only runs from the default branch).
```bash
git add .github/workflows/ftl-ux-screenshots.yml
git commit -m "ci(rc77): ftl-ux-screenshots — UxScreenshotTest on a real flip, pull /sdcard/ux-shots"
```

## Task 5: Predictive-back audit + in-app video

**Files:** Create `docs/ux/predictive-back-audit.md`; Modify `.github/scripts/capture-screenshots.sh` (append the video step).

- [ ] **Step 1: Generate the audit.** Run these and record results in the doc:
```bash
grep -n 'enableOnBackInvokedCallback' app/src/main/AndroidManifest.xml || echo "MISSING enableOnBackInvokedCallback"
grep -rnE 'BackHandler|onBackPressed|OnBackPressedCallback|addCallback|predictiveBack' app/src/main/kotlin | sort
```
Write `docs/ux/predictive-back-audit.md` as a table: every back-handling site (file:line) → classification (predictive-compatible `OnBackPressedCallback`/Compose `BackHandler` vs legacy `onBackPressed` override) → verdict/gap. Conclude with whether the app correctly opts into predictive back app-wide and any sites to fix (the fixes themselves are the follow-up RC).

- [ ] **Step 2: Add the in-app back video to capture-screenshots.sh** (after the config matrix):
```bash
# --- predictive-back in-app video ---
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
```

- [ ] **Step 3: Commit.**
```bash
git add docs/ux/predictive-back-audit.md .github/scripts/capture-screenshots.sh
git commit -m "docs+ci(rc77): predictive-back audit + in-app back-gesture video capture"
```

## Task 6: Dispatch, collect, surface (controller-operated)

- [ ] **Step 1: Merge rc77 work to master; bump versionName to `1.0-rc77`** (comment: "UX edge-case capture harness").
- [ ] **Step 2: Dispatch the emulator matrix:** `gh workflow run screenshots.yml --ref feat/md3e-redesign` (or master). Wait for `ui-screenshots` artifact; download; confirm `baseline/font360/cutout-*.png` + `back.mp4` present.
- [ ] **Step 3: Dispatch the flip:** `gh workflow run ftl-ux-screenshots.yml --ref master -f device=arcfox -f version=34`. On completion, pull `gs://$FTL_BUCKET/ux-shots-<run_id>/.../ux-shots/*.png`. If `arcfox` queues out (cf. budget-device FTL scarcity), retry once or fall back to a foldable AVD profile (documented in the spec).
- [ ] **Step 4: Surface** the font360 set, the cutout set, the flip set, `back.mp4`, and the audit to the user via the file channel; call out any truncation/overlap/inset issues observed (→ follow-up fix RC).

---

## Self-Review
- **Spec coverage:** 360dp+max-font (T2 font360) ✓; cutout/edge insets (T2 cutout + T5 audit covers code side) ✓; flip foldable (T3+T4, arcfox) ✓; predictive back audit+video (T5) ✓; dense screen states (T1) ✓; output/surface (T6) ✓; capture-only scope (fixes deferred) ✓.
- **Placeholder scan:** none — bash/YAML/Kotlin are complete; T1 references the existing `--es screenshot` pattern to follow (real code in the file), not a placeholder.
- **Consistency:** the `STATES`/`states` list matches across T2 (bash) and T3 (Kotlin) and T1 (states added); `/sdcard/ux-shots` + `--directories-to-pull` match between T3 and T4; `ux-shots-<run_id>` results-dir matches T4↔T6.

## Out-of-scope follow-ups
- Fixing any layout/inset/predictive-back gaps the artifacts reveal (next RC).
- Foldable emulator profile (only if `arcfox` FTL is unavailable).
