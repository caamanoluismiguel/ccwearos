package com.caamano.ccwearos.data

// Pure mappers from the raw values Firebase hands back (`DataSnapshot.value`:
// String / Long / Double / Boolean / Map / List / null) to our models.
//
// Why not `snap.getValue(Model::class.java)`: the reflective CustomClassMapper
// throws on any type mismatch (a 1.5 written into a Long field, a string where
// a number was expected), and a throw inside a ValueEventListener used to tear
// down the whole StateFlow. Hand-written mapping tolerates every malformed
// shape by falling back to the field default, and it needs no reflection, so
// R8 can't break it either.
//
// Every function here is total: it never throws, whatever `raw` is.
object RtdbMappers {

    fun status(raw: Any?): WrapperStatus {
        val s = raw as? String ?: return WrapperStatus.OFFLINE
        return runCatching { WrapperStatus.valueOf(s) }.getOrDefault(WrapperStatus.OFFLINE)
    }

    fun string(raw: Any?): String? = raw as? String

    fun bool(raw: Any?): Boolean = raw as? Boolean ?: false

    fun metrics(raw: Any?): Metrics {
        val m = raw.asMap() ?: return Metrics()
        return Metrics(
            dailyTokens = m.long("dailyTokens"),
            weeklyTokens = m.long("weeklyTokens"),
            monthlyTokens = m.long("monthlyTokens"),
            updatedAt = m.long("updatedAt"),
        )
    }

    fun claudeStatus(raw: Any?): ClaudeStatus? {
        val m = raw.asMap() ?: return null
        return ClaudeStatus(
            model = m.str("model"),
            contextSize = m.str("contextSize"),
            contextPct = m.double("contextPct"),
            sessionPct = m.double("sessionPct"),
            sessionResets = m.str("sessionResets"),
            weeklyPct = m.double("weeklyPct"),
            weeklyResets = m.str("weeklyResets"),
            monthlyCost = m.str("monthlyCost"),
            monthlyResets = m.str("monthlyResets"),
        )
    }

    fun toolEvent(raw: Any?): ToolEvent? {
        val m = raw.asMap() ?: return null
        return ToolEvent(
            tool = m.str("tool") ?: "",
            arg = m.str("arg"),
            ts = m.long("ts"),
        )
    }

    fun sharedSession(raw: Any?): SharedSessionMeta? {
        val m = raw.asMap() ?: return null
        return SharedSessionMeta(
            sessionId = m.str("sessionId") ?: "",
            pid = m.long("pid"),
            cwd = m.str("cwd") ?: "",
            startedAt = m.long("startedAt"),
            kind = m.str("kind") ?: "",
            heartbeatAt = m.longOrNull("heartbeatAt"),
            ownerPid = m.longOrNull("ownerPid"),
        )
    }

    fun recentSession(raw: Any?): RecentSession? {
        val m = raw.asMap() ?: return null
        return RecentSession(
            sessionId = m.str("sessionId") ?: "",
            cwd = m.str("cwd") ?: "",
            projectName = m.str("projectName") ?: "",
            mtime = m.long("mtime"),
            active = m["active"] as? Boolean ?: false,
            shared = m["shared"] as? Boolean ?: false,
            lastUserMessage = m.str("lastUserMessage"),
        )
    }

    fun claimResult(raw: Any?): ClaimResult? {
        val m = raw.asMap() ?: return null
        return ClaimResult(
            ok = m["ok"] as? Boolean ?: false,
            reason = m.str("reason"),
            sessionId = m.str("sessionId") ?: "",
            ts = m.long("ts"),
        )
    }

    fun blocker(raw: Any?): Blocker? {
        val m = raw.asMap() ?: return null
        val kind = when (m.str("kind")) {
            "trust" -> BlockerKind.TRUST
            "login" -> BlockerKind.LOGIN
            "crash" -> BlockerKind.CRASH
            "timeout" -> BlockerKind.TIMEOUT
            else -> BlockerKind.OTHER
        }
        return Blocker(kind = kind, hint = m.str("hint") ?: "", cwd = m.str("cwd"), ts = m.long("ts"))
    }

    fun outcome(raw: Any?): RunOutcome? {
        val m = raw.asMap() ?: return null
        return RunOutcome(
            ok = m["ok"] as? Boolean ?: false,
            exitCode = m.long("exitCode"),
            ts = m.long("ts"),
            stopped = m["stopped"] as? Boolean ?: false,
        )
    }

    /** Children of a list-ish node, in the order given; non-matching entries dropped. */
    fun <T> list(children: Iterable<Any?>, item: (Any?) -> T?): List<T> =
        children.mapNotNull { runCatching { item(it) }.getOrNull() }

    fun followups(children: Iterable<Any?>): List<String> =
        children.mapNotNull { (it as? String)?.takeIf(String::isNotBlank) }.take(3)

    // --- helpers ------------------------------------------------------------

    private fun Any?.asMap(): Map<*, *>? = this as? Map<*, *>

    private fun Map<*, *>.str(key: String): String? = this[key] as? String

    private fun Map<*, *>.long(key: String): Long = when (val v = this[key]) {
        is Number -> {
            val d = v.toDouble()
            if (d.isFinite()) v.toLong() else 0L
        }
        is String -> v.toLongOrNull() ?: v.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong() ?: 0L
        else -> 0L
    }

    /** Like [long], but null when the key is absent or not a usable number. */
    private fun Map<*, *>.longOrNull(key: String): Long? = when (val v = this[key]) {
        is Number -> v.toDouble().takeIf { it.isFinite() }?.let { v.toLong() }
        is String -> v.toLongOrNull() ?: v.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()
        else -> null
    }

    private fun Map<*, *>.double(key: String): Double? = when (val v = this[key]) {
        is Number -> v.toDouble().takeIf { it.isFinite() }
        is String -> v.toDoubleOrNull()?.takeIf { it.isFinite() }
        else -> null
    }
}
