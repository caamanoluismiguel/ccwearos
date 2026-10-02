package com.caamano.ccwearos.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
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
import com.caamano.ccwearos.tile.RiskClassifier

/**
 * Heads-up notification for a pending permission prompt, so a prompt that
 * arrives while the screen is off (or the app is in the background) isn't
 * missed. Fed from two places, both funnelled through [update]:
 *   • the foreground service's live RTDB listener;
 *   • an FCM wake, which reads the state once (the FGS may be dead).
 *
 * Title "Claude pide permiso", body `Herramienta · objetivo`, BigText with the
 * full prompt. Permitir / Rechazar only for prompts the risk classifier calls
 * safe; a risky one only gets "Abrir" (never one-tap allow a risky command
 * from a notification). After an action the same notification turns into a
 * short silent confirmation ("Permitido ✓" / "Rechazado") for ~2s.
 *
 * While MainActivity is visible the PermissionScreen owns the prompt, so the
 * notification is withheld (and cancelled) to avoid a double buzz.
 */
object PermissionNotifier {
    const val CHANNEL_ID = "ccwearos_permisos"
    const val NOTIFICATION_ID = 2
    const val CONFIRMATION_MS = 2_000L
    private const val TAG = "ccwearos-notif"

    // Last prompt id we buzzed for: each prompt vibrates once, even if the
    // notification is withdrawn and re-posted (app foreground → background).
    private var lastBuzzedId: String? = null
    private var postedId: String? = null

    // Prompt id whose "Permitido ✓" / "Rechazado" confirmation is showing.
    // While set, the wrapper clearing that prompt must not cancel it early.
    private var confirmingId: String? = null

    /** Creates every app notification channel (permission, results, Mac). Idempotent. */
    fun createChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val permissions = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_permissions),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notif_channel_permissions_desc)
            // The vibration comes from Haptics.permission (same pattern as the
            // in-app screen); the channel's own would double it.
            enableVibration(false)
            setShowBadge(true)
        }
        nm.createNotificationChannel(permissions)
        RunNotifier.createChannels(context, nm)
    }

    @Synchronized
    fun update(context: Context, snapshot: PermissionSnapshot, appVisible: Boolean) {
        val prompt = snapshot.prompt
        val id = snapshot.promptId
        val pending = snapshot.status == WrapperStatus.AWAITING_PERMISSION &&
            !prompt.isNullOrBlank() && id != null
        if (!pending) {
            lastBuzzedId = null
            // The answered prompt was just cleared; its confirmation ends on its own timer.
            if (confirmingId == null) cancel(context)
            return
        }
        if (appVisible) {
            // The screen is showing PermissionScreen (which buzzes itself).
            lastBuzzedId = id
            cancel(context)
            return
        }
        // Already posted, or just answered and waiting for the wrapper to clear it.
        if (postedId == id || confirmingId == id) return
        if (!post(context, prompt!!, id!!)) return
        confirmingId = null
        if (lastBuzzedId != id) {
            lastBuzzedId = id
            runCatching { Haptics.permission(context) }
        }
    }

    @Synchronized
    fun cancel(context: Context) {
        postedId = null
        confirmingId = null
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /** Swaps the prompt notification for a silent "Permitido ✓" / "Rechazado". */
    @Synchronized
    fun showConfirmation(context: Context, promptId: String, allowed: Boolean) {
        postedId = null
        confirmingId = promptId
        val text = context.getString(if (allowed) R.string.notif_allowed else R.string.notif_denied)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(text)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        notify(context, notification)
    }

    /** Ends the confirmation for [promptId], unless a newer prompt replaced it. */
    @Synchronized
    fun endConfirmation(context: Context, promptId: String) {
        if (confirmingId != promptId) return
        confirmingId = null
        if (postedId == null) NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun post(context: Context, prompt: String, promptId: String): Boolean {
        val summary = NotificationText.promptSummary(prompt)
            ?: context.getString(R.string.notif_permission_fallback)
        val open = PendingIntent.getActivity(
            context,
            REQ_OPEN,
            DeepLinks.intent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notif_permission_title))
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(prompt))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(open)
        if (RiskClassifier.isRisky(prompt)) {
            builder.addAction(0, context.getString(R.string.notif_action_open), open)
        } else {
            builder
                .addAction(0, context.getString(R.string.notif_action_allow), actionIntent(context, PermissionActionReceiver.ACTION_ALLOW, promptId, REQ_ALLOW))
                .addAction(0, context.getString(R.string.notif_action_deny), actionIntent(context, PermissionActionReceiver.ACTION_DENY, promptId, REQ_DENY))
        }
        if (!notify(context, builder.build())) return false
        postedId = promptId
        return true
    }

    @SuppressLint("MissingPermission") // checked right above the notify call
    private fun notify(context: Context, notification: Notification): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; permission notification skipped")
            return false
        }
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "notify refused: ${e.message}")
            false
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
