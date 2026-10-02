package com.caamano.ccwearos.tile

import android.content.Context
import androidx.annotation.StringRes
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.degrees
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Arc
import androidx.wear.protolayout.LayoutElementBuilders.ArcLine
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FontSetting
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.Row
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ModifiersBuilders.Background
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.ModifiersBuilders.Corner
import androidx.wear.protolayout.ModifiersBuilders.Modifiers
import androidx.wear.protolayout.ModifiersBuilders.Padding
import com.caamano.ccwearos.R
import com.caamano.ccwearos.notifications.DeepLinks

// ProtoLayout rendering for the Status Tile. Flat colors only: true black,
// one coral accent, white / gray text. No gradients, no brushes.
//
// Type scale (never below 12sp): title 20sp bold, hero number 28sp tabular,
// body 13sp, chip 14sp (secondary chip 12sp). Spacing: 4dp between body
// lines, 8dp before the action row, 22dp safe inset from the round edge.
internal object TileLayouts {
    private const val BLACK = 0xFF000000.toInt()
    private const val CORAL = 0xFFCC785C.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val GRAY = 0xFF9A9A9A.toInt()
    private const val TRACK = 0xFF262626.toInt()
    private const val CHIP_DARK = 0xFF1F1F1F.toInt()

    private const val GAP = 4f
    private const val ACTION_GAP = 8f

    /** One-shot banner shown right after a tile-side answer. */
    enum class Feedback { SENT_ALLOW, SENT_DENY, FAILED }

    /** [nonce] makes this render's Permitir / Rechazar ids unique (see TileClicks). */
    fun root(
        context: Context,
        state: TileState,
        packageName: String,
        activityClass: String,
        feedback: Feedback? = null,
        nonce: Long = 0L,
    ): LayoutElement {
        fun s(@StringRes id: Int) = context.getString(id)
        val open = launch(packageName, activityClass, null)
        val content = when (state) {
            TileState.SignedOut -> column(
                listOf(title(s(R.string.tile_signed_out_title), WHITE), body(s(R.string.tile_signed_out_body), GRAY, 2)),
                listOf(chip(s(R.string.tile_open), open, "open", filled = true)),
            )
            TileState.NoSignal -> column(
                listOf(title(s(R.string.tile_no_signal_title), WHITE), body(s(R.string.tile_no_signal_body), GRAY, 2)),
                listOf(chip(s(R.string.tile_open), open, "open", filled = false)),
            )
            TileState.Offline -> column(
                listOf(title(s(R.string.tile_offline_title), WHITE), body(s(R.string.tile_offline_body), GRAY, 2)),
            )
            is TileState.Idle -> column(
                listOf(
                    title(s(R.string.tile_ready), WHITE),
                    number(TileMapper.formatTokens(state.dailyTokens)),
                    body(s(R.string.tile_tokens_today), GRAY, 1),
                ),
                listOf(
                    // Opens MainActivity straight into voice input (DeepLinks.ACTION_VOICE).
                    chip(s(R.string.tile_ask), launch(packageName, activityClass, DeepLinks.ACTION_VOICE), "ask", filled = true),
                ),
            )
            is TileState.Running -> column(
                listOf(
                    title(s(R.string.tile_working), CORAL),
                    body(state.activity ?: s(R.string.tile_working_fallback), GRAY, 2),
                ) + listOfNotNull(state.contextPct?.let { pctLabel(context, it) }),
            )
            is TileState.Awaiting -> awaiting(context, state, open, feedback, nonce)
            is TileState.Blocked -> column(
                listOf(
                    title(s(R.string.tile_blocked_title), CORAL),
                    body(state.hint ?: s(R.string.tile_blocked_fallback), WHITE, 2),
                ),
                listOf(chip(s(R.string.tile_open), open, "open", filled = true)),
            )
            is TileState.Done -> {
                val result = launch(packageName, activityClass, DeepLinks.ACTION_RESULT)
                val fallback = if (state.ok) R.string.tile_done_ok_fallback else R.string.tile_done_failed_fallback
                column(
                    listOf(
                        title(s(if (state.ok) R.string.tile_done_ok else R.string.tile_done_failed), if (state.ok) WHITE else CORAL),
                        body(state.headline ?: s(fallback), GRAY, 2),
                    ),
                    listOf(
                        chip(s(R.string.tile_see_result), result, "result", filled = true),
                        chip(s(R.string.tile_ask), launch(packageName, activityClass, DeepLinks.ACTION_VOICE), "ask", filled = false, small = true),
                    ),
                )
            }
        }

        val layers = Box.Builder().setWidth(expand()).setHeight(expand())
            .setModifiers(Modifiers.Builder().setBackground(Background.Builder().setColor(argb(BLACK)).build()).build())
        val pct = TileMapper.contextPct(state)
        if (pct != null) {
            layers.addContent(ring(360f, TRACK))
            if (pct > 0) layers.addContent(ring(360f * pct / 100f, CORAL))
        }
        layers.addContent(content)
        return layers.build()
    }

