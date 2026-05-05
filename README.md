# Poster PDF

An Android app that turns any image into a printable, multi-page poster.
Pick a photo, choose a poster size, and Poster PDF tiles it across as many
sheets of paper as needed — with margins, overlap zones, and an assembly
guide page so you can trim and tape the result into one large print.

[![Build Android](https://github.com/Joeputin100/pdfposter/actions/workflows/build-android.yml/badge.svg)](https://github.com/Joeputin100/pdfposter/actions/workflows/build-android.yml)
[![Test Battery](https://github.com/Joeputin100/pdfposter/actions/workflows/test-battery.yml/badge.svg)](https://github.com/Joeputin100/pdfposter/actions/workflows/test-battery.yml)

## Why this exists

Anyone who's tried to print a 24×36 inch poster on an 8.5×11 inch printer
knows the standard advice: open the PDF in Preview / Adobe / a print shop's
desktop tool, manually slice it into pages, eyeball the overlap, hope the
trim lines align. It's tedious, error-prone, and gatekept behind tools that
don't exist on phones — where most people's source images live in the
first place.

Poster PDF does the slicing on your phone. It also goes a few steps beyond
"divide into rectangles": it picks the smallest sheet count for your target
print resolution, draws cut lines you can actually follow, generates an
assembly diagram, and (optionally) sharpens low-resolution photos with
either an on-device upscaler or a cloud AI model — your choice between
free-but-slow on-device and paid-but-fast cloud.

## Features

### Core (works offline, no signup)
- **Image picker** that handles any source the system shares — gallery,
  files, screenshot, Lens, web URL, SVG.
- **Tile math** that picks rows × columns, places overlap zones, and
  sizes each printable rect to your paper of choice (Letter, A4, Legal,
  Tabloid, A3, custom).
- **Live preview** showing every page as it'll print, with margin guides
  and overlap zones visible so you can plan your trim before any ink
  hits paper.
- **Assembly cycle animation** — a 30-second hand-drawn sequence
  demonstrating the print → arrange → cut → tighten → tape → pin
  workflow so first-time users know what to do with the output.
- **Brand-aware PDF output** with a wordmark header, ruler-marked
  assembly guide, label coordinates per page (A1, B2, …), and an
  optional QR code linking back to the Play Store for sharing.

### Optional (signed-in features)
- **AI sharpening** — choose between Topaz Gigapixel, Recraft Crisp,
  AuraSR, or Real-ESRGAN. Each model has its own price/quality tradeoff;
  the modal shows live cost in credits. Or pick the free on-device
  ESRGAN-TF2 that runs slower (~5 minutes for a 12 MP photo) but costs
  nothing.
- **Cloud history** — recent posters auto-save for 30 days with optional
  paid extension (1¢/MB-month, billed monthly per file).
- **9-locale UI** — English, Spanish, French, German, Portuguese (Brazil),
  Russian, Hindi, Japanese, Chinese (Simplified), Arabic. Switchable
  via the drawer's Language picker independent of OS language.

## Screenshots

The `docs/screenshots/` directory holds the gallery used in this README +
the Play Store listing. See `docs/screenshots/README.md` for the capture
script.

## Tech stack

- **UI**: Jetpack Compose + Material 3 Expressive (BOM 2026.04.01,
  material3:1.5.0-alpha18 for `MaterialExpressiveTheme` + motion scheme)
- **Kotlin** 2.0.21, **AGP** 8.9.0, **Gradle** 8.11.1
- **Source compat** Java 17 → Android API 23 (Marshmallow) through
  API 36 (Android 16)
- **PDF generation**: PDFBox-Android 2.0.27
- **AI upscaling**:
  - On-device: ESRGAN-TF2 via TFLite 2.16.1 (NnApiDelegate; not GPU)
  - Cloud: FAL.ai (Topaz, Recraft, AuraSR, Real-ESRGAN) via signed
    Cloud Functions calls
- **Auth + sync**: Firebase Auth (anon + Google), Firestore, Cloud
  Messaging, Cloud Storage
- **Backend**: TypeScript Cloud Functions v2 deployed via Cloud Build
- **Dependencies**: see [`app/build.gradle.kts`](app/build.gradle.kts)
  + [`backend/functions/package.json`](backend/functions/package.json)

## Building

### Prerequisites

- JDK 21
- Android SDK 36 + Build Tools 36.x
- Gradle 8.11.1 (the project uses no wrapper; install Gradle directly
  or let CI's `Install Gradle 8.11.1` step handle it)

### Local debug build

```sh
git clone https://github.com/Joeputin100/pdfposter
cd pdfposter
# Provide a release.keystore in the repo root (CI fetches one from
# GH Actions secrets; for local dev any keystore with the legacy
# "posterpdf" passwords works fine).
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Release AAB

The release bundle path requires real credentials:

```sh
export KEYSTORE_PASSWORD=...
export KEY_ALIAS=...
export KEY_PASSWORD=...
gradle bundleRelease
```

If those env vars aren't set, the build falls back to the in-tree
`"posterpdf"` defaults — fine for testing the bundle output, not OK for
actual Play Store submission.

### Backend

The Cloud Functions backend deploys via Cloud Build (not GH Actions —
the Cloud Build SA already has Firebase Admin + Cloud Functions
Developer roles, replicating that under workload-identity federation
isn't worth the fragility).

```sh
gcloud builds submit --config=cloudbuild-backend.yaml .
```

This builds the TypeScript, deploys functions, applies Firestore +
Storage security rules.

## CI

| Workflow | Trigger | What it runs |
|----------|---------|--------------|
| `.github/workflows/build-android.yml` | push to `master`/`feat/**` touching `app/**` | `gradle assembleDebug bundleRelease` + uploads APK + AAB as artifacts |
| `.github/workflows/test-battery.yml` | push touching `app/**` | JVM unit tests + emulator instrumentation tests on API 23 / 28 / 33 |
| `cloudbuild-backend.yaml` | manual via `gcloud builds submit` | functions + firestore rules + storage rules deploy |

## Tests

- **JVM unit tests**: see `app/src/test/kotlin/com/posterpdf/`
  - `PosterLogicTest` — sheet count, tile generation, grid label math
  - `ui/components/preview/PaneGeometryTest` — pane layout, source-rect
    fractions, edge-case clamping
- **Instrumentation tests**: see `app/src/androidTest/kotlin/com/posterpdf/`
  - `SmokeTest` — boot path, package match
  - More to come as features stabilize.
- **FAL exclusions**: pass
  `-Pandroid.testInstrumentationRunnerArguments.excludeFAL=true` to
  `connectedDebugAndroidTest` to skip anything that would call FAL
  (paid). The CI workflow already passes this flag.

## License

See [LICENSE](LICENSE).

## Contributing

Issues + pull requests welcome. For non-trivial changes, open an issue
first so we can talk through approach. Trivial fixes (typos,
documentation, single-file logic) — just send the PR.

## Contact

- Bugs / feature requests: open an issue
- Questions: <joeputin100@gmail.com>
