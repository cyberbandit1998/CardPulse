package app.cardpulse.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What is typed into the search box, which sets it names, and what the server is asked for. */
class CatalogSearchTest {
    private val setList = listOf(
        SetDto(id = "tst1_en", tcgSetId = "tst1", name = "30th Celebration", abbreviation = "30C", printedTotal = 128, total = 140),
        SetDto(id = "sv3_en", tcgSetId = "sv3", name = "Obsidian Flames", abbreviation = "OBF", printedTotal = 197, total = 230),
        SetDto(id = "sv1_en", tcgSetId = "sv1", name = "Scarlet & Violet", abbreviation = "SVI", printedTotal = 198, total = 258),
        SetDto(id = "sv3.5_en", tcgSetId = "sv3.5", name = "151", abbreviation = "MEW", printedTotal = 165, total = 207),
        SetDto(id = "cel_en", tcgSetId = "cel25", name = "Celebrations", abbreviation = "CEL", printedTotal = 25, total = 50),
        SetDto(id = "base1_en", tcgSetId = "base1", name = "Base Set", abbreviation = "BS", printedTotal = 102, total = 102),
        SetDto(id = "base2_en", tcgSetId = "base2", name = "Base Set 2", abbreviation = "BS2", printedTotal = 130, total = 130),
        SetDto(id = "ex6_en", tcgSetId = "ex6", name = "Pokémon Dragon", abbreviation = null, printedTotal = 97, total = 100),
    )

    private fun plan(scope: SearchScope, text: String, sets: List<SetDto>? = setList) =
        CatalogQuery(scope, tidySearchText(text)).plan(sets)

    private fun SearchPlan.kinds() = groups.map { it.on }

    private fun SearchPlan.params() = groups.map { it.params }

    // --- reading what was typed ----------------------------------------------------------------

    @Test
    fun `text is tidied to single spaces and folded without accents`() {
        assertEquals("Mitsuhiro Arita", tidySearchText("  Mitsuhiro   Arita \n"))
        assertEquals("pokemon", foldForSearch("Pokémon"))
        assertEquals(foldForSearch("POKÉMON"), foldForSearch("pokemon"))
    }

    @Test
    fun `a collector number is read with or without the size of its set`() {
        assertEquals(CollectorNumber("125", null), parseCollectorNumber("125"))
        assertEquals(CollectorNumber("125", null), parseCollectorNumber("#125"))
        assertEquals(CollectorNumber("130", 128), parseCollectorNumber("130/128"))
        assertEquals(CollectorNumber("130", 128), parseCollectorNumber(" 130 / 128 "))
        assertEquals(CollectorNumber("TG05", null), parseCollectorNumber("TG05"))
        assertEquals(CollectorNumber("TG05", null), parseCollectorNumber("TG05/TG30")) // the size of a subset is no set's size
        assertEquals(CollectorNumber("74a", 102), parseCollectorNumber("74a/102"))
        assertEquals(CollectorNumber("SV011", null), parseCollectorNumber("SV011"))
    }

    @Test
    fun `names and other text are not collector numbers`() {
        assertNull(parseCollectorNumber("Pikachu"))
        assertNull(parseCollectorNumber("Mitsuhiro Arita"))
        assertNull(parseCollectorNumber("OBF 125"))
        assertNull(parseCollectorNumber("12345"))
        assertNull(parseCollectorNumber("130/"))
        assertNull(parseCollectorNumber(""))
    }

    @Test
    fun `other text followed by a number is split in two`() {
        assertEquals("OBF" to "125", parseTrailingNumber("OBF 125"))
        assertEquals("Pikachu" to "58", parseTrailingNumber("Pikachu 58"))
        assertEquals("Obsidian Flames" to "125", parseTrailingNumber("Obsidian Flames #125"))
        assertEquals("Charizard ex" to "223", parseTrailingNumber("Charizard ex 223"))
    }

    @Test
    fun `a number on its own, or with nothing but digits before it, is not split`() {
        assertNull(parseTrailingNumber("125"))
        assertNull(parseTrailingNumber("130 128"))
        assertNull(parseTrailingNumber("130/128"))
        assertNull(parseTrailingNumber("Pikachu"))
        assertNull(parseTrailingNumber("Pikachu 12345"))
    }

