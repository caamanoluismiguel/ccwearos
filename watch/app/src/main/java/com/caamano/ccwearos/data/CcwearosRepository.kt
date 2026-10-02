package com.caamano.ccwearos.data

import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** One-shot read of the routing-critical paths (FCM wake, notification actions). */
data class PermissionSnapshot(
    val status: WrapperStatus,
    val prompt: String?,
    val promptId: String?,
)

class CcwearosRepository(
    private val db: FirebaseDatabase = FirebaseDatabase.getInstance(),
) : WatchRepository {
    private fun ref(path: String): DatabaseReference = db.getReference(path)

    override val status: Flow<WrapperStatus> =
        pathFlow("status", WrapperStatus.OFFLINE) { RtdbMappers.status(it.value) }

    override val metrics: Flow<Metrics> =
        pathFlow("metrics", Metrics()) { RtdbMappers.metrics(it.value) }

    override val permissionPrompt: Flow<String?> =
        pathFlow("permissionPrompt", null) { RtdbMappers.string(it.value) }

    override val permissionPromptId: Flow<String?> =
        pathFlow("permissionPromptId", null) { RtdbMappers.string(it.value) }

    // `.info/connected` is a local, always-readable node that flips with the
    // SDK's socket. Used to refuse allow/deny while offline: an offline write
    // is queued and replayed on reconnect, when it could hit another prompt.
    override val connected: Flow<Boolean> =
        pathFlow(".info/connected", false) { RtdbMappers.bool(it.value) }

    override val activity: Flow<String?> =
        pathFlow("activity", null) { RtdbMappers.string(it.value) }

    override val task: Flow<String?> =
        pathFlow("task", null) { RtdbMappers.string(it.value) }

    override val response: Flow<String?> =
        pathFlow("response", null) { RtdbMappers.string(it.value) }

    override val claudeStatus: Flow<ClaudeStatus?> =
        pathFlow("claudeStatus", null) { RtdbMappers.claudeStatus(it.value) }

    override val taskKind: Flow<TaskKind?> =
        pathFlow("taskKind", null) { TaskKind.fromRaw(RtdbMappers.string(it.value)) }

    override val headline: Flow<String?> =
        pathFlow("headline", null) { RtdbMappers.string(it.value) }

    override val toolEvents: Flow<List<ToolEvent>> =
        pathFlow("toolEvents", emptyList()) { snap ->
            RtdbMappers.list(snap.children.map { it.value }, RtdbMappers::toolEvent)
        }

    override val followups: Flow<List<String>> =
        pathFlow("followups", emptyList()) { snap ->
            RtdbMappers.followups(snap.children.map { it.value })
        }

    override val sharedSession: Flow<SharedSessionMeta?> =
        pathFlow("sharedSession", null) { RtdbMappers.sharedSession(it.value) }

    override val recentSessions: Flow<List<RecentSession>> =
        pathFlow("recentSessions", emptyList()) { snap ->
            RtdbMappers.list(snap.children.map { it.value }, RtdbMappers::recentSession)
        }

    // Sprint 4n — tap-to-claim result. Daemon writes /claimResult after
    // handling a watch-initiated claim; we observe it to drive the success
    // / error banner. Null = no recent claim or banner already dismissed.
    override val claimResult: Flow<ClaimResult?> =
        pathFlow("claimResult", null) { RtdbMappers.claimResult(it.value) }

    override val blocker: Flow<Blocker?> =
        pathFlow("blocker", null) { RtdbMappers.blocker(it.value) }

    override val outcome: Flow<RunOutcome?> =
        pathFlow("outcome", null) { RtdbMappers.outcome(it.value) }

    override val progress: Flow<RunProgress?> =
        pathFlow("progress", null) { RtdbMappers.progress(it.value) }

    override val conversationActive: Flow<Boolean> =
        pathFlow("conversationActive", false) { RtdbMappers.bool(it.value) }

    override suspend fun sendCommand(text: String, promptId: String?) {
        // Use Firebase server timestamp (not System.currentTimeMillis) so a
        // skewed device clock — including Wear OS emulators with drifted time —
        // can't make every command appear stale to the wrapper.
        val payload = buildMap<String, Any> {
            put("text", text)
            put("issuedAt", ServerValue.TIMESTAMP)
            if (promptId != null) put("promptId", promptId)
        }
        ref("command").setValue(payload).await()
    }

    override suspend fun sendPrompt(text: String, mode: PromptMode) {
        val payload = mapOf<String, Any>(
            "text" to text,
            "mode" to mode.wire,
            "issuedAt" to ServerValue.TIMESTAMP,
        )
        ref("prompt").setValue(payload).await()
    }

    // Sprint 4n — tap-to-claim. Writes /claimRequest with the (sessionId,
    // cwd) the user tapped on Page 5. Daemon consumes it and spawns
    // `cc --resume <sessionId>` in a new Mac Terminal via osascript.
    // Uses ServerValue.TIMESTAMP so the daemon's age check ignores
    // device clock skew.
    override suspend fun claimSession(sessionId: String, cwd: String) {
        val payload = mapOf<String, Any>(
            "sessionId" to sessionId,
            "cwd" to cwd,
            "issuedAt" to ServerValue.TIMESTAMP,
        )
        ref("claimRequest").setValue(payload).await()
    }

    // Watch-side ack: after the result banner auto-dismisses (or user taps
    // the close affordance) we null out /claimResult so a stale result
    // doesn't re-show on next listener reconnect.
    override suspend fun clearClaimResult() {
        ref("claimResult").setValue(null).await()
    }

    // Force-clear stale UI state directly from the watch — for when the
    // wrapper is dead but RTDB still shows status=RUNNING / sharedSession
    // populated / permissionPrompt set. The wrapper's own crash-cleanup
    // (onDisconnect) handles the normal case; this is the "I see the
    // phantom and want to dismiss it" recovery affordance, bound to the
    // long-press on the stop button.
    override suspend fun forceResetUi() {
        // Must match firebase-rules.json exactly: the watch may only write
        // status='IDLE' and null on the other six paths. Any extra path or
        // value fails the whole multi-path update with PERMISSION_DENIED.
        val updates = mapOf<String, Any?>(
            "status" to "IDLE",
            "sharedSession" to null,
            "permissionPrompt" to null,
            "permissionPromptId" to null,
            "command" to null,
            "activity" to null,
            "task" to null,
        )
        db.reference.updateChildren(updates).await()
    }

    /**
     * Current `.info/connected` value, read once. A single-value listener on
     * this node answers from local SDK state immediately; the timeout only
     * guards against a wedged SDK.
     */
    suspend fun isConnectedNow(timeoutMs: Long = 2_000): Boolean =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                ref(".info/connected").addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (cont.isActive) cont.resume(RtdbMappers.bool(snapshot.value))
                    }

                    override fun onCancelled(error: DatabaseError) {
                        if (cont.isActive) cont.resume(false)
                    }
                })
            }
        } ?: false

    /** Reads /permissionPromptId once (server when online, cache otherwise). */
    suspend fun fetchPermissionPromptId(): String? =
        RtdbMappers.string(ref("permissionPromptId").get().await().value)

    /** Reads status + prompt + id once. Null when the read fails or times out. */
    suspend fun fetchPermissionSnapshot(timeoutMs: Long = 6_000): PermissionSnapshot? =
        withTimeoutOrNull(timeoutMs) {
            runCatching {
                val root = db.reference
                val status = RtdbMappers.status(root.child("status").get().await().value)
                val prompt = RtdbMappers.string(root.child("permissionPrompt").get().await().value)
                val id = RtdbMappers.string(root.child("permissionPromptId").get().await().value)
                PermissionSnapshot(status, prompt, id)
            }.onFailure { Log.w(TAG, "fetchPermissionSnapshot failed: ${it.message}") }
                .getOrNull()
        }

    // A listener on `path`, mapped with `mapper`. Never fails downstream:
    //   • a mapper exception (malformed data) logs and emits `default`;
    //   • onCancelled (e.g. permission denied before anonymous auth lands)
    //     logs, emits `default`, and re-subscribes with exponential backoff
    //     (1s → 30s) instead of closing with an exception, which used to
    //     crash every stateIn collector.
    private fun <T> pathFlow(path: String, default: T, mapper: (DataSnapshot) -> T): Flow<T> =
        callbackFlow {
            val listener = object : ValueEventListener {
                override fun onDataChange(snap: DataSnapshot) {
                    val value = runCatching { mapper(snap) }.getOrElse { e ->
                        Log.w(TAG, "map /$path failed, using default: ${e.message}")
                        default
                    }
                    trySend(value)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.w(TAG, "listener /$path cancelled: ${error.message}")
                    trySend(default)
                    close(ListenerCancelled(path, error.message))
                }
            }
            val r = ref(path)
            r.addValueEventListener(listener)
            awaitClose { r.removeEventListener(listener) }
        }.retryWhen { cause, attempt ->
            if (cause is CancellationException) return@retryWhen false
            val backoff = (1_000L shl attempt.coerceAtMost(5).toInt()).coerceAtMost(30_000L)
            Log.w(TAG, "re-subscribing /$path in ${backoff}ms (${cause.message})")
            delay(backoff)
            true
        }

    private class ListenerCancelled(path: String, reason: String) :
        Exception("/$path cancelled: $reason")

    private companion object {
        const val TAG = "ccwearos-repo"
    }
}
