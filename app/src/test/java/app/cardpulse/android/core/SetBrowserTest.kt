package app.cardpulse.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun setDto(
    id: String,
    name: String,
    total: Int,
    printed: Int = total,
    owned: Int = 0,
    series: String? = null,
    code: String? = null,
    released: String? = null,
    lang: String = "en",
) = SetDto(
    id = id, name = name, total = total, printedTotal = printed, ownedCount = owned, series = series, abbreviation = code,
    releaseDate = released, lang = lang,
)

private fun row(id: Int, cardId: String, set: SetDto?, quantity: Int = 1) = CollectionItemDto(
    id = id, cardId = cardId, quantity = quantity, card = CardDto(id = cardId, name = "Card $cardId", setRef = set),
)

/** The Sets tab's list of every set the server has, with how much of each is owned. */
class SetBrowserTest {
    private val flames = setDto("sv3_en", "Obsidian Flames", 230, 197)
    private val paldea = setDto("sv2_en", "Paldea Evolved", 279, 193)
    private val base = setDto("base1_en", "Base Set", 102)

    @Test
    fun `every set the server lists is there, with none owned for the ones with no cards`() {
        val rows = listOf(row(1, "sv3-125_en", flames))
        val browse = browseSets(listOf(flames, paldea, base), rows)
        assertEquals(listOf("sv3_en", "sv2_en", "base1_en"), browse.map { it.id })
        assertEquals(listOf(1, 0, 0), browse.map { it.owned })
        assertEquals(listOf(230, 279, 102), browse.map { it.total })
    }

    @Test
    fun `a set with none of its cards reads 0 of its size`() {
        val unowned = browseSets(listOf(paldea), emptyList()).single()
        assertEquals("0 / 279", unowned.countText)
        assertEquals(0f, unowned.fraction, 0f)
        assertFalse(unowned.isComplete)
    }

    @Test
    fun `owned sets read like 18 of 132`() {
        val celebration = setDto("cel_en", "30th Celebration", 132)
        val rows = (1..18).map { row(it, "cel-$it", celebration) }
        assertEquals("18 / 132", browseSets(listOf(celebration), rows).single().countText)
    }

    @Test
    fun `different cards count once however many rows or copies hold them`() {
        val rows = listOf(
            row(1, "sv3-125_en", flames),
            row(2, "sv3-125_en", flames, quantity = 3), // the same card in another condition
            row(3, "sv3-001_en", flames, quantity = 4),
        )
        assertEquals(2, browseSets(listOf(flames), rows).single().owned)
    }

    @Test
    fun `rows with no copies left do not count`() {
        assertEquals(0, browseSets(listOf(flames), listOf(row(1, "sv3-1_en", flames, quantity = 0))).single().owned)
    }

    @Test
    fun `a set the collection has cards from but the list does not have is added, so nothing owned is missing`() {
        val german = setDto("sv3_de", "Obsidian-Flammen", 230, lang = "de")
        val browse = browseSets(listOf(paldea), listOf(row(1, "sv3-125_de", german)))
        assertEquals(setOf("sv2_en", "sv3_de"), browse.map { it.id }.toSet())
        val added = browse.first { it.id == "sv3_de" }
        assertEquals(1, added.owned)
        assertEquals("de", added.lang)
    }

    @Test
    fun `a set that is on the list and in the collection appears once`() {
        val browse = browseSets(listOf(flames), listOf(row(1, "sv3-125_en", flames)))
        assertEquals(1, browse.size)
    }

    @Test
    fun `a set listed twice appears once, and one with no id is ignored`() {
        val browse = browseSets(listOf(flames, flames, setDto("", "No id", 10)), emptyList())
        assertEquals(listOf("sv3_en"), browse.map { it.id })
    }

    @Test
    fun `until the collection has loaded the count the server put on the list is used`() {
        val listed = setDto("sv3_en", "Obsidian Flames", 230, owned = 5)
        val browse = browseSets(listOf(listed), emptyList(), collectionLoaded = false)
        assertEquals(5, browse.single().owned)
    }