    @Test
    fun `a search is only made once enough is typed`() {
        assertFalse(CatalogQuery(SearchScope.ALL, "P").searchable)
        assertTrue(CatalogQuery(SearchScope.ALL, "Pi").searchable)
        assertFalse(CatalogQuery(SearchScope.POKEMON, "").searchable)
        // A card number can be one digit.
        assertTrue(CatalogQuery(SearchScope.NUMBER, "5").searchable)
        assertFalse(CatalogQuery(SearchScope.NUMBER, "").searchable)
    }

    // --- finding sets --------------------------------------------------------------------------

    @Test
    fun `a set is found by its whole name, its code or its id, whatever the case`() {
        assertEquals(listOf("tst1_en"), setList.matchingSets("30th celebration").exact.map { it.id })
        assertEquals(listOf("sv3_en"), setList.matchingSets("OBF").exact.map { it.id })
        assertEquals(listOf("sv3_en"), setList.matchingSets("obf").exact.map { it.id })
        assertEquals(listOf("sv3_en"), setList.matchingSets("sv3").exact.map { it.id })
        assertEquals(listOf("sv3_en"), setList.matchingSets("sv3_en").exact.map { it.id })
        assertEquals(listOf("sv3.5_en"), setList.matchingSets("151").exact.map { it.id })
    }

    @Test
    fun `a set is found by the words of its name in any order, and the accents do not matter`() {
        assertEquals(listOf("cel_en", "tst1_en"), setList.matchingSets("celebration").all.map { it.id }.sorted())
        assertEquals(listOf("sv3_en"), setList.matchingSets("flames obsidian").partial.map { it.id })
        assertEquals(listOf("ex6_en"), setList.matchingSets("pokemon dragon").exact.map { it.id })
        assertEquals(listOf("ex6_en"), setList.matchingSets("Pokémon").partial.map { it.id })
    }

    @Test
    fun `sets named exactly come before sets that only contain the words, and the list's order is kept within each`() {
        val found = setList.matchingSets("base set")
        assertEquals(listOf("base1_en"), found.exact.map { it.id })
        assertEquals(listOf("base2_en"), found.partial.map { it.id })
        assertEquals(listOf("base1_en", "base2_en"), found.all.map { it.id })
    }

    @Test
    fun `a code only matches whole, and what matches nothing finds no set`() {
        // "sv" is the start of the codes "sv1" and "sv3", but a code is only matched whole, and no name holds it.
        assertTrue(setList.matchingSets("sv").all.isEmpty())
        // A word of a name is found inside it, so "ob" finds Obsidian Flames, but not as an exact match.
        assertEquals(listOf("sv3_en"), setList.matchingSets("ob").partial.map { it.id })
        assertTrue(setList.matchingSets("ob").exact.isEmpty())
        assertTrue(setList.matchingSets("nothing like it").all.isEmpty())
        assertTrue(setList.matchingSets("  ").all.isEmpty())
    }

    // --- what to ask for -----------------------------------------------------------------------

    @Test
    fun `a Pokemon or card name is asked for as a name`() {
        val plan = plan(SearchScope.POKEMON, "Pikachu")
        assertEquals(listOf(CardSearchParams(q = "Pikachu")), plan.params())
        assertEquals(listOf(MatchedOn.NAME), plan.kinds())
    }

    @Test
    fun `an artist is asked for as an artist`() {
        assertEquals(listOf(CardSearchParams(artist = "Mitsuhiro Arita")), plan(SearchScope.ARTIST, "Mitsuhiro Arita").params())
    }

    @Test
    fun `a set is asked for by its id, exact matches first`() {
        val plan = plan(SearchScope.SET, "base set")
        assertEquals(listOf(CardSearchParams(setId = "base1_en"), CardSearchParams(setId = "base2_en")), plan.params())
        assertEquals(listOf("tst1_en"), plan(SearchScope.SET, "30th Celebration").params().map { it.setId })
        assertEquals(listOf("sv3_en"), plan(SearchScope.SET, "OBF").params().map { it.setId })
    }

    @Test
    fun `a set that none of the sets is called gives nothing to ask for`() {
        assertTrue(plan(SearchScope.SET, "no such set").groups.isEmpty())
    }

