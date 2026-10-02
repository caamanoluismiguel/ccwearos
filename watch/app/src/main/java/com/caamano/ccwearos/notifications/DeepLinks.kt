package com.caamano.ccwearos.notifications

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import com.caamano.ccwearos.presentation.MainActivity

/**
 * Deep links into MainActivity from the tile, complication and notifications.
 *
 * Contract for MainActivity (read in onCreate AND onNewIntent):
 *   intent.getStringExtra(DeepLinks.EXTRA_ACTION)
 *     "voice"  → open the voice input right away (tile "Preguntar" chip)
 *     "result" → jump to the Resultado page (tile Done state, "Claude terminó"
 *                notification)
 *     null     → normal launch
 *
 * MainActivity is exported with a LAUNCHER filter and every caller targets it
 * by explicit class name, so no extra intent-filter is needed in the manifest.
 */
object DeepLinks {
    const val EXTRA_ACTION = "action"
    const val ACTION_VOICE = "voice"
    const val ACTION_RESULT = "result"

    /**
     * The pending deep-link action, set by MainActivity from its launch intent
     * and consumed (reset to null) by the dashboard once handled.
     */
    val pending = MutableStateFlow<String?>(null)

    /** Records the action carried by [intent], if any. */
    fun capture(intent: Intent?) {
        intent?.getStringExtra(EXTRA_ACTION)?.let { pending.value = it }
    }

    fun intent(context: Context, action: String? = null): Intent =
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (action != null) putExtra(EXTRA_ACTION, action) }
}
