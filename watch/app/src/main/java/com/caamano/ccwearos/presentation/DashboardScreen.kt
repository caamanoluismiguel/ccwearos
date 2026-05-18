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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import com.caamano.ccwearos.data.ClaudeStatus
import com.caamano.ccwearos.data.Metrics
import com.caamano.ccwearos.data.TaskKind
import com.caamano.ccwearos.data.ToolEvent
import com.caamano.ccwearos.data.WrapperStatus
import java.text.NumberFormat

// ─────────────────────────────────────────────────────────────────────────────
// DASHBOARD — up to 4-page horizontal Pager IA
//
// Page 0 — COMMAND: glanceable status + primary action (ask button).
//           Button label is dynamic: "ask claude" cold, "continuar" when a
//           prior response exists and we're IDLE (the wrapper auto-continues
//           the session, so the same button extends the conversation).
// Page 1 — METRICS: daily token counter + session/weekly/cost data.
// Page 2 — RESPONSE: last Claude reply, scrollable. Only exists when there
//           is a response to read.
// Page 3 — FOLLOWUP: contextual "what next?" chips Claude suggested + a reset
//           button. Same trigger as Page 2 (hasResult).
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
    taskKind: TaskKind? = null,
    headline: String? = null,
    toolEvents: List<ToolEvent> = emptyList(),
    followups: List<String> = emptyList(),
    sentInSession: Boolean = false,
    onAsk: (String) -> Unit = {},
    onAskWithReset: (String) -> Unit = {},
) {
    // Pages 2 + 3 show up whenever there's anything worth showing — response
    // text OR a settled taskKind (an action task with no body text still needs
    // its ✓/✗ confirmation card and a "¿y ahora qué?" follow-up surface).
    val hasResponse = !response.isNullOrBlank()
    val hasResult = hasResponse || taskKind != null
    val pageCount = if (hasResult) 4 else 2

    // "Continuar" label only when ALL three are true:
    //   1. THIS app session sent a regular prompt (sentInSession) — so app
    //      reopens feel fresh ("ask claude") even if /response still in RTDB.
    //   2. A response actually came back (hasResponse).
    //   3. Wrapper is at rest (IDLE) — mid-run hides the button anyway.
    // askWithReset() flips sentInSession=false explicitly, so right after a
    // user-initiated reset the CTA also reverts to "ask claude".
    val inConversation = sentInSession && hasResponse && status == WrapperStatus.IDLE

    val pagerState = rememberPagerState(initialPage = 0) { pageCount }

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
                    toolEvents = toolEvents,
                    inConversation = inConversation,
                    onAsk = onAsk,
                )
                1 -> MetricsPage(
                    metrics = metrics,
                    claudeStatus = claudeStatus,
                )
                2 -> ResponsePage(
                    response = response,
                    taskKind = taskKind,
                    headline = headline,
                    toolEvents = toolEvents,
                )
                3 -> FollowupPage(
                    followups = followups,
                    onAsk = onAsk,
                    onAskWithReset = onAskWithReset,
                )
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
    toolEvents: List<ToolEvent>,
    inConversation: Boolean,
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

                // When a tool is actively running, prefer the tool-derived
                // activity verb (already synthesized in the daemon — comes
                // through `activity`) and show the tool's arg below as a
                // concrete progress hint ("parser.ts", "for dir in /tmp …").
                val latestTool = toolEvents.lastOrNull()
                if (status == WrapperStatus.RUNNING && !activity.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    ActivityLine(activity, leadingGlyph = latestTool?.toolGlyph())
                    if (!latestTool?.arg.isNullOrBlank()) {
                        Spacer(Modifier.height(1.dp))
                        Text(
                            text = latestTool.arg!!.take(36),
                            color = ClaudeDim.copy(alpha = 0.6f),
                            fontFamily = MonoFamily,
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
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
                    AskRow(inConversation = inConversation, onAsk = onAsk)
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
            // Big focal number — 28sp to claim the page's attention budget.
            BigTokens(metrics.dailyTokens, fontSize = 28.sp)
            Spacer(Modifier.height(10.dp))
            DividerLine()
            Spacer(Modifier.height(8.dp))
            if (claudeStatus != null && hasAnyData(claudeStatus)) {
                ClaudeStatusSection(claudeStatus)
            } else {
                StatusLine(metrics)
            }
            Spacer(Modifier.height(10.dp))
            // "$ metrics" demoted to a footer label — keeps the focal number
            // at the top and clarifies which page you're on without competing.
            Text(
                text = "$ metrics",
                color = ClaudeCoral.copy(alpha = 0.55f),
                fontFamily = MonoFamily,
                fontSize = 9.sp,
                letterSpacing = 0.8.sp,
            )
        }
    }
}

// ─── PAGE 2: RESPONSE ────────────────────────────────────────────────────────
// Dedicated scrollable reading surface. Scroll fade gradient at bottom signals
// that there's more to read. This page is the only place that scrolls.

