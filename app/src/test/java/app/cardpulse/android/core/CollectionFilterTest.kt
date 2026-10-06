package app.cardpulse.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private fun card(
    id: String,
    rarity: String? = "Common",
    trend: Double? = null,
    market: Double? = null,
    trendHolo: Double? = null,
    marketHolo: Double? = null,
    low: Double? = null,
) = CardDto(id = id, name = "Card $id", rarity = rarity, priceTrend = trend, priceMarket = market, priceTrendHolo = trendHolo, priceMarketHolo = marketHolo, priceLow = low)

private fun entry(
    id: Int,
    card: CardDto?,
    quantity: Int = 1,
    condition: String = "NM",
    variant: String = "Normal",
    paid: Double? = 1.0,
) = CollectionItemDto(id = id, cardId = card?.id, quantity = quantity, condition = condition, variant = variant, purchasePrice = paid, card = card)

/** The price of one copy, chosen the way the server chooses it. */
class CardPriceTest {
    @Test
    fun `the chosen price field is used`() {
        val card = card("a", trend = 8.5, market = 9.1, low = 7.0)
        assertEquals(8.5, card.priceFor("Normal", "price_trend"), 0.0)
        assertEquals(9.1, card.priceFor("Normal", "price_market"), 0.0)
        assertEquals(7.0, card.priceFor("Normal", "price_low"), 0.0)
    }

    @Test
    fun `a price field this app does not know is the trend price`() {
        assertEquals(8.5, card("a", trend = 8.5, market = 9.1).priceFor("Normal", "something-new"), 0.0)
    }

    @Test
    fun `a missing or zero price falls back to the market price, and then to nothing`() {
        assertEquals(9.1, card("a", trend = null, market = 9.1).priceFor("Normal", "price_trend"), 0.0)
        assertEquals(9.1, card("a", trend = 0.0, market = 9.1).priceFor("Normal", "price_trend"), 0.0)
        assertEquals(0.0, card("a").priceFor("Normal", "price_trend"), 0.0)
        assertEquals(0.0, card("a", trend = 0.0, market = 0.0).priceFor("Normal", "price_trend"), 0.0)
    }

    @Test
    fun `holo is valued like any other finish`() {
        val card = card("a", trend = 8.5, trendHolo = 9.0)
        assertEquals(8.5, card.priceFor("Holo", "price_trend"), 0.0)
        assertEquals(8.5, card.priceFor("First Edition", "price_trend"), 0.0)
    }

    @Test
    fun `reverse holo uses the reverse listing's price when there is one`() {
        val card = card("a", trend = 0.12, trendHolo = 0.40)
        assertEquals(0.40, card.priceFor("Reverse Holo", "price_trend"), 0.0)
    }

    @Test
    fun `reverse holo falls back when the reverse price is zero or missing`() {
        assertEquals(0.12, card("a", trend = 0.12, trendHolo = 0.0).priceFor("Reverse Holo", "price_trend"), 0.0)
        assertEquals(0.12, card("a", trend = 0.12).priceFor("Reverse Holo", "price_trend"), 0.0)
        assertEquals(2.0, card("a", trend = null, marketHolo = 2.0).priceFor("Reverse Holo", "price_trend"), 0.0)
        assertEquals(3.0, card("a", market = 3.0).priceFor("Reverse Holo", "price_trend"), 0.0)
    }

    @Test
    fun `a row with no card is worth nothing`() {
        assertEquals(0.0, entry(1, card = null).unitPrice("price_trend"), 0.0)
    }

    @Test
    fun `the prices the collection sent agree with the values the dashboard sent`() {
        val collection = Fixtures.decode<List<CollectionItemDto>>("collection").associateBy { it.id }
        val dashboard = Fixtures.decode<DashboardDto>("dashboard")
        assertEquals(6, dashboard.topCards.size)
        for (top in dashboard.topCards) {
            val row = collection.getValue(top.collectionItemId)
            assertEquals("price of ${top.name} (${top.variant})", top.displayPrice, row.unitPrice("price_trend"), 0.0001)
            assertEquals("value of ${top.name} (${top.variant})", top.totalValue, row.unitPrice("price_trend") * row.quantity, 0.0001)
        }
    }
}

