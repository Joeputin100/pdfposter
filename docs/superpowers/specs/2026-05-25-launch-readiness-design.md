# Spec C — Launch-Readiness Checklist + Discoverability

**Author:** brainstormed 2026-05-25 with Claude (session "posterpdf")
**Status:** Approved design — awaiting implementation plan via `superpowers:writing-plans`
**Related:** [Spec A — Vertex Imagen upscale](2026-05-25-vertex-imagen-upscale-design.md), [Spec B — AppFunctions + In-app Gemini Q&A](2026-05-25-appfunctions-gemini-design.md)

---

## Goal

Validate and stage everything PosterPDF needs to launch cleanly AND maximize chances of an editorial "Featured Apps" placement on the Play Store. Spec C is intentionally a verification + content + outreach spec — output is reports, draft text, form submissions, and a fleet-test pipeline, NOT new feature code.

The editorial pitch leans on three signals editors look for:

1. **Full Android 16 stack adoption** (Material 3 Expressive, Predictive Back, edge-to-edge, adaptive layouts).
2. **AI integration** that makes the app a textbook agent-target — covered by Spec B (AppFunctions + Gemini Q&A) and Spec A (Vertex Imagen).
3. **Production polish** — accessibility, i18n, crash-free reliability, data-safety transparency.

Spec C verifies all three and packages them into a Play Console editorial request.

## Non-goals

- New product features. Bugs surfaced during the audits get fixed; nothing new is added.
- Replacing the existing `SmokeTest.kt` and macrobenchmark module — they continue running locally / in GitHub Actions. Spec C adds Firebase Test Lab on top.
- KYC/identity verification — that's the user's external blocker (see `play_store_account_state` memory) and is out-of-band from this spec, though listed as a launch dependency.

---

## 1. Platform-adoption audit

Verify each editorial-disqualifier item Google's documentation + Gemini's transcript flagged is actually working end-to-end on a real device. Where it isn't, file fix tasks into the implementation plan.

### Items to verify

| Item | Verification method | Fail criterion |
|---|---|---|
| Predictive Back gestures | Manual: every Activity transition + dialog dismissal animates predictive frame. | Hard pop / no preview on any transition. |
| Edge-to-edge by default | Manual on Android 15+: system bars transparent, content draws under them, insets honored. | Solid system-bar background or content clipping behind bars. |
| Adaptive layout — Pixel Fold unfolded (840dp) | Manual + screenshot via Test Lab. | Layout breakage / unusable at unfolded width. |
| Adaptive layout — tablet portrait (600dp+) | Same. | Stretched single-column layout (no 600dp wrap). |
| Adaptive layout — tablet landscape | Same. | Same. |
| Material 3 Expressive components active app-wide | grep for `Material 2` button/card usages; visual spot-check via Test Lab screenshots. | Any stray Material 2 component on a user-facing screen. |
| Dynamic color (Material You) | Manual on Android 12+ with custom wallpaper. | Colors don't adapt; static palette only. |

### Already-confirmed items (from prior RC work)

- `android:enableOnBackInvokedCallback="true"` is set in the manifest (verified during this brainstorm).
- `windowInsetsPadding` is applied to top bar via `WindowInsets.safeDrawing.only(Top + Horizontal)` (RC48–RC56 work).
- RC30 introduced 600dp-wrap behavior for tablet portrait.
- MD3E migration completed in Phase E (RC1).

### Output

A platform-adoption report (markdown) capturing pass/fail per item with screenshots from Test Lab. Failing items become tasks in the implementation plan.

---

## 2. Accessibility audit

### Items to verify

- **TalkBack walkthrough:** Every screen + every interactive element has a `contentDescription` / Compose `semantics` label. Full poster-creation flow completable with TalkBack on.
- **Touch target sizes:** Minimum 48×48dp on every tap target (Material accessibility baseline).
- **Color contrast:** Text-on-background contrast meets WCAG AA on all surfaces. The blueprint-blue palette + Fraunces/Manrope typography needs audit.
- **Font scaling:** Test at max system font size (200%) on a 360dp phone. Identify any clipping or overflow that wasn't already caught by `tools:maxLength` budgets (RC3+ Layer 2a work).
- **Voice input fallback:** When Spec B's Gemini Q&A sheet ships, verify text-only path works for users who can't or won't use voice.
- **Keyboard navigation:** External keyboard tab-order traversal hits every interactive element in a sensible order.

### Output

Accessibility audit report (markdown). Issues become tasks; "TalkBack reads it as 'unlabeled button' on screen X" is a fix; "the entire flow is accessible" is the bar.

---

## 3. Data Safety form (Play Console)

Draft answers for Play Console's Data Safety section. Mirror the answers in `play-listing/data-safety.md` for version control.

