# UX Edge-Case Verification (editorial-review hardening) — Design

- **Date:** 2026-05-28
- **Status:** Approved (brainstorm) — pending spec review → writing-plans
- **Scope type:** Capture + audit (produce artifacts so we can *see* edge-case breakage). Fixing anything the captures reveal is a **follow-up RC**, not this phase.

## 1. Goal & Context

Produce concrete evidence of how PosterPDF's UI behaves in the edge cases Play Store editorial reviewers stress, so we can spot (and later fix) breakage before launch. Four verifications, two of them reframed because a screenshot can't show what was literally asked:

1. **360dp width + max font scale** — does the layout/buttons survive small-width + 200% font? (emulator screenshots)
2. **Display-cutout edge-to-edge insets** — the screenshot-able proxy for "Edge/wrap-around" worry (curvature itself is not in the framebuffer). (emulator screenshots + a `WindowInsets` code audit)
3. **Flip foldable, odd AR** — real razr+ via FTL, unfolded + (best-effort) folded cover.
4. **Predictive back** — a manifest/code opt-in audit + one in-app back-animation video. (cross-launcher *home* animation is system-rendered, out of scope)

## 2. Scope

**In scope:** the capture/audit harness + the artifacts (PNGs, one mp4, an audit checklist).
**Out of scope:**
- *Fixing* any layout/inset/back issues the captures reveal — that's a follow-up RC informed by these artifacts.
- Physical screen **curvature** (framebuffer is flat — unshowable by `screencap`).
- **Third-party launchers** (Nova etc.) and the cross-launcher **home** predictive-back animation (not installable/controllable in CI; not our code).
- Real Samsung Z Flip/Fold (absent from FTL; razr+ `arcfox` is the flip proxy).

## 3. Components

### 3.1 Emulator config matrix (extend the existing pipeline)
The current `.github/scripts/capture-screenshots.sh` captures 3 states (`main`, `compare`, `model_picker`) on one API-33 AVD via `am start --es screenshot <state>` + `screencap`. Extend it to:
- **Loop over configs**, each applied at runtime via adb (no AVD rebuild), capturing the full screen set per config, output named `<config>-<state>.png`:
  - `baseline` — current behavior (no changes).
  - `font360` — `adb shell wm size 360x780 && adb shell wm density 160` (→ ~360dp width) **and** `adb shell settings put system font_scale 2.0` (Android's max). The headline stress.
  - `cutout` — enable a built-in cutout overlay: `adb shell cmd overlay enable com.android.internal.display.cutout.emulation.tall` (probe `…tall`/`…double`; fall back to whichever the API-33 image ships). Exercises edge-to-edge inset handling.
  - **Reset between configs** (`wm size reset`, `wm density reset`, `font_scale 1.0`, `overlay disable …`) so configs don't bleed.
- **Expand the screen set** to the dense / button-heavy screens most likely to break under 360dp+200%: `main` (two-row top bar: logo + sparkle + credit chip), `compare`, `model_picker`, plus add hook states (in MainActivity's `--es screenshot` handler) for `settings`, `getting_started`, `low_dpi_modal` (the model cards + the new >10-min "Are you sure?" confirm), and `gemini` (the Q&A sheet) — adding only the states not already wired.
- Captured in the existing `screenshots.yml` emulator job; artifacts uploaded as `ui-screenshots` (already wired).

### 3.2 Real flip — FTL `arcfox` (razr+)
A new instrumentation test `app/src/androidTest/.../UxScreenshotTest.kt`: for each screen state, launch via the intent hook (`--es screenshot <state>`), wait for idle, and `UiDevice.getInstance(...).takeScreenshot(File("/sdcard/ux-shots/<state>.png"))`. A **sibling workflow `ftl-ux-screenshots.yml`** (clean single responsibility; mirrors `ftl-upscale-test.yml`'s WIF-auth + build + gcloud plumbing) runs it on `arcfox` API-34 with `--test-targets` for `UxScreenshotTest` and `--directories-to-pull /sdcard/ux-shots`; FTL uploads that dir to the results bucket, and the controller pulls it.
- **Unfolded** (tall inner display) captured reliably.
- **Folded cover** best-effort via FTL fold state if exposed; if the app doesn't render on the tiny cover surface, **that absence is itself a documented finding** (not a test failure).

### 3.3 Predictive back — audit + video
- **Static audit** (a generated `docs/.../predictive-back-audit.md` checklist): confirm `android:enableOnBackInvokedCallback="true"` in `AndroidManifest.xml`; grep every back path (`BackHandler`, any `onBackPressed`/`OnBackPressedCallback`, the screen-swap `AnimatedContent`, the docked drawers' dismiss) and classify each as predictive-back-compatible vs legacy. Output: a table of every back-handling site + verdict + any gaps to fix.
- **One in-app video**: in the emulator job, `adb shell screenrecord /sdcard/back.mp4 &` while a short UiAutomator script triggers the system back gesture across the app's transitions (open a drawer → back; navigate a screen → back). Upload `back.mp4` as an artifact. Shows our *in-app* predictive-back behavior (the only part that's ours).

### 3.4 Output
All PNGs (`<config>-<state>.png` + the FTL flip set), `back.mp4`, and `predictive-back-audit.md` land as CI artifacts / bucket objects; the controller surfaces the key ones to the user via the file channel.

## 4. Verification (how we know the *harness* worked)
- `screenshots.yml` produces `baseline/font360/cutout` × screen-set PNGs (non-empty, decode-able).
- The FTL `arcfox` run pulls a non-empty `ux-shots/` set; unfolded screens present.
- `back.mp4` is a valid non-empty recording; `predictive-back-audit.md` enumerates every back site with a verdict.
- (The *content* — whether buttons truncate at 200% font, etc. — is for human review of the artifacts; findings feed the follow-up fix RC.)

## 5. Risks & Mitigations
| Risk | Mitigation |
|---|---|
| `font_scale 2.0` clamped on API 33 | probe actual max via `settings get system font_scale` after set; note effective value in the run log |
| cutout overlay name varies by image | probe `cmd overlay list`, pick an available `…cutout.emulation.*`; skip+log if none |
| FTL `arcfox` availability (budget/foldable scarcity, cf. Redmi/moto) | accept best-effort; if it queues out, the emulator foldable profile is a documented fallback |
| folded cover display unrenderable | capture what's possible; document the cover behavior as a finding |
| `UiDevice.takeScreenshot` perms on FTL | standard on FTL instrumentation; `--directories-to-pull` uploads results |

## 6. Out-of-scope follow-ups (tracked)
- Any layout/inset/back **fixes** the artifacts reveal → next RC.
- Emulator foldable profile (only if `arcfox` FTL proves unavailable).
