# Play Console "Set up your app" — Fill-in Cheat Sheet

Answers below match what the app actually does as of rc83 (re-verified
2026-07-05). Work top-to-bottom through Dashboard → "Finish setting up
your app" → View tasks. Items marked ⚠️ have consequences — read the note.

**Already done via API (skip these):** the entire Main store listing —
title, descriptions, icon, feature graphic, 6 screenshots, and the video
are uploaded and committed. Your job is ONLY the declaration forms below
(Google provides no API for them).

---

## 1. Privacy policy
**URL:** `https://posterpdf.web.app/privacy-policy`

## 2. App access
Choose: **"All or some functionality in my app is restricted"**
- Add instruction, name it `Google sign-in`, and in the instructions field:
  > Cloud features (AI upscaling, credit purchases, history sync) require
  > Google sign-in. Any Google account works — no special test credentials
  > are needed. Core poster/PDF generation works without sign-in.
- ⚠️ Don't pick "no restrictions" — reviewers seeing a sign-in wall after
  that answer is a common rejection.

## 3. Ads
**No, my app does not contain ads.**

## 4. Content rating
- Start questionnaire → Email: `joeputin100@gmail.com`
- Category: **Utility, Productivity, Communication, or Other**
- Violence / sexuality / language / drugs / gambling: **No** to all
- Online content (not part of the download)? **Yes** — Gemini assistant
  replies + cloud AI upscale results ("generated AI content" per their own
  example list) + the community board. Follow-ups about objectionable
  online content: **No** to all.
- Does the app allow users to interact or exchange content with others?
  **YES** — the in-app community board (posts/replies). CORRECTED
  2026-07-05: an earlier version of this sheet said No. Follow-ups:
  users communicate via public text posts only (no DMs/voice); content IS
  moderated (non-anonymous sign-in to post, admin removal, admin-only
  announcement topics); location is NOT shared.
- Does the app share user-provided location? **No**
- In-app digital purchases? **Yes**
- Expected result: **Everyone** (with "In-App Purchases" notice)

## 5. Target audience
- Age groups: **13–15, 16–17, 18+** (do NOT tick any under-13 group —
  that triggers the much stricter Families policy track)
- "Could your app unintentionally appeal to children?" **No** — store
  listing shows a classical painting, no cartoon mascots or child themes.

## 6. News app
**No.**

## 7. Data safety  ⚠️ (the big one — answers must match app behavior)
Overview answers:
- Does your app collect or share any of the required user data types? **Yes**
- Is all of the user data collected by your app encrypted in transit? **Yes**
- Do you provide a way for users to request that their data is deleted? **Yes**
- Account deletion URL: `https://posterpdf.web.app/delete-account`
- "Delete data" URL (partial, no account deletion): `https://posterpdf.web.app/delete-data`
  (both pages are SELF-SERVICE as of 2026-07-08: Google sign-in on the page →
  server-side deletion executes immediately; no email round-trip)

Declare these data types (everything: **Collected = yes, Shared = no**;
fal.ai/Firebase are "service providers" processing on our behalf, which
Play's definitions exempt from "sharing"):

| Data type | Purpose | Required/Optional | Notes |
|---|---|---|---|
| Personal info → Email address | Account management | Required for cloud features | Google sign-in |
| Personal info → User IDs | Account management, App functionality | Required for cloud features | Firebase UID |
| Photos and videos → Photos | App functionality | Optional | Only images the user sends to *cloud* upscale; on-device upscale never uploads |
| Files and docs | App functionality | Optional | ONLY cloud copies of generated poster PDFs (user-enabled); source-image filenames never leave the device (uploads are renamed to a content hash) |
| Financial info → Purchase history | App functionality | Required for purchases | Credit packs, credit ledger |
| App activity → App interactions | App functionality | Required | Poster/upscale counters for free-tier limits |
| App activity → Other user-generated content | App functionality | Optional | Community posts/replies + questions typed to the Gemini assistant (added 2026-07-05 with the UGC correction) |
| App info & performance → Crash logs | Analytics | Required | Crashlytics |
| App info & performance → Diagnostics | Analytics | Required | Crashlytics |
| Device or other IDs | App functionality, Analytics | Required | FCM push token, Crashlytics install ID |

NOT collected (answer No if asked): location, contacts, calendar, audio
(mic input is transcribed by Android's on-device/system speech service;
only the resulting text query reaches our backend), browsing history,
health, **Messages (all three: Emails / SMS / Other in-app messages)** —
the forum is public UGC (declared above), NOT user-to-user messaging;
there are no DMs. Files beyond user-chosen images + optional PDF cloud
copies are not collected.

## 8. Government apps
**No.**

## 9. Financial features
**None of the above** (selling in-app credits is NOT a "financial feature" —
that section means banking/loans/crypto).

## 10. Health
**No health features.**

---

## Store listing — ✅ DONE via API (verify, don't re-enter)

Text, icon, feature graphic, 6 screenshots, video: all uploaded 2026-07-05.
Still set manually if prompted under "Store settings":
- **Category:** App → **Photography** (alt: Productivity)
- **Tags:** poster, print, PDF, upscale, photo enhancer
- **Contact email:** `joeputin100@gmail.com`
- **Website:** `https://posterpdf.web.app`

## Remaining after forms
1. I upload the 86MB release AAB to Closed testing via the API and hand
   you the tester opt-in link for Reddit replies
2. Tester list: send me the Gmails from r/betatesters — I add them via API
3. IAP credit products (needs the payments profile finished — bank
   details — plus the first AAB; then I create all 4 SKUs via API)
