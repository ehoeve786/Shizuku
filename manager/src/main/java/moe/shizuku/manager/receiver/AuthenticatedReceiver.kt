package moe.shizuku.manager.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

abstract class AuthenticatedReceiver : BroadcastReceiver() {

    companion object {
        private const val CHANNEL_ID = "auth_errors"
        private const val CHANNEL_NAME = "Authentication Errors"
        private const val NOTIFICATION_ID = 1450
        private const val NOTIFICATION_INTERVAL_MS = 60_000L
        private val lastNotificationTime = AtomicLong(0)
    }

    final override fun onReceive(context: Context, intent: Intent) {
        val authToken = intent.getStringExtra("auth")
        val expectedToken = ShizukuSettings.getAuthToken()

        if (authToken.isNullOrEmpty()) {
            context.notifyRateLimited(
                R.string.notification_auth_missing_title,
                R.string.notification_auth_missing_message
            )
        } else if (!MessageDigest.isEqual(authToken.toByteArray(), expectedToken.toByteArray())) {
            context.notifyRateLimited(
                R.string.notification_auth_invalid_title,
                R.string.notification_auth_invalid_message
            )
        } else {
            onAuthenticated(context, intent)
        }
    }

    /** Any app can send these broadcasts, so do not let them flood the user with alerts. */
    private fun Context.notifyRateLimited(title: Int, message: Int) {
        val now = SystemClock.elapsedRealtime()
        val last = lastNotificationTime.get()
        if (last != 0L && now - last < NOTIFICATION_INTERVAL_MS) return
        if (!lastNotificationTime.compareAndSet(last, now)) return
        notify(title, message)
    }

    private fun Context.notify(title: Int, message: Int) {
        val titleStr = getString(title)
        val messageStr = getString(message)

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        )

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or 
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        val launchPendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(titleStr)
            .setContentText(messageStr)
            .setContentIntent(launchPendingIntent)
            .setAutoCancel(true)
            .setSmallIcon(R.drawable.ic_system_icon)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    abstract fun onAuthenticated(context: Context, intent: Intent)
}