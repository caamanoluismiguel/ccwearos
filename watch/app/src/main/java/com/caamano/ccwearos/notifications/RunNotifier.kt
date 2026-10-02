package com.caamano.ccwearos.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.caamano.ccwearos.R
import com.caamano.ccwearos.data.Blocker
import com.caamano.ccwearos.data.RunOutcome
import com.caamano.ccwearos.presentation.Haptics

/**
 * "Claude terminó" and "Claude necesita tu Mac" notifications, posted by the
 * foreground service only while the app is not visible (the app shows both
 * itself). Opening the app cancels them.
 *
 *  • Done: low importance and silent. ok → "Listo: <headline>" and tap opens
 *    the Resultado page; not ok → "Algo falló" and tap opens the app.
 *  • Blocked: the blocker hint's first line; one Haptics.error per blocker
 *    (keyed by its ts), cancelled as soon as the wrapper clears /blocker.
 */
object RunNotifier {
    const val CHANNEL_RESULTS = "ccwearos_resultados"
    const val CHANNEL_MAC = "ccwearos_mac"
    const val DONE_ID = 3
    const val BLOCKED_ID = 4
    private const val TAG = "ccwearos-notif"

    private const val REQ_DONE = 20
    private const val REQ_BLOCKED = 21

    private var blockedPostedTs: Long? = null
    private var blockedBuzzedTs: Long? = null

    internal fun createChannels(context: Context, nm: NotificationManager) {
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULTS,
                context.getString(R.string.notif_channel_results),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.notif_channel_results_desc) },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MAC,
                context.getString(R.string.notif_channel_mac),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notif_channel_mac_desc)
                // Haptics.error plays instead, once per blocker.
                enableVibration(false)
                setSound(null, null)
            },
        )
    }

    /** A run just finished (see [OutcomeGate]). No-op while the app is visible. */
    fun runFinished(context: Context, outcome: RunOutcome, headline: String?, appVisible: Boolean) {
        if (appVisible) return
        val text = when {
            !outcome.ok -> context.getString(R.string.notif_done_failed)
            else -> NotificationText.firstLine(headline)
                ?.let { context.getString(R.string.notif_done_ok_headline, it) }
                ?: context.getString(R.string.notif_done_ok)
        }
        val tap = PendingIntent.getActivity(
            context,
            REQ_DONE,
            DeepLinks.intent(context, if (outcome.ok) DeepLinks.ACTION_RESULT else null),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notif_done_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        notify(context, DONE_ID, notification)
    }

    /** Mirrors /blocker: posts while present and the app is hidden, cancels otherwise. */
    @Synchronized
    fun updateBlocker(context: Context, blocker: Blocker?, appVisible: Boolean) {
        if (blocker == null) {
            blockedBuzzedTs = null
            cancelBlocked(context)
            return
        }
        if (appVisible) {
            // The app shows BlockedScreen (and its own haptic).
            blockedBuzzedTs = blocker.ts
            cancelBlocked(context)
            return
        }
        if (blockedPostedTs == blocker.ts) return
        val hint = NotificationText.firstLine(blocker.hint)
            ?: context.getString(R.string.notif_blocked_fallback)
        val open = PendingIntent.getActivity(
            context,
            REQ_BLOCKED,
            DeepLinks.intent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_MAC)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notif_blocked_title))
            .setContentText(hint)
            .setStyle(NotificationCompat.BigTextStyle().bigText(blocker.hint.ifBlank { hint }))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        if (!notify(context, BLOCKED_ID, notification)) return
        blockedPostedTs = blocker.ts
        if (blockedBuzzedTs != blocker.ts) {
            blockedBuzzedTs = blocker.ts
            runCatching { Haptics.error(context) }
        }
    }

    /** The app is visible: it shows the result / blocker itself. */
    fun cancelAll(context: Context) {
        NotificationManagerCompat.from(context).cancel(DONE_ID)
        synchronized(this) { cancelBlocked(context) }
    }

    private fun cancelBlocked(context: Context) {
        blockedPostedTs = null
        NotificationManagerCompat.from(context).cancel(BLOCKED_ID)
    }

    @SuppressLint("MissingPermission") // checked right above the notify call
    private fun notify(context: Context, id: Int, notification: Notification): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; notification $id skipped")
            return false
        }
        return try {
            NotificationManagerCompat.from(context).notify(id, notification)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "notify $id refused: ${e.message}")
            false
        }
    }
}
