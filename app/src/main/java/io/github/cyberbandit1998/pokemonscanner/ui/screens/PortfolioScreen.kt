package io.github.cyberbandit1998.pokemonscanner.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import io.github.cyberbandit1998.pokemonscanner.ui.AppState
import java.text.NumberFormat

@Composable
fun PortfolioScreen(
    state: AppState,
    onRefresh: () -> Unit
) {
    val values = state.portfolio.mapNotNull { it.totalValue ?: it.collectionValue }
    val current = values.lastOrNull() ?: 0.0
    val first = values.firstOrNull() ?: current
    val delta = current - first
    val currency = NumberFormat.getCurrencyInstance()

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Portfolio", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = onRefresh) { Text("Refresh") }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("Current value")
                Text(currency.format(current), style = MaterialTheme.typography.displaySmall)
                Text(
                    "${if (delta >= 0) "+" else ""}${currency.format(delta)} over displayed period",
                    color = if (delta >= 0) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("1 month")
                Spacer(Modifier.height(12.dp))
                PortfolioChart(values, Modifier.fillMaxWidth().height(220.dp))
            }
        }
    }
}

@Composable
private fun PortfolioChart(values: List<Double>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val guideColor = MaterialTheme.colorScheme.outlineVariant

    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val min = values.minOrNull() ?: 0.0
        val max = values.maxOrNull() ?: 1.0
        val range = (max - min).takeIf { it > 0 } ?: 1.0

        drawLine(
            guideColor,
            Offset(0f, size.height),
            Offset(size.width, size.height)
        )

        val path = Path()
        values.forEachIndexed { index, value ->
            val x = size.width * index / (values.size - 1).toFloat()
            val y = size.height - ((value - min) / range * size.height).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, lineColor)
    }
}
