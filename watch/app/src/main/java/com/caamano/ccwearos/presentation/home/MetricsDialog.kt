package com.caamano.ccwearos.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Dialog
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.tooling.preview.devices.WearDevices
import com.caamano.ccwearos.R
import com.caamano.ccwearos.data.ClaudeStatus
import com.caamano.ccwearos.data.Metrics
import com.caamano.ccwearos.presentation.MonoFamily
import com.caamano.ccwearos.presentation.shortNum
import com.caamano.ccwearos.presentation.theme.CCWEAROSTheme
import com.caamano.ccwearos.presentation.ui.ArcGauge
import com.caamano.ccwearos.presentation.ui.MonoLabel
import com.caamano.ccwearos.presentation.ui.usageColor
import java.text.NumberFormat

/**
 * Lightweight usage detail behind Inicio's metrics row: session + weekly
 * gauges, tokens today, model and monthly cost. Swipe right or Cerrar to
 * dismiss (Wear Dialog handles swipe-to-dismiss).
 */
@Composable
fun MetricsDialog(
    visible: Boolean,
    claudeStatus: ClaudeStatus?,
    metrics: Metrics,
    onDismiss: () -> Unit,
) {
    Dialog(visible = visible, onDismissRequest = onDismiss) {
        MetricsDetail(claudeStatus = claudeStatus, metrics = metrics, onClose = onDismiss)
    }
}

@Composable
private fun MetricsDetail(claudeStatus: ClaudeStatus?, metrics: Metrics, onClose: () -> Unit) {
    val listState = rememberTransformingLazyColumnState()
    val sessionPct = claudeStatus?.sessionPct
    val weeklyPct = claudeStatus?.weeklyPct
    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                MonoLabel(
                    text = stringResource(R.string.home_metrics_title),
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { heading() },
                )
            }
            if (sessionPct != null || weeklyPct != null) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    ) {
                        sessionPct?.let { UsageGauge(stringResource(R.string.metrics_session), it) }
                        weeklyPct?.let { UsageGauge(stringResource(R.string.metrics_week), it) }
                    }
                }
            }
            item { TokensToday(metrics.dailyTokens) }
            val modelLine = buildString {
                claudeStatus?.model?.let { append(it.lowercase()) }
                claudeStatus?.contextSize?.let {
                    if (isNotEmpty()) append(" · ")
                    append(it.lowercase()).append(" ctx")
                }
            }
            if (modelLine.isNotEmpty()) {
                item {
                    Text(
                        text = modelLine,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            claudeStatus?.monthlyCost?.let { cost ->
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) { },
                    ) {
                        Text(text = cost, style = MaterialTheme.typography.displaySmall, maxLines = 1)
                        claudeStatus.monthlyResets?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = stringResource(R.string.metrics_resets, it),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    text = stringResource(R.string.metrics_week_month, shortNum(metrics.weeklyTokens), shortNum(metrics.monthlyTokens)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = MonoFamily,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    HomeButton(
                        label = stringResource(R.string.home_close),
                        style = HomeButtonStyle.OUTLINED,
                        onClick = onClose,
                    )
                }
            }
        }
    }
}

@Composable
private fun UsageGauge(label: String, pct: Double) {
    val value = formatPct(pct)
    ArcGauge(
        fraction = (pct / 100.0).toFloat(),
        valueText = value,
        label = label,
        color = usageColor(pct),
        description = stringResource(R.string.metrics_gauge_cd, label, value),
        diameter = 68.dp,
    )
}

@Composable
private fun TokensToday(value: Long) {
    val text = if (value >= 100_000) shortNum(value) else NumberFormat.getIntegerInstance().format(value)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { },
    ) {
        Text(text = text, style = MaterialTheme.typography.numeralExtraSmall, maxLines = 1)
        MonoLabel(text = "${stringResource(R.string.metrics_tokens)} ${stringResource(R.string.metrics_today)}")
    }
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Uso · detalle")
@Composable
private fun PreviewMetricsDetail() {
    CCWEAROSTheme {
        MetricsDetail(
            claudeStatus = ClaudeStatus(
                model = "Opus",
                contextSize = "1M",
                sessionPct = 24.0,
                weeklyPct = 81.0,
                monthlyCost = "$12.40",
                monthlyResets = "1 nov",
            ),
            metrics = Metrics(dailyTokens = 48_210, weeklyTokens = 1_204_000, monthlyTokens = 4_800_000),
            onClose = {},
        )
    }
}
