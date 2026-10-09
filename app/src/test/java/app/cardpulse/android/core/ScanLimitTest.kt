package app.cardpulse.android.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * The daily scan limit as the app reads it: what is used and left, whether the day is used up, when it starts again in the
 * phone's own time, and how a 429 from the server's limit is told from its rate limiter's. Every body here is what the real
 * routes answered (see the fixtures); the limit itself is enforced on the server.
 */
class ScanLimitTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val newYork = ZoneId.of("America/New_York")

    /** Noon on the 9th in Berlin, which the fixtures were captured at. */
    private val noon = Instant.parse("2026-10-09T10:00:00Z")

    private fun allowance(name: String): ScanAllowanceDto = Fixtures.decode(name)

    private fun error(code: Int, body: String) =
        HttpException(Response.error<Any>(code, body.toResponseBody("application/json".toMediaType())))

    // --- reading what the server sent ---------------------------------------------------------------------------------

    @Test
    fun `what the server says about a user with a limit is read as it was sent`() {
        val limited = allowance("scan_limit_me")

        assertEquals(100, limited.dailyLimit)
        assertEquals(23, limited.used)
        assertEquals(77, limited.remaining)
        assertFalse(limited.isUnlimited)
        assertEquals("2026-10-10T00:00:00+02:00", limited.resetsAt)
        assertEquals(43_200L, limited.resetsInSeconds)
        assertEquals("Europe/Berlin", limited.timezone)
    }

    @Test
    fun `a user with no limit has no number to be used up`() {
        val unlimited = allowance("scan_limit_me_unlimited")

        assertTrue(unlimited.isUnlimited)
        assertNull(unlimited.dailyLimit)
        assertNull(unlimited.remaining)
        assertNull(unlimited.scansLeft)
        assertEquals(12, unlimited.used)
    }

    @Test
    fun `a server that sends less than all of it is still understood`() {
        assertTrue(AppJson.decodeFromString<ScanAllowanceDto>("""{"unlimited": true}""").isUnlimited)
        assertTrue(AppJson.decodeFromString<ScanAllowanceDto>("{}").isUnlimited) // no limit is said, so none is assumed
        val onlyNumbers = AppJson.decodeFromString<ScanAllowanceDto>("""{"daily_limit": 10, "used": 4}""")
        assertEquals(6, onlyNumbers.scansLeft) // worked out when the server did not say
    }

    @Test
    fun `the scans left never go below zero`() {
        assertEquals(0, ScanAllowanceDto(dailyLimit = 10, used = 14, remaining = -4).scansLeft)
        assertEquals(0, ScanAllowanceDto(dailyLimit = 10, used = 14).scansLeft)
    }

    // --- what the camera says ---------------------------------------------------------------------------------------------

    @Test
    fun `a limited user sees how many of their scans are used today`() {
        assertEquals("23 of 100 scans used today", allowance("scan_limit_me").usageText())
        assertEquals("0 of 5 scans used today", ScanAllowanceDto(dailyLimit = 5, used = 0, remaining = 5).usageText())
    }

    @Test
    fun `an unlimited user sees the word Unlimited instead of numbers`() {
        assertEquals("Unlimited scans", allowance("scan_limit_me_unlimited").usageText())
        assertEquals("Unlimited scans", ScanAllowanceDto(dailyLimit = 50, unlimited = true).usageText())
    }

    // --- used up, and the day starting again -----------------------------------------------------------------------------

    @Test
    fun `the day is used up when no scan is left and it has not started over`() {
        val reached = Fixtures.decode<ScanAllowanceDto>("scan_limit_reached")

        assertTrue(reached.isUsedUp(noon))
        assertFalse(allowance("scan_limit_me").isUsedUp(noon))
        assertFalse(allowance("scan_limit_me_unlimited").isUsedUp(noon))
    }

    @Test
    fun `a used up day is not held against the user once it has started over`() {
        val reached = Fixtures.decode<ScanAllowanceDto>("scan_limit_reached")
        val reset = Instant.parse("2026-10-09T22:00:00Z") // 2026-10-10 00:00 in Berlin

        assertTrue(reached.isUsedUp(reset.minusSeconds(1)))
        assertFalse(reached.isUsedUp(reset))
        assertFalse(reached.isUsedUp(reset.plusSeconds(3600)))
    }

    @Test
    fun `with no reset time given a used up day stays used up until the server says otherwise`() {
        assertTrue(ScanAllowanceDto(dailyLimit = 3, used = 3, remaining = 0).isUsedUp(noon))
    }

    @Test
    fun `a limit of zero is used up from the start`() {
        assertTrue(ScanAllowanceDto(dailyLimit = 0, used = 0, remaining = 0, resetsAt = "2026-10-10T00:00:00+02:00").isUsedUp(noon))
    }

    // --- when scans start again ---------------------------------------------------------------------------------------------

    @Test
    fun `scans resetting at the phones midnight are said to reset at midnight, with how long that is`() {
        val reached = Fixtures.decode<ScanAllowanceDto>("scan_limit_reached")
        val evening = Instant.parse("2026-10-09T16:48:00Z") // 18:48 in Berlin

        assertEquals("Scans reset at midnight (in 5 h 12 min)", reached.resetText(evening, berlin, Locale.US))
    }

    @Test
    fun `the reset is told in the phones own time zone, not the servers`() {
        val reached = Fixtures.decode<ScanAllowanceDto>("scan_limit_reached")

        // Midnight in Berlin is 6 PM the same evening in New York.
        assertEquals("Scans reset at 6:00 PM (in 12 h)", reached.resetText(noon, newYork, Locale.US).orEmpty().replace(' ', ' '))
    }

    @Test
    fun `a reset on the next day is called tomorrow`() {
        val reached = Fixtures.decode<ScanAllowanceDto>("scan_limit_reached")
        val tokyo = ZoneId.of("Asia/Tokyo")

        // Midnight in Berlin is 7 AM on the 10th in Tokyo; it is the 9th there now.
        assertEquals("Scans reset tomorrow at 7:00 AM (in 12 h)", reached.resetText(noon, tokyo, Locale.US).orEmpty().replace(' ', ' '))
    }

    @Test
    fun `the time is written the way the phones language writes it`() {
        val reached = Fixtures.decode<ScanAllowanceDto>("scan_limit_reached")

        assertEquals("Scans reset at 18:00 (in 12 h)", reached.resetText(noon, newYork, Locale.GERMANY))
    }

    @Test
    fun `when only the seconds are given the reset is counted from now`() {
        val onlySeconds = ScanAllowanceDto(dailyLimit = 3, used = 3, remaining = 0, resetsInSeconds = 5_400)

        assertEquals("Scans reset at 7:30 AM (in 1 h 30 min)", onlySeconds.resetText(noon, newYork, Locale.US).orEmpty().replace(' ', ' '))
    }

    @Test
    fun `nothing is said about the reset when the server did not say`() {
        assertNull(ScanAllowanceDto(dailyLimit = 3, used = 3, remaining = 0).resetText(noon, berlin, Locale.US))
    }

    @Test
    fun `how long is left is told in hours and minutes`() {
        assertEquals("in 5 h 12 min", untilText(Duration.ofMinutes(5 * 60 + 12)))
        assertEquals("in 5 h", untilText(Duration.ofHours(5)))
        assertEquals("in 42 min", untilText(Duration.ofMinutes(42)))
        assertEquals("in 1 min", untilText(Duration.ofSeconds(61)))
        assertEquals("in less than a minute", untilText(Duration.ofSeconds(59)))
        assertEquals("now", untilText(Duration.ZERO))
        assertEquals("now", untilText(Duration.ofMinutes(-3)))
    }

    // --- telling the limit's 429 from the rate limiter's ----------------------------------------------------------------------

    @Test
    fun `the 429 of the daily limit carries the numbers`() {
        val reached = error(429, Fixtures.text("scan_limit_reached")).scanLimitAllowance()

        assertNotNull(reached)
        assertEquals(100, reached!!.dailyLimit)
        assertEquals(100, reached.used)
        assertEquals(0, reached.remaining)
        assertEquals("2026-10-10T00:00:00+02:00", reached.resetsAt)
        assertTrue(reached.isUsedUp(noon))
    }

    @Test
    fun `the rate limiters 429 is not the daily limit, because waiting a minute does help`() {
        assertNull(error(429, Fixtures.text("scan_limit_rate_limited")).scanLimitAllowance())
        assertNull(error(429, """{"detail": "Too many login attempts. Try again in 1 minute."}""").scanLimitAllowance())
    }

    @Test
    fun `only a 429 is the daily limit, whatever else a body says`() {
        assertNull(error(500, Fixtures.text("scan_limit_reached")).scanLimitAllowance())
        assertNull(error(404, Fixtures.text("scan_limit_reached")).scanLimitAllowance())
    }

    @Test
    fun `a body that is not what the server sends is not mistaken for the limit`() {
        for (body in listOf("", "   ", "not json", "[]", """{"code": "something_else"}""", """{"detail": "x"}""")) {
            assertNull(body, scanLimitAllowanceFromBody(body))
        }
        // The code alone says what it is; the rest falls back to what is unknown.
        assertNotNull(scanLimitAllowanceFromBody("""{"code": "scan_limit_reached"}"""))
    }

    @Test
    fun `reading the body leaves it there for the next one to read`() {
        val failure = error(429, Fixtures.text("scan_limit_reached"))

        assertNotNull(failure.scanLimitAllowance())
        assertNotNull(failure.scanLimitAllowance())
        assertEquals("Daily scan limit reached. Scans reset at midnight (Europe/Berlin).", failure.userMessage())
    }

    // --- the words shown for an error ---------------------------------------------------------------------------------------------

    @Test
    fun `an error from the daily limit says what the server said, not that there were too many requests`() {
        val message = error(429, Fixtures.text("scan_limit_reached")).userMessage()

        assertTrue(message, message.startsWith("Daily scan limit reached"))
        assertTrue(message, message.contains("midnight"))
    }

    @Test
    fun `an error from the daily limit without words still says what happened`() {
        assertEquals("Daily scan limit reached", error(429, """{"code": "scan_limit_reached", "daily_limit": 5}""").userMessage())
    }

    @Test
    fun `an error from the rate limiter keeps its own words`() {
        assertEquals("Too many requests. Wait a minute and try again.", error(429, Fixtures.text("scan_limit_rate_limited")).userMessage())
    }

    @Test
    fun `a photo the limit turned away is told apart from one the scanner could not read`() {
        assertTrue(isDailyLimitMessage("Daily scan limit reached. Scans reset at midnight (Europe/Berlin)."))
        assertTrue(isDailyLimitMessage(DAILY_LIMIT_MESSAGE))
        assertFalse(isDailyLimitMessage("The scanner gave up on this photo."))
        assertFalse(isDailyLimitMessage(null))
        assertFalse(isDailyLimitMessage(""))
    }
}