    @Test
    fun `once the collection has loaded it decides, so a card added or taken out shows at once`() {
        val listed = setDto("sv3_en", "Obsidian Flames", 230, owned = 5) // what the server said a while ago
        assertEquals(1, browseSets(listOf(listed), listOf(row(1, "sv3-1_en", listed))).single().owned)
        assertEquals(0, browseSets(listOf(listed), emptyList()).single().owned)
    }

    @Test
    fun `a set whose size is not known yet shows a dash and is never complete`() {
        val brandNew = setDto("new1_en", "Newly Announced", total = 0, printed = 0)
        val none = browseSets(listOf(brandNew), emptyList()).single()
        assertEquals("0 / —", none.countText)
        val some = browseSets(listOf(brandNew), listOf(row(1, "new1-1_en", brandNew), row(2, "new1-2_en", brandNew))).single()
        assertEquals("2 / —", some.countText)
        assertFalse(some.isComplete)
        assertEquals(0f, some.fraction, 0f)
    }

    @Test
    fun `the printed size is used when the full size is not known`() {
        assertEquals(50, browseSets(listOf(setDto("y_en", "Printed Only", total = 0, printed = 50)), emptyList()).single().total)
    }

    @Test
    fun `a set that grew past the listed size is shown full, not as 12 of 10`() {
        val small = setDto("s_en", "S", 2)
        val browse = browseSets(listOf(small), (1..3).map { row(it, "s-$it", small) }).single()
        assertEquals("3 / 3", browse.countText)
        assertTrue(browse.isComplete)
    }

    @Test
    fun `the list carries each set's code, series, language and date for searching and ordering`() {
        val listed = setDto("sv3_en", "Obsidian Flames", 230, series = "Scarlet & Violet", code = "OBF", released = "2023-08-11")
        val browse = browseSets(listOf(listed), emptyList()).single()
        assertEquals("OBF", browse.code)
        assertEquals("Scarlet & Violet", browse.series)
        assertEquals("2023-08-11", browse.releaseDate)
        assertEquals("en", browse.lang)
    }

    @Test
    fun `a set with no name falls back to its code, then its id`() {
        assertEquals("QQQ", browseSets(listOf(SetDto(id = "q_en", name = "", abbreviation = "QQQ", total = 5)), emptyList()).single().name)
        assertEquals("r_en", browseSets(listOf(SetDto(id = "r_en", name = "", total = 5)), emptyList()).single().name)
    }

    // --- ordering and searching what the list holds -------------------------------------------

    private val listed = listOf(
        setDto("new1_en", "Newly Announced", 0, series = "Scarlet & Violet", released = "2026-05-22"),
        setDto("sv3_en", "Obsidian Flames", 230, series = "Scarlet & Violet", code = "OBF", released = "2023-08-11"),
        setDto("sv2_en", "Paldea Evolved", 279, series = "Scarlet & Violet", code = "PAL", released = "2023-06-09"),
        setDto("swsh12_en", "Silver Tempest", 245, series = "Sword & Shield", code = "SIT", released = "2022-11-11"),
        setDto("base1_en", "Base Set", 102, series = "Base", code = "BS", released = "1999-01-09"),
        setDto("sv9_en", "Promo Set", 10, series = "Promos"), // no date
    )
    private val owned = listOf(row(1, "sv3-1_en", listed[1]), row(2, "sv3-2_en", listed[1]), row(3, "sv9-1_en", listed[5]))

    @Test
    fun `progress puts the sets with cards first, closest to finished first, then the rest newest first`() {
        val ordered = browseSets(listed, owned).ordered(SetOrder.PROGRESS).map { it.id }
        // Promo Set is 1 of 10, Obsidian Flames 2 of 230; none of the others is started, so the newest comes first, and a
        // set with no date goes last.
        assertEquals(listOf("sv9_en", "sv3_en", "new1_en", "sv2_en", "swsh12_en", "base1_en"), ordered)
    }

