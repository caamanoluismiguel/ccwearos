package com.caamano.ccwearos.presentation.result

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.tooling.preview.devices.WearDevices
import com.caamano.ccwearos.R
import com.caamano.ccwearos.data.RunOutcome
import com.caamano.ccwearos.data.TaskKind
import com.caamano.ccwearos.data.ToolEvent
import com.caamano.ccwearos.presentation.theme.CCWEAROSTheme
import com.caamano.ccwearos.presentation.theme.CcPalette
import com.caamano.ccwearos.presentation.theme.StatusColors
import com.caamano.ccwearos.presentation.ui.HairlineDivider
import com.caamano.ccwearos.presentation.ui.MonoLabel
import com.caamano.ccwearos.presentation.ui.Motion
import com.caamano.ccwearos.presentation.ui.PixelIcons
import com.caamano.ccwearos.presentation.ui.pixelIcon
import com.caamano.ccwearos.presentation.ui.pressFeedback
import com.caamano.ccwearos.presentation.ui.rememberReducedMotion
import kotlinx.coroutines.delay

// CONTRACT: the always-present "Resultado" page, minimal on purpose.
// First screenful = the TL;DR card (✓/✗ glyph + ≤3 lines) and at most 3
// short bullets when the answer has a list; Claude's chips (≤2, ≤24 chars).
// Everything else (full body, code, tables, tool trail) sits behind ONE
// "Ver detalle" row. Continuing the thread lives here: "Seguir esta
// conversación" (dictation, mode CONTINUE) when a thread is active, and the
// chips (also CONTINUE). Inicio's Preguntar always starts a new one.
// `onBlockedContent` fires when the response looks like TUI junk, so the
// shell can show the BlockedScreen instead of rendering it.
@Composable
fun ResultPage(
    headline: String?,
    response: String?,
    taskKind: TaskKind?,
    outcome: RunOutcome?,
    toolEvents: List<ToolEvent>,
    followups: List<String>,
    conversationActive: Boolean,
    onFollowup: (String) -> Unit,
    onContinue: () -> Unit,
    onBlockedContent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val prepared = remember(headline, response) { prepareResult(headline, response) }
    val blocked = prepared.blocked

    // Fire once per response that turns out to be junk.
    val latestOnBlocked by rememberUpdatedState(onBlockedContent)
    LaunchedEffect(response, blocked) {
        if (blocked) latestOnBlocked()
    }

    val first = remember(prepared, toolEvents.size) { firstScreenful(prepared.text, prepared.blocks, toolEvents.size) }
    val chips = remember(followups) { chipLabels(followups) }
    val status = resultStatus(outcome, taskKind)
    val isEmpty = !blocked && first.tldr == null && prepared.blocks.isEmpty() &&
        toolEvents.isEmpty() && outcome == null

    val key = responseKey(headline, response)
    val revealActive = rememberRevealActive(key, enabled = !isEmpty && !blocked)

    var detailOpen by rememberSaveable(key) { mutableStateOf(false) }
    var toolsExpanded by rememberSaveable(key) { mutableStateOf(false) }
    val expandedCode = remember(key) { mutableStateMapOf<Int, Boolean>() }

    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()

    // A new answer always starts at the top.
    LaunchedEffect(key) {
        if (listState.anchorItemIndex != 0) listState.scrollToItem(0)
    }

    ScreenScaffold(scrollState = listState, modifier = modifier) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(BLOCK_GAP),
            modifier = Modifier.fillMaxSize(),
        ) {
            var order = 0
            when {
                isEmpty -> item(key = "empty") { EmptyCard() }

                blocked -> Unit // The shell shows BlockedScreen.

                else -> {
                    val oSummary = order++
                    item(key = "summary") {
                        Reveal(revealActive, oSummary) { SummaryCard(first.tldr, status, outcome) }
                    }
                    if (first.bullets.isNotEmpty()) {
                        val o = order++
                        item(key = "bullets") {
                            Reveal(revealActive, o) { Bullets(first.bullets) }
                        }
                    }
                    if (conversationActive) {
                        val o = order++
                        item(key = "continue") {
                            Reveal(revealActive, o) {
                                FeedbackButton(
                                    text = stringResource(R.string.result_continue_conversation),
                                    icon = PixelIcons.Mic,
                                    style = ButtonStyle.OUTLINED,
                                    onClick = onContinue,
                                    transformation = SurfaceTransformation(spec),
                                    modifier = Modifier.transformedHeight(this, spec),
                                )
                            }
                        }
                    }
                    chips.forEachIndexed { i, (label, full) ->
                        val oi = order++
                        item(key = "chip-$i") {
                            Reveal(revealActive, oi) {
                                FeedbackButton(
                                    text = label,
                                    style = ButtonStyle.CHIP,
                                    onClick = { onFollowup(full) },
                                    transformation = SurfaceTransformation(spec),
                                    modifier = Modifier.transformedHeight(this, spec),
                                )
                            }
                        }
                    }
                    if (first.hasDetail) {
                        val o = order++
                        item(key = "detail-toggle") {
                            Reveal(revealActive, o) {
                                DetailRow(open = detailOpen, onToggle = { detailOpen = !detailOpen })
                            }
                        }
                    }
                    if (detailOpen) {
                        if (prepared.blocks.isNotEmpty()) {
                            item(key = "body") {
                                BodyBlocks(
                                    blocks = prepared.blocks,
                                    isCodeExpanded = { expandedCode[it] == true },
                                    onExpandCode = { expandedCode[it] = true },
                                )
                            }
                        }
                        if (toolEvents.isNotEmpty()) {
                            item(key = "tools") {
                                ToolTrail(
                                    events = toolEvents,
                                    expanded = toolsExpanded,
                                    onExpand = { toolsExpanded = true },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Owner rule: 12dp between blocks; one weight hierarchy. */
private val BLOCK_GAP = 12.dp

private object ResultType {
    val title = 18.sp
    val titleLine = 22.sp
    val body = 14.sp
    val bodyLine = 18.sp
    val meta = 12.sp
    val metaLine = 16.sp
}

// ─── Reveal ──────────────────────────────────────────────────────────────────

private const val REVEAL_MAX_STAGGERED = 8
private const val REVEAL_WINDOW_MS = 1_200L

/**
 * True while a freshly arrived response should play its staggered entrance.
 * Saved per response key, so paging away and back doesn't replay it. Always
 * false with reduced motion (and in previews).
 */
@Composable
private fun rememberRevealActive(key: Int, enabled: Boolean): Boolean {
    val reduced = rememberReducedMotion()
    var revealedKey by rememberSaveable { mutableIntStateOf(Int.MIN_VALUE) }
    val active = enabled && !reduced && revealedKey != key
    LaunchedEffect(key, active) {
        if (active) {
            delay(REVEAL_WINDOW_MS)
            revealedKey = key
        }
    }
    return active
}

/** Fade + 12dp rise, staggered by [order] × Motion.STAGGER. */
@Composable
private fun Reveal(active: Boolean, order: Int, content: @Composable () -> Unit) {
    // Same tree whether or not the reveal runs, so item state (expanded
    // flags, text layout) survives the moment the reveal window closes.
    val progress = remember { Animatable(if (active) 0f else 1f) }
    val rise = with(LocalDensity.current) { 12.dp.toPx() }
    LaunchedEffect(Unit) {
        if (progress.value < 1f) {
            delay(order.coerceAtMost(REVEAL_MAX_STAGGERED) * Motion.STAGGER.toLong())
            progress.animateTo(1f, Motion.enter(Motion.SLOW))
        }
    }
    Box(
        Modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * rise
        },
    ) { content() }
}

// ─── Pieces ──────────────────────────────────────────────────────────────────

private val CardShape = RoundedCornerShape(16.dp)
private val BlockShape = RoundedCornerShape(12.dp)

private fun Modifier.card(shape: RoundedCornerShape = CardShape): Modifier = this
    .fillMaxWidth()
    .background(CcPalette.Surface, shape)
    .border(BorderStroke(1.dp, CcPalette.Outline), shape)

@Composable
private fun EmptyCard() {
    Column(
        modifier = Modifier
            .card()
            .padding(horizontal = 14.dp, vertical = 16.dp)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.result_empty_title),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = ResultType.body,
            lineHeight = ResultType.bodyLine,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.result_empty_hint),
            color = CcPalette.TextSecondary,
            fontSize = ResultType.meta,
            lineHeight = ResultType.metaLine,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The TL;DR, large, ≤3 lines, with a small ✓ / ✗ glyph from /outcome next
 * to it. No status chips: the glyph is enough. Without a TL;DR the glyph's
 * word ("Hecho" / "Falló") is the title.
 */
@Composable
private fun SummaryCard(tldr: String?, status: ResultStatus, outcome: RunOutcome?) {
    val statusWord = when (status) {
        ResultStatus.DONE -> stringResource(R.string.result_status_done)
        ResultStatus.FAILED -> if (outcome != null && outcome.exitCode != 0L) {
            stringResource(R.string.result_status_failed_code, outcome.exitCode.toInt())
        } else {
            stringResource(R.string.result_status_failed)
        }
        else -> null
    }
    val title = tldr ?: statusWord ?: return
    val cd = listOfNotNull(statusWord?.takeIf { tldr != null }, title).joinToString(". ")
    Row(
        modifier = Modifier
            .card()
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .clearAndSetSemantics {
                contentDescription = cd
                heading()
            },
        verticalAlignment = Alignment.Top,
    ) {
        if (status == ResultStatus.DONE || status == ResultStatus.FAILED) {
            Icon(
                imageVector = if (status == ResultStatus.DONE) PixelIcons.Check else PixelIcons.Cross,
                contentDescription = null,
                tint = if (status == ResultStatus.DONE) StatusColors.running else StatusColors.error,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(14.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = ResultType.title,
            lineHeight = ResultType.titleLine,
            fontWeight = FontWeight.SemiBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Up to three short bullets from the answer's own list. */
@Composable
private fun Bullets(items: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { text ->
            Row(Modifier.fillMaxWidth()) {
                Text(
                    text = "•",
                    color = CcPalette.Coral,
                    fontSize = ResultType.body,
                    lineHeight = ResultType.bodyLine,
                    modifier = Modifier.clearAndSetSemantics { },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = text,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = ResultType.body,
                    lineHeight = ResultType.bodyLine,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** The one "Ver detalle" row; toggles the rest of the answer. */
@Composable
private fun DetailRow(open: Boolean, onToggle: () -> Unit) {
    InlineAction(
        text = stringResource(if (open) R.string.result_see_less else R.string.home_see_detail),
        onClick = onToggle,
        center = true,
    )
}

@Composable
private fun BodyBlocks(
    blocks: List<Block>,
    isCodeExpanded: (Int) -> Boolean,
    onExpandCode: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(BLOCK_GAP),
    ) {
        blocks.forEachIndexed { i, block ->
            BlockView(
                block = block,
                codeExpanded = isCodeExpanded(i),
                onExpandCode = { onExpandCode(i) },
            )
        }
    }
}

@Composable
private fun BlockView(block: Block, codeExpanded: Boolean, onExpandCode: () -> Unit) {
    val body = MaterialTheme.typography.bodyMedium
    when (block) {
        is Block.Heading -> if (block.level <= 2) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(top = 4.dp)
                    .semantics { heading() },
            ) {
                Box(
                    Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(CcPalette.Coral),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = block.spans.toAnnotated(),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        } else {
            Text(
                text = block.spans.toAnnotated(),
                color = MaterialTheme.colorScheme.onSurface,
                style = body,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { heading() },
            )
        }

        is Block.Paragraph -> Text(
            text = block.spans.toAnnotated(),
            color = MaterialTheme.colorScheme.onSurface,
            style = body,
            modifier = Modifier.fillMaxWidth(),
        )

        is Block.ListItem -> Row(
            Modifier
                .fillMaxWidth()
                .padding(start = (block.level * 14).dp),
        ) {
            Text(
                text = block.marker,
                color = CcPalette.TextSecondary,
                style = body,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .widthIn(min = if (block.ordered) 20.dp else 10.dp)
                    .clearAndSetSemantics { },
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = block.spans.toAnnotated(),
                color = MaterialTheme.colorScheme.onSurface,
                style = body,
                modifier = Modifier.weight(1f),
            )
        }

        is Block.Code -> CodeBlock(block, codeExpanded, onExpandCode)

        is Block.Quote -> Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            Box(
                Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(CcPalette.TextSecondary),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = block.spans.toAnnotated(),
                color = CcPalette.TextSecondary,
                style = body,
                fontStyle = FontStyle.Italic,
            )
        }

        is Block.Table -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            block.rows.forEachIndexed { r, row ->
                val rowCd = stringResource(R.string.result_table_row_cd, r + 1)
                Column(
                    Modifier
                        .card(BlockShape)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .semantics(mergeDescendants = true) { contentDescription = rowCd },
                ) {
                    row.forEach { (column, value) ->
                        Text(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(color = CcPalette.TextSecondary)) { append("$column: ") }
                                append(value)
                            },
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        is Block.TableTooWide -> Text(
            text = stringResource(R.string.result_table_too_wide),
            color = CcPalette.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .card(BlockShape)
                .padding(horizontal = 10.dp, vertical = 10.dp),
        )

        Block.Rule -> HairlineDivider(Modifier.padding(vertical = 2.dp))
    }
}

@Composable
private fun CodeBlock(block: Block.Code, expanded: Boolean, onExpand: () -> Unit) {
    val total = block.lines.size
    val shown = if (expanded || total <= CODE_PREVIEW_LINES) block.lines else block.lines.take(CODE_PREVIEW_LINES)
    val cd = stringResource(R.string.result_code_cd, block.lang.orEmpty(), total)
    Column(
        Modifier
            .card(BlockShape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        block.lang?.let {
            MonoLabel(it, modifier = Modifier.semantics { contentDescription = cd })
            Spacer(Modifier.height(4.dp))
        }
        Text(
            text = shown.joinToString("\n"),
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
        if (shown.size < total) {
            InlineAction(text = stringResource(R.string.result_code_full, total), onClick = onExpand)
        }
    }
}

@Composable
private fun ToolTrail(events: List<ToolEvent>, expanded: Boolean, onExpand: () -> Unit) {
    val reduced = rememberReducedMotion()
    val shown = if (expanded) events else events.takeLast(TOOL_PREVIEW_COUNT)
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (reduced) Modifier else Modifier.animateContentSize(Motion.standard()))
            .padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MonoLabel(
            text = stringResource(R.string.result_tools_title),
            modifier = Modifier.semantics { heading() },
        )
        shown.forEach { ev -> ToolRow(ev) }
        if (shown.size < events.size) {
            InlineAction(text = stringResource(R.string.result_tools_see_all, events.size), onClick = onExpand)
        }
    }
}

@Composable
private fun ToolRow(ev: ToolEvent) {
    val kind = toolKind(ev.tool)
    val label = when (kind) {
        ToolKind.COMMAND -> stringResource(R.string.result_tool_command)
        ToolKind.EDIT -> stringResource(R.string.result_tool_edit)
        ToolKind.READ -> stringResource(R.string.result_tool_read)
        ToolKind.SEARCH -> stringResource(R.string.result_tool_search)
        ToolKind.WEB -> stringResource(R.string.result_tool_web)
        ToolKind.AGENT -> stringResource(R.string.result_tool_agent)
        // MCP and unknown tools: their own name is the most honest label.
        ToolKind.OTHER -> ev.tool.substringAfterLast("__").ifBlank { "?" }
    }
    val arg = shortToolArg(ev.arg)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = ev.pixelIcon(),
            contentDescription = null,
            tint = CcPalette.Coral,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
        )
        if (arg != null) {
            Text(
                text = " · ",
                color = CcPalette.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.clearAndSetSemantics { },
            )
            Text(
                text = arg,
                color = CcPalette.TextSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

// ─── Controls with press feedback ───────────────────────────────────────────

private enum class ButtonStyle { PRIMARY, OUTLINED, CHIP }

@Composable
private fun FeedbackButton(
    text: String,
    style: ButtonStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    transformation: SurfaceTransformation? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val colors = when (style) {
        ButtonStyle.PRIMARY -> ButtonDefaults.buttonColors(
            containerColor = CcPalette.Coral,
            contentColor = CcPalette.Black,
            iconColor = CcPalette.Black,
        )
        ButtonStyle.OUTLINED -> ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            iconColor = CcPalette.Coral,
        )
        ButtonStyle.CHIP -> ButtonDefaults.buttonColors(
            containerColor = CcPalette.Surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        )
    }
    Button(
        onClick = onClick,
        colors = colors,
        border = if (style == ButtonStyle.PRIMARY) null else BorderStroke(1.dp, CcPalette.Outline),
        interactionSource = interaction,
        transformation = transformation,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .pressFeedback(interaction),
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (style == ButtonStyle.CHIP) FontWeight.Normal else FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A coral text action ("Ver más", "Ver todo") with a full 48dp target. */
@Composable
private fun InlineAction(text: String, onClick: () -> Unit, center: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .pressFeedback(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = if (center) Alignment.Center else Alignment.CenterStart,
    ) {
        Text(
            text = text,
            color = CcPalette.Coral,
            fontSize = ResultType.body,
            lineHeight = ResultType.bodyLine,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ─── Inline spans ───────────────────────────────────────────────────────────

private fun List<Span>.toAnnotated(): AnnotatedString = buildAnnotatedString {
    for (sp in this@toAnnotated) {
        val style = SpanStyle(
            fontWeight = if (sp.bold) FontWeight.SemiBold else null,
            fontStyle = if (sp.italic) FontStyle.Italic else null,
            fontFamily = if (sp.code) FontFamily.Monospace else null,
            background = if (sp.code) CcPalette.SurfaceHigh else Color.Unspecified,
            color = if (sp.link) CcPalette.Coral else Color.Unspecified,
        )
        withStyle(style) { append(sp.text) }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

@Composable
private fun PreviewHost(
    headline: String? = null,
    response: String? = null,
    taskKind: TaskKind? = null,
    outcome: RunOutcome? = null,
    toolEvents: List<ToolEvent> = emptyList(),
    followups: List<String> = emptyList(),
) {
    CCWEAROSTheme {
        ResultPage(
            headline = headline,
            response = response,
            taskKind = taskKind,
            outcome = outcome,
            toolEvents = toolEvents,
            followups = followups,
            conversationActive = true,
            onFollowup = {},
            onContinue = {},
            onBlockedContent = {},
        )
    }
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Resultado · vacío")
@Composable
private fun PreviewResultEmpty() = PreviewHost()

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Resultado · info")
@Composable
private fun PreviewResultInfo() = PreviewHost(
    headline = "El parser falla con tablas vacías",
    response = """
        **TL;DR:** El parser falla con tablas vacías.

        ## Causa
        El regex de celdas asume **al menos una** columna.

        - Revisa `splitRow` en `parser.ts`
        - Agrega un guard
          - y un test con tabla vacía

        ```ts
        if (cells.length === 0) return [];
        ```

        Sugerencias:
        - Escribe el test
        - Muestra el diff
    """.trimIndent(),
    taskKind = TaskKind.INFO,
    followups = listOf("Escribe el test", "Muestra el diff"),
)

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Resultado · larga")
@Composable
private fun PreviewResultLong() = PreviewHost(
    headline = "Tres formas de cachear las respuestas de la API en el reloj sin gastar batería de más",
    response = (1..8).joinToString("\n\n") {
        "Párrafo $it: la caché en disco de Firebase ya guarda el último estado, así que el reloj arranca con datos aunque no tenga red todavía."
    },
    taskKind = TaskKind.INFO,
)

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Resultado · acción ok")
@Composable
private fun PreviewResultActionOk() = PreviewHost(
    response = "Listo: tests en verde (72/72).",
    taskKind = TaskKind.ACTION,
    outcome = RunOutcome(ok = true, exitCode = 0),
    toolEvents = listOf(
        ToolEvent("Read", "src/parser.ts"),
        ToolEvent("Grep", "splitRow"),
        ToolEvent("Edit", "src/parser.ts"),
        ToolEvent("Bash", "npm test"),
        ToolEvent("Edit", "/Users/luis/projects/CCWEAROS/wrapper/src/parser.test.ts"),
        ToolEvent("Bash", "npm run typecheck"),
        ToolEvent("Bash", "git status"),
    ),
)

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Resultado · acción falló")
@Composable
private fun PreviewResultActionFailed() = PreviewHost(
    response = "No pude correr los tests: falta `node_modules`. Corre `npm install` en tu Mac.",
    taskKind = TaskKind.ACTION,
    outcome = RunOutcome(ok = false, exitCode = 1),
    toolEvents = listOf(ToolEvent("Bash", "npm test")),
)

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Resultado · tabla")
@Composable
private fun PreviewResultTable() = PreviewHost(
    headline = "Comparé los dos planes",
    response = """
        | Plan | Precio | Tokens |
        |------|--------|--------|
        | Pro | 20 USD | 5x |
        | Max | 100 USD | 20x |

        | a | b | c | d | e |
        |---|---|---|---|---|
        | 1 | 2 | 3 | 4 | 5 |
    """.trimIndent(),
    taskKind = TaskKind.INFO,
)
