package app.cardpulse.android.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.cardpulse.android.core.ChartPoint
import app.cardpulse.android.core.MoneyFormatter
import app.cardpulse.android.core.PortfolioRange
import app.cardpulse.android.core.costCoverage
import app.cardpulse.android.core.rangeChange
import app.cardpulse.android.ui.AccentTextButton
import app.cardpulse.android.ui.AppState
import app.cardpulse.android.ui.BottomBarOverhang
import app.cardpulse.android.ui.formatDate
import app.cardpulse.android.ui.gainColor

/** How tall the history chart is: short enough that the breakdown below it is mostly on the screen. */
private val CHART_HEIGHT = 128.dp

@Composable
fun PortfolioScreen(
    state: AppState,
    onShowHistory: (PortfolioRange, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val money = remember(state.prefs.currency, state.prefs.rateFromEur) {
        MoneyFormatter(state.prefs.currency, state.prefs.rateFromEur)
    }
    val dashboard = state.dashboard
    val change = remember(state.history) { state.history.rangeChange() }
    val coverage = remember(state.collection) { state.collection.costCoverage() }

    // Load the chart the first time this tab is shown. Fetching it makes the server store a snapshot, so the
    // app reuses a recent fetch instead of asking again on every visit.
    LaunchedEffect(Unit) { onShowHistory(state.historyRange, false) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Portfolio", style = MaterialTheme.typography.headlineMedium)

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Total value", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(dashboard?.let { money.format(it.totalValue) } ?: "—", style = MaterialTheme.typography.displaySmall)
                change?.let {
                    val percent = it.percent?.let { value -> " (${money.percent(value)})" }.orEmpty()
                    Text(
                        "${money.signed(it.absolute)}$percent over ${state.historyRange.label}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = gainColor(it.absolute),
                    )
                }
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    PortfolioRange.entries.forEach { range ->
                        FilterChip(
                            selected = state.historyRange == range,
                            onClick = { onShowHistory(range, false) },
                            label = { Text(range.label) },
                        )
                    }
                }
                if (state.historyLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.history.size < 2) {
                    Box(Modifier.fillMaxWidth().height(CHART_HEIGHT), contentAlignment = Alignment.Center) {
                        Text(
                            if (state.historyLoading) "Loading…" else "Not enough history yet. PokéCollector records a point each time prices update.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    PortfolioChart(state.history, Modifier.fillMaxWidth().height(CHART_HEIGHT))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatDate(state.history.first().time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "Low ${money.format(state.history.minOf { it.value })} · High ${money.format(state.history.maxOf { it.value })}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(formatDate(state.history.last().time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                AccentTextButton(onClick = { onShowHistory(state.historyRange, true) }, enabled = !state.historyLoading) { Text("Refresh chart") }
            }
        }

        if (dashboard != null) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Breakdown", style = MaterialTheme.typography.titleMedium)
                    Line("Cards", money.format(dashboard.cardValue))
                    if (dashboard.productValue != 0.0) Line("Sealed products", money.format(dashboard.productValue))
                    Line("Cost basis", money.format(dashboard.totalCost))
                    Line("Unrealized gain / loss", money.signed(dashboard.unrealizedPnl), gainColor(dashboard.unrealizedPnl))
                    if (dashboard.realizedPnl != 0.0) Line("Realized gain / loss", money.signed(dashboard.realizedPnl), gainColor(dashboard.realizedPnl))
                    if (state.collectionLoaded && !coverage.isComplete) {
                        Text(
                            "Only ${coverage.withCost} of ${coverage.total} collection entries have a purchase price, and a " +
                                "missing price counts as zero cost, so gains look larger than they are.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (state.movers.isNotEmpty()) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Biggest movers, last 7 days", style = MaterialTheme.typography.titleMedium)
                    state.movers.take(8).forEach { mover ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(mover.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${money.format(mover.oldPrice)} → ${money.format(mover.currentPrice)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                money.percent(mover.changePct),
                                style = MaterialTheme.typography.titleSmall,
                                color = gainColor(mover.changeAbs),
                                modifier = Modifier.width(72.dp),
                            )
                        }
                    }
                }
            }
        }

        // The round camera button rises above the bottom bar; leave it room at the end.
        Spacer(Modifier.height(BottomBarOverhang))
    }
}

@Composable
private fun Line(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
    }
}

/** A line chart with a soft fill under it. Points are spaced by time, not by count, so gaps show as gaps. */
@Composable
private fun PortfolioChart(points: List<ChartPoint>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val guideColor = MaterialTheme.colorScheme.outlineVariant

    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val values = points.map { it.value }
        val low = values.min()
        val high = values.max()
        val span = (high - low).takeIf { it > 1e-9 } ?: 1.0
        val start = points.first().time.toEpochMilli()
        val duration = (points.last().time.toEpochMilli() - start).coerceAtLeast(1L)

        val topPad = 8.dp.toPx()
        val bottomPad = 8.dp.toPx()
        val plotHeight = size.height - topPad - bottomPad

        fun xOf(point: ChartPoint) = size.width * (point.time.toEpochMilli() - start).toFloat() / duration
        fun yOf(point: ChartPoint) = topPad + plotHeight - ((point.value - low) / span * plotHeight).toFloat()

        drawLine(guideColor, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())

        val line = Path()
        val area = Path()
        points.forEachIndexed { index, point ->
            val x = xOf(point)
            val y = yOf(point)
            if (index == 0) {
                line.moveTo(x, y)
                area.moveTo(x, size.height)
                area.lineTo(x, y)
            } else {
                line.lineTo(x, y)
                area.lineTo(x, y)
            }
        }
        area.lineTo(xOf(points.last()), size.height)
        area.close()

        drawPath(area, brush = Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.28f), Color.Transparent)))
        drawPath(
            line,
            color = lineColor,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
