package com.caamano.ccwearos.presentation

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Text
import com.caamano.ccwearos.data.ClaudeStatus
import com.caamano.ccwearos.data.Metrics
import com.caamano.ccwearos.data.WrapperStatus
import java.text.NumberFormat

@Composable
fun DashboardScreen(
    status: WrapperStatus,
    metrics: Metrics,
    activity: String? = null,
    task: String? = null,
    response: String? = null,
    claudeStatus: ClaudeStatus? = null,
    onAsk: (String) -> Unit = {},
) {
    val scroll = rememberScrollState()

    // ARIA: Outer Box centers the inscribed-square content column on the round display.
    // The gradient overlay at the bottom signals scrollability without any interactive
    // element — a purely visual affordance that costs nothing in touch target space.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxSize(fraction = 0.72f)
                .verticalScroll(scroll),
            // ARIA: 8dp spacing grid — consistent vertical rhythm between all sections.
            verticalArrangement = Arrangement.spacedBy(0.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            // Top safe padding: round bezel clips heavily at the very top edge.
            Spacer(Modifier.height(16.dp))
            ClaudeHeader()
            Spacer(Modifier.height(8.dp))
            StatusPrompt(status)
            if (status == WrapperStatus.RUNNING && !activity.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                ActivityLine(activity)
            }
            if (!task.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                TaskLine(task)
            }
            // ARIA: 16dp before the focal "big number" — creates a visual pause
            // that anchors the eye to the token count as the primary glanceable.
            Spacer(Modifier.height(16.dp))
            BigTokens(metrics.dailyTokens)
            Spacer(Modifier.height(8.dp))
            DividerLine()
            Spacer(Modifier.height(6.dp))
            // When Claude is actively running, show Claude's own status-line
            // numbers (session %, weekly %, monthly $) — that's what the user
            // sees in the terminal. Falls back to our rolling-window tokens
            // when claudeStatus is absent (idle, or hasn't surfaced yet).
            if (claudeStatus != null && hasAnyData(claudeStatus)) {
                ClaudeStatusSection(claudeStatus)
            } else {
                StatusLine(metrics)
            }

            if (!response.isNullOrBlank()) {
                // ARIA: 16dp gap before response section creates a clear zone break —
                // "above = status, below = content I need to read".
                Spacer(Modifier.height(16.dp))
                DividerLine()
                Spacer(Modifier.height(8.dp))
                ResponseSection(response)
            }
            // "Ask Claude" mic button — only when idle, so it doesn't disrupt
            // someone reading the response. Center it to invite the tap.
            if (status == WrapperStatus.IDLE) {
                Spacer(Modifier.height(14.dp))
                AskRow(onAsk = onAsk)
            }
            // Bottom safe padding: matches top, prevents last line from clipping at bezel.
            Spacer(Modifier.height(24.dp))
        }

        // NOVA: Scroll affordance — a 32dp fade from black at the bottom edge.
        // GPU-composited (Brush draws as a single layer), zero touch interception.
        // Only shown when there IS a response to scroll through.
        if (!response.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Transparent,
                                0.82f to Color.Transparent,
                                1.0f to Color.Black,
                            ),
                        ),
                    ),
            )
        }
    }
}

@Composable
private fun ClaudeHeader() {
    // ARIA: Header row unchanged — mascot + "claude code" mono label is the brand anchor.
    // 10sp mono with letter spacing reads correctly at the top of the inscribed square.
    Row(verticalAlignment = Alignment.CenterVertically) {
        ClaudeMascot(width = 18.dp)
        Spacer(Modifier.padding(horizontal = 4.dp))
        Text(
            text = "claude code",
            color = ClaudeCoral,
            fontFamily = MonoFamily,
            fontSize = 10.sp,
            letterSpacing = 0.5.sp,
        )
    }
}