### Data types we collect

| Type | When collected | Where it goes | Retention |
|---|---|---|---|
| Email address | Google sign-in (optional) | Firebase Auth + Resend (for support replies) | While account active; deleted on account-delete (RC36) |
| Authentication ID | Google sign-in | Firebase Auth UID | Same as above |
| In-app purchase history | When buying credits | Firestore `users/{uid}/transactions/{txnId}` | While account active |
| Source images | When uploading for cloud upscale | GCS bucket (encrypted at rest) | 90 days default (user-configurable) |
| Generated PDFs | When user opts into cloud storage | GCS bucket (encrypted at rest) | Same |
| Device + diagnostic info (Crashlytics) | On crash / ANR | Firebase Crashlytics | Per Firebase retention policy |
| Debug log (opt-in) | When user enables debug logging | Local device + uploaded only if user taps "Send feedback" | Until user clears |

### Disclosures

- **Encryption in transit:** TLS for all uploads.
- **Encryption at rest:** GCS bucket default encryption.
- **Data deletion mechanism:** In-app account deletion under DANGER ZONE (RC36) → cascades to all Firestore/GCS data within 24h.
- **Third-party SDKs disclosed:** Firebase (Auth/Firestore/Storage/FCM/Crashlytics), Google Play Billing, FAL.ai (receives image bytes for upscaling but not user identifiers), Vertex AI (Imagen upscale + Gemini Q&A — receives image bytes + UID for rate-limit attribution), Resend (support email replies).

### Output

A complete draft of every Data Safety field, written to `play-listing/data-safety.md`. User pastes into Play Console; we keep the local copy in sync via PR-on-change.

---

## 4. OSS license attributions audit

- Run `./gradlew app:licenseReport` (or equivalent — verify which Gradle plugin is wired) and cross-check against `LICENSE.md`.
- Include Spec A's new deps (`google-auth-library` on backend, no new Android deps) and Spec B's deps (`androidx.appfunctions:*` and `@google/genai` on backend).
- Verify no GPL/AGPL-tainted deps slipped in (incompatible with Play distribution); flag if found.
- Add an "Open source licenses" entry under the Settings drawer pointing to a screen that renders the license report — this is a Play Store listing requirement that's easy to miss.

### Output

Updated `LICENSE.md` + new in-app license screen. Fail: any GPL/AGPL or unlicensed dep present in the dependency graph.

---

## 5. Play Store listing content (mirror in git)

Mirror the Play Console listing fields in a new `play-listing/` directory so we have version control + i18n parity with the in-app strings.

### Directory structure

```
play-listing/
├── en-US/
│   ├── short_description.txt   # ≤80 chars
│   ├── full_description.txt    # ≤4000 chars
│   └── release_notes.txt       # ≤500 chars
├── de/                          # German
├── es/                          # Spanish
├── fr/                          # French
├── hi/                          # Hindi
├── ar/                          # Arabic
├── pt-BR/                       # Portuguese (Brazil)
├── ja/                          # Japanese
├── ko/                          # Korean
├── zh-CN/                       # Chinese (Simplified)
├── data-safety.md               # Section 3 output
├── editorial-pitch.md           # Section 6 output
└── screenshots/                 # Output of fleet-test runs (Section 8)
    ├── phone/01-hero.png
    ├── phone/02-modal.png
    └── ...
```

### Content drafts (English; fan-out to 9 locales via translation subagents per the RC44 pattern)

- **Short description (80 char):** "Turn any image into a printable multi-page poster — free, AI-enhanced." (68 chars)
- **Full description (full draft committed to `full_description.txt` during impl):** Feature list including Imagen + Gemini integration callout, on-device free upscale, cloud upscale tiers, PDF tiling with overlap/margin control, Material 3 Expressive design.
- **Release notes:** Pulled from the current `versionName` RC notes.

### Screenshot order

| # | Subject | Capture method |
|---|---|---|
| 1 | Construction-preview hero (assembling animation mid-cycle) | Spec C section 8 fleet test |
| 2 | Low-DPI upgrade modal (showing Imagen option after Spec A ships) | Same |
| 3 | Compare AI Upscalers screen | Same |
| 4 | PDF output preview with paper-size selector | Same |
| 5 | Construction-preview on different paper size (variety) | Same |
| 6 | Gemini sparkle icon in top bar (Spec B ships) | Same |
| 7 | Gemini Q&A sheet with response (post-Spec B) | Same |
| 8 | [Post-EAP] AppFunction invocation from system Gemini | Manual capture |

### Graphics

- Feature graphic: 1024×500 px, lossless WebP (per the existing lossless-only rule).
- Promotional graphic: 180×120 px.

