package app.cardpulse.android.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private fun limitError(code: Int, body: String) =
    HttpException(Response.error<Any>(code, body.toResponseBody("application/json".toMediaType())))

/**
 * A server that knows the daily scan limit: what it says about the user's scans is whatever the test last set, and it sends
 * that with every job it returns, as the real one does. Jobs are one photo each, with the job's id as the item's id.
 */
private class LimitBackend : ScanBackend {
    @Volatile var allowance: ScanAllowanceDto? = null
    @Volatile var allowanceError: Throwable? = null
    val allowanceCalls = AtomicInteger()

    /** What the next uploads answer, one entry each; when it is empty they succeed. */
    val uploadErrors = ConcurrentLinkedQueue<Throwable>()
    val enqueueCalls = AtomicInteger()
    val enqueued = CopyOnWriteArrayList<Pair<Int, File>>()
    @Volatile var retryError: Throwable? = null
    private val statuses = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val nextJob = AtomicInteger(100)

    override suspend fun scanAllowance(): ScanAllowanceDto? {
        allowanceCalls.incrementAndGet()
        allowanceError?.let { throw it }
        return allowance
    }

    override suspend fun enqueue(photo: File): ScanJobDto {
        enqueueCalls.incrementAndGet()
        uploadErrors.poll()?.let { throw it }
        val id = nextJob.incrementAndGet()
        statuses[id] = "processing"
        enqueued += id to photo
        return ScanJobDto(id = id, total = 1, active = 1, scanLimit = allowance)
    }

    /** A photo the server already holds that its scanner gave up on. */
    fun failedJob(error: String? = "The scanner gave up on this photo."): Int {
        val id = nextJob.incrementAndGet()
        statuses[id] = "failed:${error.orEmpty()}"
        return id
    }

    private fun jobOf(id: Int): ScanJobDto {
        val status = statuses.getValue(id)
        val failed = status.startsWith("failed")
        val item = ScanItemDto(
            id = id,
            status = if (failed) "failed" else status,
            error = if (failed) status.removePrefix("failed:").ifEmpty { null } else null,
        )
        return ScanJobDto(id = id, total = 1, active = if (failed) 0 else 1, items = listOf(item), scanLimit = allowance)
    }

    override suspend fun scanJobs(): List<ScanJobDto> = statuses.keys.sortedDescending().map { jobOf(it).copy(items = emptyList()) }

    override suspend fun scanJob(jobId: Int): ScanJobDto = jobOf(jobId)

    override suspend fun resolveAndAdd(jobId: Int, itemId: Int, request: ResolveAndAddRequest): ResolveAndAddResponse =
        throw UnsupportedOperationException()

    override suspend fun skip(jobId: Int, itemId: Int): ScanItemDto = throw UnsupportedOperationException()

    override suspend fun retry(jobId: Int, itemId: Int): ScanItemDto {
        retryError?.let { throw it }
        statuses[jobId] = "processing"
        return ScanItemDto(id = itemId, status = "pending")
    }

    override suspend fun deleteScanJob(jobId: Int) = Unit

    override suspend fun scanPhotoBytes(jobId: Int, itemId: Int): ByteArray = ByteArray(0)

    override suspend fun uploadOwnerPhoto(collectionItemId: Int, jpeg: ByteArray) = Unit
}

class ScanLimitSessionTest {
    private lateinit var dir: File
    private lateinit var scope: CoroutineScope
    private lateinit var backend: LimitBackend
    private lateinit var session: ScanSession