class CollectionFilterTest {
    private val charizard = entry(1, card("c1", rarity = "Double Rare", trend = 8.5), quantity = 1, condition = "NM", variant = "Normal", paid = 5.0)
    private val charizardHolo = entry(2, card("c1", rarity = "Double Rare", trend = 8.5), quantity = 1, condition = "NM", variant = "Holo", paid = null)
    private val oddish = entry(3, card("c2", rarity = "Common", trend = 0.12, trendHolo = 0.40), quantity = 4, condition = "LP", variant = "Reverse Holo", paid = 0.05)
    private val miraidon = entry(4, card("c3", rarity = "Double Rare", trend = 2.4), quantity = 2, condition = "Mint", variant = "Normal", paid = null)
    private val promo = entry(5, card("c4", rarity = "Promo", trend = 1.0), quantity = 1, condition = "NM", variant = "Normal", paid = null)
    private val custom = entry(6, card("custom-1", rarity = null), quantity = 1, condition = "NM", variant = "Normal", paid = null)
    private val all = listOf(charizard, charizardHolo, oddish, miraidon, promo, custom)

    private fun ids(filter: CollectionFilter, rate: Double = 1.0) = all.passing(filter, "price_trend", rate).map { it.id }

    @Test
    fun `no filter shows everything, and is not active`() {
        assertFalse(CollectionFilter().isActive)
        assertEquals(0, CollectionFilter().activeCount)
        assertSame(all, all.passing(CollectionFilter(), "price_trend", 1.0))
    }

    @Test
    fun `rarity shows entries of any chosen rarity`() {
        assertEquals(listOf(1, 2, 4), ids(CollectionFilter(rarities = setOf("Double Rare"))))
        assertEquals(listOf(1, 2, 3, 4), ids(CollectionFilter(rarities = setOf("Double Rare", "Common"))))
    }

    @Test
    fun `a card with no rarity passes no rarity choice`() {
        assertFalse(custom.passes(CollectionFilter(rarities = setOf("Common")), "price_trend", 1.0))
    }

    @Test
    fun `condition shows entries in any chosen condition`() {
        assertEquals(listOf(1, 2, 5, 6), ids(CollectionFilter(conditions = setOf("NM"))))
        assertEquals(listOf(3, 4), ids(CollectionFilter(conditions = setOf("LP", "Mint"))))
    }

    @Test
    fun `variant covers holo and reverse holo`() {
        assertEquals(listOf(2), ids(CollectionFilter(variants = setOf("Holo"))))
        assertEquals(listOf(2, 3), ids(CollectionFilter(variants = setOf("Holo", "Reverse Holo"))))
    }

    @Test
    fun `copies tells a single copy from several`() {
        assertEquals(listOf(1, 2, 5, 6), ids(CollectionFilter(copies = CopiesFilter.SINGLE)))
        assertEquals(listOf(3, 4), ids(CollectionFilter(copies = CopiesFilter.MULTIPLE)))
    }

    @Test
    fun `value is per card, as the user sees it, so a rate changes what passes`() {
        // Euros: Charizard 8.5, Miraidon 2.4, Oddish (reverse holo) 0.40, Promo 1.0.
        assertEquals(listOf(1, 2), ids(CollectionFilter(minValue = 5.0)))
        assertEquals(listOf(1, 2, 4, 5), ids(CollectionFilter(minValue = 1.0)))
        // At 1.1 dollars to the euro, Miraidon is $2.64 and Promo $1.10; at 0.5 the Promo is worth 50 cents.
        assertEquals(listOf(1, 2, 4), ids(CollectionFilter(minValue = 2.5), rate = 1.1))
        assertEquals(listOf(1, 2, 4, 5), ids(CollectionFilter(minValue = 1.0), rate = 1.1))
        assertEquals(listOf(1, 2, 4), ids(CollectionFilter(minValue = 1.0), rate = 0.5))
    }

    @Test
    fun `a card with no price is worth nothing, so it fails any value`() {
        assertFalse(custom.passes(CollectionFilter(minValue = 1.0), "price_trend", 1.0))
        assertTrue(custom.passes(CollectionFilter(minValue = 0.0), "price_trend", 1.0))
    }

