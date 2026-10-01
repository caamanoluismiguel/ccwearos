package com.caamano.ccwearos.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.caamano.ccwearos.R
import com.caamano.ccwearos.data.PermissionSnapshot
import com.caamano.ccwearos.data.WrapperStatus
import com.caamano.ccwearos.presentation.Haptics
import com.caamano.ccwearos.presentation.MainActivity

/**
 * Heads-up notification for a pending permission prompt, so a prompt that
 * arrives while the screen is off (or the app is in the background) isn't
 * missed. Fed from two places, both funnelled through [update]:
 *   • the foreground service's live RTDB listener;
 *   • an FCM wake, which reads the state once (the FGS may be dead).
 *
 * While MainActivity is visible the PermissionScreen owns the prompt, so the
 * notification is withheld (and cancelled) to avoid a double buzz.
 */
object PermissionNotifier {
    const val CHANNEL_ID = "ccwearos_permisos"
    const val NOTIFICATION_ID = 2
    private const val TAG = "ccwearos-notif"

    // Last prompt id we buzzed for: each prompt vibrates once, even if the
    // notification is withdrawn and re-posted (app foreground → background).
    private var lastBuzzedId: String? = null
    private var postedId: String? = null

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Permisos",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Avisa cuando Claude necesita tu permiso para continuar."
            // The vibration comes from Haptics.permission (same pattern as the
            // in-app screen); the channel's own would double it.
            enableVibration(false)
            setShowBadge(true)
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    @Synchronized
    fun update(context: Context, snapshot: PermissionSnapshot, appVisible: Boolean) {
        val prompt = snapshot.prompt
        val id = snapshot.promptId
        val pending = snapshot.status == WrapperStatus.AWAITING_PERMISSION &&
            !prompt.isNullOrBlank() && id != null
        if (!pending) {
            lastBuzzedId = null
            cancel(context)
            return
        }
        if (appVisible) {
            // The screen is showing PermissionScreen (which buzzes itself).
            lastBuzzedId = id
            cancel(context)
            return
        }
        if (postedId == id) return
        post(context, prompt!!, id!!)
        if (lastBuzzedId != id) {
            lastBuzzedId = id
            runCatching { Haptics.permission(context) }
        }
    }

    @Synchronized
    fun cancel(context: Context) {
        postedId = null
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun post(context: Context, prompt: String, promptId: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; prompt notification skipped")
            return
        }
        val open = PendingIntent.getActivity(
            context,
            REQ_OPEN,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Claude necesita permiso")
            .setContentText(prompt)
            .setStyle(NotificationCompat.BigTextStyle().bigText(prompt))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "Permitir", actionIntent(context, PermissionActionReceiver.ACTION_ALLOW, promptId, REQ_ALLOW))
            .addAction(0, "Rechazar", actionIntent(context, PermissionActionReceiver.ACTION_DENY, promptId, REQ_DENY))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            postedId = promptId
        } catch (e: SecurityException) {
            Log.w(TAG, "notify refused: ${e.message}")
        }
    }

    private fun actionIntent(context: Context, action: String, promptId: String, requestCode: Int) =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, PermissionActionReceiver::class.java)
                .setAction(action)
                .putExtra(PermissionActionReceiver.EXTRA_PROMPT_ID, promptId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private const val REQ_OPEN = 10
    private const val REQ_ALLOW = 11
    private const val REQ_DENY = 12
}
