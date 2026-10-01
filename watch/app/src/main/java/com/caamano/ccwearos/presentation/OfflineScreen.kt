package com.caamano.ccwearos.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.tooling.preview.devices.WearDevices
import com.caamano.ccwearos.R
import com.caamano.ccwearos.presentation.theme.CCWEAROSTheme
import com.caamano.ccwearos.presentation.ui.MascotState
import com.caamano.ccwearos.presentation.ui.PixelMascot

// The wrapper on the Mac isn't reachable. Grey, still mascot (no motion = no
// life on the other end) + one plain sentence. TimeText comes from AppScaffold.
@Composable
fun OfflineScreen() {
    ScreenScaffold { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = (LocalConfiguration.current.screenWidthDp * 0.14f).dp)
                .semantics(mergeDescendants = true) { },
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PixelMascot(state = MascotState.Offline, size = 32.dp)
            Text(
                text = stringResource(R.string.offline_title),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.offline_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Offline")
@Composable
private fun PreviewOffline() {
    CCWEAROSTheme { OfflineScreen() }
}
