package com.caamano.ccwearos.presentation

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Text

@Composable
fun PermissionScreen(
    prompt: String?,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    val view = LocalView.current
    LaunchedEffect(prompt) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxSize(fraction = 0.72f),
            // ARIA: 6dp spacing base, centered vertically — tighter than dashboard
            // because this screen must be fully scannable in one glance + action.
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ClaudeMascot(width = 18.dp)
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text("claude code", color = ClaudeCoral, fontFamily = MonoFamily, fontSize = 10.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$ permission",
                    color = ClaudeAmber,
                    fontFamily = MonoFamily,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.padding(horizontal = 1.dp))
                BlinkingCursor(color = ClaudeAmber, fontSize = 11.sp)
            }
            // ARIA: Prompt text switches from MonoFamily to Default (sans-serif) for
            // readability — the prompt is prose, not code. Normal weight instead of
            // unspecified (which defaults to Light on some Wear OS versions).
            // 4 lines max so long permission strings don't push buttons off-screen.
            Text(
                text = prompt ?: "Claude needs permission.",
                color = Color.White.copy(alpha = 0.92f),
                fontFamily = FontFamily.Default,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "[Y/n]",
                color = ClaudeDim.copy(alpha = 0.6f),
                fontFamily = MonoFamily,
                fontSize = 9.sp,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TerminalButton(label = "n", color = ClaudeRed, onClick = onDeny)
                TerminalButton(label = "y", color = ClaudeGreen, onClick = onAllow)
            }
        }
    }
}

@Composable
private fun TerminalButton(label: String, color: Color, onClick: () -> Unit) {
    // KAI: 48dp height is the Wear OS minimum touch target (Material Design for Wear).
    // Previous 38dp height was a touch target violation — users on moving wrists miss it.
    // Width stays 52dp (comfortably tappable, fits two buttons side-by-side in 0.72× width).
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = Color.Black,
        ),
        modifier = Modifier.size(width = 52.dp, height = 48.dp),
    ) {
        Text(
            text = label,
            fontFamily = MonoFamily,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
        )
    }
}
