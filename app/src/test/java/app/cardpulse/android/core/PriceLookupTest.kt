package app.cardpulse.android.core

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

private fun idle(startedAt: String?) =
    SyncStatusDto(isPriceSyncRunning = false, lastPriceSync = startedAt?.let { SyncLogDto(status = "success", startedAt = it) })

private fun running(startedAt: String?) =
    SyncStatusDto(isPriceSyncRunning = true, lastPriceSync = startedAt?.let { SyncLogDto(status = "running", startedAt = it) })

/** A server whose price sync does what the script says. Each list repeats its last entry once it runs out. */
private class FakePrices(
    private val beforeStart: List<SyncStatusDto>,
    private val afterStart: List<SyncStatusDto> = listOf(idle("t0")),
    private val startError: Throwable? = null,
    private val statusError: Throwable? = null,
) : PriceBackend {
    val starts = AtomicInteger()
    val statusCalls = AtomicInteger()
    private var before = 0
    private var after = 0

    override suspend fun startPriceSync() {
        starts.incrementAndGet()
        startError?.let { throw it }
    }

    override suspend fun syncStatus(): SyncStatusDto {
        statusCalls.incrementAndGet()
        statusError?.let { throw it }
        return if (starts.get() == 0) beforeStart[minOf(before++, beforeStart.lastIndex)]
        else afterStart[minOf(after++, afterStart.lastIndex)]
    }
}

class PriceLookupTest {
    private suspend fun look(backend: PriceBackend, giveUpAfterMs: Long = 2_000) =
        lookUpPrices(backend, pollMs = 5, giveUpAfterMs = giveUpAfterMs)

    @Test
    fun `a lookup starts a price sync and waits for a new run to finish`() = runBlocking {
        val server = FakePrices(
            beforeStart = listOf(idle("t0")),
            afterStart = listOf(running("t1"), running("t1"), idle("t1")),
        )
        assertEquals(PriceLookup.DONE, look(server))
        assertEquals(1, server.starts.get())
    }

    @Test
    fun `an account that is not an admin is told so, without waiting`() = runBlocking {
        val refused = HttpException(
            Response.error<Any>(403, """{"detail":"Admin access required"}""".toResponseBody("application/json".toMediaType())),
        )
        val server = FakePrices(beforeStart = listOf(idle("t0")), startError = refused)
        assertEquals(PriceLookup.NOT_ALLOWED, look(server))
        assertEquals(1, server.statusCalls.get()) // only the look before starting
    }

    @Test
    fun `a sync that is already running is waited out before a new one is started`() = runBlocking {
        val server = FakePrices(
            beforeStart = listOf(running("t0"), running("t0"), idle("t0")),
            afterStart = listOf(running("t1"), idle("t1")),
        )
        assertEquals(PriceLookup.DONE, look(server))
        assertEquals(1, server.starts.get())
        assertTrue(server.statusCalls.get() >= 5)
    }

    @Test
    fun `a sync that never finishes is given up on`() = runBlocking {
        val server = FakePrices(beforeStart = listOf(idle("t0")), afterStart = listOf(running("t1")))
        assertEquals(PriceLookup.GAVE_UP, look(server, giveUpAfterMs = 100))
    }

    @Test
    fun `a sync that never shows up is given up on`() = runBlocking {
        // The server stays idle and its latest run is still the old one.
        val server = FakePrices(beforeStart = listOf(idle("t0")), afterStart = listOf(idle("t0")))
        assertEquals(PriceLookup.GAVE_UP, look(server, giveUpAfterMs = 100))
    }

    @Test
    fun `a server that cannot be reached is reported, not hidden`() = runBlocking {
        val server = FakePrices(beforeStart = listOf(idle("t0")), statusError = IOException("offline"))
        try {
            look(server)
            fail("expected the failure to be passed on")
        } catch (error: IOException) {
            assertEquals("offline", error.message)
        }
        Unit
    }
}
