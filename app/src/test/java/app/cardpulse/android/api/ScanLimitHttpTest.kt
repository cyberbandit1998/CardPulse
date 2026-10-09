package app.cardpulse.android.api

import app.cardpulse.android.core.AppJson
import app.cardpulse.android.core.Fixtures
import app.cardpulse.android.core.ScanJobDto
import app.cardpulse.android.core.ScanSession
import app.cardpulse.android.core.SessionState
import app.cardpulse.android.core.TileState
import app.cardpulse.android.core.isDailyLimitMessage
import app.cardpulse.android.core.isUsedUp
import app.cardpulse.android.core.scanLimitAllowance
import app.cardpulse.android.core.tileState
import app.cardpulse.android.core.usageText
import app.cardpulse.android.core.userMessage
import app.cardpulse.android.data.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The daily scan limit over the real HTTP stack: the route the app asks, how it reads what PokéCollector's own routes answered
 * (captured from them running against a database; see the fixtures), what a refused scan looks like on the wire, and the whole
 * thing from taking a photo to the camera knowing its day is used up.
 */
class ScanLimitHttpTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: Repository
    private lateinit var dir: File
    private lateinit var scope: CoroutineScope
    private val seen = CopyOnWriteArrayList<RecordedRequest>()
    private val noon = Instant.parse("2026-10-09T10:00:00Z")

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val session = SessionHolder().apply { serverUrl = server.url("/").toString() }
        val client = HttpClientFactory.create(session, debug = false) {}
        repo = Repository(HttpClientFactory.retrofit(client, AppJson).create(PokeApi::class.java), session)
        dir = Files.createTempDirectory("scan-limit-http").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
        server.shutdown()
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun fixture(name: String, code: Int = 200) = json(Fixtures.text(name), code)

    private fun next(): RecordedRequest = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS)) { "no request arrived" }

    // --- the route and what it answers -----------------------------------------------------------------------------------------

    @Test
    fun `the app asks the server what is left on its own route, signed in like every other request`() = runBlocking {
        server.enqueue(fixture("scan_limit_me"))

        val allowance = repo.scanAllowance()

        val request = next()
        assertEquals("GET", request.method)
        assertEquals("/api/scan-limits/me", request.requestUrl!!.encodedPath)
        assertEquals(setOf<String>(), request.requestUrl!!.queryParameterNames)
        assertEquals("23 of 100 scans used today", allowance!!.usageText())
        assertEquals("Europe/Berlin", allowance.timezone)
    }

    @Test
    fun `an unlimited user is read as unlimited`() = runBlocking {
        server.enqueue(fixture("scan_limit_me_unlimited"))

        assertEquals("Unlimited scans", repo.scanAllowance()!!.usageText())
    }

    @Test
    fun `a job comes back with the count, as queued and as polled`() = runBlocking {
        val queued = AppJson.decodeFromString<ScanJobDto>(Fixtures.text("scan_job_enqueued"))
        val polled = AppJson.decodeFromString<ScanJobDto>(Fixtures.text("scan_job_detail_with_limit"))

        assertEquals("23 of 100 scans used today", queued.scanLimit!!.usageText())
        assertEquals("24 of 100 scans used today", polled.scanLimit!!.usageText())
        assertEquals(1, polled.items.size) // and the job itself is read as before
        assertEquals("pending", polled.items[0].status)
    }

    @Test
    fun `a job from a server without the scan limits has no count and is read as before`() {
        val older = AppJson.decodeFromString<ScanJobDto>(Fixtures.text("scan_job_detail"))

        assertNull(older.scanLimit)
        assertTrue(older.items.isNotEmpty())
    }

    // --- being refused ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `queueing a photo with no scan left is a 429 that carries the numbers`() = runBlocking {
        server.enqueue(fixture("scan_job_enqueue_refused", code = 429))
        val file = File(dir, "scan-1.jpg").apply { writeText("jpeg") }

        val failure = runCatching { repo.enqueue(file) }.exceptionOrNull() as HttpException

        assertEquals(429, failure.code())
        val reached = failure.scanLimitAllowance()
        assertNotNull(reached)
        assertEquals(100, reached!!.dailyLimit)
        assertEquals(0, reached.remaining)
        assertEquals("2026-10-10T00:00:00+02:00", reached.resetsAt)
        assertTrue(reached.isUsedUp(noon))
        assertTrue(failure.userMessage(), isDailyLimitMessage(failure.userMessage()))
    }

    @Test
    fun `the servers rate limiter answers a different 429 which is not the daily limit`() = runBlocking {
        server.enqueue(fixture("scan_limit_rate_limited", code = 429))
        val file = File(dir, "scan-1.jpg").apply { writeText("jpeg") }

        val failure = runCatching { repo.enqueue(file) }.exceptionOrNull() as HttpException

        assertNull(failure.scanLimitAllowance())
        assertEquals("Too many requests. Wait a minute and try again.", failure.userMessage())
    }

    @Test
    fun `an older server has no such route and says so with a 404`() = runBlocking {
        server.enqueue(fixture("scan_limit_not_installed", code = 404))

        val failure = runCatching { repo.scanAllowance() }.exceptionOrNull() as HttpException

        assertEquals(404, failure.code())
        assertNull(failure.scanLimitAllowance())
    }

    // --- the whole thing -------------------------------------------------------------------------------------------------------------

    /** A server that answers by what is asked, and counts the uploads. */
    private fun serverThat(uploads: AtomicInteger, onUpload: () -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val path = request.requestUrl!!.encodedPath
                return when {
                    request.method == "POST" && path == "/api/cards/recognize/jobs" -> {
                        uploads.incrementAndGet()
                        onUpload()
                    }
                    request.method == "GET" && path == "/api/scan-limits/me" -> fixture("scan_limit_me")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    private fun sessionOverTheNetwork() = ScanSession(
        backend = repo,
        scope = scope,
        describe = { it.userMessage() },
        pollMs = 10_000, waitingPollMs = 10_000, errorPollMs = 10_000, uploadRetryMs = listOf(10, 10),
    )

    private suspend fun ScanSession.await(until: (SessionState) -> Boolean): SessionState =
        withTimeout(5_000) { state.first(until) }

    @Test
    fun `a photo taken with no scan left is refused once, kept, and the camera knows the day is used up`() = runBlocking {
        val uploads = AtomicInteger()
        serverThat(uploads) { fixture("scan_job_enqueue_refused", code = 429) }
        val session = sessionOverTheNetwork()
        val photo = File(dir, "scan-1.jpg").apply { writeText("jpeg") }

        val id = session.capture(photo)
        val state = session.await { it.entries.firstOrNull { entry -> entry.id == id }?.tileState() == TileState.SEND_FAILED }

        assertEquals(1, uploads.get()) // a 429 from the limit is not retried
        val entry = state.entries.first()
        assertTrue(entry.uploadError.orEmpty(), isDailyLimitMessage(entry.uploadError))
        assertTrue(state.allowance!!.isUsedUp(noon))
        assertEquals("100 of 100 scans used today", state.allowance!!.usageText())
        assertTrue(photo.exists())
    }

    @Test
    fun `the rate limiters 429 is retried and the photo gets through`() = runBlocking {
        val uploads = AtomicInteger()
        serverThat(uploads) {
            if (uploads.get() < 3) fixture("scan_limit_rate_limited", code = 429) else fixture("scan_job_enqueued")
        }
        val session = sessionOverTheNetwork()

        val id = session.capture(File(dir, "scan-1.jpg").apply { writeText("jpeg") })
        val state = session.await { it.entries.firstOrNull { entry -> entry.id == id }?.tileState() == TileState.READING }

        assertEquals(3, uploads.get())
        assertEquals("23 of 100 scans used today", state.allowance!!.usageText()) // the count came with the job that got through
    }

    @Test
    fun `asking for the count over the network puts it in the session`() = runBlocking {
        serverThat(AtomicInteger()) { fixture("scan_job_enqueued") }
        val session = sessionOverTheNetwork()

        session.refreshAllowance()

        assertEquals("23 of 100 scans used today", session.state.value.allowance!!.usageText())
        assertEquals("/api/scan-limits/me", seen.last().requestUrl!!.encodedPath)
    }

    @Test
    fun `a server without the update leaves the session with no count`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = fixture("scan_limit_not_installed", code = 404)
        }
        val session = sessionOverTheNetwork()

        session.refreshAllowance()

        assertNull(session.state.value.allowance)
        assertNull(session.state.value.message) // not something to tell the user about
    }
}

