# Poster PDF Privacy Policy

Last updated: 2026-04-24

Poster PDF ("we", "us", "our") respects your privacy. This policy explains what data is collected, why, and how it is used.

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
- Source images selected by the user (stored locally in app sandbox; optionally encrypted)
- Upscaled image outputs (local cache and optional cloud storage references)
- Generated PDF files (local storage)

### d) Debug Logs (optional)
- If Debug Logging is enabled by user, app events may be written to local storage

## 2. Why We Use Data
- Provide core app features (poster generation)
- Enforce free-tier and credit limits fairly
- Persist user state across reinstall/sign-in
- Process and track upscaling transactions safely with stage/commit/refund
- Show generation/upscale history

## 3. Legal Basis / Consent
- We process data necessary to provide the app service.
- Optional features (debug logging) are user-controlled.

## 4. Third-Party Services
- Firebase (Authentication, Firestore, Functions)
- Google Play Billing (for purchases)
- fal.ai (for AI upscaling requests via backend proxy)

Your API requests to third-party providers are proxied through backend services where possible to protect secrets.

## 5. Data Retention
- Free-tier remote upscale artifacts: up to 30 days (planned policy)
- Paid-tier remote upscale artifacts: retained unless deleted by user (planned policy)
- Transaction and accounting records may be retained for abuse prevention and billing reconciliation.

## 6. Security
- Sensitive API secrets are stored server-side (Secret Manager)
- App sandbox and encryption are used for local sensitive files where implemented
- Access controls are enforced via authenticated backend rules

## 7. Your Choices
- Disable debug logging
- Delete local files from device storage
- Request account/history deletion (contact)

## 8. Children
Poster PDF is not directed to children under 13 (or local digital age of consent).

## 9. Changes
We may update this policy. The "Last updated" date will reflect revisions.

## 10. Contact
For privacy requests or support:
- Email: support@pdfposter.app (replace with your preferred address)
