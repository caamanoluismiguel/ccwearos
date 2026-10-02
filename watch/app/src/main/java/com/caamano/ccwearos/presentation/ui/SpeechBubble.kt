package com.caamano.ccwearos.presentation.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import com.caamano.ccwearos.presentation.theme.CcPalette

private val BubbleShape = RoundedCornerShape(3.dp)

/**
 * The mascot's pixel speech bubble ("¡Listo!", "¿Me dejas?"). Square-ish
 * corners, solid surface, coral hairline and a stepped pixel tail pointing
 * down at the mascot. No glow, no gradient. Pops in with [Motion.settle]
 * from the tail, pops out quickly; [text] null hides it. Announced politely
 * to TalkBack. Keep texts short (≤12 chars).
 */
@Composable
fun SpeechBubble(text: String?, modifier: Modifier = Modifier) {
    val reduced = rememberReducedMotion()
    // Keep the last text on screen while the bubble pops out.
    var last by remember { mutableStateOf(text.orEmpty()) }
    if (text != null) last = text
    val origin = TransformOrigin(0.5f, 1f)
    AnimatedVisibility(
        visible = text != null,
        modifier = modifier,
        enter = if (reduced) fadeIn(snap()) else scaleIn(Motion.settle(), initialScale = 0.4f, transformOrigin = origin) + fadeIn(Motion.enter(Motion.FAST)),
        exit = if (reduced) fadeOut(snap()) else scaleOut(Motion.exit(), targetScale = 0.6f, transformOrigin = origin) + fadeOut(Motion.exit()),
        label = "speech-bubble",
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = last
                liveRegion = LiveRegionMode.Polite
            },
        ) {
            Box(
                Modifier
                    .background(CcPalette.Surface, BubbleShape)
                    .border(1.dp, CcPalette.Coral, BubbleShape)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = last,
                    color = CcPalette.TextPrimary,
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
            // Stepped pixel tail: 3 rows narrowing toward the mascot.
            Canvas(Modifier.size(width = 6.dp, height = 3.dp)) {
                val p = size.height / 3f
                drawRect(CcPalette.Coral, Offset(0f, 0f), Size(size.width, p))
                drawRect(CcPalette.Coral, Offset(p, p), Size(size.width - 2 * p, p))
                drawRect(CcPalette.Coral, Offset(size.width / 2f - p / 2f, 2 * p), Size(p, p))
            }
        }
    }
}