    @Test
    fun `name is alphabetical and keeps every set`() {
        val ordered = browseSets(listed, owned).ordered(SetOrder.NAME).map { it.name }
        assertEquals(listOf("Base Set", "Newly Announced", "Obsidian Flames", "Paldea Evolved", "Promo Set", "Silver Tempest"), ordered)
    }

    @Test
    fun `cards puts the most owned first`() {
        val ordered = browseSets(listed, owned).ordered(SetOrder.CARDS).map { it.id }
        assertEquals(listOf("sv3_en", "sv9_en"), ordered.take(2))
        assertEquals(6, ordered.size)
    }

    @Test
    fun `search finds a set by its printed code or its series as well as its name`() {
        val browse = browseSets(listed, owned)
        assertEquals(listOf("sv3_en"), browse.matching("obf").map { it.id })
        assertEquals(listOf("sv3_en"), browse.matching("sv3").map { it.id })
        assertEquals(setOf("new1_en", "sv3_en", "sv2_en"), browse.matching("violet").map { it.id }.toSet())
        assertEquals(listOf("swsh12_en"), browse.matching("sword tempest").map { it.id })
    }

    // --- finding one set ------------------------------------------------------------------------

    @Test
    fun `a set is found in the list, or else in the collection's own cards`() {
        val german = setDto("sv3_de", "Obsidian-Flammen", 230, lang = "de")
        val rows = listOf(row(1, "sv3-125_de", german))
        assertEquals("Paldea Evolved", findSet("sv2_en", listOf(paldea), rows)?.name)
        assertEquals("Obsidian-Flammen", findSet("sv3_de", listOf(paldea), rows)?.name)
        assertNull(findSet("nope_en", listOf(paldea), rows))
    }

    // --- what the server really sent ------------------------------------------------------------

    @Test
    fun `the list the server really sent, with the collection it really sent`() {
        val catalogue = Fixtures.decode<List<SetDto>>("sets")
        val collection = Fixtures.decode<List<CollectionItemDto>>("collection")
        val browse = browseSets(catalogue, collection)

        // Seven English sets on the list, and the German Obsidian Flames, which is in the collection but not on the list.
        assertEquals(8, browse.size)
        assertEquals(
            listOf("sv9_en", "sv3_en", "sv3_de", "sv1_en", "new1_en", "sv2_en", "swsh12_en", "base1_en"),
            browse.ordered(SetOrder.PROGRESS).map { it.id },
        )
        fun count(id: String) = browse.first { it.id == id }.countText
        assertEquals("2 / 230", count("sv3_en")) // Charizard ex (two rows) and Oddish
        assertEquals("1 / 258", count("sv1_en"))
        assertEquals("1 / 10", count("sv9_en"))
        assertEquals("1 / 230", count("sv3_de"))
        assertEquals("0 / 279", count("sv2_en"))
        assertEquals("0 / 245", count("swsh12_en"))
        assertEquals("0 / 102", count("base1_en"))
        assertEquals("0 / —", count("new1_en")) // the server has not said how many cards it has
    }

    @Test
    fun `the server's own owned counts agree with the collection it sent`() {
        val catalogue = Fixtures.decode<List<SetDto>>("sets")
        val collection = Fixtures.decode<List<CollectionItemDto>>("collection")
        val fromServer = browseSets(catalogue, emptyList(), collectionLoaded = false).associate { it.id to it.owned }
        val fromCollection = browseSets(catalogue, collection, collectionLoaded = true).associate { it.id to it.owned }
        for ((id, owned) in fromServer) assertEquals("owned in $id", owned, fromCollection.getValue(id))
    }

