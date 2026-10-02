package app.cardpulse.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomCardTest {
    private val obsidian = SetDto(id = "sv3_en", tcgSetId = "sv3", name = "Obsidian Flames", series = "Scarlet & Violet", abbreviation = "OBF", lang = "en")
    private val obsidianDe = SetDto(id = "sv3_de", tcgSetId = "sv3", name = "Obsidian Flammen", series = "Karmesin & Purpur", lang = "de")
    private val homemade = SetDto(id = "my-set", tcgSetId = null, name = "My Set", lang = "fr")

    // --- what the form says is wrong -----------------------------------------------------------

    @Test
    fun `a card needs a name`() {
        assertEquals("Give the card a name.", CustomCardForm().problem())
        assertEquals("Give the card a name.", CustomCardForm(name = "   ").problem())
        assertNull(CustomCardForm(name = "Charizard ex").problem())
    }

    @Test
    fun `an image link has to be https, like the server insists`() {
        val form = CustomCardForm(name = "Charizard ex")
        assertNull(form.copy(imageUrl = "").problem())
        assertNull(form.copy(imageUrl = "   ").problem())
        assertNull(form.copy(imageUrl = "https://example.com/c.png").problem())
        assertNull(form.copy(imageUrl = "  HTTPS://example.com/c.png ").problem())
        assertEquals("The image link has to start with https://", form.copy(imageUrl = "http://example.com/c.png").problem())
        assertEquals("The image link has to start with https://", form.copy(imageUrl = "example.com/c.png").problem())
    }

    @Test
    fun `the name is checked before the image`() {
        assertEquals("Give the card a name.", CustomCardForm(imageUrl = "http://x").problem())
    }

    // --- how the chosen set reads --------------------------------------------------------------

    @Test
    fun `a picked set reads like the website's list`() {
        assertEquals("Obsidian Flames (sv3_en)", CustomCardForm(set = obsidian).setLabel())
    }

    @Test
    fun `a typed set id reads as typed, and nothing reads as nothing`() {
        assertEquals("sv1", CustomCardForm(otherSetId = " sv1 ").setLabel())
        assertNull(CustomCardForm(otherSetId = "  ").setLabel())
        assertNull(CustomCardForm().setLabel())
    }

    @Test
    fun `a picked set is shown rather than text typed before it was picked`() {
        assertEquals("Obsidian Flames (sv3_en)", CustomCardForm(set = obsidian, otherSetId = "sv1").setLabel())
    }

    // --- what is sent --------------------------------------------------------------------------

    @Test
    fun `a card with only a name sends nothing else`() {
        val request = CustomCardForm(name = "  Charizard ex ").toRequest()
        assertEquals(
            CustomCardRequest(name = "Charizard ex", lang = "en", isSharedTemplate = false),
            request,
        )
        assertEquals("""{"name":"Charizard ex","lang":"en","is_shared_template":false}""", AppJson.encodeToString(CustomCardRequest.serializer(), request))
    }

    @Test
    fun `every field is trimmed and blank ones are left out`() {
        val request = CustomCardForm(
            name = "Charizard ex",
            number = " 025 ",
            rarity = "  ",
            hp = " 200",
            artist = "Mitsuhiro Arita ",
            imageUrl = " https://example.com/c.png ",
            shareAsTemplate = true,
        ).toRequest()
        assertEquals("025", request.number) // kept as typed: "025" is not "25"
        assertNull(request.rarity)
        assertEquals("200", request.hp)
        assertEquals("Mitsuhiro Arita", request.artist)
        assertEquals("https://example.com/c.png", request.imageUrl)
        assertTrue(request.isSharedTemplate)
        assertNull(request.setId)
    }

    @Test
    fun `types go out in the website's order, and none means none`() {
        val picked = CustomCardForm(name = "x", types = setOf("Psychic", "Fire", "Stellar")).toRequest()
        assertEquals(listOf("Fire", "Psychic", "Stellar"), picked.types)
        assertNull(CustomCardForm(name = "x").toRequest().types)
        // A type the list doesn't have is not sent.
        assertNull(CustomCardForm(name = "x", types = setOf("Banana")).toRequest().types)
    }

    @Test
    fun `a picked set sends the original set id and its language`() {
        val request = CustomCardForm(name = "x", set = obsidianDe).toRequest()
        assertEquals("sv3", request.setId) // the server keys cards by sv3, not by the per-language row sv3_de
        assertEquals("de", request.lang)
    }

    @Test
    fun `a set without an original id falls back to its own id`() {
        val request = CustomCardForm(name = "x", set = homemade).toRequest()
        assertEquals("my-set", request.setId)
        assertEquals("fr", request.lang)
    }

    @Test
    fun `a typed set id is sent as typed, in English`() {
        val request = CustomCardForm(name = "x", otherSetId = " custom-set ").toRequest()
        assertEquals("custom-set", request.setId)
        assertEquals("en", request.lang)
        assertNull(CustomCardForm(name = "x", otherSetId = " ").toRequest().setId)
    }

    @Test
    fun `a picked set wins over typed text`() {
        val request = CustomCardForm(name = "x", set = obsidian, otherSetId = "zzz").toRequest()
        assertEquals("sv3", request.setId)
    }

    @Test
    fun `the wire format uses the server's field names`() {
        val form = CustomCardForm(
            name = "Charizard ex", set = obsidian, number = "125", rarity = "Double Rare", types = setOf("Fire"),
            hp = "330", artist = "5ban Graphics", imageUrl = "https://example.com/c.png", shareAsTemplate = true,
        )
        assertEquals(
            """{"name":"Charizard ex","set_id":"sv3","number":"125","rarity":"Double Rare","types":["Fire"],"hp":"330",""" +
                """"artist":"5ban Graphics","image_url":"https://example.com/c.png","lang":"en","is_shared_template":true}""",
            AppJson.encodeToString(CustomCardRequest.serializer(), form.toRequest()),
        )
    }

    // --- the set picker ------------------------------------------------------------------------

    private val sets = listOf(obsidian, obsidianDe, homemade)

    @Test
    fun `an empty search lists every set`() {
        assertEquals(sets, sets.matching(""))
        assertEquals(sets, sets.matching("   "))
    }

    @Test
    fun `sets are found by name, id, code, abbreviation or series, ignoring case`() {
        assertEquals(listOf(obsidian, obsidianDe), sets.matching("obsidian"))
        assertEquals(listOf(obsidian), sets.matching("sv3_en"))
        assertEquals(listOf(obsidian, obsidianDe), sets.matching("SV3"))
        assertEquals(listOf(obsidian), sets.matching("obf"))
        assertEquals(listOf(obsidianDe), sets.matching("karmesin"))
        assertEquals(listOf(homemade), sets.matching("my set"))
    }

    @Test
    fun `every word has to match`() {
        assertEquals(listOf(obsidian), sets.matching("obsidian flames"))
        assertEquals(emptyList<SetDto>(), sets.matching("obsidian banana"))
    }

    @Test
    fun `there are twelve types and they are the website's`() {
        assertEquals(12, CardTypes.ALL.size)
        assertEquals("Fire", CardTypes.ALL.first())
        assertEquals("Stellar", CardTypes.ALL.last())
    }
}
