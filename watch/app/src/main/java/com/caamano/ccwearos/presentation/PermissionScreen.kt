package com.caamano.ccwearos.presentation

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.delay

@Composable
fun PermissionScreen(
    prompt: String?,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    val view = LocalView.current
    LaunchedEffect(prompt) {
        // Debounce so two snapshots within 100ms during Firebase reconnect
        // don't fire a double-buzz that feels like a glitchy single vibration.
        // Also skip empty prompts — the watch sometimes wakes from ambient
        // with `prompt=null` cached before the real prompt arrives; firing
        // a haptic on that cached null is a phantom buzz.
        if (prompt.isNullOrBlank()) return@LaunchedEffect
        delay(120)
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
    // Hardware back / swipe-right on Wear OS would close the app — surprising
    // for a modal asking for a critical decision. Swallow back so the user
    // has to explicitly tap allow or deny.
    BackHandler(enabled = true) { /* no-op: prevent accidental dismiss */ }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxSize(fraction = 0.78f)
                .verticalScroll(rememberScrollState()),
            // Top-anchored + scrollable. Centered alignment used to push the
            // deny button off the bottom of the round bezel when the prompt
            // was 3-4 lines long. Top-aligned with scroll guarantees both
            // buttons are reachable on any prompt length.
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Top),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Tighter single-line header. The combined mascot + "permission"
            // pip frees enough vertical space for allow + deny to fit on a
            // round screen without scroll on typical (1-3 line) prompts.
            Row(verticalAlignment = Alignment.CenterVertically) {
                ClaudeMascot(width = 14.dp)
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(
                    text = "permission",
                    color = ClaudeAmber,
                    fontFamily = MonoFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = prompt ?: "Claude needs permission.",
                color = Color.White.copy(alpha = 0.92f),
                fontFamily = FontFamily.Default,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            // Pill-shaped, full-width buttons. On a round screen the corner
            // crop makes small circular buttons hard to hit; capsules that
            // span 80% of the inscribed width are forgiving even with a
            // moving wrist. Text "allow"/"deny" is more discoverable than
            // single-letter "y"/"n" especially in stressful moments.
            TerminalButton(label = "allow", color = ClaudeGreen, onClick = onAllow)
            Spacer(Modifier.height(4.dp))
            TerminalButton(label = "deny", color = ClaudeRed, onClick = onDeny)
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun TerminalButton(label: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = Color.Black,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        Text(
            text = label,
            fontFamily = MonoFamily,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
        )
    }
}
