package com.caamano.ccwearos.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.tooling.preview.devices.WearDevices
import com.caamano.ccwearos.R
import com.caamano.ccwearos.presentation.theme.CCWEAROSTheme
import com.caamano.ccwearos.presentation.theme.StatusColors
import com.caamano.ccwearos.presentation.ui.MascotState
import com.caamano.ccwearos.presentation.ui.PixelMascot
import kotlinx.coroutines.delay

// Confirmation shown when the user taps a past session on the Sessions page.
// Drawn as an opaque overlay above the dashboard. Native Wear structure:
// ScreenScaffold + TransformingLazyColumn (rotary scroll) with the primary
// action as an EdgeButton hugging the bottom of the round screen.
// BackHandler maps swipe-back to an explicit cancel.
@Composable
fun ConfirmClaimDialog(
    sessionId: String,
    cwd: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(sessionId) {
        // 120ms debounce mirrors PermissionScreen: smooths over a Firebase
        // reconnect burst that might briefly remount.
        if (sessionId.isBlank()) return@LaunchedEffect
        delay(120)
        Haptics.tick(context)
    }
    BackHandler(enabled = true) { onCancel() }

    val listState = rememberTransformingLazyColumnState()
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        ScreenScaffold(
            scrollState = listState,
            edgeButton = {
                EdgeButton(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StatusColors.running,
                        contentColor = Color.Black,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.claim_confirm),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            },
        ) { contentPadding ->
            TransformingLazyColumn(
                state = listState,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) { heading() },
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PixelMascot(state = MascotState.Waiting, size = 20.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.claim_title),
                            color = StatusColors.waiting,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                item {
                    Text(
                        text = projectBasename(cwd),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.claim_id, sessionId.take(8)),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.claim_body),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Button(
                        onClick = onCancel,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = StatusColors.error,
                        ),
                        border = BorderStroke(1.dp, StatusColors.error),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.claim_cancel),
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

// "ccwearos" rather than "/Users/.../CCWEAROS". Falls back to the raw cwd.
internal fun projectBasename(cwd: String): String {
    val trimmed = cwd.trimEnd('/')
    val slash = trimmed.lastIndexOf('/')
    return if (slash >= 0 && slash < trimmed.length - 1) {
        trimmed.substring(slash + 1)
    } else {
        trimmed.ifBlank { "?" }
    }
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Confirm claim")
@Composable
private fun PreviewConfirmClaim() {
    CCWEAROSTheme {
        ConfirmClaimDialog(
            sessionId = "3f9a1c2e-0000",
            cwd = "/Users/me/projects/CCWEAROS",
            onConfirm = {},
            onCancel = {},
        )
    }
}