    @Test
    fun `the list decodes with what each set is, and how many are owned`() {
        val catalogue = Fixtures.decode<List<SetDto>>("sets")
        assertEquals(7, catalogue.size)
        val flames = catalogue.first { it.id == "sv3_en" }
        assertEquals("Obsidian Flames", flames.name)
        assertEquals("sv3", flames.tcgSetId)
        assertEquals("OBF", flames.abbreviation)
        assertEquals("Scarlet & Violet", flames.series)
        assertEquals("2023-08-11", flames.releaseDate)
        assertEquals(230, flames.total)
        assertEquals(197, flames.printedTotal)
        assertEquals(2, flames.ownedCount)
        assertEquals(0, catalogue.first { it.id == "sv2_en" }.ownedCount)
        assertNull(catalogue.first { it.id == "sv9_en" }.releaseDate)
    }
}

/** One set's checklist: every card, and which of them the collection holds. */
class SetChecklistTest {
    private val checklist = Fixtures.decode<SetChecklistDto>("set_checklist")
    private val collection = Fixtures.decode<List<CollectionItemDto>>("collection")

    private fun card(id: String, name: String = "Card $id", number: String? = "1", owned: Int = 0, picture: Boolean = true) =
        ChecklistCardDto(id = id, name = name, number = number, imagesSmall = if (picture) "https://img/$id" else null, ownedQuantity = owned)

    private fun checklistOf(vararg cards: ChecklistCardDto) = SetChecklistDto(set = SetDto(id = "s_en", name = "S"), cards = cards.toList())

    @Test
    fun `the checklist decodes with the set, every card and what the server said was owned`() {
        assertEquals("Obsidian Flames", checklist.set.name)
        assertEquals("sv3_en", checklist.set.id)
        assertEquals(6, checklist.cards.size)
        assertEquals(6, checklist.totalCount)
        assertEquals(2, checklist.ownedCount)
        assertEquals(listOf("001", "002", "003", "020", "125", "223"), checklist.cards.map { it.number })
        val charizard = checklist.cards.first { it.id == "sv3-125_en" }
        assertTrue(charizard.owned)
        assertEquals(2, charizard.ownedQuantity)
        assertEquals(listOf(1, 2), charizard.ownedItems.map { it.id })
        assertEquals(listOf("Normal", "Holo"), charizard.ownedItems.map { it.variant })
        assertFalse(checklist.cards.first { it.id == "sv3-002_en" }.owned)
    }

    @Test
    fun `a card the server has no picture of says so`() {
        assertEquals(listOf(true, true, true, true, true, false), checklist.cards.map { it.hasPicture })
    }

    @Test
    fun `a set with none of its cards owned decodes as all missing`() {
        val none = Fixtures.decode<SetChecklistDto>("set_checklist_unowned_set")
        assertEquals("Paldea Evolved", none.set.name)
        assertEquals(3, none.cards.size)
        assertTrue(none.cards.none { it.owned })
        assertEquals(0, none.ownedCount)
    }

    @Test
    fun `a set the server does not have is its own plain message`() {
        assertEquals("Set not found", errorDetailFromBody(Fixtures.text("set_not_found")))
    }

    @Test
    fun `ownership comes from the collection once it has loaded`() {
        val entries = checklist.entries(collection, collectionLoaded = true)
        assertEquals(listOf(4, 0, 0, 0, 2, 0), entries.map { it.copies }) // Oddish x4, Charizard ex in two rows
        assertEquals(ChecklistTally(owned = 2, total = 6), entries.tally())
    }

    @Test
    fun `a card taken out of the collection shows as missing at once, whatever the server last said`() {
        val withoutOddish = collection.filter { it.cardId != "sv3-001_en" }
        val entries = checklist.entries(withoutOddish, collectionLoaded = true)
        assertFalse(entries.first { it.card.id == "sv3-001_en" }.owned) // the checklist still says owned
        assertEquals(1, entries.tally().owned)
    }

    @Test
    fun `a card owned only in another language is missing here`() {
        val germanOnly = collection.filter { it.cardId == "sv3-125_de" }
        val entries = checklist.entries(germanOnly, collectionLoaded = true)
        assertEquals(0, entries.tally().owned)
    }

    @Test
    fun `until the collection has loaded the server's answer is used`() {
        val entries = checklist.entries(emptyList(), collectionLoaded = false)
        assertEquals(listOf(4, 0, 0, 0, 2, 0), entries.map { it.copies })
    }