    private fun awaiting(
        context: Context,
        state: TileState.Awaiting,
        open: ActionBuilders.Action,
        feedback: Feedback?,
        nonce: Long,
    ): LayoutElement {
        fun s(@StringRes id: Int) = context.getString(id)
        val heading = title(s(R.string.tile_permission), CORAL)
        if (feedback != null) {
            val msg = when (feedback) {
                Feedback.SENT_ALLOW -> s(R.string.tile_sent_allow)
                Feedback.SENT_DENY -> s(R.string.tile_sent_deny)
                Feedback.FAILED -> s(R.string.tile_send_failed)
            }
            return column(
                listOf(heading, body(msg, WHITE, 1)),
                listOf(chip(s(R.string.tile_open), open, "open", filled = false)),
            )
        }
        val preview = body(state.promptPreview.ifBlank { s(R.string.tile_permission_fallback) }, WHITE, 2)
        val id = state.promptId
        // Permitir / Rechazar only when the whole prompt is readable here and
        // it is not risky (TileMapper decides; TileDataSource.answer re-checks).
        return if (state.quickActions && id != null) {
            column(
                listOf(heading, preview),
                listOf(
                    Row.Builder()
                        .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                        .addContent(chip(s(R.string.tile_deny), load(), TileClicks.encode(false, id, nonce), filled = false))
                        .addContent(Spacer.Builder().setWidth(dp(6f)).build())
                        .addContent(chip(s(R.string.tile_allow), load(), TileClicks.encode(true, id, nonce), filled = true))
                        .build(),
                    chip(s(R.string.tile_open), open, "open", filled = false, small = true),
                ),
            )
        } else {
            column(
                listOf(heading, preview),
                listOf(chip(s(R.string.tile_open), open, "open", filled = true)),
            )
        }
    }

    /** [text] lines 4dp apart, then [actions] 8dp below and 4dp apart. */
    private fun column(text: List<LayoutElement>, actions: List<LayoutElement> = emptyList()): LayoutElement {
        val c = Column.Builder()
            .setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        text.forEachIndexed { i, el ->
            if (i > 0) c.addContent(Spacer.Builder().setHeight(dp(GAP)).build())
            c.addContent(el)
        }
        actions.forEachIndexed { i, el ->
            c.addContent(Spacer.Builder().setHeight(dp(if (i == 0) ACTION_GAP else GAP)).build())
            c.addContent(el)
        }
        return Box.Builder()
            .setWidth(expand()).setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(Modifiers.Builder().setPadding(Padding.Builder().setAll(dp(22f)).build()).build())
            .addContent(c.build())
            .build()
    }

    private fun title(text: String, color: Int): LayoutElement = Text.Builder()
        .setText(text)
        .setMaxLines(1)
        .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE)
        .setFontStyle(FontStyle.Builder().setSize(sp(20f)).setColor(argb(color))
            .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD).build())
        .build()

    private fun number(text: String): LayoutElement = Text.Builder()
        .setText(text)
        .setMaxLines(1)
        .setFontStyle(FontStyle.Builder().setSize(sp(28f)).setColor(argb(WHITE))
            .setWeight(LayoutElementBuilders.FONT_WEIGHT_NORMAL)
            .setSettings(FontSetting.tabularNum())
            .build())
        .build()

    private fun pctLabel(context: Context, pct: Int): LayoutElement = Text.Builder()
        .setText(context.getString(R.string.tile_context_pct, pct))
        .setMaxLines(1)
        .setFontStyle(FontStyle.Builder().setSize(sp(12f)).setColor(argb(GRAY))
            .setSettings(FontSetting.tabularNum())
            .build())
        .build()

    private fun body(text: String, color: Int, lines: Int): LayoutElement = Text.Builder()
        .setText(text)
        .setMaxLines(lines)
        .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE)
        .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
        .setFontStyle(FontStyle.Builder().setSize(sp(13f)).setColor(argb(color)).build())
        .build()

    private fun chip(
        label: String,
        action: ActionBuilders.Action,
        id: String,
        filled: Boolean,
        small: Boolean = false,
    ): LayoutElement {
        val bg = if (filled) CORAL else CHIP_DARK
        val fg = if (filled) BLACK else WHITE
        val click = Clickable.Builder().setId(id).setOnClick(action).build()
        return Box.Builder()
            .setWidth(wrap())
            .setHeight(dp(if (small) 32f else 40f))
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                Modifiers.Builder()
                    .setClickable(click)
                    .setBackground(Background.Builder().setColor(argb(bg))
                        .setCorner(Corner.Builder().setRadius(dp(20f)).build()).build())
                    .setPadding(Padding.Builder().setStart(dp(14f)).setEnd(dp(14f)).build())
                    .setSemantics(ModifiersBuilders.Semantics.Builder().setContentDescription(label).build())
                    .build(),
            )
            .addContent(
                Text.Builder().setText(label).setMaxLines(1)
                    .setFontStyle(FontStyle.Builder().setSize(sp(if (small) 12f else 14f)).setColor(argb(fg))
                        .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD).build())
                    .build(),
            )
            .build()
    }

    private fun ring(lengthDeg: Float, color: Int): LayoutElement = Arc.Builder()
        .setAnchorAngle(degrees(0f))
        .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
        .addContent(
            ArcLine.Builder()
                .setLength(degrees(lengthDeg.coerceIn(0f, 360f)))
                .setThickness(dp(4f))
                .setColor(argb(color))
                .build(),
        )
        .build()

    /** Launches MainActivity; [deepLink] goes in the DeepLinks.EXTRA_ACTION extra. */
    private fun launch(packageName: String, activityClass: String, deepLink: String?): ActionBuilders.Action {
        val activity = ActionBuilders.AndroidActivity.Builder()
            .setPackageName(packageName)
            .setClassName(activityClass)
        if (deepLink != null) {
            activity.addKeyToExtraMapping(
                DeepLinks.EXTRA_ACTION,
                ActionBuilders.AndroidStringExtra.Builder().setValue(deepLink).build(),
            )
        }
        return ActionBuilders.LaunchAction.Builder().setAndroidActivity(activity.build()).build()
    }

    private fun load(): ActionBuilders.Action = ActionBuilders.LoadAction.Builder().build()
}
