package app.cardpulse.android.core

import java.time.Instant

enum class PortfolioRange(val apiPeriod: String, val label: String) {
    WEEK("1w", "1W"),
    MONTH("1m", "1M"),
    QUARTER("3m", "3M"),
    YEAR("1y", "1Y"),
    ALL("max", "All"),
}

data class ChartPoint(val time: Instant, val value: Double)

/** [percent] is null when a percentage would say nothing true: see [rangeChange]. */
data class RangeChange(val absolute: Double, val percent: Double?)

/** Snapshots as chart points, oldest first; entries whose date can't be read are dropped. */
fun List<SnapshotDto>.toChartPoints(): List<ChartPoint> =
    mapNotNull { snapshot -> parseServerInstant(snapshot.date)?.let { ChartPoint(it, snapshot.value) } }
        .sortedBy { it.time }

/**
 * A portfolio that began with less than this (in the server's currency, euros) has no percentage worth showing: going from
 * 17 cents to 115 euros is not "+68246.7%", it is a collection that was just started.
 */
const val MIN_START_FOR_PERCENT = 1.0

/** More than eleven times the start (+1000%) is a collection that grew by adding cards, not a figure worth quoting. */
const val MAX_PERCENT_SHOWN = 1000.0

/**
 * Change from the first to the last point, or null with fewer than two points. The percentage is left out ([RangeChange.percent]
 * is null, and the screen shows only the amount) when the start was zero or too small to measure from ([MIN_START_FOR_PERCENT]),
 * or when the gain is beyond [MAX_PERCENT_SHOWN]. A loss is at most 100%, so it always has one.
 */
fun List<ChartPoint>.rangeChange(): RangeChange? {
    if (size < 2) return null
    val startValue = first().value
    val endValue = last().value
    val percent = if (startValue >= MIN_START_FOR_PERCENT) (endValue - startValue) / startValue * 100.0 else null
    return RangeChange(endValue - startValue, percent?.takeIf { it <= MAX_PERCENT_SHOWN })
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

/** Copies, not entries, in the rows that carry no purchase price: the cards a gain figure is not honest about. */
fun List<CollectionItemDto>.cardsMissingCost(): Int =
    filter { it.purchasePrice == null }.sumOf { it.quantity.coerceAtLeast(0) }

/**
 * The entry the server values highest, or null when it lists none. The server sends them best first, but this does not
 * rely on that; when two are worth the same, the one the server listed first wins.
 */
fun DashboardDto.topCard(): TopCardDto? = topCards.maxByOrNull { it.totalValue }
