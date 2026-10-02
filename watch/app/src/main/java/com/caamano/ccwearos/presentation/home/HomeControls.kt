package com.caamano.ccwearos.presentation.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.caamano.ccwearos.presentation.Haptics
import com.caamano.ccwearos.presentation.theme.CcPalette
import com.caamano.ccwearos.presentation.theme.StatusColors
import com.caamano.ccwearos.presentation.ui.Motion
import com.caamano.ccwearos.presentation.ui.rememberReducedMotion

/** Visual weight of a home control. Exactly one PRIMARY per screen. */
enum class HomeButtonStyle { PRIMARY, OUTLINED, DANGER, MUTED }

/**
 * Press feedback shared by every tappable on Inicio: scale to
 * [Motion.PRESSED_SCALE] while pressed (skipped with reduced motion).
 */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val reduced = rememberReducedMotion()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduced) Motion.PRESSED_SCALE else 1f,
        animationSpec = Motion.standard(Motion.FAST),
        label = "press",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * The one button shape on Inicio. Every tap fires Haptics.tick before the
 * action ("¿qué hice?"), long-press fires Haptics.error (destructive reset).
 */
@Composable
fun HomeButton(
    label: String,
    style: HomeButtonStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    height: Dp = 48.dp,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
) {
    val context = LocalContext.current
    val interaction = remember { MutableInteractionSource() }
    val (container, content, border) = when (style) {
        HomeButtonStyle.PRIMARY -> Triple(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, null)
        HomeButtonStyle.OUTLINED -> Triple(Color.Transparent, MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.outline)
        HomeButtonStyle.DANGER -> Triple(Color.Transparent, StatusColors.error, StatusColors.error)
        HomeButtonStyle.MUTED -> Triple(CcPalette.Surface, CcPalette.TextSecondary, null)
    }
    Button(
        onClick = {
            Haptics.tick(context)
            onClick()
        },
        onLongClick = onLongClick?.let { long ->
            {
                Haptics.error(context)
                long()
            }
        },
        onLongClickLabel = onLongClickLabel,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        border = border?.let { BorderStroke(1.dp, it) },
        contentPadding = PaddingValues(horizontal = 14.dp),
        interactionSource = interaction,
        modifier = modifier
            .fillMaxWidth(0.84f)
            .height(height)
            .pressScale(interaction),
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Small text link ("Ver detalle", "Última respuesta · hace 3 min"). */
@Composable
fun HomeLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val context = LocalContext.current
    val interaction = remember { MutableInteractionSource() }
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .pressScale(interaction)
            .clip(RoundedCornerShape(12.dp))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                Haptics.tick(context)
                onClick()
            }
            // 48dp-ish touch target without a visible box.
            .heightIn(min = 32.dp)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    )
}
