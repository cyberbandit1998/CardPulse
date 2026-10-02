package app.cardpulse.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionEditsTest {
    private fun row(id: Int, card: String = "sv3-125_en", quantity: Int = 1, photo: Boolean = false, lang: String = "en") =
        CollectionItemDto(id = id, cardId = card, quantity = quantity, condition = "NM", variant = "Normal", lang = lang, hasScanPhoto = photo)

    @Test
    fun `a row says which exact copy it is`() {
        assertEquals("NM · Normal · EN", row(1).rowLabel())
        assertEquals("NM · Normal · ZH-TW", row(1, lang = "zh-tw").rowLabel())
    }

    @Test
    fun `removing a row leaves the others in order`() {
        val all = listOf(row(1), row(2), row(3))
        assertEquals(listOf(1, 3), all.without(2).map { it.id })
        assertEquals(listOf(1, 2, 3), all.without(99).map { it.id }) // not there: nothing changes
    }

    @Test
    fun `a changed row stays where it was`() {
        val all = listOf(row(1), row(2, quantity = 4), row(3))
        val changed = all.replacing(row(2, quantity = 3))
        assertEquals(listOf(1, 2, 3), changed.map { it.id })
        assertEquals(3, changed[1].quantity)
    }

    @Test
    fun `the photo goes only with the last row of its card`() {
        val mine = row(1, photo = true)
        // The same card in another condition: the photo is still needed there.
        assertFalse(mine.takesItsPhotoWhenRemoved(listOf(mine, row(2))))
        // Another card entirely: the photo goes.
        assertTrue(mine.takesItsPhotoWhenRemoved(listOf(mine, row(2, card = "sv9-5_en"))))
        // Nothing to lose when there is no photo.
        assertFalse(row(1).takesItsPhotoWhenRemoved(listOf(row(1))))
    }
}
