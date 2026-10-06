package app.cardpulse.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {
    @Test
    fun `light and dark ignore the phone, system follows it`() {
        assertFalse(ThemeMode.LIGHT.isDark(phoneIsDark = true))
        assertFalse(ThemeMode.LIGHT.isDark(phoneIsDark = false))
        assertTrue(ThemeMode.DARK.isDark(phoneIsDark = true))
        assertTrue(ThemeMode.DARK.isDark(phoneIsDark = false))
        assertTrue(ThemeMode.SYSTEM.isDark(phoneIsDark = true))
        assertFalse(ThemeMode.SYSTEM.isDark(phoneIsDark = false))
    }

    @Test
    fun `every mode survives being saved and read back`() {
        for (mode in ThemeMode.entries) assertEquals(mode, ThemeMode.fromKey(mode.key))
    }

    @Test
    fun `nothing saved, or something this version does not know, keeps the look the app always had`() {
        assertEquals(ThemeMode.DARK, ThemeMode.DEFAULT)
        assertEquals(ThemeMode.DEFAULT, ThemeMode.fromKey(null))
        assertEquals(ThemeMode.DEFAULT, ThemeMode.fromKey(""))
        assertEquals(ThemeMode.DEFAULT, ThemeMode.fromKey("sepia"))
    }

    @Test
    fun `the keys are distinct, so one mode can not be read back as another`() {
        assertEquals(ThemeMode.entries.size, ThemeMode.entries.map { it.key }.toSet().size)
    }
}

class SetProgressTest {
    private fun set(id: String, name: String, total: Int, printed: Int = total) =
        SetDto(id = id, name = name, total = total, printedTotal = printed)

    private fun item(
        id: Int,
        cardId: String,
        set: SetDto?,
        quantity: Int = 1,
        price: Double? = null,
    ) = CollectionItemDto(
        id = id,
        cardId = cardId,
        quantity = quantity,
        purchasePrice = price,
        card = CardDto(id = cardId, name = "Card $cardId", setRef = set),
    )

    @Test
    fun `one entry per set, counting different cards once however many rows or copies hold them`() {
        val flames = set("sv3_en", "Obsidian Flames", 230, 197)
        val rows = listOf(
            item(1, "sv3-125_en", flames),
            item(2, "sv3-125_en", flames, quantity = 3), // the same card in another condition
            item(3, "sv3-001_en", flames, quantity = 4),
        )
        val progress = rows.setProgress().single()
        assertEquals("sv3_en", progress.id)
        assertEquals("Obsidian Flames", progress.name)
        assertEquals(2, progress.owned)
        assertEquals(230, progress.total)
    }

    @Test
    fun `the set closest to finished comes first, then the one with more cards, then by name`() {
        val big = set("a_en", "Big", 100)
        val small = set("b_en", "Small", 10)
        val twin1 = set("c_en", "Zeta", 20)
        val twin2 = set("d_en", "Alpha", 20)
        val rows = listOf(
            item(1, "a-1", big), item(2, "a-2", big), item(3, "a-3", big), // 3 of 100
            item(4, "b-1", small), // 1 of 10: further on than the big one
            item(5, "c-1", twin1), item(6, "d-1", twin2), // 1 of 20 each: a tie, so by name
        )
        assertEquals(listOf("Small", "Alpha", "Zeta", "Big").map { it.lowercase() }, rows.setProgress().map { it.name.lowercase() })
    }

    @Test
    fun `a set in another language is a set of its own, as on the server`() {
        val en = set("sv3_en", "Obsidian Flames", 230)
        val de = set("sv3_de", "Obsidian-Flammen", 230)
        val progress = listOf(item(1, "sv3-125_en", en), item(2, "sv3-125_de", de)).setProgress()
        assertEquals(setOf("sv3_en", "sv3_de"), progress.map { it.id }.toSet())
        assertTrue(progress.all { it.owned == 1 })
    }

    @Test
    fun `cards with no set, and sets with no size, are left out`() {
        val unsized = set("x_en", "No Size", total = 0, printed = 0)
        val printedOnly = set("y_en", "Printed Only", total = 0, printed = 50)
        val rows = listOf(
            item(1, "custom-1", set = null), // a card made by hand
            item(2, "x-1", unsized),
            item(3, "y-1", printedOnly),
            CollectionItemDto(id = 4, cardId = null, card = null),
        )
        val progress = rows.setProgress()
        assertEquals(listOf("y_en"), progress.map { it.id })
        assertEquals(50, progress.single().total) // the printed size is used when the full size is not known
    }

    @Test
    fun `rows with no copies left do not count`() {
        val s = set("s_en", "S", 10)
        assertTrue(listOf(item(1, "s-1", s, quantity = 0)).setProgress().isEmpty())
    }

