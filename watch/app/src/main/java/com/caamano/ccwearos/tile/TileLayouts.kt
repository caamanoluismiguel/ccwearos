package com.caamano.ccwearos.tile

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

// ProtoLayout rendering for the Status Tile. Flat colors only: true black,
// one coral accent, white / gray text. No gradients, no brushes.
internal object TileLayouts {
    private const val BLACK = 0xFF000000.toInt()
    private const val CORAL = 0xFFCC785C.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val GRAY = 0xFF9A9A9A.toInt()
    private const val TRACK = 0xFF262626.toInt()
    private const val CHIP_DARK = 0xFF1F1F1F.toInt()

    /** One-shot banner shown right after a tile-side answer. */
    enum class Feedback { SENT_ALLOW, SENT_DENY, FAILED }

    /** [nonce] makes this render's Permitir / Rechazar ids unique (see TileClicks). */
    fun root(
        state: TileState,
        packageName: String,
        activityClass: String,
        feedback: Feedback? = null,
        nonce: Long = 0L,
    ): LayoutElement {
        val open = launch(packageName, activityClass)
        val content = when (state) {
            TileState.SignedOut -> column(
                title("Abre la app", WHITE),
                body("Inicia sesión en el reloj", GRAY, 2),
                chip("Abrir", open, "open", filled = true),
            )
            TileState.NoSignal -> column(
                title("Sin señal", WHITE),
                body("Revisa la conexión del reloj", GRAY, 2),
                chip("Abrir", open, "open", filled = false),
            )
            TileState.Offline -> column(
                title("Sin conexión", WHITE),
                body("Sin conexión con tu Mac", GRAY, 2),
            )
            is TileState.Idle -> column(
                title("Listo", WHITE),
                tokens(TileMapper.formatTokens(state.dailyTokens)),
                body("tokens hoy", GRAY, 1),
                chip("Preguntar", open, "ask", filled = true),
            )
            is TileState.Running -> column(
                title("Trabajando", CORAL),
                body(state.activity ?: "Claude está en eso", GRAY, 1),
            )
            is TileState.Awaiting -> awaiting(state, open, feedback, nonce)
        }

        val layers = Box.Builder().setWidth(expand()).setHeight(expand())
            .setModifiers(Modifiers.Builder().setBackground(Background.Builder().setColor(argb(BLACK)).build()).build())
        val pct = when (state) {
            is TileState.Idle -> state.contextPct
            is TileState.Running -> state.contextPct
            else -> null
        }
        if (pct != null) {
            layers.addContent(ring(360f, TRACK))
            if (pct > 0) layers.addContent(ring(360f * pct / 100f, CORAL))
        }
        layers.addContent(content)
        return layers.build()
    }

    private fun awaiting(
        state: TileState.Awaiting,
        open: ActionBuilders.Action,
        feedback: Feedback?,
        nonce: Long,
    ): LayoutElement {
        if (feedback != null) {
            val msg = when (feedback) {
                Feedback.SENT_ALLOW -> "Permitido"
                Feedback.SENT_DENY -> "Rechazado"
                Feedback.FAILED -> "No se pudo enviar"
            }
            return column(
                title("Permiso", CORAL),
                body(msg, WHITE, 1),
                chip("Abrir", open, "open", filled = false),
            )
        }
        val preview = state.promptPreview.ifBlank { "Claude pide permiso" }
        val id = state.promptId
        return if (state.quickActions && id != null) {
            column(
                title("Permiso", CORAL),
                body(preview, WHITE, 2),
                Row.Builder()
                    .addContent(chip("Rechazar", load(), TileClicks.encode(false, id, nonce), filled = false))
                    .addContent(Spacer.Builder().setWidth(dp(6f)).build())
                    .addContent(chip("Permitir", load(), TileClicks.encode(true, id, nonce), filled = true))
                    .build(),
                chip("Abrir", open, "open", filled = false, small = true),
            )
        } else {
            column(
                title("Permiso", CORAL),
                body(preview, WHITE, 2),
                chip("Abrir", open, "open", filled = true),
            )
        }
    }

    private fun column(vararg items: LayoutElement): LayoutElement {
        val c = Column.Builder()
            .setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        items.forEachIndexed { i, el ->
            if (i > 0) c.addContent(Spacer.Builder().setHeight(dp(4f)).build())
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
        .setFontStyle(FontStyle.Builder().setSize(sp(20f)).setColor(argb(color))
            .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD).build())
        .build()

    private fun tokens(text: String): LayoutElement = Text.Builder()
        .setText(text)
        .setMaxLines(1)
        .setFontStyle(FontStyle.Builder().setSize(sp(28f)).setColor(argb(WHITE))
            .setWeight(LayoutElementBuilders.FONT_WEIGHT_NORMAL)
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
            .setHeight(dp(if (small) 30f else 40f))
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

    private fun launch(packageName: String, activityClass: String): ActionBuilders.Action =
            ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder()
                        .setPackageName(packageName)
                        .setClassName(activityClass)
                        .build(),
                )
                .build()

    private fun load(): ActionBuilders.Action = ActionBuilders.LoadAction.Builder().build()
}
