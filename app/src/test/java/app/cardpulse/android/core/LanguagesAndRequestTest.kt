package app.cardpulse.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguagesAndRequestTest {
    @Test
    fun `language codes are normalised the way the server expects`() {
        assertEquals("de", CardLanguages.normalize(" DE "))
        assertEquals("zh-cn", CardLanguages.normalize("zh_CN"))
        assertEquals("pt-br", CardLanguages.normalize("pt-BR"))
        assertNull(CardLanguages.normalize("xx"))
        assertNull(CardLanguages.normalize(""))
        assertNull(CardLanguages.normalize(null))
    }

    @Test
    fun `every language the server accepts is offered once`() {
        val server = listOf("en", "fr", "es", "es-mx", "it", "pt", "pt-br", "pt-pt", "de", "nl", "pl", "ru", "ja", "ko", "zh-tw", "id", "th", "zh-cn")
        assertEquals(server.sorted(), CardLanguages.ALL.map { it.code }.sorted())
        assertEquals(CardLanguages.ALL.size, CardLanguages.ALL.map { it.code }.toSet().size)
    }

    @Test
    fun `names and labels are never blank`() {
        assertEquals("German", CardLanguages.name("de"))
        assertEquals("DE", CardLanguages.label("de"))
        assertEquals("ZH-TW", CardLanguages.label("zh-tw"))
        assertEquals("XX", CardLanguages.name("xx"))
        assertEquals("", CardLanguages.name(null))
    }

    @Test
    fun `a composite card id is split into card and language`() {
        assertEquals("sv3-125" to "de", CardLanguages.splitCardId("sv3-125_de"))
        assertEquals("sv1-1" to "zh-tw", CardLanguages.splitCardId("sv1-1_zh-tw"))
        assertEquals("sv3-125" to null, CardLanguages.splitCardId("sv3-125"))
        // An underscore that isn't followed by a language belongs to the id.
        assertEquals("custom-ab_cd" to null, CardLanguages.splitCardId("custom-ab_cd"))
    }

    private val english = ScanMatchDto(id = "sv3-125_en", tcgCardId = "sv3-125", lang = "en", name = "Charizard ex")

    @Test
    fun `adding in the scanned language keeps the candidate's own id`() {
        val request = english.toAddRequest(AddEdits(quantity = 2, condition = "LP", variant = "Holo"))
        assertEquals("sv3-125_en", request.cardId)
        assertEquals("sv3-125", request.confirmedCardId)
        assertEquals("en", request.lang)
    }

    @Test
    fun `choosing another language swaps the suffix and the language`() {
        val request = english.toAddRequest(AddEdits(lang = "fr"))
        assertEquals("sv3-125_fr", request.cardId)
        assertEquals("sv3-125", request.confirmedCardId) // still the candidate the server returned
        assertEquals("fr", request.lang)
    }

    @Test
    fun `a language the server does not know falls back to the scanned one`() {
        val request = english.toAddRequest(AddEdits(lang = "klingon"))
        assertEquals("sv3-125_en", request.cardId)
        assertEquals("en", request.lang)
    }

    @Test
    fun `the language can be chosen in the form the server writes it`() {
        assertEquals("sv3-125_zh-cn", english.toAddRequest(AddEdits(lang = "ZH_CN")).cardId)
    }

    @Test
    fun `the choices read as one short line`() {
        assertEquals("LP · Holo · EN", AddEdits(quantity = 2, condition = "LP", variant = "Holo").details("en"))
        assertEquals("NM · Normal · DE", AddEdits(lang = "de").details("en"))
        assertEquals("NM · Normal · JA", AddEdits().details("ja"))
    }
}