@Composable
private fun StatusPrompt(status: WrapperStatus) {
    val (label, color) = when (status) {
        WrapperStatus.IDLE -> "idle" to Color.White.copy(alpha = 0.7f)
        WrapperStatus.RUNNING -> "running" to ClaudeGreen
        WrapperStatus.AWAITING_PERMISSION -> "prompt" to ClaudeAmber
        WrapperStatus.OFFLINE -> "offline" to ClaudeRed
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$ $label",
            color = color,
            fontFamily = MonoFamily,
            fontSize = 11.sp,
        )
        Spacer(Modifier.padding(horizontal = 1.dp))
        BlinkingCursor(color = color, fontSize = 11.sp)
    }
}

@Composable
private fun ActivityLine(activity: String) {
    AnimatedContent(
        targetState = activity,
        transitionSpec = {
            fadeIn(animationSpec = tween(220)) togetherWith
                fadeOut(animationSpec = tween(180))
        },
        label = "activity",
    ) { value ->
        Text(
            text = "✻ $value",
            color = ClaudeCoral.copy(alpha = 0.85f),
            fontFamily = MonoFamily,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TaskLine(task: String) {
    Text(
        text = task.lowercase(),
        color = Color.White.copy(alpha = 0.62f),
        fontFamily = MonoFamily,
        fontSize = 9.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun BigTokens(value: Long) {
    val target = value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val animated by animateIntAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "today-tokens",
    )
    // ARIA: BigTokens is the primary glanceable — 24sp Bold makes it the undisputed
    // focal point. FontWeight.Light was invisible on AMOLED at arm's length.
    // The "tokens · today" label stays small to not compete with the number.
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = NumberFormat.getIntegerInstance().format(animated),
            color = Color.White,
            fontFamily = MonoFamily,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "tokens · today",
            color = ClaudeDim.copy(alpha = 0.55f),
            fontFamily = MonoFamily,
            fontSize = 9.sp,
        )
    }
}

@Composable
private fun DividerLine() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(ClaudeCoral.copy(alpha = 0.35f)),
    )
}

@Composable
private fun StatusLine(metrics: Metrics) {
    Text(
        text = "w ${shortNum(metrics.weeklyTokens)}  ·  m ${shortNum(metrics.monthlyTokens)}",
        color = ClaudeDim.copy(alpha = 0.65f),
        fontFamily = MonoFamily,
        fontSize = 9.sp,
    )
}

@Composable
private fun ResponseSection(response: String) {
    // ARIA: ResponseSection is the most information-dense area. Design goals:
    //  1. "$ OUTPUT" label reads as a section header, not inline text — uppercase + tracking.
    //  2. Body text is 12sp Normal (NOT Light) — FontWeight.Light at small sizes on AMOLED
    //     disappears. Normal is the minimum legible weight at arm's length.
    //  3. 18sp lineHeight on 12sp text = 1.5× ratio. Canonical readable body line-height.
    //  4. Paragraphs split on double-newline get 8dp vertical spacing between them —
    //     this breaks the "wall of text" problem without adding markdown complexity.
    //  5. Sans-serif (FontFamily.Default) over mono for prose — the system font on Wear OS
    //     (Roboto Condensed) is specifically tuned for small round displays.
    //  6. Left-aligned: reading direction, not center-aligned which creates ragged left edge.
    //  7. No horizontal padding reduction — maintain the full inscribed-square width budget.
    val paragraphs = response.split(Regex("\\n{2,}")).filter { it.isNotBlank() }

    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        // Section label: mono, uppercase, coral, tracked — reads as a divider label.
        Text(
            text = "$ OUTPUT",
            color = ClaudeCoral.copy(alpha = 0.75f),
            fontFamily = MonoFamily,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.2.sp,
        )
        Spacer(Modifier.height(8.dp))

        if (paragraphs.size > 1) {
            paragraphs.forEachIndexed { index, paragraph ->
                Text(
                    text = renderMarkdownInline(paragraph.trim()),
                    color = Color.White.copy(alpha = 0.90f),
                    fontFamily = FontFamily.Default,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Normal,
                )
                if (index < paragraphs.lastIndex) {
                    Spacer(Modifier.height(8.dp))
                }
            }
        } else {
            Text(
                text = renderMarkdownInline(response.trim()),
                color = Color.White.copy(alpha = 0.90f),
                fontFamily = FontFamily.Default,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Normal,
            )
        }
    }
}

