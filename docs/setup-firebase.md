# Firebase + Backend Setup (one-time)

The app and backend ship with **placeholders** so the project builds and runs
without any Firebase configuration. To actually authenticate users, store
history, and call backend endpoints, complete the steps below.

Project ID used throughout: **`static-webbing-461904-c4`** (already set as the
default `gcloud` project on this workstation).

---

## 1. Enable Firebase on the GCP project

1. Go to <https://console.firebase.google.com/>.
2. Click **Add project** → **Add Firebase to Google Cloud project** and pick
   `static-webbing-461904-c4`.
3. Skip Google Analytics (not needed for this project).

## 2. Enable Authentication

1. In Firebase Console → **Build → Authentication → Get started**.
2. Under **Sign-in method**, enable:
   - **Anonymous** (required — the app signs users in anonymously on first
     launch so history works immediately).
   - **Google** (required for the "Sign in with Google" button).

## 3. Enable Firestore

1. In Firebase Console → **Build → Firestore Database → Create database**.
2. Start in **production mode** (rules are committed under
   `backend/firestore.rules`).
3. Pick a region — `us-central1` is the safest default to match the Functions
   region the app expects.

## 4. Add the Android app + replace `app/google-services.json`

1. Firebase Console → **Project settings → Your apps → Add app → Android**.
2. Package name: `com.pdfposter`.
3. (Optional) Add a SHA-1 of the release keystore — required for Google
   Sign-in on release builds. Get it via:
   ```bash
   keytool -list -v -keystore release.keystore -alias posterpdf -storepass posterpdf
   ```
4. Download the generated `google-services.json` and **overwrite**
   `app/google-services.json` (the placeholder currently in the repo).

## 5. Get the OAuth Web client ID

The Android Sign-In flow needs the **Web** OAuth client ID, not the Android
one — Firebase mints a Web client automatically when you enable Google
Sign-in.

1. Firebase Console → **Project settings → General → Your apps**, or
   GCP Console → **APIs & Services → Credentials**.
2. Find the **Web client (auto created by Google Service)** entry.
3. Copy its Client ID (looks like `123456789012-xxxx.apps.googleusercontent.com`).
4. Paste it into `BackendConfig.WEB_CLIENT_ID` in
   `app/src/main/kotlin/com/pdfposter/data/backend/BackendConfig.kt`.

## 6. Deploy the backend Functions

From `backend/functions/`:

```bash
npm install
npm run build
firebase login            # one-time, in browser
firebase use static-webbing-461904-c4
firebase deploy --only functions,firestore:rules,firestore:indexes
```

The deploy outputs the live function URL. Confirm it matches
`BackendConfig.BASE_URL`
(`https://us-central1-static-webbing-461904-c4.cloudfunctions.net/api`).

### FAL_KEY secret (only needed before upscale ships)

```bash
firebase functions:secrets:set FAL_KEY
```

## 7. Verify

After all of the above, on next app launch:

- Anonymous sign-in succeeds silently.
- Generating a PDF writes a row to Firestore under
  `users/{uid}/history/...`.
- The hamburger drawer's **History** section shows the new entry.
- The **Sign in with Google** button completes the OAuth flow and the
  account section flips to show your Google profile.

If any step fails, check `adb logcat | grep -E "AuthRepository|BackendClient"`
on a connected device — both classes log their failures with the cause.
