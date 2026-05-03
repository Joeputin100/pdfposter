# Out-of-band TODOs (cross-session persistence)

Items deferred from the MD3E redesign work. Each is its own future plan.

---

## TODO 1 — Migrate Android build to GitHub Actions ✅ DONE 2026-05-02

**Outcome:**
Android build moved from Cloud Build to GitHub Actions in a single session. Backend deploys still go through `cloudbuild-backend.yaml` per the original CBA — backend SA already wired with Firebase Admin / Functions Developer / SA User roles.

**A/B comparison data (commit `dc3f7fb`, both pipelines on the same source):**

| | Cloud Build | GH Actions |
|---|---|---|
| Wall-clock | 28m 29s | **7m 59s** (3.6× faster) |
| Debug APK SHA-256 | `614d22…dab15c` | `614d22…dab15c` (identical) |
| Release AAB | 27,115,356 B | 27,115,426 B (+70 B metadata) |
| Free-tier cost | ~28 min of 120/day | $0 (public repo) |

The 70-byte AAB diff is bundletool tooling timestamps — irrelevant to install behavior. APK byte-identical means functional equivalence.

**Departures from the original plan:**
- **Skipped Workload Identity Federation.** Used base64-encoded GH secrets for the keystore + `google-services.json` instead. Simpler, fully working, no GCP-side IAM setup. WIF would be needed only if we ever want the GHA workflow to deploy to GCP (which it doesn't — backend stays on Cloud Build).
- **Skipped the 1-2 week parallel-run period.** APK byte-identical on first comparison removed the need.
- **Used Gradle 8.10.2 manually downloaded** instead of `gradle/actions/setup-gradle@v3` — project has no `gradlew` wrapper today. Adding the wrapper is a small follow-up that would unlock the action's caching path.
- **Removed `tensorflow-lite-gpu`** dep along the way — R8 minify failed on a missing class; we weren't using GPU delegate anyway. (See commit `dc3f7fb`.)

**What landed:**
- `.github/workflows/build-android.yml` (on `master` and `feat/**`, with path filters to skip non-app changes)
- `gh secret set RELEASE_KEYSTORE_BASE64` and `GOOGLE_SERVICES_JSON_BASE64`
- `cloudbuild.yaml` deleted

**Follow-ups (low priority):**
- Add `gradlew` wrapper → drop the manual gradle install step (saves ~30s/run + enables `setup-gradle` caching)
- Move keystore passwords from `app/build.gradle.kts` hardcoded "posterpdf" to GH secrets (TODO 4 step 3)
- Consider a `r0adkll/upload-google-play` step for automated Play Store internal-track uploads once Play Console is paid (TODO follow-on after Phase G ships)

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

## TODO 7 — Make the GitHub presence a showcase piece

**Status:** Long-term polish; runs in parallel with engineering.

**Why:** The repo at `https://github.com/Joeputin100/pdfposter` is the public artifact of this app. For a Play-Store-featured-app candidate, the GitHub repo is the secondary calling card — recruiters, fellow developers, journalists, and the curious all land there before they land on the Play Store listing. Currently it's a working dev repo, not a showcase.

**Workstreams:**

### A. Repository hygiene
- **README.md (top-level, missing today)**: hero image / animated GIF of the construction preview, value proposition, "what it does" 1-paragraph, screenshots, install link to Play Store, link to Privacy Policy, build status badge from Cloud Build (or GH Actions when migrated), Firebase status badge, license, contact / contributing section. Aim for the first scroll to convey *what the app is* without scrolling past it.
- **LICENSE file**: pick a license deliberately (MIT for permissive, AGPL-3.0 if you want to require derivatives stay open-source, or proprietary "all rights reserved" if you'd rather show source without granting use). Without a license, by default no one can legally fork.
- **Topics + description**: set GitHub repo topics (`android`, `jetpack-compose`, `material3`, `kotlin`, `pdf-generator`, `firebase`, `material-3-expressive`) and a one-sentence description. These feed GitHub Search.
- **`.github/`**: `ISSUE_TEMPLATE/bug_report.md`, `ISSUE_TEMPLATE/feature_request.md`, `PULL_REQUEST_TEMPLATE.md`, `FUNDING.yml` (if accepting sponsorship), `dependabot.yml` (auto-PRs for dep bumps).
- **Branch protection on `master`**: require PR review, require CI passing, no force-pushes (set after MD3E lands so this current rewrite isn't blocked).

### B. Visual marketing
- **Repo social preview image** (1280×640 PNG): Settings → Social Preview. Use a hero shot of the construction preview's Reveal phase (with the thumb tacks pinned) — this is the single most photogenic frame the app produces.
- **`docs/screenshots/` directory**: 6–8 carefully-staged screenshots covering each major flow (image pick → preview → settings → generate → history → assembly cycle in motion). Include 1 video / animated GIF (≤5 MB) demonstrating the Assembly Cycle.
- **`docs/architecture.md`**: 1–2 page walkthrough of the layer diagram (Compose UI → ViewModel → Repository → Backend / Firebase). Aim it at a developer reading code review.
- **`docs/design-system.md`**: showcase the blueprint-blue + Fraunces brand identity, MD3E motion tokens, and component patterns. Lift the "Phase B + E" sections of the redesign plan and edit for a public audience.

### C. Code-level polish for showcase reading
- **All TODOs in code link to issues**: `// TODO: …` becomes `// TODO(#42): …` so a reader can click through.
- **Top-of-file comments on the major composables**: `MainActivity.kt`, `PosterPreview.kt`, `PaneGeometry.kt` — one paragraph each explaining what this file is for and the major decisions, written for someone who's never seen the codebase.
- **Module README files**: `app/README.md`, `backend/README.md` (already exists; expand it).
- **Type the gnarly bits**: ensure public-facing classes (`PosterLogic`, `MainViewModel`, `PaneGeometry`) have KDoc.

### D. CI badges + automation
- After GH Actions migration (TODO 1), add badges for build status, latest release, Play Store install count, and code coverage. Each badge is a 1-line README addition.
- GitHub Releases for each Play Store release: tag (`v1.0`), changelog, attached AAB.
- Optional: Renovate or Dependabot configured for weekly dep PRs.

### E. Discoverability
- **Submit to lists**: `awesome-android`, `awesome-jetpack-compose`, `awesome-material-3` curated GitHub lists accept PRs; landing on one of those gets meaningful inbound traffic.
- **Cross-link**: Play Store listing description includes GitHub URL. Personal site / portfolio links to the repo.

**Estimate:** 6–10 hours, distributed: A is 2h, B is 3h (most of it screenshots + GIF rendering), C is 2h, D is 1h post-GH-Actions-migration, E is 1h plus the wait time for `awesome-*` PRs to merge.

**When to start:** A + B can start the day MD3E redesign lands on master. C piggybacks on each phase's PR. D is gated on TODO 1 (GH Actions). E is the very last step.

---

## TODO 8 — Aggressive automated testing CI (Tiers 1–5b on every build)

**Status:** Planned for after Phase F (security review) but before final Play Store submission. Bundle with TODO 1 (GH Actions migration).

**Goal:** Run all of Tiers 1, 2, 3, 4, 5a, 5b on every push, with the user's Galaxy A26 (Android 16 / One UI 8) reserved as the tier-5c manual smoke-test device. Repo is public → unlimited GH Actions Linux minutes; FTL fits in free tier.

**Two new workflow files:**

### `.github/workflows/test.yml` — every push, ~10–15 min wall-clock

Three jobs in parallel:
1. **`jvm`** (Tiers 1–3): `./gradlew test verifyPaparazzi lint`. ~5 min cached.
2. **`emulator`** (Tier 4): `reactivecircus/android-emulator-runner@v2` API 34, runs `./gradlew connectedDebugAndroidTest`. ~10 min with cached AVD snapshot.
3. **`ftl-virtual`** (Tier 5a): `gcloud firebase test android run` against `Pixel2.arm/30`, `Pixel2.arm/33`, `MediumPhone.arm/34`. Free tier (~5 device-hours/day budget covers ~60 short runs).

Gate `emulator` + `ftl-virtual` on `jvm` passing first (cheap fail-fast).

### `.github/workflows/ftl-physical-weekly.yml` — Mondays + on `v*` / `md3e-*` tags

Single job runs `gcloud firebase test android run` against real-device models from FTL's catalog. Free tier: 0.5 physical device-hours/day (~6 short runs); weekly cadence consumes ~2 hours/month, well within budget.

### Setup prerequisites (one-time)
- **Workload Identity Federation** for GH Actions → GCP auth. Configure pool + provider in GCP IAM (~30 min). Use OIDC tokens, not service-account JSON keys (long-lived keys are a rotation pain).
- **Service account** `ftl-runner@static-webbing-461904-c4.iam.gserviceaccount.com` with `Firebase Test Lab Admin` + `Cloud Storage Object Admin` (for FTL result uploads).
- **Add `org.junit.platform:junit-platform-launcher` + `com.google.testing.platform:core-proto:0.0.9-alpha02`** if FTL needs newer test-platform protos.

### Code-side prerequisites
- **Paparazzi**: add `app.cash.paparazzi:paparazzi-gradle-plugin:1.3.5` (or current) to project. Write 8–10 initial snapshots: `PosterPreview` × {1×1, 3×4, no-margin, large-overlap, dark-theme, custom-paper}; `MainScreen` × {empty-state, image-picked, advanced-options-expanded}.
- **Instrumented tests**: `androidx.compose.ui:ui-test-junit4` already on classpath via BOM. Write Compose UI tests for paper-size selector, generate-button click flow, history navigation.
- **Macrobenchmark** module: see TODO 3 — gated on tier-5c access (TODO 9). For v1.0 use the FTL-virtual-device profile.

**Estimate:** 4 hours one-time setup + ~30 min per new test class.

**Layered with the A26 manual ritual** — described in TODO 9 — this gives full coverage: Tiers 1–4 fast-fail-fast on every PR, FTL virtual matrix on every push to master, FTL physical matrix weekly + on tag, A26 manual smoke before tag.

---

## TODO 9 — Solve tier-5c (real-device benchmarking on Galaxy A26)

**Status:** Open question. Blocks optimal Baseline Profile generation but does not block v1.0 launch.

**The constraint:** the user's only Android device is the Galaxy A26 (Android 16, One UI 8). They lack:
- A computer (no traditional adb-over-USB)
- A second device (no second-phone adb-pairing bootstrap)
- A wifi network (no adb-over-wifi pairing)

**Why this matters:** Macrobenchmark requires `adb shell` access. It installs/uninstalls the test app, clears package data between iterations, and reads perfetto traces — all `adb`-gated. Without adb the user cannot run `./gradlew :app:generateReleaseBaselineProfile` against their A26.

**Manual smoke testing is unaffected.** Sideloading APKs via the Files app + Chrome download from a GCS signed URL works without adb. Only Macrobenchmark / Baseline Profile generation is blocked.

### Path A — Ship v1.0 with FTL-virtual-generated Baseline Profile (recommended)
- Run Macrobenchmark from the GH Actions FTL workflow against `Pixel2.arm` virtual device.
- Pull the resulting `baseline-prof.txt` as a workflow artifact.
- Commit it via a follow-up PR (or auto-commit via a scheduled workflow).
- **Quality gap:** ~10–20% less optimal than a profile generated against the actual A26 hardware (Exynos 1380 has different cache hierarchy than virtual Pixel ARM).
- **Setup time:** ~2 hours (Macrobenchmark module + FTL game-loop invocation).

### Path B — Wait for adb access
- One-time event: borrow a friend's laptop / library computer, do baseline profile generation, commit.
- Or acquire a Raspberry Pi 5 (~$35 one-time) — runs adb on Linux, sits in a drawer, USB-C cable to A26, generate profile occasionally.
- Or: occasional public-library access; a 30-minute session is enough for one Macrobenchmark run.
- **Quality:** optimal profile (Exynos-tuned, One UI 8 quirks accounted for).
- **Setup time:** variable depending on access.

### Path C — Cloud device service
- BrowserStack App Live ($39/mo) gives remote real-device access including Samsung Galaxy A series (occasionally exact A26).
- Free tier: 30 min/mo limited.
- Heavy for a one-time profile generation; lighter for occasional debug sessions if needed.
- **Quality:** good if exact A26 model available; otherwise marginal improvement over FTL virtual.
- **Setup time:** account creation + walkthrough, ~1 hour.

### Path D — Skip Baseline Profile for v1.0
- App will start ~20–30% slower than it could (still usable, just suboptimal).
- Add Baseline Profile in v1.1 once tier-5c is solved.
- **Quality:** no profile = baseline AOT.
- **Setup time:** $0.

### Recommendation

**v1.0 → Path A** (FTL-virtual profile). Automated, free, ships with the redesign. Document the limitation in `app/src/main/baseline-prof.txt` header.

**Post-v1.0 → Path B with Raspberry Pi 5** (or borrowed laptop). Regenerate the profile as a single dedicated PR titled "perf: regenerate baseline profile on real Galaxy A26 hardware". Compare cold-start times before/after on FTL physical Samsung models to verify the upgrade is real.

**Path C** is the right answer if there's also a recurring need for remote A26 debugging sessions (e.g., occasional crash reproduction). Otherwise overpaying.

**Estimate:**
- Path A: 2 hours (one-time setup) + 0.5 device-hours/run on FTL.
- Path B: 30 min one-time once adb is available.

---

## TODO 10 — FinOps module (profitability, cash flow, cost-driver dashboard)

**Status:** Future. Lands once Phase G has real revenue + cost data flowing.

**Why this matters:**
Phase G introduces a multi-input/multi-output economic loop — Play Store revenue minus Play's 15% fee minus FAL inference cost minus Cloud Functions / Storage / Firestore cost = gross margin per credit. Without dedicated reporting we'll be guessing whether the 50%-target margin formula in `pricing-policy.md` is actually holding, whether one SKU subsidises another, or whether a price change at FAL has silently inverted the curve. A FinOps module surfaces all of that in one place.

**Data sources to unify:**
- **Revenue:** Play Store reports (CSV export from Play Console; eventually the Play Developer Reporting API). Per-SKU, per-country, per-day. Net of Play's 15% fee, refunds, taxes withheld.
- **FAL cost:** new `falModelCostUsd` history (currently `pricing/current` is a single-doc snapshot — append to a `pricing/history/{date}` ledger instead so trends are queryable). Plus actual usage = count of `upscaleTransactions{status='succeeded'}` per day × `creditsCost` × `falModelCostUsd`.
- **Google Cloud cost:** Billing export to BigQuery dataset `cloud_billing_export` (one-time setup in Billing → Billing export). Filter to project `static-webbing-461904-c4`. Breaks down by service (Functions invocations, Firestore reads/writes, Cloud Storage egress, Cloud Logging ingestion, Firebase Auth SMS — once it's enabled).
- **Refund / chargeback rate:** `creditLog{type='refund'}` counts vs. `creditLog{type='grant'}`. Negative-margin canary if refunds spike.
- **Float / cash flow:** Play pays out monthly with a ~30-day delay; FAL bills monthly; Cloud bills monthly. Float = (revenue earned) - (revenue paid) + (cost incurred) - (cost paid). Need running ledger.

**Module shape:**
- `backend/functions/src/finops/` (new directory):
  - `dailyRollup.ts` — scheduled cron at 02:00 UTC writing `/finops/daily/{YYYY-MM-DD}` with the day's revenue, FAL spend, GCP spend, credits sold, credits burned, refund count, computed gross margin.
  - `bigqueryAdapter.ts` — pulls Cloud billing data from the BigQuery export. Requires `roles/bigquery.dataViewer` on the dataset.
  - `playReportingAdapter.ts` — pulls Play revenue once Play Console is funded + Reporting API is enabled. Until then, manual CSV upload to a `/finops/manual-revenue/{month}` doc.
  - `falCostAdapter.ts` — already half-built; reuse `pricing.ts`'s FAL pricing fetch, multiply by transaction counts.
- `backend/functions/src/finops/dashboard.ts` — admin-gated callable returning a 90-day window of rolled-up metrics for the dashboard UI.
- **Dashboard UI** — open question:
  - **Option A:** in-app admin screen behind the same `admin: true` custom claim used by `getFalBalance`. Keeps everything in Compose. Reuses MD3E charting; the `androidx.graphics:graphics-shapes` lib already on classpath does sparklines well.
  - **Option B:** standalone web dashboard (Next.js + Recharts) hosted on Firebase Hosting. Easier to extend with rich tables, exports, more sophisticated filtering. Costs nothing extra on Hosting's free tier.
  - Recommend **B** once dashboard becomes worth more than 1 chart — admin web UX is much friendlier than admin mobile UX for a finance tool.

**Reports the module should answer:**
1. **Per-SKU profitability:** for each of `credits_small/medium/large/jumbo`, the rolling 30-day gross margin. Catches "the $1.99 SKU is now unprofitable because FAL raised the Topaz rate".
2. **Cost-driver waterfall:** revenue → minus Play fee → minus FAL inference → minus GCP infra → gross margin. Updates daily.
3. **Cash flow forecast:** projected balance 30 / 60 / 90 days out given current burn + expected Play payout date + scheduled FAL invoice. Catches "we'll go cash-negative in May before Play deposits in June".
4. **Cohort behaviour:** % of users who buy a second pack within 30 days. Drives "is this a sustainable business or a one-time-purchase trap?"
5. **Anomaly alerts:** wired into Cloud Monitoring — page if the day's gross margin drops below 30%, or refund rate exceeds 5%, or any single user burns >$X of FAL credits in <1 hr (fraud canary).

**Bootstrapping order:**
1. Set up BigQuery billing export now (one-time GCP console action, takes 5 min, no code) so historical data starts accumulating.
2. Append-only `pricing/history` instead of single `pricing/current` doc — small change, lots of leverage.
3. `dailyRollup.ts` cron with just FAL + GCP data (Play data lands later when funded).
4. CSV-upload Play revenue stub for the launch month.
5. Standalone web dashboard — full Phase H deliverable, post v1.0.

**Estimate:**
- Bootstrapping (steps 1–4): 6 hours.
- Full web dashboard with anomaly alerting (step 5): 16–24 hours, mostly chart + filter UX.

---

## TODO 11 — Server-side input-dimension verification (anti-abuse for variable credit cost)

**Status:** Future. Captured during the Phase G economics revision (2026-05-02). Not blocking v1 launch since there's a single trusted user.

**Why this matters:**
Per `plans/2026-05-02-phase-g-economics-revision.md` (G-R9), credit cost now scales with `inputMp × scale²`. The client computes `inputMp` from the bitmap header before calling `requestUpscale` and the server trusts the value for the debit. A malicious client could under-report `inputMp` (e.g., claim 1 MP when actually sending 50 MP) to under-pay credits. With a single user this is paper; once the app has scale, it's a real loss vector.

**The fix:**
After FAL completes a job, the response includes the output image URL. Either:
- HEAD the URL and parse `Content-Length` + image-format magic bytes to derive output dimensions, or
- Range-request the first ~1 KB and parse JPEG/PNG/WEBP headers directly.

Compute `actual_output_mp` from the header. Verify `actual_output_mp / scale² > claimed_input_mp × 1.05` is false (5% tolerance for compression rounding). On failure:
1. Log a fraud event to Cloud Logging at `WARNING` (so Cloud Monitoring can alert)
2. Compute `additional_credits_owed = ceil((actual_output_mp - claimed_output_mp) / 5)`
3. Idempotently increment `users/{uid}.credits_owed` (a separate balance the user must clear before the next purchase)
4. Mark `upscaleTransactions/{txId}.fraud_flag = true`

**Where this lives:**
`backend/functions/src/upscale.ts` — extend the success path between `extractOutputUrl()` and the final `set({status: 'succeeded'})`. Don't refund or undo; the user already got their upscale. Just charge the difference.

**Tighter alternative (consider for v2):**
Verify dimensions BEFORE submit by reading the input from Storage. Adds 1-2 seconds of latency but closes the abuse window completely. Requires `getStorage().bucket().file(...).download()` + metadata parse. Pre-debit verification means we never under-charge.

**Estimate:** 2 hours.

---

## (Append future deferrals here)
