package com.caamano.ccwearos.data

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class CcwearosRepository(
    private val db: FirebaseDatabase = FirebaseDatabase.getInstance(),
) {
    private fun ref(path: String): DatabaseReference = db.getReference(path)

    val status: Flow<WrapperStatus> = pathFlow("status") { snap ->
        val raw = snap.getValue(String::class.java)
        runCatching { WrapperStatus.valueOf(raw ?: "OFFLINE") }
            .getOrDefault(WrapperStatus.OFFLINE)
    }

    val metrics: Flow<Metrics> = pathFlow("metrics") { snap ->
        snap.getValue(Metrics::class.java) ?: Metrics()
    }

    val permissionPrompt: Flow<String?> = pathFlow("permissionPrompt") { snap ->
        snap.getValue(String::class.java)
    }

    val activity: Flow<String?> = pathFlow("activity") { snap ->
        snap.getValue(String::class.java)
    }

    val task: Flow<String?> = pathFlow("task") { snap ->
        snap.getValue(String::class.java)
    }

    val response: Flow<String?> = pathFlow("response") { snap ->
        snap.getValue(String::class.java)
    }

    val claudeStatus: Flow<ClaudeStatus?> = pathFlow("claudeStatus") { snap ->
        snap.getValue(ClaudeStatus::class.java)
    }

    val taskKind: Flow<TaskKind?> = pathFlow("taskKind") { snap ->
        TaskKind.fromRaw(snap.getValue(String::class.java))
    }

    val headline: Flow<String?> = pathFlow("headline") { snap ->
        snap.getValue(String::class.java)
    }

    val toolEvents: Flow<List<ToolEvent>> = pathFlow("toolEvents") { snap ->
        val list = mutableListOf<ToolEvent>()
        for (child in snap.children) {
            child.getValue(ToolEvent::class.java)?.let { list.add(it) }
        }
        list
    }

    val followups: Flow<List<String>> = pathFlow("followups") { snap ->
        val list = mutableListOf<String>()
        for (child in snap.children) {
            child.getValue(String::class.java)?.takeIf { it.isNotBlank() }?.let(list::add)
            if (list.size >= 3) break
        }
        list
    }

    val sharedSession: Flow<SharedSessionMeta?> = pathFlow("sharedSession") { snap ->
        snap.getValue(SharedSessionMeta::class.java)
    }

    val recentSessions: Flow<List<RecentSession>> = pathFlow("recentSessions") { snap ->
        val list = mutableListOf<RecentSession>()
        for (child in snap.children) {
            child.getValue(RecentSession::class.java)?.let(list::add)
        }
        list
    }

    suspend fun sendCommand(text: String) {
        // Use Firebase server timestamp (not System.currentTimeMillis) so a
        // skewed device clock — including Wear OS emulators with drifted time —
        // can't make every command appear stale to the wrapper.
        val payload = mapOf<String, Any>(
            "text" to text,
            "issuedAt" to ServerValue.TIMESTAMP,
        )
        ref("command").setValue(payload).await()
    }

    suspend fun sendPrompt(text: String) {
        val payload = mapOf<String, Any>(
            "text" to text,
            "issuedAt" to ServerValue.TIMESTAMP,
        )
        ref("prompt").setValue(payload).await()
    }

    private fun <T> pathFlow(path: String, mapper: (DataSnapshot) -> T): Flow<T> =
        callbackFlow {
            val listener = object : ValueEventListener {
                override fun onDataChange(snap: DataSnapshot) {
                    trySend(mapper(snap))
                }

                override fun onCancelled(error: DatabaseError) {
                    close(error.toException())
                }
            }
            val r = ref(path)
            r.addValueEventListener(listener)
            awaitClose { r.removeEventListener(listener) }
        }
}
