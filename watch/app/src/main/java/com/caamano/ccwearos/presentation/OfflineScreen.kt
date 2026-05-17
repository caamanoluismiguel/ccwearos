package com.caamano.ccwearos.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text

@Composable
fun OfflineScreen() {
    // ARIA: Offline screen is a full-center composition — everything centered horizontally
    // and vertically. 8dp spacing between elements matches the dashboard grid.
    // The mascot breathes = false signals "dead / no connection" state.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxSize(fraction = 0.72f),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ClaudeMascot(width = 18.dp, breathe = false)
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text("claude code", color = ClaudeCoral, fontFamily = MonoFamily, fontSize = 10.sp)
            }
            Text(
                text = "$ offline",
                color = ClaudeRed,
                fontFamily = MonoFamily,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "wrapper not reachable",
                color = ClaudeDim.copy(alpha = 0.65f),
                fontFamily = MonoFamily,
                fontSize = 9.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
