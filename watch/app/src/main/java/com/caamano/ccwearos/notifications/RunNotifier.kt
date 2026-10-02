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
 *  • Done: its own high-importance channel so it actually reaches the wrist
 *    with the screen off; the buzz is Haptics.done (failure: Haptics.error),
 *    not the system pattern. ok → "¡Listo!" + the TL;DR's first line; not ok
 *    → "No se pudo" + reason. Tap opens Resultado. A run the user stopped
 *    posts nothing. One alert per outcome ts (OutcomeGate + [doneAlertedTs]).
 *  • Blocked: the blocker hint's first line; one Haptics.error per blocker
 *    (keyed by its ts), cancelled as soon as the wrapper clears /blocker.
 */
object RunNotifier {
    /** Old silent channel, deleted on startup (importance can't be raised in place). */
    private const val CHANNEL_RESULTS_LEGACY = "ccwearos_resultados"
    const val CHANNEL_DONE = "ccwearos_terminado"
    const val CHANNEL_MAC = "ccwearos_mac"
    const val DONE_ID = 3
    const val BLOCKED_ID = 4
    private const val TAG = "ccwearos-notif"

    private const val REQ_DONE = 20
    private const val REQ_BLOCKED = 21

    private var blockedPostedTs: Long? = null
    private var doneAlertedTs: Long? = null
    private var blockedBuzzedTs: Long? = null

    internal fun createChannels(context: Context, nm: NotificationManager) {
        nm.deleteNotificationChannel(CHANNEL_RESULTS_LEGACY)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DONE,
                context.getString(R.string.notif_channel_results),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notif_channel_results_desc)
                // Haptics.done / Haptics.error play instead (our own patterns).
                enableVibration(false)
                setSound(null, null)
            },
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

    /**
     * A run just finished (see [OutcomeGate]). No-op while the app is visible
     * (the in-app moment covers it, and replays on wake if it was missed).
     */
    @Synchronized
    fun runFinished(context: Context, outcome: RunOutcome, headline: String?, appVisible: Boolean) {
        val copy = NotificationText.doneCopy(outcome, headline, appVisible, doneAlertedTs) ?: return
        val title = context.getString(if (copy.ok) R.string.notif_done_ok_title else R.string.notif_done_failed_title)
        val text = copy.line ?: if (copy.ok) {
            context.getString(R.string.notif_done_ok_fallback)
        } else {
            context.getString(R.string.notif_done_failed_fallback, outcome.exitCode.toInt())
        }
        val tap = PendingIntent.getActivity(
            context,
            REQ_DONE,
            DeepLinks.intent(context, DeepLinks.ACTION_RESULT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        if (!notify(context, DONE_ID, notification)) return
        doneAlertedTs = outcome.ts
        runCatching { if (copy.ok) Haptics.done(context) else Haptics.error(context) }
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
