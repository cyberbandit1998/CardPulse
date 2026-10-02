package app.cardpulse.android.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.util.Locale
import javax.net.ssl.SSLHandshakeException
import org.junit.Test

class ServerTimeTest {
    @Test
    fun `parses every shape the server sends`() {
        val expected = Instant.parse("2026-10-02T14:29:24Z")
        assertEquals(expected, parseServerInstant("2026-10-02T14:29:24"))
        assertEquals(expected, parseServerInstant("2026-10-02T14:29:24Z"))
        assertEquals(expected, parseServerInstant("2026-10-02T14:29:24+00:00"))
        assertEquals(expected, parseServerInstant("2026-10-02T16:29:24+02:00"))
        assertEquals(Instant.parse("2026-10-02T14:29:26.890390Z"), parseServerInstant("2026-10-02T14:29:26.890390"))
    }

    @Test
    fun `rejects junk`() {
        assertNull(parseServerInstant(null))
        assertNull(parseServerInstant(""))
        assertNull(parseServerInstant("   "))
        assertNull(parseServerInstant("yesterday"))
    }
}

class MoneyTest {
    private val usd = MoneyFormatter("USD", 1.1, Locale.US)

    @Test
    fun `converts euros at the given rate`() {
        assertEquals("$11.00", usd.format(10.0))
        assertEquals("€10.00", MoneyFormatter("EUR", 1.0, Locale.US).format(10.0))
    }

    @Test
    fun `signed amounts`() {
        assertEquals("+$1.10", usd.signed(1.0))
        assertEquals("−$1.10", usd.signed(-1.0))
        assertEquals("$0.00", usd.signed(0.0))
        assertEquals("$0.00", usd.signed(0.001))
    }

    @Test
    fun `percent`() {
        assertEquals("+11.1%", usd.percent(11.1))
        assertEquals("-3.0%", usd.percent(-3.0))
    }

    @Test
    fun `an unknown currency code falls back instead of crashing`() {
        assertNotNull(MoneyFormatter("???", 1.0, Locale.US).format(1.0))
    }
}

class MoneyInputTest {
    @Test
    fun `typed amounts convert to euros the way the web app does`() {
        assertEquals(5.0, MoneyInput.toEuros("5", 1.0)!!, 0.0)
        assertEquals(10.0, MoneyInput.toEuros("11", 1.1)!!, 0.0001)
        assertEquals(0.9091, MoneyInput.toEuros("1.00", 1.1)!!, 0.00005)
        assertEquals(2.5, MoneyInput.toEuros(" 2,5 ", 1.0)!!, 0.0)
    }

    @Test
    fun `blank, invalid and negative input means no price`() {
        assertNull(MoneyInput.toEuros("", 1.0))
        assertNull(MoneyInput.toEuros("abc", 1.0))
        assertNull(MoneyInput.toEuros("-1", 1.0))
        assertNull(MoneyInput.toEuros("NaN", 1.0))
    }

    @Test
    fun `stored euros show in the display currency with two decimals`() {
        assertEquals("11.00", MoneyInput.toInput(10.0, 1.1))
        assertEquals("5.00", MoneyInput.toInput(5.0, 1.0))
        assertEquals("", MoneyInput.toInput(null, 1.1))
    }

    @Test
    fun `a bad rate falls back to one`() {
        assertEquals(5.0, MoneyInput.toEuros("5", 0.0)!!, 0.0)
    }
}

class PortfolioTest {
    private fun point(day: Int, value: Double) = ChartPoint(Instant.parse("2026-10-%02dT00:00:00Z".format(day)), value)

    @Test
    fun `range change is first to last`() {
        val change = listOf(point(1, 100.0), point(2, 90.0), point(3, 110.0)).rangeChange()!!
        assertEquals(10.0, change.absolute, 0.0001)
        assertEquals(10.0, change.percent!!, 0.0001)
    }

    @Test
    fun `percent is unknown when the start is zero`() {
        val change = listOf(point(1, 0.0), point(2, 5.0)).rangeChange()!!
        assertEquals(5.0, change.absolute, 0.0001)
        assertNull(change.percent)
    }

    @Test
    fun `needs two points`() {
        assertNull(emptyList<ChartPoint>().rangeChange())
        assertNull(listOf(point(1, 1.0)).rangeChange())
    }

    @Test
    fun `chart points are sorted and unreadable dates dropped`() {
        val points = listOf(
            SnapshotDto(date = "2026-10-03T00:00:00", value = 3.0),
            SnapshotDto(date = "garbage", value = 99.0),
            SnapshotDto(date = "2026-10-01T00:00:00", value = 1.0),
        ).toChartPoints()
        assertEquals(listOf(1.0, 3.0), points.map { it.value })
    }

    @Test
    fun `every range has a distinct api period`() {
        assertEquals(PortfolioRange.entries.size, PortfolioRange.entries.map { it.apiPeriod }.toSet().size)
        assertEquals("max", PortfolioRange.ALL.apiPeriod)
        assertEquals("1m", PortfolioRange.MONTH.apiPeriod)
    }
}

class ScanLogicTest {
    private val now = Instant.parse("2026-10-02T12:00:00Z")

