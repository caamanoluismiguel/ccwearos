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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.Text
import com.caamano.ccwearos.data.ClaudeStatus
import com.caamano.ccwearos.data.Metrics
import com.caamano.ccwearos.data.WrapperStatus
import java.text.NumberFormat

// ─────────────────────────────────────────────────────────────────────────────
// DASHBOARD — 3-page horizontal Pager IA
//
// Page 0 — COMMAND: glanceable status + primary action (ask button).
//           This is what you see every time you raise your wrist. Zero scroll.
// Page 1 — METRICS: daily token counter + session/weekly/cost data.
// Page 2 — RESPONSE: last Claude reply, scrollable. Only exists when there
//           is a response to read — keeps the indicator at 2 dots otherwise.
//
// HorizontalPagerScaffold handles HorizontalPageIndicator (dots, BottomCenter)
// automatically without any additional composables.
// ─────────────────────────────────────────────────────────────────────────────

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
    // KAI: Page count is reactive — response page only appears when there's
    // something to read. The pager state key on pageCount ensures it recomposes
    // correctly when the count changes (e.g. response arrives mid-session).
    val hasResponse = !response.isNullOrBlank()
    val pageCount = if (hasResponse) 3 else 2

    val pagerState = rememberPagerState(initialPage = 0) { pageCount }

    // ARIA: HorizontalPagerScaffold is the Wear Material3 scaffold that places
    // HorizontalPageIndicator at BottomCenter and coordinates its show/hide
    // with paging transitions. We get the dots for free.
    HorizontalPagerScaffold(pagerState = pagerState) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            when (page) {
                0 -> CommandPage(
                    status = status,
                    activity = activity,
                    task = task,
                    onAsk = onAsk,
                )
                1 -> MetricsPage(
                    metrics = metrics,
                    claudeStatus = claudeStatus,
                )
                2 -> ResponsePage(response = response ?: "")
                else -> Box(Modifier.fillMaxSize())
            }
        }
    }
}

// ─── PAGE 0: COMMAND ─────────────────────────────────────────────────────────
// Full screen = glance + action. Everything visible at once, zero scroll.
// Layout: top-safe spacer → header → status → activity/task → spacer → button → bottom-safe
//
// NOVA: The ask button sits at a fixed bottom position inside a fillMaxSize Column
// with verticalArrangement = SpaceBetween, so the status cluster floats top-left
// (terminal feel) and the CTA anchors bottom-center. No FAB layering needed
// because there's no scroll to fight against.

@Composable
private fun CommandPage(
    status: WrapperStatus,
    activity: String?,
    task: String?,
    onAsk: (String) -> Unit,
) {
    // ARIA: Inscribed-square (0.72×) centers all content safely inside the round bezel.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxSize(fraction = 0.72f),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.Start,
        ) {
            // ── Top cluster: brand + status + live activity ──────────────────
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Spacer(Modifier.height(14.dp))
                ClaudeHeader()
                Spacer(Modifier.height(8.dp))
                StatusPrompt(status)

                // NOVA: AnimatedContent on activity fades old → new verb in 220ms.
                // Task shows below when available, single line + ellipsis so it
                // never pushes the button off screen.
                if (status == WrapperStatus.RUNNING && !activity.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    ActivityLine(activity)
                }
                if (!task.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    TaskLine(task)
                }
            }

            // ── Bottom cluster: ask button (IDLE only) ───────────────────────
            // ARIA: SpaceBetween pushes the button to the bottom of the column,
            // giving it a stable home regardless of how many status lines appear.
            // When not IDLE, an empty box holds the space so the top cluster
            // doesn't reflow on status change.
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (status == WrapperStatus.IDLE) {
                    AskRow(onAsk = onAsk)
                } else {
                    // KAI: Placeholder preserves button-zone height so the status
                    // cluster doesn't jump when transitioning IDLE ↔ RUNNING.
                    Spacer(Modifier.height(48.dp))
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

// ─── PAGE 1: METRICS ─────────────────────────────────────────────────────────
// The numbers. Daily token count (big), claude status line (detail).
// No scroll — fits comfortably in the inscribed square.

@Composable
private fun MetricsPage(
    metrics: Metrics,
    claudeStatus: ClaudeStatus?,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxSize(fraction = 0.72f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            // ARIA: Small coral label orients the user ("you're on the metrics page").
            Text(
                text = "$ metrics",
                color = ClaudeCoral.copy(alpha = 0.65f),
                fontFamily = MonoFamily,
                fontSize = 9.sp,
                letterSpacing = 0.8.sp,
            )
            Spacer(Modifier.height(10.dp))
            BigTokens(metrics.dailyTokens)
            Spacer(Modifier.height(10.dp))
            DividerLine()
            Spacer(Modifier.height(8.dp))
            if (claudeStatus != null && hasAnyData(claudeStatus)) {
                ClaudeStatusSection(claudeStatus)
            } else {
                StatusLine(metrics)
            }
        }
    }
}

// ─── PAGE 2: RESPONSE ────────────────────────────────────────────────────────
// Dedicated scrollable reading surface. Scroll fade gradient at bottom signals
// that there's more to read. This page is the only place that scrolls.

@Composable
private fun ResponsePage(response: String) {
    val scroll = rememberScrollState()

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxSize(fraction = 0.72f)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Spacer(Modifier.height(14.dp))
            ResponseSection(response)
            // ZERO: Bottom padding so the last line of text clears the page
            // indicator dots (which sit at BottomCenter, roughly 24dp from edge).
            Spacer(Modifier.height(32.dp))
        }

        // NOVA: Scroll fade gradient. Masks text behind the page indicator zone
        // and signals "more below" when content is long. GPU-composited overlay.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.75f to Color.Transparent,
                            0.90f to Color.Black,
                            1.0f to Color.Black,
                        ),
                    ),
                ),
        )
    }
}

// ─── SHARED COMPOSABLES ───────────────────────────────────────────────────────

@Composable
private fun ClaudeHeader() {
    // Brand anchor: mascot + "claude code" mono label — present on every page
    // that needs identity (Command page). 10sp keeps it small and unobtrusive.
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
    // NOVA: Fade-crossfade on content change (220ms in / 180ms out).
    // 12sp up from 10sp — now the dominant text on the Command page since
    // BigTokens has been moved to its own Metrics page.
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
            fontSize = 12.sp,
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
        maxLines = 2,
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
    // ARIA: BigTokens is the focal element on the Metrics page — 24sp Bold.
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
    // ARIA: Same design as v1 — "$ OUTPUT" label as section header, 12sp body,
    // 1.5× line-height, paragraphs split on double-newline.
    val paragraphs = response.split(Regex("\\n{2,}")).filter { it.isNotBlank() }

    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Text(
            text = "$ output",
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

// Minimal inline markdown: **bold**, *italic*, `code`. Block-level ignored.
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