    @Test
    fun `rows with no copies left are not owned`() {
        val set = SetDto(id = "s_en", name = "S", total = 3)
        val rows = listOf(row(1, "s-1", set, quantity = 0), row(2, "s-2", set, quantity = 2))
        val entries = checklistOf(card("s-1"), card("s-2")).entries(rows, collectionLoaded = true)
        assertEquals(listOf(0, 2), entries.map { it.copies })
    }

    @Test
    fun `the filters show all, only the owned, or only the missing, in the server's order`() {
        val entries = checklist.entries(collection, collectionLoaded = true)
        assertEquals(6, entries.filtered(ChecklistFilter.ALL).size)
        assertEquals(listOf("001", "125"), entries.filtered(ChecklistFilter.OWNED).map { it.card.number })
        assertEquals(listOf("002", "003", "020", "223"), entries.filtered(ChecklistFilter.MISSING).map { it.card.number })
    }

    @Test
    fun `the tally counts what is owned and what is missing`() {
        val tally = ChecklistTally(owned = 18, total = 132)
        assertEquals("18 / 132", tally.countText)
        assertEquals(114, tally.missing)
        assertEquals(13, tally.percent)
        assertFalse(tally.isComplete)
        assertEquals(18f / 132f, tally.fraction, 0.0001f)
    }

    @Test
    fun `a set is not 100 percent until it really is complete`() {
        assertEquals(99, ChecklistTally(owned = 199, total = 200).percent)
        assertTrue(ChecklistTally(owned = 200, total = 200).isComplete)
        assertEquals(100, ChecklistTally(owned = 200, total = 200).percent)
    }

    @Test
    fun `a checklist with no cards is empty, not complete`() {
        val tally = emptyList<ChecklistEntry>().tally()
        assertEquals(0, tally.total)
        assertEquals(0f, tally.fraction, 0f)
        assertEquals(0, tally.percent)
        assertFalse(tally.isComplete)
    }

    @Test
    fun `every card says in words whether it is owned, so colour is never the only difference`() {
        val entries = checklist.entries(collection, collectionLoaded = true).associateBy { it.card.number }
        assertEquals("Owned ×4", entries.getValue("001").statusText)
        assertEquals("Missing", entries.getValue("002").statusText)
        assertEquals("Owned", ChecklistEntry(card("x", number = "9"), copies = 1).statusText)
        assertEquals("#125", entries.getValue("125").numberText)
        assertEquals("Charizard ex, number 125, owned 2", entries.getValue("125").description)
        assertEquals("Gloom, number 002, missing", entries.getValue("002").description)
        assertEquals("Oddish, number 001, owned 4", entries.getValue("001").description)
        assertEquals("Pikachu, number 5, owned", ChecklistEntry(card("p", "Pikachu", "5"), 1).description)
    }

    @Test
    fun `a card with no number or name is still described`() {
        val entry = ChecklistEntry(card("x", name = "", number = null), copies = 0)
        assertEquals("", entry.numberText)
        assertEquals("Unnamed card, missing", entry.description)
        assertEquals("Missing", entry.statusText)
    }

    @Test
    fun `tapping an owned card finds its collection row, the one the server named first, else any row of that card`() {
        val entries = checklist.entries(collection, collectionLoaded = true).associateBy { it.card.id }
        assertEquals(1, entries.getValue("sv3-125_en").collectionRow(collection)?.id) // the first row the server named
        assertEquals(3, entries.getValue("sv3-001_en").collectionRow(collection)?.id)
        // The server named a row that has since been taken out: any other row of the card will do.
        val others = collection.filter { it.id != 1 }
        assertEquals(2, entries.getValue("sv3-125_en").collectionRow(others)?.id)
        // Nothing left of the card at all.
        assertNull(entries.getValue("sv3-125_en").collectionRow(collection.filter { it.cardId != "sv3-125_en" }))
        assertNotNull(entries.getValue("sv3-001_en").collectionRow(collection))
    }
}

