package com.caamano.ccwearos.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import com.caamano.ccwearos.R
import com.caamano.ccwearos.data.AnswerGate
import com.caamano.ccwearos.data.CcwearosRepository
import com.caamano.ccwearos.data.CommandText
import com.caamano.ccwearos.data.WrapperStatus
import com.caamano.ccwearos.presentation.Haptics
import com.caamano.ccwearos.tile.RiskClassifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Permitir" / "Rechazar" from the permission notification. Same guards as
 * the in-app buttons, re-checked at tap time because the notification may be
 * stale:
 *   1. connected (an offline write would be replayed later, on another prompt);
 *   2. the CURRENT /permissionPromptId still equals the id the notification
 *      was posted for and the wrapper still awaits it, otherwise the tap is
 *      dropped;
 *   3. Permitir only: the CURRENT prompt text is still not risky (the
 *      notification never offers it for a risky one; this re-checks);
 *   4. [AnswerGate.shared]: at most one answer per id per process.
 * On success the notification shows "Permitido ✓" / "Rechazado" for
 * [PermissionNotifier.CONFIRMATION_MS] with Haptics.tick, then goes away.
 */
class PermissionActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val allow = when (intent.action) {
            ACTION_ALLOW -> true
            ACTION_DENY -> false
            else -> return
        }
        val notifiedId = intent.getStringExtra(EXTRA_PROMPT_ID) ?: return
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                // goAsync gives ~10s: 6s for the answer, 2s for the confirmation.
                val sent = withTimeoutOrNull(6_000) { handle(app, allow, notifiedId) }
                if (sent == null) toast(app, R_SEND_FAILED)
                if (sent == true) {
                    delay(PermissionNotifier.CONFIRMATION_MS)
                    PermissionNotifier.endConfirmation(app, notifiedId)
                }
            } finally {
                pending.finish()
            }
        }
    }

    /** True when the answer was written (confirmation showing). */
    private suspend fun handle(context: Context, allow: Boolean, notifiedId: String): Boolean {
        val repo = CcwearosRepository()
        if (!repo.isConnectedNow()) {
            toast(context, R.string.notif_offline)
            return false
        }
        val snap = repo.fetchPermissionSnapshot(timeoutMs = 4_000)
        if (snap == null || snap.promptId != notifiedId || snap.status != WrapperStatus.AWAITING_PERMISSION) {
            Log.i(TAG, "dropping stale answer (notified=$notifiedId current=${snap?.promptId})")
            PermissionNotifier.cancel(context)
            return false
        }
        if (allow && RiskClassifier.isRisky(snap.prompt)) {
            Log.w(TAG, "refusing one-tap allow of a risky prompt")
            toast(context, R.string.notif_open_to_allow)
            return false
        }
        if (!AnswerGate.shared.tryClaim(notifiedId)) {
            PermissionNotifier.cancel(context)
            return false
        }
        return try {
            repo.sendCommand(if (allow) CommandText.ALLOW else CommandText.DENY, notifiedId)
            PermissionNotifier.showConfirmation(context, notifiedId, allow)
            runCatching { Haptics.tick(context) }
            true
        } catch (e: Exception) {
            Log.w(TAG, "answer write failed: ${e.message}")
            AnswerGate.shared.release(notifiedId)
            toast(context, R_SEND_FAILED)
            false
        }
    }

    private suspend fun toast(context: Context, @StringRes msg: Int) = withContext(Dispatchers.Main) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val ACTION_ALLOW = "com.caamano.ccwearos.action.PERMISSION_ALLOW"
        const val ACTION_DENY = "com.caamano.ccwearos.action.PERMISSION_DENY"
        const val EXTRA_PROMPT_ID = "promptId"
        private const val TAG = "ccwearos-action"
        private val R_SEND_FAILED = R.string.notif_send_failed
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
