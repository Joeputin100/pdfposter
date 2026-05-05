package com.posterpdf.data.backend

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.io.File

/**
 * RC17 — submits a support ticket (bug report / feature request / billing
 * question) to Firestore at `/support/{auto-id}`. Reads via Admin SDK on
 * the backend; the firestore.rules pin `uid == request.auth.uid` and
 * forbid client read/update/delete entirely so each ticket is a one-way
 * write.
 *
 * Diagnostic payload is opt-in. When the user checks the box we attach:
 *   - Device manufacturer + model (e.g. "samsung SM-S908U")
 *   - Android version (Build.VERSION.SDK_INT)
 *   - App version name + code
 *   - Tail of the debug log (last [LOG_TAIL_BYTES] bytes ≈ ~200 lines)
 *
 * NEVER attached, regardless of opt-in:
 *   - Photos / images / generated PDFs (the user's content stays on-device)
 *   - File system contents outside the app's own log
 *   - Location, contacts, phone number, list of installed apps
 */
class SupportRepository(private val auth: AuthRepository) {

    suspend fun submit(
        context: Context,
        subject: String,
        category: String,
        description: String,
        includeDiagnostics: Boolean,
    ): Result<String> {
        return try {
            auth.ensureSignedIn()
            val session = auth.session.value
            val uid = session.uid ?: return Result.failure(IllegalStateException("not signed in"))
            val payload = mutableMapOf<String, Any?>(
                "uid" to uid,
                "email" to session.email,
                "displayName" to session.displayName,
                "isAnonymous" to session.isAnonymous,
                "subject" to subject.trim(),
                "category" to category,
                "description" to description.trim(),
                "diagnosticsIncluded" to includeDiagnostics,
                "createdAt" to FieldValue.serverTimestamp(),
            )
            if (includeDiagnostics) {
                payload["device"] = mapOf(
                    "manufacturer" to Build.MANUFACTURER,
                    "model" to Build.MODEL,
                    "androidSdk" to Build.VERSION.SDK_INT,
                    "androidRelease" to Build.VERSION.RELEASE,
                    "appVersion" to com.posterpdf.BuildConfig.VERSION_NAME,
                    "appVersionCode" to com.posterpdf.BuildConfig.VERSION_CODE,
                )
                payload["debugLogTail"] = readDebugLogTail(context)
            }
            val ref = FirebaseFirestore.getInstance()
                .collection("support")
                .add(payload)
                .await()
            Log.i(TAG, "support ticket created: ${ref.id}")
            Result.success(ref.id)
        } catch (t: Throwable) {
            Log.w(TAG, "support submit failed: ${t.message}")
            Result.failure(t)
        }
    }

    /**
     * Returns the last [LOG_TAIL_BYTES] of the debug log file, or null
     * if no log exists. Reads from the tail by seeking, so a multi-MB
     * log doesn't pull the entire file into memory.
     */
    private fun readDebugLogTail(context: Context): String? {
        val file: File = com.posterpdf.MainViewModel.debugLogFile(context) ?: return null
        if (!file.exists()) return null
        val len = file.length()
        if (len == 0L) return ""
        return try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val start = (len - LOG_TAIL_BYTES).coerceAtLeast(0L)
                raf.seek(start)
                val bytes = ByteArray((len - start).toInt())
                raf.readFully(bytes)
                String(bytes, Charsets.UTF_8)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "log tail read failed: ${t.message}")
            null
        }
    }

    companion object {
        private const val TAG = "SupportRepository"

        /** ~50 KB of log = ~500 typical lines. Plenty for triage; small
         *  enough that Firestore's 1 MiB document limit is comfortable. */
        private const val LOG_TAIL_BYTES = 50_000L
    }
}