    private val noon = Instant.parse("2026-10-09T10:00:00Z")
    private val twentyThree: ScanAllowanceDto = Fixtures.decode("scan_limit_me")
    private val unlimited: ScanAllowanceDto = Fixtures.decode("scan_limit_me_unlimited")
    private val reachedBody = Fixtures.text("scan_limit_reached")
    private val reached: ScanAllowanceDto = Fixtures.decode("scan_limit_reached")
    private val rateLimited = Fixtures.text("scan_limit_rate_limited")

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("scan-limit").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        backend = LimitBackend()
        session = ScanSession(
            backend = backend,
            scope = scope,
            describe = { it.userMessage() },
            pollMs = 10, waitingPollMs = 20, errorPollMs = 20, uploadRetryMs = listOf(10, 10),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun photo(name: String): File = File(dir, "scan-$name.jpg").apply { writeText("jpeg-$name") }

    private suspend fun await(timeoutMs: Long = 5_000, until: (SessionState) -> Boolean): SessionState =
        withTimeout(timeoutMs) { session.state.first(until) }

    private fun SessionState.tile(id: Long) = entries.firstOrNull { it.id == id }?.tileState()

    // --- asking what is left --------------------------------------------------------------------------------------------------

    @Test
    fun `nothing is known about the scans until the server has been asked`() {
        assertNull(session.state.value.allowance)
    }

    @Test
    fun `asking the server puts what is left of today into the session`() = runBlocking {
        backend.allowance = twentyThree

        session.refreshAllowance()

        val allowance = session.state.value.allowance
        assertEquals(twentyThree, allowance)
        assertEquals("23 of 100 scans used today", allowance!!.usageText())
    }

    @Test
    fun `an unlimited user is shown as unlimited`() = runBlocking {
        backend.allowance = unlimited

        session.refreshAllowance()

        assertEquals("Unlimited scans", session.state.value.allowance!!.usageText())
    }

    @Test
    fun `a server without the daily scan limits leaves nothing to show`() = runBlocking {
        backend.allowance = null // the default of a backend with no such route

        session.refreshAllowance()

        assertNull(session.state.value.allowance)
    }

    @Test
    fun `a server that answers that route with 404 leaves nothing to show, even after it was once known`() = runBlocking {
        backend.allowance = twentyThree
        session.refreshAllowance()
        assertNotNull(session.state.value.allowance)

        backend.allowanceError = limitError(404, Fixtures.text("scan_limit_not_installed"))
        session.refreshAllowance()

        assertNull(session.state.value.allowance)
    }

    @Test
    fun `a failed look keeps what was last heard`() = runBlocking {
        backend.allowance = twentyThree
        session.refreshAllowance()

        backend.allowanceError = IOException("offline")
        session.refreshAllowance()
        backend.allowanceError = limitError(500, """{"detail": "boom"}""")
        session.refreshAllowance()

        assertEquals(twentyThree, session.state.value.allowance)
        assertNull(session.state.value.message) // and it is not an error the user has to dismiss
    }

    // --- the count moves with the scans -----------------------------------------------------------------------------------

    @Test
    fun `the job that comes back from sending a photo carries the count`() = runBlocking {
        backend.allowance = twentyThree
        val id = session.capture(photo("1"))

        await { it.tile(id) == TileState.READING }

        assertEquals(twentyThree, session.state.value.allowance)
    }

    @Test
    fun `every look at a job moves the count as photos start to be read`() = runBlocking {
        backend.allowance = twentyThree
        val id = session.capture(photo("1"))
        await { it.tile(id) == TileState.READING }

        backend.allowance = twentyThree.copy(used = 24, remaining = 76)

        val moved = await { it.allowance?.used == 24 }
        assertEquals(76, moved.allowance!!.remaining)
        assertEquals("24 of 100 scans used today", moved.allowance!!.usageText())
    }

    @Test
    fun `a server that sends no count with its jobs does not wipe the one that is known`() = runBlocking {
        backend.allowance = twentyThree
        session.refreshAllowance()

        backend.allowance = null // jobs come back without scan_limit, as from a server without the update
        val id = session.capture(photo("1"))
        await { it.tile(id) == TileState.READING }
        delay(50)

        assertEquals(twentyThree, session.state.value.allowance)
    }

    // --- the limit turning a photo away ---------------------------------------------------------------------------------------------

    @Test
    fun `a photo refused for the daily limit is not sent again and says why`() = runBlocking {
        backend.uploadErrors += limitError(429, reachedBody)

        val id = session.capture(photo("1"))
        val state = await { it.tile(id) == TileState.SEND_FAILED }

        // Asking again in a second changes nothing, so it asked once.
        assertEquals(1, backend.enqueueCalls.get())
        val entry = state.entries.first { it.id == id }
        assertTrue(entry.uploadError.orEmpty(), isDailyLimitMessage(entry.uploadError))
        assertTrue(entry.uploadError.orEmpty(), entry.uploadError.orEmpty().contains("midnight"))
        // The server's answer says how much was used and when it starts over, which is what the camera shows.
        assertEquals(reached, state.allowance)
        assertTrue(state.allowance!!.isUsedUp(noon))
        assertTrue(entry.photo!!.exists()) // and the photo is kept, to be sent after the reset
    }

    @Test
    fun `the rate limiters 429 is still retried, because waiting a moment helps`() = runBlocking {
        backend.uploadErrors += limitError(429, rateLimited)
        backend.uploadErrors += limitError(429, rateLimited)

        val id = session.capture(photo("1"))
        await { it.tile(id) == TileState.READING }

        assertEquals(3, backend.enqueueCalls.get())
        assertNull(session.state.value.allowance) // and it said nothing about the daily limit
    }

    @Test
    fun `a photo that was turned away is sent when the user sends it again after the reset`() = runBlocking {
        backend.uploadErrors += limitError(429, reachedBody)
        val id = session.capture(photo("1"))
        await { it.tile(id) == TileState.SEND_FAILED }

        backend.allowance = twentyThree.copy(used = 0, remaining = 100)
        session.resend(id)
        val state = await { it.tile(id) == TileState.READING }

        assertEquals(2, backend.enqueueCalls.get())
        assertFalse(state.allowance!!.isUsedUp(noon))
    }

    @Test
    fun `photos taken after the limit was reached are turned away one by one and none is lost`() = runBlocking {
        repeat(3) { backend.uploadErrors += limitError(429, reachedBody) }

        val ids = (1..3).map { session.capture(photo("$it")) }

        await { state -> ids.all { state.tile(it) == TileState.SEND_FAILED } }
        assertEquals(3, backend.enqueueCalls.get())
        assertTrue(session.state.value.entries.all { it.photo?.exists() == true })
    }

    @Test
    fun `reading a failed photo again with no scan left says why on that photo`() = runBlocking {
        val job = backend.failedJob("Daily scan limit reached. Scans reset at midnight (Europe/Berlin).")
        session.resumeServer()
        val entry = await { it.entries.isNotEmpty() }.entries.first()
        assertEquals(TileState.FAILED, entry.tileState())
        assertTrue(isDailyLimitMessage(entry.item?.error))

        backend.retryError = limitError(429, reachedBody)
        session.retryItem(entry.id)
        val failed = await { state -> state.entries.first().error != null }.entries.first()

        assertTrue(failed.error.orEmpty(), isDailyLimitMessage(failed.error))
        assertEquals(TileState.FAILED, failed.tileState()) // still failed, and not retried by itself
        assertEquals(job, failed.jobId)
    }

    @Test
    fun `a photo the queue marked failed for the limit shows its reason, not a generic one`() = runBlocking {
        backend.failedJob("Daily scan limit reached. Scans reset at midnight (Europe/Berlin).")

        session.resumeServer()
        val entry = await { it.entries.isNotEmpty() }.entries.first()

        assertTrue(isDailyLimitMessage(entry.item?.error))
    }
}