### Output

Versioned `play-listing/` directory ready to paste into Play Console.

---

## 6. Editorial feature request submission

Draft the submission text emphasizing:

- **"Built for Android 16"** — minSdk 23 (broad device coverage at ~99% across active devices) but targetSdk 36 with full Android 16 spec adoption (MD3E, Predictive Back, edge-to-edge, adaptive layouts, AppFunctions).
- **AppFunctions EAP participant** — referenced once approval clears.
- **"Pure Google" stack** — Kotlin + Compose + Firebase + Vertex AI (Imagen + Gemini 3.5 Flash) + Material 3 Expressive. The transcript explicitly noted this framing as the right editorial pitch.
- **In-app Gemini Q&A with vision and tool-calling** — concrete demo flow Google's editorial team can replay.
- **Utility use case** — clean, focused, fast PDF generation that solves a real-world need.
- **Polish signals** — 9 i18n locales, accessibility audit complete, Predictive Back / edge-to-edge / adaptive layouts verified.

### Submission timing

Wait for:
1. **Spec A shipped** (Imagen visible in model picker) — enables the "Pure Google stack" pitch.
2. **Spec B Part 1 + Part 2 shipped** (AppFunctions registered + in-app Gemini Q&A live).
3. **EAP approval cleared** — the strongest single sentence in the pitch.
4. **All Spec C audits green** — no platform-adoption gaps.

If KYC clears before EAP (likely), submit a v1 of the editorial request without the EAP claim; revise + resubmit once EAP lands.

### Output

A drafted text + screenshot bundle in `play-listing/editorial-pitch.md`, ready to paste into the Play Console editorial form.

---

## 7. Crashlytics pre-launch baseline + Play Pre-launch Report

- Crashlytics dashboard already wired (RC21-6). Pre-launch task: set a custom alert rule for "crash-free-users < 99%" → email to `joeputin100@gmail.com`.
- Run Play Console's automated Pre-launch Report on the release-track APK by uploading to internal-testing track. Triage flagged issues (crashes, accessibility findings, performance, security).
- Set up a Crashlytics dashboard URL in the team Slack/email so the dev sees stability at a glance during launch week.

### Output

A pre-launch dashboard checklist:

- [ ] Crashlytics auto-alerts wired
- [ ] Play Pre-launch Report run + zero blocker issues
- [ ] Manual smoke-test on 3+ real devices (Pixel 9, Galaxy S25, low-end device)

---

## 8. Firebase Test Lab fleet testing with screenshots

**Why this isn't redundant with Section 7:** The Play Pre-launch Report runs a Robo crawl on a Google-picked device matrix and surfaces aggregate findings. It does NOT let us pick devices, pick screenshot breakpoints, or fail CI on regressions. Firebase Test Lab via `gcloud firebase test android run` gives us all three.

### Device matrix

Defined in `testlab-fleet.yaml` at repo root:

| Device | Form factor | Why include |
|---|---|---|
| Pixel 9 Pro (API 36) | Modern flagship phone | Reference Android 16 experience |
| Galaxy S25 (API 36) | Samsung One UI 7+ | Catch OEM skin rendering differences |
| Pixel Tablet (API 35) | Tablet landscape | Adaptive-layout verification |
| Pixel Fold unfolded (API 35) | Foldable | Adaptive-layout verification |
| Pixel 4a (API 33) | Older mid-range | minSdk-adjacent coverage |
| Pixel 9 with Spanish locale | Localization | Catch locale-specific clipping |
| Pixel 9 with Hindi locale | Localization (RTL-like long words) | Catch font-fallback issues |

