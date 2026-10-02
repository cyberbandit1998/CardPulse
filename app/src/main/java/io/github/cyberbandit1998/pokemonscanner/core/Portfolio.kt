package io.github.cyberbandit1998.pokemonscanner.core

import java.time.Instant

enum class PortfolioRange(val apiPeriod: String, val label: String) {
    WEEK("1w", "1W"),
    MONTH("1m", "1M"),
    QUARTER("3m", "3M"),
    YEAR("1y", "1Y"),
    ALL("max", "All"),
}

data class ChartPoint(val time: Instant, val value: Double)

data class RangeChange(val absolute: Double, val percent: Double?)

/** Snapshots as chart points, oldest first; entries whose date can't be read are dropped. */
fun List<SnapshotDto>.toChartPoints(): List<ChartPoint> =
    mapNotNull { snapshot -> parseServerInstant(snapshot.date)?.let { ChartPoint(it, snapshot.value) } }
        .sortedBy { it.time }

/** Change from the first to the last point, or null with fewer than two points. */
fun List<ChartPoint>.rangeChange(): RangeChange? {
    if (size < 2) return null
    val startValue = first().value
    val endValue = last().value
    val percent = if (startValue > 0.0) (endValue - startValue) / startValue * 100.0 else null
    return RangeChange(endValue - startValue, percent)
}

/**
 * How many collection rows carry a purchase price. PokéCollector counts a missing price as zero
 * cost, so gains look inflated until every row has one; the UI says so using this.
 */
data class CostCoverage(val withCost: Int, val total: Int) {
    val isComplete: Boolean get() = total == 0 || withCost >= total
}

fun List<CollectionItemDto>.costCoverage(): CostCoverage =
    CostCoverage(withCost = count { it.purchasePrice != null }, total = size)
