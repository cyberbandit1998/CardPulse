package app.cardpulse.android.core

private val PRICE_FIELDS = setOf("price_market", "price_trend", "price_avg1", "price_avg7", "price_avg30", "price_low")

/**
 * What the server prices one copy of this card at, in euros, for the user's price field ([priceField], such as
 * "price_trend") and the variant the copy is. It is the choice the server's own valuation makes, so the figures agree with the
 * dashboard's: a Reverse Holo copy uses the reverse listing's price when there is one, and a price of zero counts as missing
 * (Cardmarket sometimes reports zero for "none"), falling back to the market price. 0 when the card has no price at all.
 */
fun CardDto.priceFor(variant: String, priceField: String): Double {
    val field = if (priceField in PRICE_FIELDS) priceField else "price_trend"
    val base = when (field) {
        "price_market" -> priceMarket
        "price_low" -> priceLow
        "price_avg1" -> priceAvg1
        "price_avg7" -> priceAvg7
        "price_avg30" -> priceAvg30
        else -> priceTrend
    }
    fun firstUsable(vararg prices: Double?): Double = prices.firstOrNull { it != null && it > 0.0 } ?: 0.0
    if (variant != "Reverse Holo") return firstUsable(base, priceMarket)
    val reverse = when (field) {
        "price_market" -> priceMarketHolo
        "price_low" -> priceLowHolo
        "price_avg1" -> priceAvg1Holo
        "price_avg7" -> priceAvg7Holo
        "price_avg30" -> priceAvg30Holo
        else -> priceTrendHolo
    }
    return firstUsable(reverse, base, priceMarketHolo, priceMarket)
}

/** One copy of this row's card, in euros ([priceFor]); 0 for a row with no card. */
fun CollectionItemDto.unitPrice(priceField: String): Double = card?.priceFor(variant, priceField) ?: 0.0

/** How many copies a row must hold to pass the "copies" filter. */
enum class CopiesFilter(val label: String) {
    ANY("Any"),
    SINGLE("1 copy"),
    MULTIPLE("2 or more"),
}

/**
 * What the Collection tab narrows its entries to. Choosing several values of one kind (two rarities) shows entries with
 * either; different kinds all have to hold (a rare in near-mint). Nothing chosen narrows nothing.
 */
data class CollectionFilter(
    val rarities: Set<String> = emptySet(),
    val conditions: Set<String> = emptySet(),
    /** "Holo", "Reverse Holo", "Normal", "First Edition": the variant a copy is. */
    val variants: Set<String> = emptySet(),
    val copies: CopiesFilter = CopiesFilter.ANY,
    /** Only cards worth at least this much each, in the currency the user sees amounts in; 0 for no limit. */
    val minValue: Double = 0.0,
    /** Only entries with no purchase price recorded, the ones that make the gain look larger than it is. */
    val missingPrice: Boolean = false,
) {
    /** How many kinds of narrowing are on: the number on the Filter chip. */
    val activeCount: Int
        get() = listOf(
            rarities.isNotEmpty(),
            conditions.isNotEmpty(),
            variants.isNotEmpty(),
            copies != CopiesFilter.ANY,
            minValue > 0.0,
            missingPrice,
        ).count { it }

    val isActive: Boolean get() = activeCount > 0
}

/**
 * Whether this entry passes [filter]. [priceField] and [rateFromEur] say how the user's prices are chosen and converted, as
 * for every amount shown, so "worth at least $5" means $5 as it appears on screen.
 */
fun CollectionItemDto.passes(filter: CollectionFilter, priceField: String, rateFromEur: Double): Boolean {
    if (filter.rarities.isNotEmpty() && card?.rarity !in filter.rarities) return false
    if (filter.conditions.isNotEmpty() && condition !in filter.conditions) return false
    if (filter.variants.isNotEmpty() && variant !in filter.variants) return false
    when (filter.copies) {
        CopiesFilter.ANY -> Unit
        CopiesFilter.SINGLE -> if (quantity != 1) return false
        CopiesFilter.MULTIPLE -> if (quantity < 2) return false
    }
    if (filter.minValue > 0.0 && unitPrice(priceField) * rateFromEur < filter.minValue) return false
    if (filter.missingPrice && purchasePrice != null) return false
    return true
}

/** The entries that pass [filter], in the order they were in. */
fun List<CollectionItemDto>.passing(filter: CollectionFilter, priceField: String, rateFromEur: Double): List<CollectionItemDto> =
    if (!filter.isActive) this else filter { it.passes(filter, priceField, rateFromEur) }

/** What the filter can offer for a collection: only values that some entry has, so no choice ever shows nothing. */
data class CollectionFilterOptions(
    /** The most common first. */
    val rarities: List<String>,
    /** The usual order (Mint to heavily played) for the conditions it knows, then any other. */
    val conditions: List<String>,
    val variants: List<String>,
    /** "Worth at least" steps in the user's currency, up to the most valuable card. Empty when no card has a price. */
    val valueSteps: List<Double>,
    /** Whether any entry holds two or more copies, so "2 or more" can show something. */
    val hasMultiples: Boolean,
    /** Entries with no purchase price. */
    val missingPriceCount: Int,
)

/** The round amounts offered as "worth at least", in any currency; those above the most valuable card are left out. */
private val VALUE_LADDER = listOf(1.0, 5.0, 20.0, 50.0, 100.0, 500.0, 1000.0)

fun List<CollectionItemDto>.filterOptions(priceField: String, rateFromEur: Double): CollectionFilterOptions {
    fun <T> counted(values: List<T>): List<T> =
        values.groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<T, Int>> { it.value }.thenBy { it.key.toString().lowercase() }).map { it.key }

    val conditionOrder = Conditions.ALL
    val mostValuable = maxOfOrNull { it.unitPrice(priceField) * rateFromEur } ?: 0.0
    return CollectionFilterOptions(
        rarities = counted(mapNotNull { it.card?.rarity?.takeIf { rarity -> rarity.isNotBlank() } }),
        conditions = map { it.condition }.distinct().sortedWith(
            compareBy<String> { conditionOrder.indexOf(it).let { index -> if (index < 0) Int.MAX_VALUE else index } }.thenBy { it.lowercase() },
        ),
        variants = map { it.variant }.distinct().sortedWith(
            compareBy<String> { Variants.ALL.indexOf(it).let { index -> if (index < 0) Int.MAX_VALUE else index } }.thenBy { it.lowercase() },
        ),
        valueSteps = VALUE_LADDER.filter { it <= mostValuable },
        hasMultiples = any { it.quantity >= 2 },
        missingPriceCount = count { it.purchasePrice == null },
    )
}
