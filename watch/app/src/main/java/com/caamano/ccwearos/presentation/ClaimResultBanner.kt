package com.caamano.ccwearos.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.delay

// Sprint 4n — transient banner that surfaces /claimResult to the user.
// Renders as a discreet pill near the TOP of the round watch face so it
// doesn't cover the dashboard content the user might be reading. Auto-
// dismisses after 4s via LaunchedEffect; user has no manual close
// affordance (keeping the surface area tiny on a 1.5" round screen).
@Composable
fun ClaimResultBanner(
    ok: Boolean,
    message: String,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(message) {
        // 4s is long enough to read but short enough to stay out of the
        // way. The result message is at most one short line by daemon-side
        // convention (`reason` capped at ~80 chars in claim-handler).
        delay(4_000)
        onDismiss()
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        // Top-aligned, slightly inset from the bezel curve. On a 480x480
        // round screen the inscribed-square at 0.78 covers the dashboard
        // header zone — we sit just above it so the user's glance lands
        // on the banner first.
        contentAlignment = BiasAlignment(0f, -0.78f),
    ) {
        val tint = if (ok) ClaudeGreen else ClaudeRed
        Row(
            modifier = Modifier
                .fillMaxWidth(fraction = 0.82f)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.92f))
                .border(
                    BorderStroke(1.dp, tint.copy(alpha = 0.55f)),
                    shape = RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            // Status glyph — coral check on success, red x on error. Keep
            // monospace so the icon column has a stable width across re-
            // renders.
            Text(
                text = if (ok) "✓" else "✗",
                color = tint,
                fontFamily = MonoFamily,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(6.dp))
            Column {
                Text(
                    text = message,
                    color = Color.White.copy(alpha = 0.95f),
                    fontFamily = MonoFamily,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Start,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
