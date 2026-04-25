# Poster PDF Backend (Phase 2 Foundation)

Firebase Functions (TypeScript) backend for:
- Account bootstrap/state (`/v1/bootstrap`)
- Credit quote + staged debit/commit/refund
- History records API
- Auth via Firebase ID token

## Stack
- Firebase Functions v2 (Node 20)
- Firestore
- Express + CORS

## Local Setup
1. Install dependencies:
   - `cd backend/functions && npm install`
2. Set local secret file (already created):
   - `backend/functions/.secrets.local`
3. Build functions:
   - `npm run build`
4. Start emulators from `backend/functions`:
   - `npm run serve`

## Deployment
From `backend/functions`:
- `npm run deploy`

## Secret Management
- Runtime uses `FAL_KEY` secret via Functions v2 `secrets: ["FAL_KEY"]`.
- Production value should be stored in Google Secret Manager and bound to the function.
- `.secrets.local` is for local dev only and is gitignored.

## Firestore Collections (initial)
- `users/{uid}`
  - `paidCredits`
  - `stagedPaidCredits`
  - `freeUpscalesUsed`
  - `nagDismissed`
  - `supportPurchaseActive`
- `upscaleTransactions/{txId}`
  - `uid`, `mode`, `credits`, `status`, `sourceHash`
- `users/{uid}/history/{historyId}`
  - `type`, `sourceHash`, `localUri`, `remoteUri`, `metadata`, `createdAt`

## Notes
- Current quote endpoint uses tiered output-MP pricing approximation from fal Topaz docs and 100% markup.
- Upscale execution endpoint is intentionally stubbed for Phase 2; client contracts are ready.