    @Test
    fun `a number is asked for as a number, and with the size of its set only in the sets that print that many cards`() {
        assertEquals(listOf(CardSearchParams(number = "125")), plan(SearchScope.NUMBER, "125").params())
        val sized = plan(SearchScope.NUMBER, "130/128")
        assertEquals(listOf(CardSearchParams(setId = "tst1_en", number = "130")), sized.params())
        assertNull(sized.note)
    }

    @Test
    fun `a number with a size that no set prints is asked for in every set, and says so`() {
        val plan = plan(SearchScope.NUMBER, "130/99")
        assertEquals(listOf(CardSearchParams(number = "130")), plan.params())
        assertEquals("No set prints 99 cards, so these are the cards numbered 130 in every set.", plan.note)
    }

    @Test
    fun `a number with a size, when the sets could not be had, is asked for in every set and says so`() {
        val plan = plan(SearchScope.NUMBER, "130/128", sets = null)
        assertEquals(listOf(CardSearchParams(number = "130")), plan.params())
        assertNotNull(plan.note)
    }

    @Test
    fun `text that is not a number gives nothing to ask for in the Number scope, and says what a number looks like`() {
        val plan = plan(SearchScope.NUMBER, "Pikachu")
        assertTrue(plan.groups.isEmpty())
        assertEquals("That doesn't look like a card number. Try 125, TG05 or 125/197.", plan.note)
    }

    @Test
    fun `All asks for a name, an artist and a rarity, in that order`() {
        val plan = plan(SearchScope.ALL, "Pikachu")
        assertEquals(listOf(MatchedOn.NAME, MatchedOn.ARTIST, MatchedOn.RARITY), plan.kinds())
        assertEquals(
            listOf(CardSearchParams(q = "Pikachu"), CardSearchParams(artist = "Pikachu"), CardSearchParams(rarity = "Pikachu")),
            plan.params(),
        )
    }

    @Test
    fun `All puts a set named in full first, then the name and artist, then sets that only hold the words`() {
        val whole = plan(SearchScope.ALL, "30th Celebration")
        assertEquals(listOf(MatchedOn.SET, MatchedOn.NAME, MatchedOn.ARTIST, MatchedOn.RARITY), whole.kinds())
        assertEquals("tst1_en", whole.groups.first().params.setId)

        val partial = plan(SearchScope.ALL, "celebration")
        assertEquals(listOf(MatchedOn.NAME, MatchedOn.ARTIST, MatchedOn.SET, MatchedOn.SET, MatchedOn.RARITY), partial.kinds())
        assertEquals(listOf("tst1_en", "cel_en"), partial.params().mapNotNull { it.setId })
    }

    @Test
    fun `All asks a number for the sets that print that many cards and for nothing else`() {
        val plan = plan(SearchScope.ALL, "130/128")
        assertEquals(listOf(CardSearchParams(setId = "tst1_en", number = "130")), plan.params())
    }

    @Test
    fun `All puts a set whose whole name is the number before the cards of that number`() {
        val plan = plan(SearchScope.ALL, "151")
        assertEquals(listOf(CardSearchParams(setId = "sv3.5_en"), CardSearchParams(number = "151")), plan.params())
    }

    @Test
    fun `a number with letters is asked for as a number, a name and an artist too`() {
        val plan = plan(SearchScope.ALL, "TG05")
        assertEquals(listOf(MatchedOn.NUMBER, MatchedOn.NAME, MatchedOn.ARTIST), plan.kinds())
        assertEquals(CardSearchParams(number = "TG05"), plan.groups.first().params)
    }

    @Test
    fun `a set code and a number is that set's card, and is never handed to the server as a name`() {
        val all = plan(SearchScope.ALL, "OBF 125")
        assertEquals(CardSearchParams(setId = "sv3_en", number = "125"), all.groups.first().params)
        assertTrue(all.params().none { it.q == "OBF 125" })

        for (scope in listOf(SearchScope.POKEMON, SearchScope.SET, SearchScope.NUMBER)) {
            val plan = plan(scope, "obf 125")
            assertTrue("$scope", CardSearchParams(setId = "sv3_en", number = "125") in plan.params())
            assertTrue("$scope", plan.params().none { it.q == "obf 125" })
        }
    }

    @Test
    fun `a set's name and a number is that set's card`() {
        val plan = plan(SearchScope.SET, "Obsidian Flames 125")
        assertEquals(listOf(CardSearchParams(setId = "sv3_en", number = "125")), plan.params())
    }

