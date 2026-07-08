# Poster PDF Privacy Policy

Last updated: 2026-07-02

Poster PDF ("we", "us", "our") respects your privacy. This policy explains what data is collected, why, and how it is used — and, just as importantly, what never leaves your device.

## 1. Data We Collect

### a) Account Data
- Google account identifier (when signed in)
- Basic authentication metadata provided by Firebase Authentication

### b) App Usage Data
- Posters generated count
- Upscale usage counters (free and paid)
- Credit balances and transactions
- History metadata (generation timestamps, source hash, output references)

### c) Image/File Data
- Images you send to **cloud** AI upscaling, and their upscaled results (stored in your private cloud storage area)
- Generated PDF files you choose to keep a cloud copy of

### d) Crash & Diagnostic Data
- Crash reports and performance diagnostics (Firebase Crashlytics)

### e) Debug Logs (optional)
- If Debug Logging is enabled by you, app events may be written to local storage on your device only

## 2. What Stays on Your Device

- Source images you select stay on your phone unless **you** choose a cloud AI model. The on-device AI upscaler runs entirely offline — those images never leave your device.
- Generated PDFs and caches are stored locally unless you enable a cloud copy.
- Voice input for the in-app assistant is transcribed by Android's system speech service; the audio itself is never sent to our servers — only the resulting text question.

We never collect: your location, contacts, calendar, browsing history, advertising identifiers, or files other than the images you explicitly select. Poster PDF contains no ads and no ad-tracking SDKs.

## 3. Why We Use Data
- Provide core app features (poster generation and AI upscaling)
- Enforce free-tier and credit limits fairly
- Persist user state across reinstall/sign-in
- Process and track upscaling transactions safely with stage/commit/refund
- Show generation/upscale history
- Diagnose crashes and performance problems

## 4. Third-Party Services
- Firebase / Google Cloud (Authentication, Firestore, Cloud Functions, Storage, Crashlytics, Cloud Messaging)
- Google Play Billing (for purchases)
- fal.ai (AI upscaling models, via our backend proxy)
- Google Cloud Vertex AI (Gemini — powers the in-app AI assistant, via our backend proxy; receives your typed/spoken questions as text, not your images)

When you choose a cloud AI model, the image is made available to the AI provider (fal.ai) through a private, time-limited link, solely to fulfil your request. API keys and requests are proxied through our backend so secrets never live in the app.

## 5. Data Retention
- Cloud copies of posters and upscale results are stored **free for 30 days**.
- After 30 days they are either covered by optional paid storage (if you enable it) or enter a 30-day grace period and are then **automatically deleted**.
- You can delete any cloud copy at any time from the app, and request full account deletion at any time (see Contact).
- Transaction and accounting records may be retained for abuse prevention, tax, and billing reconciliation.

## 6. Security — an honest description
- All data is encrypted **in transit** (HTTPS/TLS).
- Data stored in Firebase/Google Cloud is encrypted **at rest** with Google-managed encryption.
- Your cloud storage area is isolated per account: security rules allow only your signed-in account to access your files.
- Cloud AI upscaling is **not end-to-end encrypted** — by its nature, our backend and the AI provider you select must access the actual image to process it. Processing is automated; no humans review your images, and we never use them for advertising or to train models. If you prefer that images never leave your phone, use the on-device upscaler.
- Sensitive API secrets are stored server-side (Google Secret Manager).

## 7. Legal Basis / Consent
- We process data necessary to provide the app service.
- Optional features (cloud upscaling, cloud copies, debug logging) are user-controlled.

## 8. Your Choices
- Use on-device upscaling to keep images entirely local
- Delete cloud copies from within the app, or wipe ALL cloud data (keeping your account and credits) self-service at https://posterpdf.web.app/delete-data
- Disable debug logging
- Delete local files from device storage
- Request account/history deletion: https://posterpdf.web.app/delete-account

## 9. Children
Poster PDF is not directed to children under 13 (or the local digital age of consent).

## 10. Changes
We may update this policy. The "Last updated" date will reflect revisions.

## 11. Contact
For privacy requests or support:
- Email: joeputin100@gmail.com
- Account deletion: https://posterpdf.web.app/delete-account
