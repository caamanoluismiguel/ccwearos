package com.caamano.ccwearos.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.caamano.ccwearos.data.AnswerGate
import com.caamano.ccwearos.data.CcwearosRepository
import com.caamano.ccwearos.data.CommandText
import com.caamano.ccwearos.presentation.Haptics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Permitir" / "Rechazar" from the permission notification. Same guards as
 * the in-app buttons, re-checked at tap time because the notification may be
 * stale:
 *   1. connected (an offline write would be replayed later, on another prompt);
 *   2. the CURRENT /permissionPromptId still equals the id the notification
 *      was posted for, otherwise the tap is dropped;
 *   3. [AnswerGate.shared] — at most one answer per id per process.
 */
class PermissionActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val text = when (intent.action) {
            ACTION_ALLOW -> CommandText.ALLOW
            ACTION_DENY -> CommandText.DENY
            else -> return
        }
        val notifiedId = intent.getStringExtra(EXTRA_PROMPT_ID) ?: return
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(8_000) { handle(app, text, notifiedId) }
                    ?: toast(app, "No se pudo enviar tu respuesta.")
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(context: Context, text: String, notifiedId: String) {
        val repo = CcwearosRepository()
        if (!repo.isConnectedNow()) {
            toast(context, "Sin conexión. Abre la app para responder.")
            return
        }
        val current = runCatching { repo.fetchPermissionPromptId() }.getOrNull()
        if (current == null || current != notifiedId) {
            Log.i(TAG, "dropping stale answer (notified=$notifiedId current=$current)")
            PermissionNotifier.cancel(context)
            return
        }
        if (!AnswerGate.shared.tryClaim(current)) {
            PermissionNotifier.cancel(context)
            return
        }
        try {
            repo.sendCommand(text, current)
            PermissionNotifier.cancel(context)
            if (text == CommandText.DENY) runCatching { Haptics.tick(context) }
        } catch (e: Exception) {
            Log.w(TAG, "answer write failed: ${e.message}")
            AnswerGate.shared.release(current)
            toast(context, "No se pudo enviar tu respuesta.")
        }
    }

    private suspend fun toast(context: Context, msg: String) = withContext(Dispatchers.Main) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val ACTION_ALLOW = "com.caamano.ccwearos.action.PERMISSION_ALLOW"
        const val ACTION_DENY = "com.caamano.ccwearos.action.PERMISSION_DENY"
        const val EXTRA_PROMPT_ID = "promptId"
        private const val TAG = "ccwearos-action"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
