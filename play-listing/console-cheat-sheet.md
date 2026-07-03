# Play Console "Set up your app" — Fill-in Cheat Sheet

Answers below match what the app actually does as of rc82 (verified against
source 2026-07-02). Work top-to-bottom through Dashboard → "Finish setting
up your app" → View tasks. Items marked ⚠️ have consequences — read the note
before clicking.

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
- Does the app allow users to interact or exchange content with others? **No**
  (posters are private to the user; nothing is shared in-app)
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

Declare these data types (everything: **Collected = yes, Shared = no**;
fal.ai/Firebase are "service providers" processing on our behalf, which
Play's definitions exempt from "sharing"):

| Data type | Purpose | Required/Optional | Notes |
|---|---|---|---|
| Personal info → Email address | Account management | Required for cloud features | Google sign-in |
| Personal info → User IDs | Account management, App functionality | Required for cloud features | Firebase UID |
| Photos and videos → Photos | App functionality | Optional | Only images the user sends to *cloud* upscale; on-device upscale never uploads |
| Financial info → Purchase history | App functionality | Required for purchases | Credit packs, credit ledger |
| App activity → App interactions | App functionality | Required | Poster/upscale counters for free-tier limits |
| App info & performance → Crash logs | Analytics | Required | Crashlytics |
| App info & performance → Diagnostics | Analytics | Required | Crashlytics |
| Device or other IDs | App functionality, Analytics | Required | FCM push token, Crashlytics install ID |

NOT collected (answer No if asked): location, contacts, calendar, audio
(mic input is transcribed by Android's on-device/system speech service;
only the resulting text query reaches our backend), browsing history,
health, files other than user-chosen images.

## 8. Government apps
**No.**

## 9. Financial features
**None of the above** (selling in-app credits is NOT a "financial feature" —
that section means banking/loans/crypto).

## 10. Health
**No health features.**

---

## Store listing (Main store listing page)

- **App name:** `Poster PDF`
- **Short description** (74 chars):
  > Turn any picture into a huge print-ready poster. AI upscale, tiled PDF.
- **Full description:** see `play-listing/full-description.txt`
- **App icon:** upload `play-listing/icon-512.png`
- **Feature graphic:** upload `play-listing/feature-graphic.png`
- **Phone screenshots:** pending (min 2, target 8) — being produced
- **Category:** App → **Photography** (alt: Productivity)
- **Tags:** poster, print, PDF, upscale, photo enhancer
- **Contact email:** `joeputin100@gmail.com`
- **Website:** `https://posterpdf.web.app`

## Remaining after forms
1. Closed testing release (I build + you upload the AAB, or grant me a
   Console API service account to upload directly)
2. Testers list: Console → Closed testing → create email list of 12+
3. IAP products (needs merchant account linked + first AAB uploaded)
