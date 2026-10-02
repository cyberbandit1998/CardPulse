package app.cardpulse.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualAddTest {
    private val charizard = CardDto(
        id = "sv3-125_en", name = "Charizard ex", setId = "sv3", number = "125", rarity = "Double Rare", lang = "en",
        setRef = SetDto(id = "sv3_en", tcgSetId = "sv3", name = "Obsidian Flames", abbreviation = "OBF", lang = "en"),
    )

    // --- the card number -----------------------------------------------------------------------

    @Test
    fun `a printed number loses what comes after the slash`() {
        assertEquals("125", cardNumberForSearch("125/197"))
        assertEquals("125", cardNumberForSearch(" 125 / 197 "))
        assertEquals("TG01", cardNumberForSearch("TG01/TG30"))
    }

    @Test
    fun `a hash sign and spaces are not part of the number`() {
        assertEquals("125", cardNumberForSearch("#125"))
        assertEquals("25", cardNumberForSearch("  #25 "))
    }

    @Test
    fun `leading zeros and letters are kept, the server compares them itself`() {
        assertEquals("025", cardNumberForSearch("025"))
        assertEquals("H04", cardNumberForSearch("H04"))
        assertEquals("74a", cardNumberForSearch("74a"))
    }

    @Test
    fun `no number is no number`() {
        assertNull(cardNumberForSearch(""))
        assertNull(cardNumberForSearch("   "))
        assertNull(cardNumberForSearch("#"))
        assertNull(cardNumberForSearch("/197"))
    }

    // --- what is looked up ---------------------------------------------------------------------

    @Test
    fun `the name is tidied and the number cut down`() {
        val key = CustomCardForm(name = "  Charizard   ex ", number = "125/197").lookup
        assertEquals(LookupKey("Charizard ex", "125"), key)
    }

    @Test
    fun `two spellings of the same thing are the same lookup`() {
        assertEquals(CustomCardForm(name = "Pikachu", number = "25").lookup, CustomCardForm(name = " Pikachu ", number = "#25").lookup)
    }

    @Test
    fun `one letter is not enough to look for`() {
        assertFalse(LookupKey("", null).searchable)
        assertFalse(LookupKey("P", "25").searchable)
        assertTrue(LookupKey("Pi", null).searchable)
        assertTrue(LookupKey("OBF 125", null).searchable) // a set code and a number works too
    }

    @Test
    fun `cards named exactly what was typed come first, the rest keep their order`() {
        val tera = charizard.copy(id = "sv3-1_en", name = "Charizard ex Tera")
        val v = charizard.copy(id = "swsh-2_en", name = "Charizard V")
        val exactDe = charizard.copy(id = "sv3-125_de", name = "charizard EX", lang = "de")
        assertEquals(listOf(exactDe, charizard, tera, v), listOf(tera, v, exactDe, charizard).bestFirst("Charizard ex"))
        assertEquals(listOf(tera, v), listOf(tera, v).bestFirst("Charizard ex"))
    }

    // --- what is known about a card ------------------------------------------------------------

    @Test
    fun `a card's id without its language`() {
        assertEquals("sv3-125", charizard.plainCardId())
        assertEquals("sv3-125", charizard.copy(id = "sv3-125_de", tcgCardId = "sv3-125").plainCardId())
        assertEquals("custom-abc", CardDto(id = "custom-abc").plainCardId())
    }

    @Test
    fun `a card's language comes from the card, else from its id, else it is English`() {
        assertEquals("en", charizard.ownLanguage())
        assertEquals("de", charizard.copy(id = "sv3-125_de", lang = "de").ownLanguage())
        assertEquals("ja", charizard.copy(id = "sv3-125_ja", lang = null).ownLanguage())
        assertEquals("en", CardDto(id = "custom-abc").ownLanguage())
        assertEquals("zh-tw", charizard.copy(id = "x_zh-tw", lang = "ZH_TW").ownLanguage())
    }

    @Test
    fun `the set is named as well as the server allows`() {
        assertEquals("Obsidian Flames", charizard.setName())
        assertEquals("OBF", charizard.copy(setRef = SetDto(id = "sv3_en", name = "", abbreviation = "OBF")).setName())
        assertEquals("sv3", charizard.copy(setRef = null).setName())
        assertNull(charizard.copy(setRef = null, setId = null).setName())
        assertNull(charizard.copy(setRef = null, setId = " ").setName())
    }

    @Test
    fun `the subtitle reads like the scanner's`() {
        assertEquals("Obsidian Flames · #125 · Double Rare", charizard.subtitle())
        assertEquals("Obsidian Flames · #125", charizard.copy(rarity = null).subtitle())
        assertEquals("", CardDto(id = "x").subtitle())
    }

    @Test
    fun `the facts are the types, the hit points and the artist`() {
        val full = AppJson.decodeFromString(
            CardDto.serializer(),
            """{"id":"sv3-125_en","name":"Charizard ex","types":["Fire"],"hp":"330","artist":"5ban Graphics"}""",
        )
        assertEquals("Fire · HP 330 · Illus. 5ban Graphics", full.facts())
        assertEquals(listOf("Fire"), full.typeNames)
        assertEquals("", charizard.facts())
        val dual = full.copy(types = AppJson.parseToJsonElement("""["Fire","Dragon"]"""), artist = null)
        assertEquals("Fire / Dragon · HP 330", dual.facts())
    }

    @Test
    fun `odd types never stop a card from being read`() {
        fun decode(types: String) = AppJson.decodeFromString(CardDto.serializer(), """{"id":"x","name":"n","types":$types}""")
        assertEquals(emptyList<String>(), decode("null").typeNames)
        assertEquals(emptyList<String>(), decode(""""Fire"""").typeNames)
        assertEquals(emptyList<String>(), decode("""[{"name":"Fire"}]""").typeNames)
        assertEquals(listOf("Fire"), decode("""["Fire", null, " "]""").typeNames)
        // A number where text is expected is accepted, as everywhere else in the app.
        assertEquals("330", AppJson.decodeFromString(CardDto.serializer(), """{"id":"x","hp":330}""").hp)
    }

    // --- which variants it comes in -------------------------------------------------------------

    @Test
    fun `the variants a card comes in are named as the collection names them`() {
        val all = charizard.copy(variantsNormal = true, variantsHolo = true, variantsReverse = true, variantsFirstEdition = true)
        assertEquals(listOf("Normal", "Holo", "Reverse Holo", "First Edition"), all.variantNames())
        assertEquals(listOf("Holo"), charizard.copy(variantsNormal = false, variantsHolo = true, variantsReverse = false).variantNames())
        assertEquals(emptyList<String>(), charizard.variantNames()) // the server didn't say
        assertEquals(Variants.ALL, all.variantNames()) // the same names, in the same order, as the choices offered
    }

    @Test
    fun `a variant the card does not come in is swapped for one it does`() {
        val holoOnly = charizard.copy(variantsNormal = false, variantsHolo = true, variantsReverse = false)
        assertEquals("Holo", holoOnly.suggestedVariant("Normal"))
        assertEquals("Holo", holoOnly.suggestedVariant("Holo"))
        val normalAndReverse = charizard.copy(variantsNormal = true, variantsReverse = true, variantsHolo = false)
        assertEquals("Reverse Holo", normalAndReverse.suggestedVariant("Reverse Holo")) // a variant it has stays
        assertEquals("Normal", normalAndReverse.suggestedVariant("First Edition"))
    }

    @Test
    fun `nothing is changed when the server does not say what a card comes in`() {
        assertEquals("Holo", charizard.suggestedVariant("Holo"))
        assertEquals("First Edition", CardDto(id = "custom-abc").suggestedVariant("First Edition"))
    }

    // --- is it already owned? ------------------------------------------------------------------

    @Test
    fun `a result is new, owned or unknown the way a scanned card is`() {
        val row = CollectionItemDto(id = 1, cardId = "sv3-125_en", quantity = 2, condition = "NM", variant = "Holo", lang = "en", card = charizard)
        val index = CollectionIndex(listOf(row))
        assertEquals(Ownership.Unknown, (null as CollectionIndex?).ownershipOf(charizard))
        assertEquals(Ownership.New, index.ownershipOf(charizard.copy(id = "sv3-200_en")))
        val owned = index.ownershipOf(charizard) as Ownership.Owned
        assertEquals(2, owned.total)
        // The same card in another language counts as the same card, with a line for each language.
        assertEquals(2, (index.ownershipOf(charizard.copy(id = "sv3-125_de", lang = "de")) as Ownership.Owned).total)
    }

    // --- what is sent to add it ----------------------------------------------------------------

    @Test
    fun `a catalogue card is added as it is, in its own language`() {
        val request = charizard.toAddRequest(AddEdits(quantity = 3, condition = "LP", variant = "Holo", purchasePrice = 4.5))
        assertEquals(
            AddToCollectionRequest(cardId = "sv3-125_en", quantity = 3, condition = "LP", variant = "Holo", purchasePrice = 4.5, lang = "en"),
            request,
        )
    }

    @Test
    fun `another language swaps the suffix, as for a scanned card`() {
        val request = charizard.toAddRequest(AddEdits(lang = "de"))
        assertEquals("sv3-125_de", request.cardId)
        assertEquals("de", request.lang)
        // Choosing the card's own language changes nothing.
        assertEquals("sv3-125_en", charizard.toAddRequest(AddEdits(lang = "en")).cardId)
        // A language the server doesn't take is ignored rather than sent.
        assertEquals("sv3-125_en", charizard.toAddRequest(AddEdits(lang = "klingon")).cardId)
    }

    @Test
    fun `a result found in German is swapped back to English the same way`() {
        val german = charizard.copy(id = "sv3-125_de", lang = "de")
        val request = german.toAddRequest(AddEdits(lang = "en"))
        assertEquals("sv3-125_en", request.cardId)
        assertEquals("en", request.lang)
    }

    @Test
    fun `a card made by hand keeps its own id and language whatever is asked`() {
        val made = CardDto(id = "custom-abc", name = "Charizard ex", lang = "de", isCustom = true)
        val request = made.toAddRequest(AddEdits(lang = "fr"))
        assertEquals("custom-abc", request.cardId)
        assertEquals("de", request.lang)
        // The id alone is enough to know it was made by hand.
        assertEquals("custom-abc", made.copy(isCustom = false).toAddRequest(AddEdits(lang = "fr")).cardId)
    }

    @Test
    fun `quantity is kept within what the server takes and a negative price is dropped`() {
        assertEquals(1, charizard.toAddRequest(AddEdits(quantity = 0)).quantity)
        assertEquals(999, charizard.toAddRequest(AddEdits(quantity = 5_000)).quantity)
        assertNull(charizard.toAddRequest(AddEdits(purchasePrice = -1.0)).purchasePrice)
        assertEquals(0.0, charizard.toAddRequest(AddEdits(purchasePrice = 0.0)).purchasePrice)
    }

    @Test
    fun `the add body uses the server's field names and sends tags as an empty list`() {
        assertEquals(
            """{"card_id":"sv3-125_en","quantity":2,"condition":"NM","variant":"Normal","printing_details":[],"lang":"en"}""",
            AppJson.encodeToString(AddToCollectionRequest.serializer(), charizard.toAddRequest(AddEdits(quantity = 2))),
        )
    }
}
