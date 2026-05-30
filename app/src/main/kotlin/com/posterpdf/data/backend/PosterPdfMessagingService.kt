package com.posterpdf.data.backend

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.posterpdf.MainActivity
import com.posterpdf.R

/**
 * RC12b — Firebase Cloud Messaging service. Receives push notifications
 * for the events the backend writes to /users/{uid}/notifications:
 *   • storage_billed       — monthly storage charge applied
 *   • storage_grace_started — balance hit 0 mid-bill, grace period begun
 *   • storage_deletion_imminent — 24h before grace expiry / blob delete
 *   • storage_deleted      — grace expired, cloud copies removed
 *
 * onNewToken: device's FCM token. Push it to Firestore at
 * /users/{uid}.fcmTokens (array). Server-side billing cron reads this
 * array and sends per-token messages.
 *
 * onMessageReceived: foreground delivery — Android only auto-renders
 * the notification.title/body when the app is *backgrounded*. In
 * foreground, we render manually here.
 */
class PosterPdfMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            Log.d(TAG, "onNewToken before auth — caching for later upload")
            // Stash in SharedPreferences for upload after sign-in. The
            // AuthRepository already has a hook for this; for now we just
            // log. Token is also retrievable via FirebaseMessaging.getToken()
            // any time, so worst case the next signin re-fetches.
            return
        }
        // RC15: see AuthRepository.registerFcmTokenForCurrentUser — set+merge
        // instead of update() so a fresh doc is created on first write.
        FirebaseFirestore.getInstance()
            .collection("users").document(uid)
            .set(
                mapOf("fcmTokens" to FieldValue.arrayUnion(token)),
                com.google.firebase.firestore.SetOptions.merge(),
            )
            .addOnFailureListener { e -> Log.w(TAG, "FCM token upload failed: ${e.message}") }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        ensureChannel()
        val title = message.notification?.title ?: message.data["title"] ?: "PosterPDF"
        val body = message.notification?.body ?: message.data["body"] ?: ""
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        val mgr = getSystemService(NotificationManager::class.java)
        // Use a hash of the body as the id so identical messages don\'t stack.
        mgr?.notify(body.hashCode(), n)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Storage & billing",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Monthly storage charges and deletion warnings"
            }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "PosterPdfFcm"
        const val CHANNEL_ID = "billing"
    }
}
