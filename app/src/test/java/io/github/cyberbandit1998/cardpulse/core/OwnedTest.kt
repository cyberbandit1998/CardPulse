package io.github.cyberbandit1998.cardpulse.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnedTest {
    private val collection = Fixtures.decode<List<CollectionItemDto>>("collection")
    private val index = CollectionIndex(collection)

    private fun owned(id: String) = index.ownershipOf(id) as Ownership.Owned

    @Test
    fun `copies in several rows and languages add up`() {
        // sv3-125 is in the fixture as EN NM Normal, EN NM Holo and DE NM Normal.
        val charizard = owned("sv3-125")
        assertEquals(3, charizard.total)
        assertEquals(3, Ownership.Owned(3, emptyList()).copies)
        assertEquals(
            listOf("1× NM · Normal · DE", "1× NM · Holo · EN", "1× NM · Normal · EN"),
            charizard.lines.map { it.describe() },
        )
    }

    @Test
    fun `quantities are summed per kind of copy and the biggest stack comes first`() {
        assertEquals(listOf("4× LP · Reverse Holo · EN"), owned("sv3-001").lines.map { it.describe() })
        assertEquals(listOf("2× Mint · Normal · EN"), owned("sv1-198").lines.map { it.describe() })
    }

    @Test
    fun `a card that is not in the collection is new`() {
        assertSame(Ownership.New, index.ownershipOf("sv1-1"))
        assertEquals(0, Ownership.New.copies)
    }

    @Test
    fun `rows that differ only by price count as one kind of copy`() {
        val first = CollectionItemDto(id = 1, cardId = "sv1-1_en", quantity = 1, purchasePrice = 2.0, card = CardDto(id = "sv1-1_en", tcgCardId = "sv1-1"))
        val second = first.copy(id = 2, quantity = 2, purchasePrice = null)
        val result = CollectionIndex(listOf(first, second)).ownershipOf("sv1-1") as Ownership.Owned
        assertEquals(3, result.total)
        assertEquals(1, result.lines.size)
        assertEquals("3× NM · Normal · EN", result.lines.single().describe())
    }

    @Test
    fun `a scanned candidate is matched by its card regardless of language`() {
        val german = ScanMatchDto(id = "sv3-125_de", tcgCardId = "sv3-125", name = "Glurak ex")
        val japanese = ScanMatchDto(id = "sv3-125_ja", name = "リザードンex") // no tcg_card_id: falls back to the id
        assertEquals(3, index.ownershipOf(german).copies)
        assertEquals(3, index.ownershipOf(japanese).copies)
        assertSame(Ownership.New, index.ownershipOf(ScanMatchDto(id = "sv9-1_en", tcgCardId = "sv9-1", name = "x")))
    }

    @Test
    fun `a collection that has not loaded is unknown, never new`() {
        val noCollection: CollectionIndex? = null
        assertSame(Ownership.Unknown, noCollection.ownershipOf(ScanMatchDto(id = "sv3-125_en", name = "x")))
    }

    @Test
    fun `rows without a card or with no copies are ignored`() {
        val rows = listOf(
            CollectionItemDto(id = 1, cardId = null, quantity = 3),
            CollectionItemDto(id = 2, cardId = "sv1-1_en", quantity = 0, card = CardDto(id = "sv1-1_en", tcgCardId = "sv1-1")),
        )
        assertSame(Ownership.New, CollectionIndex(rows).ownershipOf("sv1-1"))
    }

    @Test
    fun `the id is read from the row when the card object is missing`() {
        val bare = CollectionItemDto(id = 1, cardId = "sv1-1_de", quantity = 2, lang = "de")
        val result = CollectionIndex(listOf(bare)).ownershipOf("sv1-1") as Ownership.Owned
        assertEquals("2× NM · Normal · DE", result.lines.single().describe())
    }

    @Test
    fun `headlines read naturally`() {
        assertEquals("Checking your collection…", Ownership.Unknown.headline())
        assertEquals("New to your collection", Ownership.New.headline())
        assertEquals("You already own 1 copy", Ownership.Owned(1, emptyList()).headline())
        assertEquals("You already own 3 copies", owned("sv3-125").headline())
    }

    @Test
    fun `a candidate knows its card and language`() {
        val withFields = ScanMatchDto(id = "sv3-125_de", tcgCardId = "sv3-125", lang = "de", name = "x")
        assertEquals("sv3-125", withFields.plainCardId())
        assertEquals("de", withFields.scannedLanguage())
        val bare = ScanMatchDto(id = "sv1-1_zh-tw", name = "x")
        assertEquals("sv1-1", bare.plainCardId())
        assertEquals("zh-tw", bare.scannedLanguage())
        assertTrue(ScanMatchDto(id = "sv1-1", name = "x").scannedLanguage() == "en")
    }
}
