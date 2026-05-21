package com.caamano.ccwearos.presentation

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
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

// Sprint 4n — confirmation dialog shown when the user taps a session row on
// Page 5. Renders as an overlay above DashboardScreen (Box on top of the
// horizontal pager — see DashboardScreen wiring). Patterns lifted from
// PermissionScreen so the watch's "confirm something destructive-ish"
// language stays consistent: large pill buttons, top-aligned scrollable
// content for round bezel safety, BackHandler swallows accidental swipe.
@Composable
fun ConfirmClaimDialog(
    sessionId: String,
    cwd: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val view = LocalView.current
    LaunchedEffect(sessionId) {
        // 120ms debounce mirrors PermissionScreen — small delay smooths over
        // any Firebase reconnect burst that might briefly remount.
        if (sessionId.isBlank()) return@LaunchedEffect
        delay(120)
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
    // Hardware-back / swipe-right should not silently dismiss a confirm
    // dialog — make the user explicitly tap "Cancelar" or "Resumir".
    BackHandler(enabled = true) { onCancel() }

    Box(
        Modifier
            .fillMaxSize()
            // Opaque black so the dashboard behind doesn't bleed through.
            // We're intentionally a modal — the pager underneath is paused
            // visually too.
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize(fraction = 0.78f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Top),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Mascot + amber pip = "this is an action the watch is asking
            // you to confirm" — same visual language as PermissionScreen.
            Row(verticalAlignment = Alignment.CenterVertically) {
                ClaudeMascot(width = 14.dp)
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(
                    text = "resumir sesión",
                    color = ClaudeAmber,
                    fontFamily = MonoFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = projectBasename(cwd),
                color = Color.White.copy(alpha = 0.95f),
                fontFamily = MonoFamily,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "id=${sessionId.take(8)}…",
                color = ClaudeDim.copy(alpha = 0.7f),
                fontFamily = MonoFamily,
                fontSize = 9.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text =
                    "se abrirá una nueva Terminal " +
                        "en tu Mac con cc --resume",
                color = Color.White.copy(alpha = 0.7f),
                fontFamily = FontFamily.Default,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            ConfirmPillButton(
                label = "resumir",
                color = ClaudeGreen,
                onClick = onConfirm,
            )
            Spacer(Modifier.height(4.dp))
            ConfirmPillButton(
                label = "cancelar",
                color = ClaudeRed,
                onClick = onCancel,
            )
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun ConfirmPillButton(
    label: String,
    color: Color,
    onClick: () -> Unit,
) {
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

// Pull the basename out of an absolute cwd so the dialog shows
// "ccwearos" rather than "/Users/luismiguelcaamano/projects/CCWEAROS".
// Falls back to the raw cwd if it doesn't look like a path.
private fun projectBasename(cwd: String): String {
    val trimmed = cwd.trimEnd('/')
    val slash = trimmed.lastIndexOf('/')
    return if (slash >= 0 && slash < trimmed.length - 1) {
        trimmed.substring(slash + 1)
    } else {
        trimmed.ifBlank { "?" }
    }
}