// Minimal inline markdown renderer for the response section. Handles **bold**,
// *italic*, and `code`. Keeps everything else as plain text — no block-level
// markdown on a watch.
private fun renderMarkdownInline(text: String): AnnotatedString = buildAnnotatedString {
    val pattern = Regex("""(\*\*([^*]+?)\*\*|\*([^*\n]+?)\*|`([^`\n]+?)`)""")
    var cursor = 0
    for (m in pattern.findAll(text)) {
        if (m.range.first > cursor) {
            append(text.substring(cursor, m.range.first))
        }
        when {
            m.groupValues[2].isNotEmpty() ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(m.groupValues[2])
                }
            m.groupValues[3].isNotEmpty() ->
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(m.groupValues[3])
                }
            m.groupValues[4].isNotEmpty() ->
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = Color.White.copy(alpha = 0.08f),
                    ),
                ) {
                    append(" ${m.groupValues[4]} ")
                }
        }
        cursor = m.range.last + 1
    }
    if (cursor < text.length) {
        append(text.substring(cursor))
    }
}

@Composable
private fun AskRow(onAsk: (String) -> Unit) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val text = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) onAsk(text)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Button(
            onClick = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask Claude")
                }
                launcher.launch(intent)
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = ClaudeCoral,
                contentColor = Color.Black,
            ),
            modifier = Modifier.size(width = 120.dp, height = 48.dp),
        ) {
            Text(
                text = "ask claude",
                fontFamily = MonoFamily,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
            )
        }
    }
}

private fun hasAnyData(s: ClaudeStatus): Boolean =
    s.model != null ||
        s.sessionPct != null ||
        s.weeklyPct != null ||
        s.monthlyCost != null

@Composable
private fun ClaudeStatusSection(s: ClaudeStatus) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Model + context size — top row, coral, small mono header style.
        val modelLine = buildString {
            if (s.model != null) append(s.model.lowercase())
            if (s.contextSize != null) {
                if (isNotEmpty()) append(" · ")
                append(s.contextSize.lowercase()).append(" ctx")
            }
        }
        if (modelLine.isNotEmpty()) {
            Text(
                text = modelLine,
                color = ClaudeCoral.copy(alpha = 0.85f),
                fontFamily = MonoFamily,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.4.sp,
            )
        }

        // Session + weekly usage on one line if both available.
        val usageParts = buildList {
            if (s.sessionPct != null) add("session ${formatPct(s.sessionPct)}")
            if (s.weeklyPct != null) add("weekly ${formatPct(s.weeklyPct)}")
        }
        if (usageParts.isNotEmpty()) {
            Text(
                text = usageParts.joinToString("  ·  "),
                color = Color.White.copy(alpha = 0.78f),
                fontFamily = MonoFamily,
                fontSize = 10.sp,
            )
        }

        // Monthly cost + reset date.
        if (s.monthlyCost != null) {
            val tail = if (!s.monthlyResets.isNullOrBlank()) {
                " · resets ${s.monthlyResets}"
            } else ""
            Text(
                text = "${s.monthlyCost}$tail",
                color = ClaudeDim.copy(alpha = 0.72f),
                fontFamily = MonoFamily,
                fontSize = 9.sp,
            )
        }
    }
}

private fun formatPct(v: Double): String {
    val rounded = if (v >= 10.0) v.toInt().toString() else "%.1f".format(v).trimEnd('0').trimEnd('.')
    return "$rounded%"
}
