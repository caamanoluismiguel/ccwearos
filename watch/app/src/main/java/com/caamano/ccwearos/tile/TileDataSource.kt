package com.caamano.ccwearos.tile

import android.util.Log
import com.caamano.ccwearos.data.AnswerGate
import com.caamano.ccwearos.data.RtdbMappers
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

// One-shot RTDB reads for the tile and complication services. Those services
// are short-lived binders, so they can't hold listeners: every request reads
// the handful of paths it needs once, with a timeout. The app's anonymous
// auth session is persisted by Firebase, so a signed-in check is enough.
object TileDataSource {
    private const val TAG = "ccwearos-tile"
    private const val READ_TIMEOUT_MS = 4_000L

    // Wrapper drops the answer unless promptId matches the live prompt.
    const val ALLOW_TEXT = "1\r"
    const val DENY_TEXT = "\u001B"

    fun isSignedIn(): Boolean = FirebaseAuth.getInstance().currentUser != null

    suspend fun load(): TileState {
        if (!isSignedIn()) return TileState.SignedOut
        return TileMapper.map(readSnapshot(), signedIn = true)
    }

    suspend fun readSnapshot(): TileSnapshot? = try {
        withTimeoutOrNull(READ_TIMEOUT_MS) {
            coroutineScope {
                val db = FirebaseDatabase.getInstance()
                fun read(path: String) = async { db.getReference(path).get().await() }
                val status = read("status")
                val activity = read("activity")
                val prompt = read("permissionPrompt")
                val promptId = read("permissionPromptId")
                val tokens = read("metrics/dailyTokens")
                val ctx = read("claudeStatus/contextPct")
                val blockerRaw = read("blocker")
                val outcomeRaw = read("outcome")
                val headline = read("headline")
                val blocker = RtdbMappers.blocker(blockerRaw.await().value)
                val outcome = RtdbMappers.outcome(outcomeRaw.await().value)
                TileSnapshot(
                    blocked = blocker != null,
                    blockerHint = blocker?.hint,
                    outcomeOk = outcome?.ok,
                    outcomeTs = outcome?.ts ?: 0L,
                    headline = RtdbMappers.string(headline.await().value),
                    status = status.await().getValue(String::class.java),
                    activity = activity.await().getValue(String::class.java),
                    permissionPrompt = prompt.await().getValue(String::class.java),
                    permissionPromptId = promptId.await().getValue(String::class.java),
                    dailyTokens = (tokens.await().value as? Number)?.toLong(),
                    contextPct = (ctx.await().value as? Number)?.toDouble(),
                )
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "tile read failed: ${e.message}")
        null
    }

    /**
     * Answers the live permission prompt, but only if it is still the one the
     * user saw ([expectedPromptId]), still awaiting, and still not risky.
     * Everything is re-read at action time; any mismatch writes nothing.
     */
    suspend fun answer(expectedPromptId: String, allow: Boolean): Boolean {
        if (!isSignedIn() || expectedPromptId.isBlank()) return false
        val snap = readSnapshot() ?: return false
        if (snap.status != "AWAITING_PERMISSION") return false
        if (snap.permissionPromptId != expectedPromptId) return false
        // Denying is always safe; allowing re-checks risk on the fresh text.
        if (allow && (RiskClassifier.isRisky(snap.permissionPrompt) || !TileMapper.fullyVisible(snap.permissionPrompt))) return false
        // Same in-process guard as the screen and the notification: one answer
        // per prompt id. It only covers taps while this process is alive; the
        // wrapper's promptId check is the real cross-process guard.
        if (!AnswerGate.shared.tryClaim(expectedPromptId)) return false
        val payload = mapOf<String, Any>(
            "text" to if (allow) ALLOW_TEXT else DENY_TEXT,
            "issuedAt" to ServerValue.TIMESTAMP,
            "promptId" to expectedPromptId,
        )
        val sent = try {
            withTimeoutOrNull(READ_TIMEOUT_MS) {
                FirebaseDatabase.getInstance().getReference("command").setValue(payload).await()
                true
            } ?: false
        } catch (e: CancellationException) {
            AnswerGate.shared.release(expectedPromptId)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "tile answer failed: ${e.message}")
            false
        }
        // Failed write: give the claim back so the user can retry.
        if (!sent) AnswerGate.shared.release(expectedPromptId)
        return sent
    }
}