    @Test
    fun `missing price shows the entries that have no purchase price`() {
        assertEquals(listOf(2, 4, 5, 6), ids(CollectionFilter(missingPrice = true)))
    }

    @Test
    fun `different kinds all have to hold`() {
        val filter = CollectionFilter(rarities = setOf("Double Rare"), conditions = setOf("NM"), missingPrice = true)
        assertEquals(listOf(2), ids(filter))
        assertEquals(3, filter.activeCount)
        assertTrue(filter.isActive)
    }

    @Test
    fun `the filters never change the order`() {
        assertEquals(listOf(6, 5, 2), all.reversed().passing(CollectionFilter(missingPrice = true, conditions = setOf("NM")), "price_trend", 1.0).map { it.id })
    }

    @Test
    fun `each kind of narrowing counts once, however many values it holds`() {
        val filter = CollectionFilter(rarities = setOf("A", "B", "C"), copies = CopiesFilter.MULTIPLE, minValue = 5.0, variants = setOf("Holo"))
        assertEquals(4, filter.activeCount)
    }

    @Test
    fun `the real collection, filtered`() {
        val collection = Fixtures.decode<List<CollectionItemDto>>("collection")
        fun count(filter: CollectionFilter) = collection.passing(filter, "price_trend", 1.0).size
        assertEquals(6, count(CollectionFilter()))
        assertEquals(1, count(CollectionFilter(variants = setOf("Holo"))))
        assertEquals(1, count(CollectionFilter(variants = setOf("Reverse Holo"))))
        assertEquals(4, count(CollectionFilter(missingPrice = true))) // only two of the six rows have a purchase price
        assertEquals(3, count(CollectionFilter(minValue = 5.0))) // Charizard ex twice, and Glurak-ex at 7.90
    }

    // --- what the filter offers ---------------------------------------------------------------

    @Test
    fun `the options are only the values some entry has, the most common rarity first`() {
        val options = all.filterOptions("price_trend", 1.0)
        assertEquals(listOf("Double Rare", "Common", "Promo"), options.rarities) // 3, 1, 1: ties by name
        assertEquals(listOf("Mint", "NM", "LP"), options.conditions) // the usual order, best condition first
        assertEquals(listOf("Normal", "Holo", "Reverse Holo"), options.variants)
        assertTrue(options.hasMultiples)
        assertEquals(4, options.missingPriceCount)
    }

    @Test
    fun `value steps stop at the most valuable card, in the user's currency`() {
        assertEquals(listOf(1.0, 5.0), all.filterOptions("price_trend", 1.0).valueSteps) // the best card is 8.50
        assertEquals(listOf(1.0, 5.0, 20.0), all.filterOptions("price_trend", 3.0).valueSteps) // 25.50 in a currency worth 3 euros
        assertTrue(listOf(custom).filterOptions("price_trend", 1.0).valueSteps.isEmpty())
    }

    @Test
    fun `an unfamiliar condition or variant is offered after the familiar ones`() {
        val odd = listOf(
            entry(1, card("x"), condition = "Gem Mint", variant = "Cosmos Holo"),
            entry(2, card("y"), condition = "LP", variant = "Normal"),
        )
        val options = odd.filterOptions("price_trend", 1.0)
        assertEquals(listOf("LP", "Gem Mint"), options.conditions)
        assertEquals(listOf("Normal", "Cosmos Holo"), options.variants)
    }

    @Test
    fun `an empty collection offers nothing`() {
        val options = emptyList<CollectionItemDto>().filterOptions("price_trend", 1.0)
        assertTrue(options.rarities.isEmpty())
        assertTrue(options.conditions.isEmpty())
        assertTrue(options.valueSteps.isEmpty())
        assertFalse(options.hasMultiples)
        assertEquals(0, options.missingPriceCount)
    }

    @Test
    fun `whole amounts in the display currency have no cents`() {
        val formatter = MoneyFormatter("USD", 1.0, java.util.Locale.US)
        assertEquals("$5", formatter.wholeDisplayAmount(5.0))
        assertEquals("$1,000", formatter.wholeDisplayAmount(1000.0))
    }
}
