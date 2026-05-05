package com.posterpdf.data.backend

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

data class AuthSession(
    val uid: String? = null,
    val isAnonymous: Boolean = true,
    val displayName: String? = null,
    val email: String? = null,
    val photoUrl: String? = null,
    val signedIn: Boolean = false,
)

/**
 * Phase-3 anonymous-then-Google auth.
 *
 * Strategy:
 *  - On first call, sign the user in anonymously so they have a stable uid
 *    immediately and can write history records to Firestore from day one.
 *  - "Sign in with Google" links the Google credential to the existing anon
 *    account when possible, preserving uid and history. If linking fails
 *    (e.g. that Google account is already attached to another uid), fall back
 *    to a fresh sign-in.
 *
 * Defensive: every Firebase touchpoint is wrapped so the app remains usable
 * for offline PDF generation even if google-services.json is a placeholder
 * or Firebase services aren't enabled in the project yet.
 */
class AuthRepository private constructor(appContext: Context) {

    private val auth: FirebaseAuth? = try {
        FirebaseApp.initializeApp(appContext)
        FirebaseAuth.getInstance()
    } catch (t: Throwable) {
        Log.w(TAG, "Firebase unavailable: ${t.message}")
        null
    }

    private val _session = MutableStateFlow(AuthSession())
    val session: StateFlow<AuthSession> = _session.asStateFlow()

    init {
        auth?.addAuthStateListener { fa -> _session.value = fa.currentUser.toSession() }
        _session.value = auth?.currentUser.toSession()
    }

    suspend fun ensureSignedIn() {
        val a = auth ?: return
        if (a.currentUser != null) {
            registerFcmTokenForCurrentUser()
            return
        }
        try {
            a.signInAnonymously().await()
            registerFcmTokenForCurrentUser()
        } catch (t: Throwable) {
            Log.w(TAG, "anonymous sign-in failed: ${t.message}")
        }
    }

    /**
     * RC12b — fetch the device's current FCM token and write it into
     * /users/{uid}.fcmTokens (an array — Firestore arrayUnion handles dedup).
     * Server-side dailySweep reads this array to deliver storage-billing
     * pushes. Best-effort; failures are logged but never propagated.
     *
     * Called from ensureSignedIn() (covers both fresh anon and returning
     * Google sign-in flows) and after any successful upgradeToGoogle().
     * The FirebaseMessagingService.onNewToken hook also writes the token,
     * but that only fires on token rotation — not on app start with a
     * pre-existing token, so this explicit registration is the reliable
     * path for already-signed-in users.
     */
    private suspend fun registerFcmTokenForCurrentUser() {
        val uid = auth?.currentUser?.uid ?: return
        try {
            val token = com.google.firebase.messaging.FirebaseMessaging.getInstance()
                .token.await()
            // RC15: switched .update(...) → .set(..., merge) because update()
            // fails with NOT_FOUND for users who haven't yet had a /users/{uid}
            // doc written by the backend (fresh anon sign-in). The user's
            // RC14 debug log showed test_push: delivered=0 across every chip,
            // because the token was never persisted server-side. set+merge
            // creates the doc on first write, then arrayUnion's idempotency
            // handles repeated registrations.
            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                .collection("users").document(uid)
                .set(
                    mapOf(
                        "fcmTokens" to com.google.firebase.firestore.FieldValue.arrayUnion(token),
                    ),
                    com.google.firebase.firestore.SetOptions.merge(),
                )
                .await()
            Log.i(TAG, "FCM token registered for uid=$uid")
        } catch (t: Throwable) {
            Log.w(TAG, "FCM token registration failed: ${t.message}")
        }
    }

    suspend fun getIdToken(forceRefresh: Boolean = false): String? {
        val u = auth?.currentUser ?: return null
        return try {
            u.getIdToken(forceRefresh).await()?.token
        } catch (t: Throwable) {
            Log.w(TAG, "getIdToken failed: ${t.message}")
            null
        }
    }

    fun googleSignInIntent(activity: Activity, webClientId: String): Intent {
        // RC20.2: explicit .requestProfile() so the photo URL ends up on the
        // GoogleSignInAccount even though DEFAULT_SIGN_IN already requests
        // the profile scope — being explicit here keeps the intent obvious
        // and survives any future change to DEFAULT_SIGN_IN's defaults.
        val opts = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .requestProfile()
            .build()
        val client = GoogleSignIn.getClient(activity, opts)
        // Force account picker to appear — without signOut(), Google Sign-In
        // silently re-uses whichever account was last authenticated, even when
        // the device has multiple Google accounts the user may want to choose
        // between. Returning the intent after signOut() puts us back at the
        // chooser. (signOut() is local-only; doesn't revoke server tokens.)
        client.signOut()
        return client.signInIntent
    }

    suspend fun handleGoogleSignInResult(data: Intent?): Result<Unit> {
        val a = auth ?: return Result.failure(IllegalStateException("Firebase not initialized"))
        return try {
            val acct = GoogleSignIn.getSignedInAccountFromIntent(data).await()
            val cred = GoogleAuthProvider.getCredential(acct.idToken, null)
            val current = a.currentUser
            if (current != null && current.isAnonymous) {
                try {
                    current.linkWithCredential(cred).await()
                } catch (linkFailure: Throwable) {
                    Log.w(TAG, "link anon->google failed (${linkFailure.message}); falling back to plain sign-in")
                    a.signInWithCredential(cred).await()
                }
            } else {
                a.signInWithCredential(cred).await()
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            Log.w(TAG, "google sign-in failed: ${t.message}")
            Result.failure(t)
        }
    }

    fun signOut() {
        auth?.signOut()
    }

    private fun FirebaseUser?.toSession(): AuthSession = if (this == null) {
        AuthSession()
    } else {
        // RC20.2: FirebaseUser.photoUrl is the "primary" photo URL across
        // providers and can be null right after signInWithCredential even
        // when the Google provider entry has it. Fall back to the
        // google.com providerData entry's photoUrl so the chip + drawer
        // both render the avatar instead of the initial-letter placeholder.
        val photo = photoUrl?.toString()
            ?: providerData
                .firstOrNull { it.providerId == "google.com" }
                ?.photoUrl
                ?.toString()
        // RC16: Log.i went to logcat which the user can't access. Mirror
        // the diagnostic into the user-facing debug log file via the
        // logEvent path — the user can then submit the next saved log
        // and we can see whether photoUrl is null (Google scopes
        // problem) or set-but-not-rendering (Coil/AsyncImage).
        Log.i(TAG, "session: uid=$uid anon=$isAnonymous photoUrl=${photo ?: "<null>"}")
        AuthRepository.lastPhotoUrl = photo
        AuthSession(
            uid = uid,
            isAnonymous = isAnonymous,
            displayName = displayName,
            email = email,
            photoUrl = photo,
            signedIn = true,
        )
    }

    companion object {
        private const val TAG = "AuthRepository"

        @Volatile private var INSTANCE: AuthRepository? = null

        /** RC16 diagnostic: the most recently observed photoUrl from the auth
         *  state listener. Read from MainActivity / MainViewModel and written
         *  to the debug log file once per session-update so the user-facing
         *  log shows whether the URL is actually null vs. failing to load. */
        @Volatile var lastPhotoUrl: String? = null

        fun get(context: Context): AuthRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AuthRepository(context.applicationContext).also { INSTANCE = it }
            }
    }
}