    @Test
    fun `a name and a number is asked for as a name and a number, not as a set code`() {
        val plan = plan(SearchScope.ALL, "Pikachu 58")
        assertEquals(listOf(CardSearchParams(q = "Pikachu", number = "58")), plan.params())
        assertEquals(listOf(CardSearchParams(q = "Charizard ex", number = "223")), plan(SearchScope.POKEMON, "Charizard ex 223").params().take(1))
    }

    @Test
    fun `text that the server would read as a set code and a number is never sent as q`() {
        val serverReadsAsCode = Regex("^([A-Za-z][A-Za-z0-9]*)\\s+(\\d+)$")
        val typed = listOf(
            "Pikachu 58", "OBF 125", "obf 125", "Mew 2", "sv3 125", "Base Set 2", "Pikachu 2 58", "Charizard ex 223",
            "Pikachu", "Mitsuhiro Arita", "30th Celebration", "130/128", "TG05", "151", "Pokémon",
        )
        for (scope in SearchScope.entries) for (text in typed) for (sets in listOf(setList, null)) {
            for (group in plan(scope, text, sets).groups) {
                val q = group.params.q ?: continue
                assertFalse("$scope '$text' sent q='$q'", serverReadsAsCode.matches(q))
            }
        }
    }

    @Test
    fun `nothing is asked for until enough is typed`() {
        assertTrue(plan(SearchScope.ALL, "P").groups.isEmpty())
        assertTrue(plan(SearchScope.POKEMON, "").groups.isEmpty())
        assertTrue(plan(SearchScope.NUMBER, "").groups.isEmpty())
    }

    @Test
    fun `without the sets nothing is found by set but a name, an artist and a rarity still are`() {
        val plan = plan(SearchScope.ALL, "30th Celebration", sets = null)
        assertEquals(listOf(MatchedOn.NAME, MatchedOn.ARTIST, MatchedOn.RARITY), plan.kinds())
        assertTrue(plan(SearchScope.SET, "30th Celebration", sets = null).groups.isEmpty())
    }

    @Test
    fun `a rarity is only asked for when the text is long enough to be one`() {
        assertEquals(listOf(MatchedOn.NAME, MatchedOn.ARTIST), plan(SearchScope.ALL, "ex").kinds())
        assertTrue(MatchedOn.RARITY in plan(SearchScope.ALL, "rare").kinds())
    }

    // --- showing a card found ------------------------------------------------------------------

    private val charizard = CardDto(
        id = "sv3-125_en", name = "Charizard ex", setId = "sv3", number = "125", rarity = "Double Rare", artist = "5ban Graphics",
        setRef = SetDto(id = "sv3_en", tcgSetId = "sv3", name = "Obsidian Flames", abbreviation = "OBF"),
    )

    @Test
    fun `a number is shown with the size of its set when it is known and the number is plain digits`() {
        assertEquals("125/197", charizard.printedNumber(setList))
        assertEquals("125", charizard.printedNumber(emptyList()))
        assertEquals("TG05", charizard.copy(number = "TG05").printedNumber(setList))
        assertNull(charizard.copy(number = null).printedNumber(setList))
        assertNull(charizard.copy(number = " ").printedNumber(setList))
        // The set is found by its tcg id too, when the card does not carry the set.
        assertEquals("125/197", charizard.copy(setRef = null).printedNumber(setList))
    }

    @Test
    fun `the subtitle is the set, the number and the rarity, whichever the card has`() {
        assertEquals("Obsidian Flames · #125/197 · Double Rare", charizard.searchSubtitle(setList))
        assertEquals("Obsidian Flames · #125 · Double Rare", charizard.searchSubtitle(emptyList()))
        assertEquals("Obsidian Flames · #125", charizard.copy(rarity = null).searchSubtitle(setList).replace("/197", ""))
    }

    @Test
    fun `an artist the catalogue does not have is left out rather than shown blank`() {
        assertEquals("5ban Graphics", charizard.artistName())
        assertNull(charizard.copy(artist = null).artistName())
        assertNull(charizard.copy(artist = "  ").artistName())
        assertEquals("Mitsuhiro Arita", charizard.copy(artist = " Mitsuhiro Arita ").artistName())
    }
}
