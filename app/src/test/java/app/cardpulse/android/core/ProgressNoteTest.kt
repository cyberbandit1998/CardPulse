package app.cardpulse.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class ProgressNoteTest {
    private fun entry(
        upload: Upload = Upload.SENT,
        status: String? = null,
        retryReason: String? = null,
        next: String? = null,
        slow: Boolean = false,
    ) = ScanEntry(
        id = 1, photo = null, upload = upload, jobId = 1, slow = slow,
        item = status?.let { ScanItemDto(id = 1, status = it, retryReason = retryReason, nextAttemptAt = next) },
    )

    @Test
    fun `each stage says what is going on`() {
        assertEquals("Sending to your server…", entry(upload = Upload.SENDING).progressNote().title)
        assertEquals("Sent to your server", entry().progressNote().title)
        assertEquals("Waiting its turn", entry(status = "pending").progressNote().title)
        assertEquals("Your server is reading this card", entry(status = "processing").progressNote().title)
        assertEquals("Waiting to try again", entry(status = "retrying").progressNote().title)
    }

    @Test
    fun `a rate limit is explained, with the time the server will try again`() {
        val zone = ZoneId.of("UTC")
        val locale = Locale.US
        val note = entry(status = "retrying", retryReason = "rate_limit", next = "2026-10-02T14:32:00Z").progressNote(zone, locale)
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(zone)
            .format(Instant.parse("2026-10-02T14:32:00Z"))
        assertEquals("The scanner is rate-limited. It will try again at $time.", note.detail)
    }

    @Test
    fun `a photo waiting to retry always has an explanation, even without a reason or a time`() {
        assertEquals(
            "The scanner asked your server to wait before trying again.",
            entry(status = "retrying").progressNote().detail,
        )
        assertEquals(
            "The scanner's daily quota is used up.",
            entry(status = "retrying", retryReason = "daily_quota").progressNote().detail,
        )
    }

    @Test
    fun `a photo that looks stuck says so and what to do about it`() {
        val calm = entry(status = "processing").progressNote()
        val stuck = entry(status = "processing", slow = true).progressNote()
        assertFalse(calm.detail.contains("stuck"))
        assertTrue(stuck.detail.startsWith(calm.detail))
        assertTrue(stuck.detail.contains("may be stuck"))
        assertTrue(stuck.detail.contains("cancel it"))
        assertEquals(calm.title, stuck.title)
    }
}
