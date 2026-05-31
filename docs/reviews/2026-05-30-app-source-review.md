# PosterPDF app-source review — 2026-05-30 (rc80, master)

Whole-app code review of the APK source (`app/src/main` Kotlin + manifest + build.gradle), done by 6 parallel Opus reviewers over disjoint slices after the cloud ultrareview couldn't ingest the 22k-line diff. Scope excluded RC history, translations, docs, assets, and the Firebase backend (TypeScript, not in the APK).

Aggregate: ~18 Critical, ~42 Important, ~44 Minor (some overlap across slices). This file is the editor-consolidated version with priority and status notes.

---

## Cross-cutting themes (read first)

1. **Unvalidated free-text numeric inputs (margin / overlap / poster dims) cause divide-by-zero → NaN/Infinity.** Same root cause surfaces in PDF generation *and* the preview. Highest-leverage single fix: validate `overlap < printable`, `2·margin < page`, poster dims > 0 at the input boundary.
2. **i18n leaks in shipped, high-visibility flows.** The paid upscale progress bar, voice errors, intent-chooser titles, and all count strings (no `<plurals>`) render English in a 9-locale app.
3. **Client-trusted money.** Both the billing grant path and the credit-spend/cancel path trust the client; server-authority and idempotency are incomplete. (Billing is Phase G — still WIP, task #14 — so these are "before billing ships," not live regressions.)
4. **On-device ML lifecycle matches the documented SIGSEGV/timeout history** — no cooperative cancellation, cross-thread GPU-delegate close. Worth fixing before the next FTL run.
5. **Main-thread I/O** on image import, hashing, PDF save, and upload-byte estimation — ANR risk on large images / slow SAF providers.

---

## CRITICAL

### Permissions / manifest (S1)
- **`MainActivity.kt:464-486` — runtime-requests `WRITE_EXTERNAL_STORAGE`, which rc80 just removed from the manifest.** A request for an undeclared permission is silently denied, so the launch-time permission dialog can never succeed and re-fires on cold start. *This is a regression from the rc80 manifest change — fix now: delete the `storagePermissions` block + its `LaunchedEffect`; SAF needs no storage permission.*
- **`AndroidManifest.xml:10` — `READ_EXTERNAL_STORAGE` unused / no `maxSdkVersion`** (image ingest is all SAF). Remove, or cap `maxSdkVersion="32"`. Play data-safety liability.
- **`AndroidManifest.xml:24` — `RECORD_AUDIO` declared app-wide.** Confirm the Gemini-mic feature actually ships this RC; if not, drop it (sensitive-permission review trigger).

### PDF / preview geometry (S1 + S4)
- **`PosterLogic.kt:147-153,173-174` — `tileStepX/Y = printable − overlap` divides by ≤0 when `overlap ≥ printable`** → `Infinity.toInt()` = Int.MAX_VALUE pages → OOM/hang. Validate inputs.
- **`PaneGeometry.kt:99-100,105-109` — `compute()` lacks the `stepX/stepY<=0` and `paperW/H==0` guards its sibling `computePaneCount()` has** → NaN scale propagates into pane rects + camera math, corrupting the whole frame. Mirror the guards.

### On-device ML (S3)
- **`UpscalerOnDevice.kt:198-307` — tile loop has no `ensureActive()`/`yield()`,** so `withTimeout(15min)` (MainViewModel.kt:512) and user-cancel cannot stop native inference. Add a cancellation check per tile/band.
- **`TileEngine.kt:109-112` — GPU delegate `close()` runs on an arbitrary thread and isn't mutex-guarded vs in-flight `run()`** → thread-affine delegate / use-after-free (matches Pixel 6 SIGSEGV history). Confine create/run/close to one thread; serialize close through the mutex.

### Billing — Phase G WIP, not live (S3)
- **`BillingRepository.kt:276-293` — live (non-G12) path grants credits client-side and is non-idempotent (additive),** so a failed consume → double-grant on restore. Block any credit-granting build until `redeemPurchase` is server-backed + idempotent.
- **`BillingRepository.kt:251-263` — credits granted before consume/acknowledge; failed consume only logged** → unbounded replay. Grant only after server redeem keyed by purchaseToken.

### State / network (S2)
- **`MainViewModel.kt:947-955 / 784-834` — `cancelAiUpscale()` cancels only the client coroutine; the backend tx/FAL job already charged is never refunded/reconciled** → charged-but-nothing-delivered. Add backend cancel/refund or next-launch reconciliation keyed on the open txId.
- **`MainViewModel.kt:1216` — credit-balance snapshot listener discards the error arm** → silent freeze of the displayed balance on permission/offline failure. Handle the error.
- **`AiUpscaleRepository.kt:113,157-159` — no timeout on upload; result download does `URL(outputUrl).openStream()` on an unvalidated server string with unbounded `readBytes()`** → indefinite hang + scheme/host/OOM risk. Add timeouts, validate `https`+host, cap bytes.

### Preview (S4)
- **`PosterPreview.kt:101-110` — a second `rememberInfiniteTransition`/`animateFloat` runs forever but its value is never read** → permanent recomposition/frame cost. Delete it.

### i18n (S5 + S6)
- **`UpscaleProgressBar.kt:85-98` — the entire paid-upscale progress label set is hardcoded English** with non-locale number formatting. Move to `stringResource`/plurals; make `stageLabel` composable.
- **`HistoryScreen.kt:300,314` — `Intent.createChooser(..., "Open PDF"/"Share PDF")` hardcoded English** chooser titles.
- **`CreditsHistoryScreen.kt:121` — ledger merges two `.limit(100)` queries and can silently truncate** a financial history with no "showing latest N" affordance.
- **`CommunityComposeScreen.kt:79-80 / CommunityRepository.kt:120` — Release-Notes write-gate is client-cosmetic only;** real enforcement depends entirely on `firestore.rules` admin check — confirm that rule gates `topic==release_notes` on `/admins/{uid}`.

---

## IMPORTANT (by area)

### Entry / lifecycle / build (S1)
- `MainActivity.kt:104-134` — `enableEdgeToEdge()` + crash handler run *before* `super.onCreate`; move edge-to-edge after super.
- `MainActivity.kt:1658-1668` + `PosterLogic.kt:805` — **wrong Play package id `com.pdfposter`** in the rate-us banner + market URL (real id is `com.posterpdf`; the PDF QR is correct). Derive from `BuildConfig.APPLICATION_ID`.
- `MainActivity.kt:140-181` — test-hook intents handled only in `onCreate`, not `onNewIntent` (warm-launch no-op); route both through one guarded function.
- `MainActivity.kt:514-519` — PDF save copies a multi-MB file on the main thread (SAF callback). Move to `Dispatchers.IO`.
- `PosterLogic.kt:239-387` — `PDDocument`/content-streams/bitmaps not in try/finally → native leak on mid-generation throw. Wrap + recycle in finally (guard `!==` like the SVG path).
- `PosterLogic.kt:323` — raster path draws the full poster image into every page and clips (full-res XObject per page) → OOM on large posters; SVG path is correctly per-tile.
- `build.gradle.kts:50-52` — release keystore password literal `"posterpdf"` fallback + debug signed with release config. Rotate; fail build if env unset; give debug its own keystore.
- `build.gradle.kts:166-168` — **Ktor netty *server* bundled into the APK** (unused). Remove `ktor-server-*`.
- `MainActivity.kt:233-253` — JankStats not paused on stop (toggle in onResume/onStop).

### State / network / concurrency (S2)
- `MainViewModel.kt:554-559,897-902,1384,1532` — nested fire-and-forget `viewModelScope.launch` for `suspend` saves; race with `loadSettings`' restore of `selectedImageUri`. Call inline.
- `MainViewModel.kt:1339-1419` — `ignoreFlowUpdates` boolean latch is non-atomic vs the DataStore flow; can drop loads/edits. Make the flow the single source of truth.
- `MainViewModel.kt:810,819-830,977` — `runAiUpscale` + `estimateUploadBytes` (opens the URI) run on the main thread. Move to IO.
- `AiUpscaleRepository.kt:78-101` — full source read → decode → re-encode to PNG, three full-size allocations in RAM (cloud path lacks the band-streaming the on-device path got). OOM risk on 17 MP sources.
- `MainViewModel.kt:1500-1561` — `updateImage` does two full `readBytes()` (hash + decode) + file copy/cleanup on the main thread. Stream the digest, move off-main.
- `MainViewModel.kt:2194,2222-2229` — debug log + support-diagnostics tail can carry `photoUrl`/email/source-URI; redact before attaching to a ticket. Confirm `submitSupport` honors `includeDiagnostics=false`.

### ML / billing (S3)
- `UpscalerOnDevice.kt:328-331` — scratch cache key uses 32-bit `String.hashCode()`; same-dim images with colliding hashes reuse each other's bands (validation is byte-length only). Use SHA-256 + a per-file header.
- `UpscalerOnDevice.kt:212-223` — torn-write detection is length-only; add CRC per band.
- `UpscaleForegroundService.kt:46-60` — `startForeground` without the type arg + can throw `ForegroundServiceStartNotAllowedException` on API 14+ if started backgrounded; `dataSync` has a ~6h/24h budget. Pass type, try/catch, start while foreground.
- `RegionSource.kt:36-43` — `decodeRegion` null not checked; defensive rect-clamp.
- `TileEngine.kt:84` — CPU fallback hardcodes `setNumThreads(4)` + relies on implicit XNNPACK; set explicitly, size to cores.
- `BillingRepository.kt:340-366` — `ensureConnected` can reuse a dead client; null it on terminal failure; reconnect on disconnect.
- `BillingRepository.kt:228-235,397` — `TEST_MODE = BuildConfig.DEBUG` test faucet grants free credits; add an explicit `ALLOW_TEST_BILLING` flag and assert false in uploaded artifacts.

### Preview (S4)
- `PosterPreview.kt:112-158` — two independent 16ms tickers run even off-screen/backgrounded; collapse to one + gate to visible/active.
- `PosterPreview.kt:267-346` — camera `SideEffect` recomputes phase/ease every recomposition; move to `derivedStateOf` keyed on inputs.
- `Decorations.kt:326-360,1772-1794` — `Paint` allocated per-frame (and per-pane in `drawPaneLabel`); hoist.
- `PosterPreview.kt:681-684` — `dashPathEffect` allocated each frame; `remember`/`drawWithCache`.
- `PosterPreview.kt:355-460` — decoded source bitmap shared with ViewModel never recycled on switch; document ownership / recycle prior decode.
- `PosterPreview.kt:182,634-637,761` — tap hit-test (`paneBounds`) ignores `userZoom`; taps miss at zoom≠1.

### UI components (S5)
- `VoiceInputController.kt:55-78` — hardcoded English mic errors; `EXTRA_LANGUAGE` passed a `Locale` not a BCP-47 tag (use `toLanguageTag()`).
- `LowDpiUpgradeModal.kt:1084` — dead `onDeviceThumb != null` (non-null type).
- `ManageAccountDialog.kt:85,173-213` — danger-zone dialog body has no `verticalScroll`; clips at 200% font.
- `LowDpiUpgradeModal.kt:256-269` — locale-unsafe money formatting (`"%.2f"` no `Locale`, `$`/`¢` glyphs); use `Locale.US` for USD value.
- `BBCodeText.kt:62-70` — deprecated `ClickableText`; links lack color affordance. Migrate to `LinkAnnotation`.
- `UnitsToggleCard.kt:69-71` — cm ruler labels positioned by blank-padded list; misaligns at large font.

### Screens / theme (S6)
- `CommunityPostScreen.kt:179` — reply box still shown on a soft-deleted post; hide when `deletedAt != null`.
- `SupportScreen.kt:71-72` — category seeded from a *localized* display string and submitted as such; model as a stable key.
- `CreditsHistoryScreen.kt` / `HistoryScreen.kt` — failed load renders as empty state (no error/retry) on a billing screen.
- `UpscaleComparisonScreen.kt:139-220` — bitmaps decoded full-res for 20dp chips, prior bitmaps not recycled; use `inSampleSize` + cache.
- `Community*` — in-flight submit guards are `remember` not `rememberSaveable` → double-submit on rotation.
- `SupportScreen.kt:284-289` — transparent click-catcher over the read-only field breaks TalkBack; use `ExposedDropdownMenuBox`.
- count strings lack `<plurals>` (`community_reply_count`, `*_minutes_ago`, `history_dim_pages_grid`).

---

## MINOR (selected; full lists in each slice)
- `PosterPdfMessagingService.kt:81` — notification id = `body.hashCode()` collides; use backend `notificationId`.
- `NetworkSpeedProbe.kt:108` — speed-probe blobs best-effort-deleted under the user's billed upload prefix; use a TTL / non-billed prefix.
- `AiUpscaleRepository.kt:82` — `sha256Hex(...).take(40)` truncation undocumented.
- `Theme.kt:74` — `view.context as Activity` unchecked cast (use `as?`); `Theme.kt:75` — `window.statusBarColor` deprecated/no-op on API 35+.
- `GettingStartedScreen.kt:231-247` — four `VideoView`s never released / not paused offscreen.
- `UpscaleComparisonScreen.kt:254-277` — hardcoded brand colors bypass theme (dark-mode contrast).
- `FlippingCoin.kt:53` — default `contentDescription = "1¢ coin"` hardcoded English.
- ml dead code: `UpscaleStateStore` legacy per-tile snapshot path; `Decorations` `DUST_PUFF_AGSL`/`drawDustPuff`; `UpscaleEta.etaForFal` (known).
- Stale comments: `PosterPreview.kt:120` "12-second cycle" vs 38/47s actual.

---

## Reviewer-verified NON-issues (don't re-flag)
- AGSL `RuntimeShader` API-gating is correct — fully isolated in `PrinterInkShader.kt`; `PosterPreview` bytecode names only `Any`; clean pre-33 fallback. (The rc80 Test Battery fix holds.)
- The "type CANCEL to erase" gate keeps the literal `CANCEL` token across all 9 locales — reachable everywhere.
- Credit denomination: COGS budget ($0.00425/credit) for job sizing vs retail (1cr≈1¢) for display are deliberately different and internally consistent — no ¢↔credit off-by-one in the live calculator vs spend path.
- Manifest exported flags clean (MainActivity exported w/ filters; FileProvider + both services not exported); FileProvider `external-files-path` covers both PDF + debug-log dirs.