(Final device IDs will use Test Lab's catalog names, e.g., `Pixel9.arm.34` — verified during impl.)

### Test pipeline

Two passes per fleet run:

**Pass A — Robo crawl (broad coverage):**

```bash
gcloud firebase test android run \
  --type robo \
  --app app/build/outputs/apk/release/app-release.apk \
  --device-ids=Pixel9.arm.36,SamsungS25.arm.36,PixelTablet.arm.35,PixelFold.arm.35,Pixel4a.arm.33 \
  --locales=en,es,hi \
  --orientations=portrait,landscape \
  --timeout=10m \
  --results-dir=gs://posterpdf-testlab-screenshots/$(date +%Y%m%d-%H%M%S)
```

Robo automatically explores the app, captures screenshots at every screen state, records video, logs crashes. Output is downloaded from GCS and committed (PR check) so we can diff visual changes across builds.

**Pass B — Instrumentation tests with targeted screenshots:**

A new `app/src/androidTest/kotlin/com/posterpdf/screenshots/ListingScreenshots.kt` test class uses `androidx.test.runner.screenshot` + Compose UI test to navigate to the 7 specific screens we want for the Play Store listing (per Section 5's screenshot order). Each test takes ONE screenshot at a deterministic state. Run via:

```bash
gcloud firebase test android run \
  --type instrumentation \
  --app app/build/outputs/apk/release/app-release.apk \
  --test app/build/outputs/apk/androidTest/release/app-release-androidTest.apk \
  --test-targets="class com.posterpdf.screenshots.ListingScreenshots" \
  --device-ids=Pixel9.arm.36 \
  --locales=en,de,es,fr,hi,ar,pt-BR,ja,ko,zh-CN \
  --orientations=portrait \
  --results-dir=gs://posterpdf-testlab-screenshots/listing/$(date +%Y%m%d-%H%M%S)
```

Output: per-locale screenshots ready to upload to the Play Store listing.

### CI integration

GitHub Actions workflow `.github/workflows/fleet-test.yml`:

- Trigger: push to `master` or PR labeled `fleet-test`.
- Authenticate via workload identity federation (GCP service account already exists for Cloud Build).
- Run Pass A (Robo) on every trigger; Pass B (listing screenshots) only on `master`.
- Fail the workflow if any device reports a crash; surface the GCS URL for the test report in the PR check.

### Cost

Test Lab pricing as of 2026:

- Virtual devices: $1/device-hour. Free tier: 15 device-tests/day.
- Physical devices: $5/device-hour. Free tier: 5 device-tests/day.

A Pass A run hits 5 devices × ~5 min each = ~25 device-minutes (~$2-5 per run depending on virtual/physical mix). At one run per master-push (~few/day), monthly cost is under $30. Free tier covers most of it.

### Output

- `testlab-fleet.yaml` — device matrix config.
- `.github/workflows/fleet-test.yml` — CI pipeline.
- `app/src/androidTest/kotlin/com/posterpdf/screenshots/ListingScreenshots.kt` — deterministic screenshot tests.
- `play-listing/screenshots/<locale>/` directory populated from Pass B runs.

---

## Sequencing

```
┌─────────────────────────────────────────────┐
│ Run independently — start now               │
├─────────────────────────────────────────────┤
│ 1. Platform-adoption audit                  │
│ 2. Accessibility audit                      │
│ 3. Data Safety form draft                   │
│ 4. OSS license audit                        │
│ 8. Firebase Test Lab fleet pipeline setup   │
└─────────────────────────────────────────────┘
              │
              ▼ (run audits via Test Lab; capture screenshots)
┌─────────────────────────────────────────────┐
│ Gated on Spec A + B shipping                │
├─────────────────────────────────────────────┤
│ 5. Play Store listing content (needs Imagen │
│    card + Gemini UI in screenshots)         │
│ 7. Pre-launch Crashlytics + Play Report     │
└─────────────────────────────────────────────┘
              │
              ▼
┌─────────────────────────────────────────────┐
│ Gated on EAP approval + KYC clearing        │
├─────────────────────────────────────────────┤
│ 6. Editorial feature request submission     │
│ — LAUNCH —                                  │
└─────────────────────────────────────────────┘
```

## Open verification items (resolved during implementation)

1. **Test Lab device catalog IDs** — current device IDs in Google's catalog may differ from the illustrative `.arm.36` suffixes used above.
2. **Gradle license-report plugin choice** — confirm whether the codebase already has `com.jaredsburrows.license` or `com.cmgapps.licenses` wired.
3. **GCS bucket for Test Lab results** — confirm bucket exists (likely `posterpdf-testlab-results` or similar) and Cloud Build SA has write access; create if missing.
4. **Workload Identity Federation for GitHub Actions → GCP** — verify the project's existing WIF binding works for the Test Lab service, or create new IAM mapping.
5. **i18n key sweep** for any string still hard-coded in Kotlin (this was nominally completed in RC39 but a fresh sweep before launch is cheap insurance).

## Out of scope

- Marketing site / landing page.
- Twitter/Bluesky launch announcement copy.
- App icon refinements (settled in Phase E).
- Pricing changes / new IAP SKUs (settled in H-P1.10c).
- Beyond-Android platforms (iOS, web).

---

## Implementation order summary

1. **Section 8: Test Lab pipeline first** — gives us screenshots + visual regression detection for the audits below.
2. **Sections 1, 2, 4: Platform/accessibility/OSS audits** — run on Test Lab output + manual sweeps.
3. **Section 3: Data Safety draft** — concurrent with audits.
4. **Section 5: Play Store listing content** — waits for Spec A + B screenshots.
5. **Section 7: Pre-launch monitoring** — wire alerts.
6. **Section 6: Editorial pitch + submission** — last, post-EAP.
