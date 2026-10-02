package com.caamano.ccwearos.presentation.home

import android.content.Context
import com.caamano.ccwearos.data.Blocker

// A dismissed blocker stays dismissed across app launches: its /blocker ts is
// written to SharedPreferences (last [MAX_DISMISSED_BLOCKERS] only), so closing
// "Claude necesita tu Mac" once means it never re-shows or re-buzzes, even if
// the wrapper leaves the node set. Blockers older than [BLOCKER_MAX_AGE_MS] are
// never shown at all: by then the run they belong to is long gone.

/** Persistence for dismissed blocker ts values. */
interface BlockerDismissalStore {
    fun load(): List<Long>
    fun save(ts: List<Long>)
}

/** Process-only store: the default for tests and before [BlockerDismissals.init]. */
class InMemoryBlockerDismissalStore : BlockerDismissalStore {
    private var values: List<Long> = emptyList()
    override fun load(): List<Long> = values
    override fun save(ts: List<Long>) {
        values = ts
    }
}

private class PrefsBlockerDismissalStore(context: Context) : BlockerDismissalStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): List<Long> = parseDismissed(prefs.getString(KEY, null))

    override fun save(ts: List<Long>) {
        prefs.edit().putString(KEY, ts.joinToString(",")).apply()
    }

    private companion object {
        const val PREFS = "ccwearos_blockers"
        const val KEY = "dismissed_ts"
    }
}

/** Process-wide store, bound to SharedPreferences in Application.onCreate. */
object BlockerDismissals {
    @Volatile
    var store: BlockerDismissalStore = InMemoryBlockerDismissalStore()
        private set

    fun init(context: Context) {
        store = PrefsBlockerDismissalStore(context)
    }
}

const val MAX_DISMISSED_BLOCKERS = 10

/** A blocker this old is history, not news. */
const val BLOCKER_MAX_AGE_MS = 6 * 60 * 60_000L

/**
 * Whether a blocker may show: not dismissed, and not older than
 * [BLOCKER_MAX_AGE_MS]. A ts in the future (Mac clock ahead) counts as fresh.
 */
fun blockerVisible(b: Blocker, dismissedTs: Set<Long>, nowMs: Long): Boolean {
    if (b.ts in dismissedTs) return false
    return nowMs - b.ts <= BLOCKER_MAX_AGE_MS
}

/** Appends [ts] (moving it to the end if present) and keeps the newest [keep]. */
internal fun addDismissal(existing: List<Long>, ts: Long, keep: Int = MAX_DISMISSED_BLOCKERS): List<Long> =
    (existing.filter { it != ts } + ts).takeLast(keep)

internal fun parseDismissed(raw: String?): List<Long> =
    raw?.split(',')?.mapNotNull { it.trim().toLongOrNull() }.orEmpty()
