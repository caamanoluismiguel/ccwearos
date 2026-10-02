package com.caamano.ccwearos.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.ui.tooling.preview.WearPreviewLargeRound
import com.caamano.ccwearos.presentation.theme.CCWEAROSTheme
import com.caamano.ccwearos.presentation.theme.CcPalette

// Motion lab: every mascot state and every feedback component on one round
// 480×480 canvas. Previews run as reduced motion (static frames), which is
// exactly the "is the state still readable without motion?" check. Use
// interactive preview mode to see the loops.

@Composable
private fun LabCell(label: String, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        content()
        MonoLabel(label)
    }
}

@WearPreviewLargeRound
@Composable
private fun MascotLabPreview() {
    CCWEAROSTheme {
        Box(Modifier.fillMaxSize().background(CcPalette.Black), contentAlignment = Alignment.Center) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                MascotState.entries.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { s -> LabCell(s.name.take(5)) { PixelMascot(state = s, size = 32.dp) } }
                    }
                }
            }
        }
    }
}

@WearPreviewLargeRound
@Composable
private fun MascotLargePreview() {
    CCWEAROSTheme {
        Box(Modifier.fillMaxSize().background(CcPalette.Black), contentAlignment = Alignment.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PixelMascot(state = MascotState.Waiting, size = 56.dp)
                PixelMascot(state = MascotState.Blocked, size = 56.dp)
            }
        }
    }
}

@WearPreviewLargeRound
@Composable
private fun FeedbackLabPreview() {
    CCWEAROSTheme {
        Box(Modifier.fillMaxSize().background(CcPalette.Black), contentAlignment = Alignment.Center) {
            SuccessRing(trigger = 1)
            ProgressHalo(active = true, modifier = Modifier.padding(8.dp), showTrack = true)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ElapsedTimer(startedAtMillis = 0L, endedAtMillis = 754_000L)
                Spacer(Modifier.height(6.dp))
                val src = remember { MutableInteractionSource() }
                Text(
                    "press",
                    modifier = Modifier.pressFeedback(src, haptic = false).shake(trigger = 0),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(PixelIcons.Laptop, PixelIcons.Signal, PixelIcons.Lock, PixelIcons.Question).forEachIndexed { i, icon ->
                        StaggeredReveal(index = i) {
                            Icon(icon, contentDescription = null, tint = CcPalette.TextSecondary, modifier = Modifier.width(18.dp))
                        }
                    }
                }
            }
        }
    }
}
