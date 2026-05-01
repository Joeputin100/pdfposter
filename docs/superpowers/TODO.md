# Out-of-band TODOs (cross-session persistence)

Items deferred from the MD3E redesign work. Each is its own future plan.

---

## TODO 1 — Migrate Android build to GitHub Actions

**Status:** Deferred until MD3E redesign lands.

**Scope:** Move `cloudbuild.yaml` (Android APK + AAB build only) to `.github/workflows/build-android.yml`. **Keep `cloudbuild-backend.yaml` on Cloud Build** — already wired to a Cloud Build SA with `Firebase Admin`, `Cloud Functions Developer`, `Service Account User` roles; replicating that path under workload identity federation is fragility risk for negligible UX gain.

**Why GH Actions for Android:**
- Free for both private and public repos at this volume (~360 build-min/mo, well under 2000-min private free tier).
- Native `actions/cache@v4` gives Gradle/Kotlin/AGP caching → 12 min → 5–7 min builds once warm.
- PR-native status checks + log access in the same UI as code review.
- Marketplace actions for Play Store upload (`r0adkll/upload-google-play`) and release artifacts (`softprops/action-gh-release`).

**Steps when ready:**
1. Generate workload identity federation pool + provider in GCP console (one-time).
2. Move keystore from `gs://static-webbing-461904-c4_artifacts/release.keystore` to a base64 GH secret (`KEYSTORE_BASE64`) plus passwords as separate secrets (`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`). **Note:** the keystore is currently *also* committed to the repo — see TODO 4 for cleanup before this migration.
3. Author `.github/workflows/build-android.yml`:
   - `actions/setup-java@v4` (zulu, 21)
   - `gradle/actions/setup-gradle@v3` (auto-cache)
   - decode keystore + run `./gradlew assembleDebug bundleRelease`
   - upload .aab + .apk as workflow artifacts
4. Run both pipelines in parallel for 1–2 weeks; compare outputs byte-for-byte where possible.
5. Decommission Cloud Build trigger for the Android job.

**Estimate:** 4 hours.

---

## TODO 2 — Adaptive layouts (foldable + tablet)

**Status:** Tier-2 Play-Store-featured-readiness item, deferred until MD3E redesign lands.

**Scope:** Make the app behave well on foldables and tablets. Currently the layout assumes a phone-sized portrait viewport; on an unfolded foldable or a 10" tablet, the single-column scroll wastes most of the screen.

**Dependencies:**
- `androidx.compose.material3.adaptive:adaptive:1.0.0+`
- `androidx.compose.material3.adaptive:adaptive-navigation:1.0.0+`
- `androidx.compose.material3.adaptive:adaptive-layout:1.0.0+`
- `androidx.window:window:1.3.0+` for foldable state detection

**Approach:**
- `ListDetailPaneScaffold` for tablet (image picker + settings on left, preview on right).
- `WindowSizeClass` to switch between phone (single column), foldable half-open (two columns with hinge gap), and tablet (split view).
- Re-test History screen as detail pane vs. full-screen swap.

**Estimate:** 6–8 hours.

---

## TODO 3 — Optimize baseline profiles

**Status:** Tier-3 Play-Store-featured-readiness item; biggest single perf-per-effort win in the bucket.

**Why this matters:**
Baseline profiles encode "which classes/methods to AOT-compile at install time" instead of waiting for JIT during runtime. For a Compose app, cold-start time typically drops 20–30%, and the first-frame render of complex screens (the construction preview!) gets meaningfully smoother. This shows up in **Android Vitals** as improved startup percentiles, which feeds directly into Play Store algorithmic featuring.

**Dependencies:**
- `androidx.profileinstaller:profileinstaller:1.4.0+` (runtime)
- `androidx.benchmark:benchmark-macro-junit4:1.3.0+` (test-time, for profile generation)
- A new module: `:baselineprofile` (or `:macrobenchmark`)

**Approach:**
1. Add the `:baselineprofile` module via Android Studio's Module Wizard ("Baseline Profile Generator").
2. Author a Macrobenchmark test that runs the app's "golden path" — splash → main screen → image pick → preview → generate PDF.
3. Generate the profile via `./gradlew :app:generateReleaseBaselineProfile`. The Gradle task spins up an emulator (or connects to a device), runs the macrobenchmark, captures the trace, and writes `app/src/main/baseline-prof.txt`.
4. Verify profile installation: `./gradlew :app:installRelease` → check that `art-profile-installed` log appears.
5. Re-run macrobenchmark with profile installed and capture the *baseline-vs-no-baseline* delta — should see ~20–30% improvement on `timeToInitialDisplay` for cold start.
6. Wire the profile generation into CI: a recurring (weekly?) Cloud Build job regenerates the profile, especially after major UI changes.

