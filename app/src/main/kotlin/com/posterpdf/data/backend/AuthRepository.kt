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
        if (a.currentUser != null) return
        try {
            a.signInAnonymously().await()
        } catch (t: Throwable) {
            Log.w(TAG, "anonymous sign-in failed: ${t.message}")
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
        val opts = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
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
        AuthSession(
            uid = uid,
            isAnonymous = isAnonymous,
            displayName = displayName,
            email = email,
            photoUrl = photoUrl?.toString(),
            signedIn = true,
        )
    }

    companion object {
        private const val TAG = "AuthRepository"

        @Volatile private var INSTANCE: AuthRepository? = null

        fun get(context: Context): AuthRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AuthRepository(context.applicationContext).also { INSTANCE = it }
            }
    }
}
