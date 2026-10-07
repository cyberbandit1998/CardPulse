package app.cardpulse.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WishlistTest {
    @Test
    fun ownedCardIdsCountsOnlyRowsWithCopies() {
        val rows = listOf(
            CollectionItemDto(id = 1, cardId = "a", quantity = 2),
            CollectionItemDto(id = 2, cardId = null, card = CardDto(id = "b")),
            CollectionItemDto(id = 3, cardId = "c", quantity = 0),
            CollectionItemDto(id = 4, cardId = ""),
        )
        assertEquals(setOf("a", "b"), rows.ownedCardIds())
    }

    @Test
    fun wishlistCardIdPrefersTheRowsCardId() {
        assertEquals("x", CollectionItemDto(id = 1, cardId = "x", card = CardDto(id = "y")).wishlistCardId())
        assertEquals("y", CollectionItemDto(id = 1, card = CardDto(id = "y")).wishlistCardId())
        assertNull(CollectionItemDto(id = 1).wishlistCardId())
    }

    @Test
    fun targetNeedsBothPricesAndAPositivePrice() {
        assertTrue(isAtOrBelowTarget(4.0, 5.0))
        assertTrue(isAtOrBelowTarget(5.0, 5.0))
        assertFalse(isAtOrBelowTarget(6.0, 5.0))
        assertFalse(isAtOrBelowTarget(null, 5.0))
        assertFalse(isAtOrBelowTarget(4.0, null))
        assertFalse(isAtOrBelowTarget(0.0, 5.0))
    }

    @Test
    fun unpricedCardHasNoWishlistPrice() {
        assertNull(CardDto(id = "a").wishlistPrice("price_trend"))
    }

    @Test
    fun filterMapsToOwnedColumn() {
        assertNull(WishlistFilter.ALL.ownedOrNull)
        assertEquals(false, WishlistFilter.MISSING.ownedOrNull)
        assertEquals(true, WishlistFilter.OWNED.ownedOrNull)
    }
}