**Caveats:**
- Macrobenchmark needs a real device or emulator — Cloud Build's `cimg/android` image doesn't have one; this generation step needs to happen locally on a Pixel (or via Firebase Test Lab / a self-hosted runner).
- The profile is a text file checked into the repo; treat it as build-derived but version-controlled.
- Without code stability (Phase D's animation loops are heavy), the profile measurements will move around. **Recommendation:** generate the *first* baseline profile only AFTER the MD3E redesign has settled (e.g., one month post-merge).

**Estimate:** 3–4 hours initial setup + recurring CI integration.

---

## TODO 4 — Audit + clean repo of committed credentials

**Status:** Discovered during the MD3E session's pre-push security check (2026-05-01).

**Findings:**
- `release.keystore` is committed to the repo and visible in **all 26 unpushed commits**. The repo at `https://github.com/Joeputin100/pdfposter` returns HTTP 200 (public). If/when these commits are pushed, the keystore enters the public history.
- `app/build.gradle.kts:25-27` hardcodes `storePassword = "posterpdf"`, `keyAlias = "posterpdf"`, `keyPassword = "posterpdf"`. Combined with the keystore presence, *anyone with the repo URL can sign artifacts as if they were the user* (modulo Play App Signing, which Google itself holds the upload-key swap path for).
- `app/google-services.json` is tracked. Generally OK for Firebase config (API keys are SHA-1-fingerprint-restricted), but if the app uses Firebase services not so restricted (e.g., Realtime Database with open rules), this could leak.
- `keystore_gen.yaml` exists in the repo root — review for credential exposure.
- `.gitignore` lacks `*.keystore`, `*.jks`, `release.keystore`, `keystore_gen.yaml`.

**Remediation steps (in order):**
1. **Decide first:** does the user plan to use this keystore as an upload key for Play Store? If yes, **rotate it immediately** — the hardcoded password makes it compromised.
2. Add to `.gitignore`:
   ```
   *.keystore
   *.jks
   release.keystore
   keystore_gen.yaml
   ```
3. Move keystore passwords out of `app/build.gradle.kts`. Read from environment variables or `~/.gradle/gradle.properties`:
   ```kotlin
   signingConfigs {
       create("release") {
           storeFile = file("../release.keystore")
           storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "posterpdf"  // dev fallback
           keyAlias = System.getenv("KEY_ALIAS") ?: "posterpdf"
           keyPassword = System.getenv("KEY_PASSWORD") ?: "posterpdf"
       }
   }
   ```
4. Remove keystore + sensitive files from git index but keep on disk:
   ```bash
   git rm --cached release.keystore keystore_gen.yaml
   git commit -m "chore: untrack keystore + generation config"
   ```
5. **Optional, intrusive:** rewrite history to remove the keystore from past commits via `git-filter-repo --path release.keystore --invert-paths`. This breaks all existing clones; only do it before pushing publicly. **Skip if the upload key was never compromised** (i.e., the keystore was never published).
6. Verify Cloud Build still works — `cloudbuild.yaml` step 1 fetches the keystore from GCS, so removing it from the repo is fine for Cloud Build.

**Phase F of the MD3E plan (`docs/superpowers/plans/2026-05-01-md3e-redesign.md`)** runs `/security-review`, which will surface these findings; the remediation here is the work to do when those findings come up.

**Estimate:** 1 hour for items 1–4. History rewrite (item 5): ~30 min, but requires a force-push and breaks all existing clones.

---

## TODO 5 — Crashlytics + JankStats integration

**Status:** Tier-3 Play-Store-featured-readiness item.

**Why:** Android Vitals reports crash rate, ANR rate, slow-frame rate, slow-cold-start rate. Apps that consistently hit Vitals quality bars get algorithmic featuring lift. Crashlytics is the most accurate way to measure crashes; JankStats is the most accurate way to measure jank.

**Dependencies:**
- `com.google.firebase:firebase-crashlytics-ktx` (already on Firebase BOM 32.7.0)
- `com.google.gms:google-services` (already there)
- `com.google.firebase:firebase-crashlytics-gradle:3.0.2` (plugin)
- `androidx.metrics:metrics-performance:1.0.0-beta01`

**Approach:**
1. Add the Crashlytics Gradle plugin to root `build.gradle.kts`.
2. Apply the plugin in app module.
3. Auto-init via the Firebase initializer; no further code needed for crash reporting.
4. JankStats: instantiate in `MainActivity.onCreate`, attach to the window's frame metrics, log slow frames to Crashlytics non-fatal events.

**Estimate:** 2 hours.

---

## TODO 6 — Localization to 5+ languages

**Status:** Tier-3 Play-Store-featured-readiness item.

**Why:** Localized apps reach more users, qualify for more regional featuring, and signal "global app" to the Play Store algorithm. Realistic minimum for featuring: 5 locales beyond English.

**Suggested locales:** Spanish (es), French (fr), German (de), Portuguese-Brazil (pt-BR), Japanese (ja), plus optionally Hindi (hi) or Arabic (ar).

**Approach:**
1. Audit `app/src/main/res/values/strings.xml` — extract all hardcoded UI strings from `MainActivity.kt` etc. into resources (the current code has ~30 inline string literals that need extraction).
2. Use Android Studio's "Translations Editor" to manage locale variants.
3. Get translations: machine-translate via Google Translate API for first pass, then have a native speaker review (Fiverr / Tolgee). For an indie app, machine translations are a defensible starting point.
4. Add `<locale-config>` resource (`res/xml/locales_config.xml`) declaring all supported locales.
5. Integrate `LocaleManagerCompat.setApplicationLocales()` so the user can switch the app's language independent of the OS language.

**Estimate:** 4 hours engineering + variable translation cost.

---

## (Append future deferrals here)