    @Test
    fun `a set that grew past the size the server knows is shown full, not as 12 of 10`() {
        val s = set("s_en", "S", 2)
        val rows = (1..3).map { item(it, "s-$it", s) }
        val progress = rows.setProgress().single()
        assertEquals(3, progress.owned)
        assertEquals(3, progress.total)
        assertTrue(progress.isComplete)
        assertEquals(1f, progress.fraction, 0f)
    }

    @Test
    fun `fraction is the part owned, between 0 and 1`() {
        assertEquals(0.25f, SetProgress("s", "S", owned = 1, total = 4).fraction, 0f)
        assertEquals(0f, SetProgress("s", "S", owned = 0, total = 0).fraction, 0f)
        assertFalse(SetProgress("s", "S", owned = 0, total = 0).isComplete)
        assertEquals(1f, SetProgress("s", "S", owned = 9, total = 4).fraction, 0f)
    }

    @Test
    fun `a name that is missing falls back to the abbreviation, then the id`() {
        val abbreviated = SetDto(id = "q_en", name = "", abbreviation = "QQQ", total = 5)
        val bare = SetDto(id = "r_en", name = "", total = 5)
        val names = listOf(item(1, "q-1", abbreviated), item(2, "r-1", bare)).setProgress().map { it.name }.toSet()
        assertEquals(setOf("QQQ", "r_en"), names)
    }

    @Test
    fun `the collection the server really sent`() {
        val progress = Fixtures.decode<List<CollectionItemDto>>("collection").setProgress()
        assertEquals(
            listOf("Promo Set", "Obsidian Flames", "Obsidian-Flammen", "Scarlet & Violet"),
            progress.map { it.name },
        )
        val flames = progress.first { it.id == "sv3_en" }
        assertEquals(2, flames.owned) // Charizard ex (two rows) and Oddish
        assertEquals(230, flames.total)
        assertEquals(1, progress.first { it.id == "sv9_en" }.owned)
        assertEquals(10, progress.first { it.id == "sv9_en" }.total)
    }
}

class CardsMissingCostTest {
    @Test
    fun `counts the copies in rows with no purchase price`() {
        val rows = Fixtures.decode<List<CollectionItemDto>>("collection")
        // Rows 2, 4, 5 and 6 have no price: 1 + 2 + 1 + 1 copies. Rows 1 and 3 do, however many copies they hold.
        assertEquals(5, rows.cardsMissingCost())
    }

    @Test
    fun `nothing missing in an empty collection or when every row has a price`() {
        assertEquals(0, emptyList<CollectionItemDto>().cardsMissingCost())
        assertEquals(0, listOf(CollectionItemDto(id = 1, quantity = 3, purchasePrice = 0.0)).cardsMissingCost())
    }

    @Test
    fun `a price of zero is a price`() {
        assertEquals(2, listOf(CollectionItemDto(id = 1, quantity = 2, purchasePrice = null)).cardsMissingCost())
        assertEquals(0, listOf(CollectionItemDto(id = 1, quantity = 2, purchasePrice = 0.0)).cardsMissingCost())
    }
}

class SetLogoUrlTest {
    @Test
    fun `the logo comes from the server's image cache, whatever path the server lives under`() {
        assertEquals(
            "https://cards.example.com/api/images/set/sv3_en/logo",
            ServerUrls.setLogo("https://cards.example.com/", "sv3_en"),
        )
        assertEquals(
            "https://h.example/poke/api/images/set/sv3_zh-tw/logo",
            ServerUrls.setLogo("https://h.example/poke/", "sv3_zh-tw"),
        )
        assertEquals("", ServerUrls.setLogo("", "sv3_en"))
    }
}

class SetAndNumberTest {
    @Test
    fun `the set and the number, with no rarity`() {
        val card = CardDto(id = "sv3-125_en", name = "Charizard ex", number = "125", rarity = "Double Rare", setRef = SetDto(id = "sv3_en", name = "Obsidian Flames"))
        assertEquals("Obsidian Flames · #125", card.setAndNumber())
    }

    @Test
    fun `leaves out whatever is not known`() {
        assertEquals("#125", CardDto(id = "x", name = "X", number = "125").setAndNumber())
        assertEquals("Obsidian Flames", CardDto(id = "x", name = "X", setRef = SetDto(id = "sv3_en", name = "Obsidian Flames")).setAndNumber())
        assertEquals("", CardDto(id = "x", name = "X", number = " ").setAndNumber())
    }

    @Test
    fun `falls back to the set id when the set row is missing, as the collection list does`() {
        assertEquals("sv3 · #001", CardDto(id = "x", name = "X", setId = "sv3", number = "001").setAndNumber())
    }
}
