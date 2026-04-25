package com.pdfposter.data.backend

object BackendConfig {
    // Replace with your Firebase Functions HTTPS URL once deployed.
    // Example: https://us-central1-<project-id>.cloudfunctions.net/api
    const val BASE_URL = "https://us-central1-static-webbing-461904-c4.cloudfunctions.net/api"

    // OAuth 2.0 Web client ID from Firebase Console -> Authentication -> Sign-in providers -> Google.
    // Required for GoogleSignIn.requestIdToken(...) to mint a token Firebase Auth will accept.
    // PLACEHOLDER — replace before shipping. See docs/setup-firebase.md.
    const val WEB_CLIENT_ID = "000000000000-placeholder.apps.googleusercontent.com"
}