    @Test
    fun `retry notes explain the reason and the wait`() {
        assertEquals(
            "The scanner is rate-limited. Retrying in 30s.",
            retryNote("rate_limit", "2026-10-02T12:00:30", now),
        )
        assertEquals(
            "The scanner's daily quota is used up. Retrying in about 5 min.",
            retryNote("daily_quota", "2026-10-02T12:05:00", now),
        )
        assertEquals(
            "The card database isn't reachable right now. Retrying shortly.",
            retryNote("catalogue_unavailable", "2026-10-02T11:59:00", now),
        )
        assertEquals("Retrying.", retryNote(null, null, now))
        assertEquals("Retrying in 10s.", retryNote("something-new", "2026-10-02T12:00:10", now))
    }

    @Test
    fun `phase from status`() {
        fun phase(status: String, resolved: Boolean = false) = ScanItemDto(id = 1, status = status, resolved = resolved).phase()
        assertEquals(ItemPhase.QUEUED, phase("pending"))
        assertEquals(ItemPhase.PROCESSING, phase("processing"))
        assertEquals(ItemPhase.WAITING_TO_RETRY, phase("retrying"))
        assertEquals(ItemPhase.NEEDS_REVIEW, phase("done"))
        assertEquals(ItemPhase.FAILED, phase("failed"))
        assertEquals(ItemPhase.HANDLED, phase("done", resolved = true))
        assertEquals(ItemPhase.QUEUED, phase("something-new"))
    }

    @Test
    fun `recognized summary tolerates numbers and gaps`() {
        val numeric = ScanItemDto(
            id = 1,
            recognized = buildJsonObject {
                put("name", "Pikachu")
                put("number_local", 58)
                put("number_total", 102)
            },
        )
        assertEquals("Pikachu · 58/102", numeric.recognizedSummary())
        assertNull(ScanItemDto(id = 2).recognizedSummary())
        assertEquals("OBF", ScanItemDto(id = 3, recognized = buildJsonObject { put("set_code", "OBF") }).recognizedSummary())
    }

    @Test
    fun `server urls`() {
        val base = "https://cards.example.com/"
        assertEquals("https://cards.example.com/api/images/card/sv3-125_en/small", ServerUrls.cardImage(base, "sv3-125_en"))
        assertEquals("https://cards.example.com/api/images/card/sv3-125_en/large", ServerUrls.cardImage(base, "sv3-125_en", large = true))
        assertEquals("https://cards.example.com/api/collection/7/photo", ServerUrls.ownPhoto(base, 7))
        assertEquals(
            "https://cards.example.com/api/cards/recognize/jobs/3/items/9/image",
            ServerUrls.scanItemImage(base, 3, 9),
        )
        assertEquals(
            "https://cards.example.com/api/cards/recognize/jobs/3/items/9/candidates/2/image",
            ServerUrls.candidateImage(base, 3, 9, 2),
        )
        // A server hosted under a path keeps that prefix, and odd characters are escaped.
        assertEquals("https://h.example/poke/api/images/card/custom-a%20b/small", ServerUrls.cardImage("https://h.example/poke/", "custom-a b"))
        assertEquals("", ServerUrls.cardImage("", "x"))
    }
}

class ErrorTextTest {
    private fun http(code: Int, body: String = "") =
        HttpException(Response.error<Any>(code, body.toResponseBody("application/json".toMediaType())))

    @Test
    fun `http errors use the server's own words when there are any`() {
        assertEquals("Incorrect username or password", http(401, """{"detail":"Incorrect username or password"}""").userMessage())
        assertEquals("This scan has already been handled.", http(409, """{"detail":"This scan has already been handled."}""").userMessage())
        assertEquals("Your session has expired. Sign in again.", http(401).userMessage())
    }

    @Test
    fun `describing the same error twice gives the same words`() {
        // The body can only be read once, so a second description used to fall back to the generic text.
        val error = http(409, """{"detail":"This scan has already been handled."}""")
        assertEquals("This scan has already been handled.", error.userMessage())
        assertEquals("This scan has already been handled.", error.userMessage())
    }

    @Test
    fun `proxy and server failures are explained`() {
        assertTrue(http(524).userMessage().contains("unreachable or too slow"))
        assertTrue(http(502, "<html>").userMessage().contains("HTTP 502"))
        assertTrue(http(500).userMessage().contains("HTTP 500"))
        assertEquals("Too many requests. Wait a minute and try again.", http(429).userMessage())
    }

    @Test
    fun `network failures are explained`() {
        assertTrue(UnknownHostException("x").userMessage().contains("Can't find that server"))
        assertTrue(SSLHandshakeException("x").userMessage().contains("HTTPS"))
        assertTrue(SocketTimeoutException("x").userMessage().contains("too long"))
        assertTrue(ConnectException("x").userMessage().contains("Can't connect"))
        assertEquals("Network error.", IOException().userMessage())
        assertTrue(NoServerException().userMessage().contains("address"))
        assertEquals(
            "That doesn't look right",
            NotPokeCollectorException("That doesn't look right").userMessage(),
        )
    }
}
