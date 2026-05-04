package com.posterpdf.ml

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.posterpdf.MainActivity
import com.posterpdf.R

/**
 * RC11 — foreground service that hosts the on-device ESRGAN upscale work.
 *
 * Why a service: the upscale can run for minutes on older / thermal-throttled
 * devices. Without a foreground service, Samsung's PPK or Android's Doze can
 * kill the process the moment the user backgrounds the app, losing all the
 * tile work done so far.
 *
 * The service does not run the upscale itself — that stays in
 * [com.posterpdf.MainViewModel.runFreeUpscale] inside a viewModelScope coroutine.
 * The service exists purely to register a `startForeground` notification that
 * promotes the process to "foreground" priority so the OS won't reap it.
 *
 * The notification updates as the upscale progresses (per-tile heartbeat from
 * the ESRGAN loop posts progress updates here). Tapping the notification
 * brings the user back to MainActivity. Cancel comes from the in-app dialog,
 * not the notification.
 *
 * Pairs with [UpscaleStateStore] for tile-level resume on PPK kill.
 */
class UpscaleForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val done = intent?.getIntExtra(EXTRA_DONE, 0) ?: 0
        val total = intent?.getIntExtra(EXTRA_TOTAL, 100) ?: 100
        val isInitial = intent?.getBooleanExtra(EXTRA_INITIAL, false) ?: false
        val notification = buildNotification(done, total)
        if (isInitial) {
            // First call must use startForeground to promote the process.
            // Subsequent calls just update the existing notification.
            startForeground(NOTIF_ID, notification)
        } else {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr?.notify(NOTIF_ID, notification)
        }
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "On-device sharpening",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progress updates for the on-device photo sharpener"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(done: Int, total: Int): Notification {
        val pct = if (total > 0) (done * 100 / total) else 0
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sharpening your photo")
            .setContentText(
                if (total > 0) "$pct% · $done of $total tiles complete"
                else "Starting…",
            )
            .setProgress(total, done, total <= 0)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "upscale_progress"
        const val NOTIF_ID = 1001
        private const val EXTRA_DONE = "done"
        private const val EXTRA_TOTAL = "total"
        private const val EXTRA_INITIAL = "initial"

        /** Promote the process to foreground priority + post the initial
         *  progress notification. Call once at the start of an upscale. */
        fun start(ctx: Context, totalTiles: Int) {
            val intent = Intent(ctx, UpscaleForegroundService::class.java).apply {
                putExtra(EXTRA_DONE, 0)
                putExtra(EXTRA_TOTAL, totalTiles)
                putExtra(EXTRA_INITIAL, true)
            }
            ContextCompat.startForegroundService(ctx, intent)
        }

        /** Update the existing notification with new tile progress. Cheap;
         *  call from the upscale loop's onProgress callback. */
        fun updateProgress(ctx: Context, done: Int, total: Int) {
            val intent = Intent(ctx, UpscaleForegroundService::class.java).apply {
                putExtra(EXTRA_DONE, done)
                putExtra(EXTRA_TOTAL, total)
                putExtra(EXTRA_INITIAL, false)
            }
            // startService instead of startForegroundService — we\'re already in foreground.
            try {
                ctx.startService(intent)
            } catch (_: IllegalStateException) {
                // Process backgrounded between tiles; the existing service
                // notification stays current until next tile.
            }
        }

        /** Stop the service + dismiss its notification. Call from the
         *  finally block of runFreeUpscale (success, cancel, or failure). */
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, UpscaleForegroundService::class.java))
        }
    }
}
