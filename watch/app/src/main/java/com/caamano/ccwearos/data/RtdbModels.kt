package com.caamano.ccwearos.data

import com.google.firebase.database.IgnoreExtraProperties

enum class WrapperStatus {
    IDLE,
    RUNNING,
    AWAITING_PERMISSION,
    OFFLINE,
}

@IgnoreExtraProperties
data class Metrics(
    val dailyTokens: Long = 0,
    val weeklyTokens: Long = 0,
    val monthlyTokens: Long = 0,
    val updatedAt: Long = 0,
)

@IgnoreExtraProperties
data class PendingCommand(
    val text: String = "",
    val issuedAt: Long = 0,
)

@IgnoreExtraProperties
data class ClaudeStatus(
    val model: String? = null,
    val contextSize: String? = null,
    val contextPct: Double? = null,
    val sessionPct: Double? = null,
    val sessionResets: String? = null,
    val weeklyPct: Double? = null,
    val weeklyResets: String? = null,
    val monthlyCost: String? = null,
    val monthlyResets: String? = null,
)