// ─── PAGE 3: FOLLOWUP — "¿Y ahora qué?" ──────────────────────────────────────
// Contextual next-actions surface. Shows up after a response settles. Two
// tiers of options:
//   1. Claude-suggested chips (from /followups) — tap = send that text as the
//      next prompt; wrapper auto-continues the session via --continue.
//   2. A single fixed "↻ nueva conversación" button — explicit reset path
//      without having to remember the voice phrase. Routes to onAskWithReset
//      which prepends "nueva conversación, " before sending /prompt.
// Note: there is NO "continuar" button here. Page 0's CTA already plays that
// role (it re-labels to "continuar" when inConversation = true).

@Composable
private fun FollowupPage(
    followups: List<String>,
    onAsk: (String) -> Unit,
    onAskWithReset: (String) -> Unit,
) {
    val resetLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val text = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) onAskWithReset(text)
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxSize(fraction = 0.78f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = "¿y ahora qué?",
                color = ClaudeDim,
                fontFamily = MonoFamily,
                fontSize = 11.sp,
                letterSpacing = 0.8.sp,
            )
            Spacer(Modifier.height(10.dp))

            if (followups.isNotEmpty()) {
                followups.forEach { suggestion ->
                    FollowupChip(text = suggestion, onTap = { onAsk(suggestion) })
                    Spacer(Modifier.height(6.dp))
                }
                Spacer(Modifier.height(4.dp))
                DividerLine()
                Spacer(Modifier.height(10.dp))
            }

            // Reset is always available — explicit way to start fresh without
            // remembering "nueva conversación" as a voice phrase.
            Button(
                onClick = {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                        )
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Nueva conversación")
                    }
                    resetLauncher.launch(intent)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = ClaudeAmber,
                ),
                border = BorderStroke(1.dp, ClaudeAmber.copy(alpha = 0.55f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "↻ nueva conversación",
                    fontFamily = MonoFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun FollowupChip(text: String, onTap: () -> Unit) {
    Button(
        onClick = onTap,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White.copy(alpha = 0.07f),
            contentColor = Color.White,
        ),
        border = BorderStroke(1.dp, ClaudeCoral.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 38.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Default,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.Normal,
            color = Color.White.copy(alpha = 0.92f),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ResponsePage(
    response: String?,
    taskKind: TaskKind?,
    headline: String?,
    toolEvents: List<ToolEvent>,
) {
    when (taskKind) {
        TaskKind.ACTION -> ActionResultLayout(response, toolEvents)
        TaskKind.INFO -> InfoResultLayout(response, headline)
        null -> {
            // Pre-classification or no-result state: show what we have.
            if (response.isNullOrBlank()) {
                EmptyResultLayout()
            } else {
                InfoResultLayout(response, headline)
            }
        }
    }
}

// ─── ACTION VARIANT ──────────────────────────────────────────────────────────
// "Did it work?" focal layout: large ✓ or ✗ + 1-line outcome + tool chips.
// No scrolling body — the user just needs to know the action settled.

@Composable
private fun ActionResultLayout(
    response: String?,
    toolEvents: List<ToolEvent>,
) {
    val failed = remember(response) {
        response?.let { r ->
            Regex("\\b(error|failed|canceled|cancelled|denied|aborted)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(r)
        } ?: false
    }
    val outcomeLine = remember(response) {
        response
            ?.split(Regex("\\n{2,}"))
            ?.map { it.trim() }
            ?.lastOrNull { it.isNotBlank() }
            ?.replace(Regex("^\\*?\\*?TL;?DR:?\\*?\\*?\\s*[:—-]?\\s*", RegexOption.IGNORE_CASE), "")
            ?.take(140)
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxSize(fraction = 0.72f),
            verticalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (failed) "✗" else "✓",
                color = if (failed) ClaudeRed else ClaudeGreen,
                fontFamily = MonoFamily,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            if (!outcomeLine.isNullOrBlank()) {
                Text(
                    text = renderMarkdownInline(outcomeLine),
                    color = Color.White.copy(alpha = 0.92f),
                    fontFamily = FontFamily.Default,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
            }
            if (toolEvents.isNotEmpty()) {
                ToolChipRow(toolEvents)
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun ToolChipRow(events: List<ToolEvent>) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        items(events.size) { i ->
            val ev = events[i]
            Card(
                onClick = { /* no-op for now; chip is decorative */ },
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.Transparent,
                    contentColor = ClaudeCoral,
                ),
                border = BorderStroke(1.dp, ClaudeCoral.copy(alpha = 0.55f)),
                modifier = Modifier.heightIn(min = 22.dp),
            ) {
                Text(
                    text = "${ev.toolGlyph()} ${ev.shortLabel()}",
                    color = ClaudeCoral.copy(alpha = 0.92f),
                    fontFamily = MonoFamily,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

// ─── INFO VARIANT ────────────────────────────────────────────────────────────
// TL;DR-first layout. Big headline up top, scrollable details below.

@Composable
private fun InfoResultLayout(response: String?, headline: String?) {
    val scroll = rememberScrollState()
    // Compute headline + body once per response change.
    val finalHeadline = remember(headline, response) {
        when {
            !headline.isNullOrBlank() -> headline.take(120)
            response.isNullOrBlank() -> null
            else -> {
                // Fallback: first sentence of the response.
                val cleaned = response.replace(
                    Regex("^\\s*\\*{0,2}TL;?DR:?\\*{0,2}\\s*[:—-]?\\s*", RegexOption.IGNORE_CASE),
                    "",
                )
                cleaned.split(Regex("\\.\\s+|\\n\\n")).firstOrNull()?.trim()?.take(120)
            }
        }
    }
    val body = remember(response, finalHeadline) {
        response
            ?.replace(
                Regex(
                    "^\\s*\\*{0,2}TL;?DR:?\\*{0,2}\\s*[:—-]?\\s*[^\\n]+\\n?",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            )
            ?.trim()
            .orEmpty()
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxSize(fraction = 0.72f)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = "$ tl;dr",
                color = ClaudeCoral.copy(alpha = 0.75f),
                fontFamily = MonoFamily,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.2.sp,
            )
            Spacer(Modifier.height(6.dp))
            if (!finalHeadline.isNullOrBlank()) {
                Text(
                    text = finalHeadline,
                    color = Color.White,
                    fontFamily = FontFamily.Default,
                    fontSize = 18.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                DividerLine()
                Spacer(Modifier.height(8.dp))
            }
            if (body.isNotBlank()) {
                Text(
                    text = renderMarkdownInline(body),
                    color = Color.White.copy(alpha = 0.88f),
                    fontFamily = FontFamily.Default,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Normal,
                )
            }
            Spacer(Modifier.height(32.dp))
        }

        // Scroll fade — masks text behind the indicator dots.
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

@Composable
private fun EmptyResultLayout() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxSize(fraction = 0.72f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "$ no output yet",
                color = ClaudeDim.copy(alpha = 0.5f),
                fontFamily = MonoFamily,
                fontSize = 11.sp,
            )
        }
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
private fun ActivityLine(activity: String, leadingGlyph: String? = null) {
    // NOVA: Fade-crossfade on content change (220ms in / 180ms out).
    // 12sp up from 10sp — now the dominant text on the Command page since
    // BigTokens has been moved to its own Metrics page.
    val glyph = leadingGlyph ?: "✻"
    AnimatedContent(
        targetState = "$glyph $activity",
        transitionSpec = {
            fadeIn(animationSpec = tween(220)) togetherWith
                fadeOut(animationSpec = tween(180))
        },
        label = "activity",
    ) { value ->
        Text(
            text = value,
            color = ClaudeCoral.copy(alpha = 0.85f),
            fontFamily = MonoFamily,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// Map a tool name to a single-char glyph that fits the terminal aesthetic.
// Used on both the Command page (activity verb prefix) and the Action result
// page (chip leaders).
private fun ToolEvent.toolGlyph(): String = when (tool.replace("\\s+".toRegex(), "")) {
    "Bash" -> "⌘"
    "Edit", "Write" -> "✎"
    "Read" -> "▤"
    "Grep" -> "⌕"
    "Glob" -> "⌕"
    "WebFetch", "WebSearch" -> "⊕"
    "Task" -> "▦"
    else -> "▪"
}

private fun ToolEvent.shortLabel(): String = when (tool.replace("\\s+".toRegex(), "")) {
    "Bash" -> "bash"
    "Edit" -> "edit"
    "Write" -> "write"
    "Read" -> "read"
    "Grep" -> "grep"
    "Glob" -> "glob"
    "WebFetch" -> "fetch"
    "WebSearch" -> "search"
    "Task" -> "task"
    else -> tool.lowercase()
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
private fun BigTokens(value: Long, fontSize: androidx.compose.ui.unit.TextUnit = 24.sp) {
    val target = value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val animated by animateIntAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "today-tokens",
    )
    // ARIA: BigTokens is the focal element on the Metrics page — Bold + large.
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = NumberFormat.getIntegerInstance().format(animated),
            color = Color.White,
            fontFamily = MonoFamily,
            fontSize = fontSize,
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
private fun AskRow(
    inConversation: Boolean = false,
    onAsk: (String) -> Unit,
) {
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
    // Dual-label CTA: cold-start says "ask claude"; once a response exists and
    // we're IDLE, the wrapper will auto-continue via `claude --continue`, so we
    // re-label to "continuar" to signal "this is a thread, not a fresh ask".
    val label = if (inConversation) "continuar" else "ask claude"
    val voicePrompt = if (inConversation) "Continuar conversación" else "Ask Claude"
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
                    putExtra(RecognizerIntent.EXTRA_PROMPT, voicePrompt)
                }
                launcher.launch(intent)
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = ClaudeCoral,
                contentColor = Color.Black,
            ),
            modifier = Modifier.size(width = 140.dp, height = 48.dp),
        ) {
            Text(
                text = label,
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