/** The Sets tab's filters: All, Owned, Incomplete and Complete. */
class SetFilterTest {
    private val untouched = SetProgress("a_en", "Untouched", owned = 0, total = 100)
    private val started = SetProgress("b_en", "Started", owned = 3, total = 100)
    private val nearly = SetProgress("c_en", "Nearly", owned = 99, total = 100)
    private val done = SetProgress("d_en", "Done", owned = 100, total = 100)
    private val unsized = SetProgress("e_en", "Unknown size", owned = 2, total = 0)
    private val unsizedEmpty = SetProgress("f_en", "Unknown and empty", owned = 0, total = 0)
    private val sets = listOf(untouched, started, nearly, done, unsized, unsizedEmpty)

    @Test
    fun `all is every set the server lists`() {
        assertEquals(sets, sets.filtered(SetFilter.ALL))
    }

    @Test
    fun `owned is any set with at least one card`() {
        assertEquals(listOf(started, nearly, done, unsized), sets.filtered(SetFilter.OWNED))
    }

    @Test
    fun `incomplete is owned but not complete`() {
        assertEquals(listOf(started, nearly, unsized), sets.filtered(SetFilter.INCOMPLETE))
    }

    @Test
    fun `complete is every card owned`() {
        assertEquals(listOf(done), sets.filtered(SetFilter.COMPLETE))
    }

    @Test
    fun `a set whose size is not known yet is never complete, however many cards are owned`() {
        assertFalse(unsized.matches(SetFilter.COMPLETE))
        assertTrue(unsized.matches(SetFilter.INCOMPLETE))
        assertFalse(unsizedEmpty.matches(SetFilter.OWNED))
    }

    @Test
    fun `the filters keep the order they were given`() {
        val reversed = sets.reversed()
        assertEquals(listOf(unsized, done, nearly, started), reversed.filtered(SetFilter.OWNED))
    }

    @Test
    fun `owned is exactly the incomplete and the complete sets together`() {
        val owned = sets.filtered(SetFilter.OWNED).toSet()
        assertEquals(owned, (sets.filtered(SetFilter.INCOMPLETE) + sets.filtered(SetFilter.COMPLETE)).toSet())
        assertTrue(sets.filtered(SetFilter.INCOMPLETE).intersect(sets.filtered(SetFilter.COMPLETE).toSet()).isEmpty())
    }

    @Test
    fun `an empty list stays empty under every filter`() {
        for (filter in SetFilter.entries) assertTrue(emptyList<SetProgress>().filtered(filter).isEmpty())
    }

    @Test
    fun `the filters follow a set that has just been completed`() {
        val almost = SetDto(id = "s_en", name = "S", total = 2)
        val one = browseSets(listOf(almost), listOf(row(1, "s-1_en", almost)))
        assertEquals(listOf("s_en"), one.filtered(SetFilter.INCOMPLETE).map { it.id })
        val both = browseSets(listOf(almost), listOf(row(1, "s-1_en", almost), row(2, "s-2_en", almost)))
        assertEquals(listOf("s_en"), both.filtered(SetFilter.COMPLETE).map { it.id })
        assertTrue(both.filtered(SetFilter.INCOMPLETE).isEmpty())
    }

    @Test
    fun `the labels are the ones on the chips`() {
        assertEquals(listOf("All", "Owned", "Incomplete", "Complete"), SetFilter.entries.map { it.label })
    }

    @Test
    fun `the real list, filtered`() {
        val browse = browseSets(Fixtures.decode<List<SetDto>>("sets"), Fixtures.decode<List<CollectionItemDto>>("collection"))
        assertEquals(8, browse.filtered(SetFilter.ALL).size)
        assertEquals(setOf("sv9_en", "sv3_en", "sv3_de", "sv1_en"), browse.filtered(SetFilter.OWNED).map { it.id }.toSet())
        assertEquals(4, browse.filtered(SetFilter.INCOMPLETE).size)
        assertTrue(browse.filtered(SetFilter.COMPLETE).isEmpty())
    }
}
