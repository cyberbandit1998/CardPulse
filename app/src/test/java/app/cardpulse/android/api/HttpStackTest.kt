package app.cardpulse.android.api

import app.cardpulse.android.core.AddEdits
import app.cardpulse.android.core.AppJson
import app.cardpulse.android.core.Fixtures
import app.cardpulse.android.core.NoServerException
import app.cardpulse.android.core.NotPokeCollectorException
import app.cardpulse.android.core.PortfolioRange
import app.cardpulse.android.core.ScanJobDto
import app.cardpulse.android.core.toAddRequest
import app.cardpulse.android.core.userMessage
import app.cardpulse.android.data.Repository
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs the real HTTP stack (Retrofit, converters, interceptors) against a local server that replays
 * responses captured from PokéCollector, and checks what the app actually puts on the wire.
 */
class HttpStackTest {
    private lateinit var server: MockWebServer
    private lateinit var session: SessionHolder
    private lateinit var client: OkHttpClient
    private lateinit var repo: Repository
    private var expiredCalls = 0

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        session = SessionHolder().apply { serverUrl = server.url("/").toString() }
        expiredCalls = 0
        client = HttpClientFactory.create(session, debug = false) { expiredCalls++ }
        repo = Repository(HttpClientFactory.retrofit(client, AppJson).create(PokeApi::class.java), session)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun fixture(name: String, code: Int = 200) = json(Fixtures.text(name), code)

    private fun next(): RecordedRequest = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS)) { "no request arrived" }

    private fun RecordedRequest.bodyText(): String = body.readUtf8()

    // --- connection and sign-in ---------------------------------------------------------------

    @Test
    fun `checking a server reads health then auth mode`() = runBlocking {
        server.enqueue(fixture("health"))
        server.enqueue(fixture("auth_mode_multi"))

        val check = repo.checkServer(server.url("/").toString())

        assertTrue(check.multiUser)
        val health = next()
        assertEquals("/api/health", health.path)
        assertNull(health.getHeader("Authorization"))
        assertEquals("/api/auth/mode", next().path)
    }

    @Test
    fun `single-user servers are reported as such`() = runBlocking {
        server.enqueue(fixture("health"))
        server.enqueue(fixture("auth_mode_single"))
        assertEquals(false, repo.checkServer(server.url("/").toString()).multiUser)
    }

    @Test
    fun `a server that is not PokeCollector is refused`() = runBlocking {
        server.enqueue(json("""{"status":"ok","service":"some-other-app"}"""))
        val error = runCatching { repo.checkServer(server.url("/").toString()) }.exceptionOrNull()
        assertTrue(error is NotPokeCollectorException)
    }

    @Test
    fun `a web page instead of JSON gets an understandable message`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>Welcome</html>"))
        val error = runCatching { repo.checkServer(server.url("/").toString()) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.userMessage(), error.userMessage().contains("PokéCollector"))
    }

    @Test
    fun `login posts form fields and later calls carry the token`() = runBlocking {
        server.enqueue(fixture("login"))
        val login = repo.login("  admin ", "p&ss word")

        val loginRequest = next()
        assertEquals("POST", loginRequest.method)
        assertEquals("/api/auth/login", loginRequest.path)
        assertTrue(loginRequest.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
        // OkHttp writes a space as "+", which is how form bodies are encoded; the server decodes it back.
        assertEquals("username=admin&password=p%26ss+word", loginRequest.bodyText())
        assertNull(loginRequest.getHeader("Authorization"))
        assertEquals("admin", login.user.username)

        server.enqueue(fixture("collection"))
        repo.loadCollection()
        assertEquals("Bearer fixture-token-not-a-real-credential", next().getHeader("Authorization"))
    }

    @Test
    fun `login never carries an old token`() = runBlocking {
        session.token = "stale-token"
        server.enqueue(fixture("login"))
        repo.login("admin", "pw")
        assertNull(next().getHeader("Authorization"))
    }

    @Test
    fun `the token is never sent to another host`() {
        val other = MockWebServer().apply { start() }
        try {
            session.token = "secret-token"
            other.enqueue(MockResponse().setResponseCode(401))
            client.newCall(Request.Builder().url(other.url("/artwork.webp")).build()).execute().close()

            val seen = checkNotNull(other.takeRequest(3, TimeUnit.SECONDS))
            assertNull(seen.getHeader("Authorization"))
            assertEquals("A 401 from a third party is not a session expiry", 0, expiredCalls)
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun `a 401 to a request that carried a token reports expiry`() = runBlocking {
        session.token = "expired-token"
        server.enqueue(fixture("unauthorized", code = 401))
        val error = runCatching { repo.loadCollection() }.exceptionOrNull()
        assertEquals(401, (error as HttpException).code())
        assertEquals(1, expiredCalls)
    }

    @Test
    fun `a failed login is not mistaken for an expired session`() = runBlocking {
        server.enqueue(fixture("login_error", code = 401))
        val error = runCatching { repo.login("admin", "wrong") }.exceptionOrNull()
        assertEquals("Incorrect username or password", error!!.userMessage())
        assertEquals(0, expiredCalls)
    }

    @Test
    fun `no server address fails clearly`() = runBlocking {
        session.serverUrl = ""
        val error = runCatching { repo.loadCollection() }.exceptionOrNull()
        assertTrue(error is NoServerException)
    }

    @Test
    fun `a server under a path keeps its prefix`() = runBlocking {
        session.serverUrl = server.url("/poke/").toString()
        server.enqueue(fixture("collection"))
        repo.loadCollection()
        assertEquals("/poke/api/collection/", next().path)
    }

    // --- collection, preferences, portfolio ---------------------------------------------------

    @Test
    fun `collection loads every row`() = runBlocking {
        server.enqueue(fixture("collection"))
        val result = repo.loadCollection()
        assertEquals(6, result.items.size)
        assertEquals(0, result.unreadable)
        assertEquals("/api/collection/", next().path)
    }

    @Test
    fun `one unreadable row is reported and does not hide the rest`() = runBlocking {
        val rows = Fixtures.text("collection").trimEnd().removeSuffix("]") + """,{"card_id":"x","quantity":"many"}]"""
        server.enqueue(json(rows))
        val result = repo.loadCollection()
        assertEquals(6, result.items.size)
        assertEquals(1, result.unreadable)
    }

    @Test
    fun `prefs convert euros using the server's exchange rate`() = runBlocking {
        server.enqueue(fixture("settings"))
        server.enqueue(fixture("exchange_rate"))
        val prefs = repo.loadPrefs()
        assertEquals("USD", prefs.currency)
        assertEquals(1.1, prefs.rateFromEur, 0.0001)
        assertEquals("price_trend", prefs.priceField)
        assertEquals("/api/settings/", next().path)
        assertEquals("/api/settings/exchange-rate?from=EUR&to=USD", next().path)
    }

    @Test
    fun `prefs fall back to euros instead of mislabeling them when the rate is unavailable`() = runBlocking {
        server.enqueue(fixture("settings"))
        server.enqueue(MockResponse().setResponseCode(500))
        val prefs = repo.loadPrefs()
        assertEquals("EUR", prefs.currency)
        assertEquals(1.0, prefs.rateFromEur, 0.0)
    }

    @Test
    fun `dashboard and history requests carry the chosen price field and period`() = runBlocking {
        server.enqueue(fixture("dashboard"))
        server.enqueue(fixture("investment_tracker_1m"))
        server.enqueue(fixture("top_movers"))

        assertEquals(10, repo.loadDashboard("price_avg7").totalCards)
        assertEquals(10, repo.loadHistory(PortfolioRange.MONTH, "price_avg7").size)
        assertEquals(5, repo.loadMovers("price_avg7").size)

        assertEquals("/api/dashboard/?price_field=price_avg7", next().path)
        assertEquals("/api/analytics/investment-tracker?period=1m&price_field=price_avg7", next().path)
        assertEquals("/api/analytics/top-movers?days=7&price_field=price_avg7&sort_by=percentage", next().path)
    }

    // --- scanning -----------------------------------------------------------------------------

    private fun photos(vararg names: String): List<File> = names.map { name ->
        File.createTempFile(name, ".jpg").apply { writeText("jpeg-bytes-$name"); deleteOnExit() }
    }

    @Test
    fun `scan upload sends one files part per photo and the individual positions`() = runBlocking {
        server.enqueue(fixture("scan_job_detail"))
        val files = photos("aaa", "bbb")

        val job = repo.enqueueScan(files, individual = true)

        assertEquals(1, job.id)
        val request = next()
        assertEquals("POST", request.method)
        assertEquals("/api/cards/recognize/jobs", request.path)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data; boundary="))
        val body = request.bodyText()
        assertEquals(2, Regex("name=\"files\"").findAll(body).count())
        assertTrue(body, body.contains("jpeg-bytes-aaa"))
        assertTrue(body, body.contains("jpeg-bytes-bbb"))
        assertTrue(body, body.contains("name=\"individual_positions\""))
        assertTrue(body, body.contains("[0,1]"))
    }

    @Test
    fun `batched scan mode sends no individual positions`() = runBlocking {
        server.enqueue(fixture("scan_job_detail"))
        repo.enqueueScan(photos("ccc", "ddd"), individual = false)
        assertTrue(next().bodyText().contains("[]"))
    }

    @Test
    fun `job detail and list are fetched from the queue endpoints`() = runBlocking {
        server.enqueue(fixture("scan_jobs"))
        server.enqueue(fixture("scan_job_detail"))
        assertEquals(1, repo.scanJobs().size)
        assertEquals(6, repo.scanJob(1).items.size)
        assertEquals("/api/cards/recognize/jobs", next().path)
        assertEquals("/api/cards/recognize/jobs/1", next().path)
    }

    @Test
    fun `adding a candidate posts the composite and plain ids together`() = runBlocking {
        server.enqueue(fixture("resolve_and_add"))
        val match = Fixtures.decode<ScanJobDto>("scan_job_detail").items[0].matches[0]

        val response = repo.resolveAndAdd(1, 1, match.toAddRequest(AddEdits(quantity = 3, condition = "LP", variant = "Holo")))

        assertEquals(7, response.collectionItem.id)
        val request = next()
        assertEquals("POST", request.method)
        assertEquals("/api/cards/recognize/jobs/1/items/1/resolve-and-add", request.path)
        val body = request.bodyText()
        assertTrue(body, body.contains("\"card_id\":\"sv3-125_en\""))
        assertTrue(body, body.contains("\"confirmed_card_id\":\"sv3-125\""))
        assertTrue(body, body.contains("\"quantity\":3"))
        assertTrue(body, body.contains("\"condition\":\"LP\""))
        assertTrue(body, body.contains("\"variant\":\"Holo\""))
    }

    @Test
    fun `adding twice surfaces the server's already-handled conflict`() = runBlocking {
        server.enqueue(fixture("resolve_and_add_repeat", code = 409))
        val match = Fixtures.decode<ScanJobDto>("scan_job_detail").items[0].matches[0]
        val error = runCatching { repo.resolveAndAdd(1, 1, match.toAddRequest(AddEdits())) }.exceptionOrNull()
        assertEquals(409, (error as HttpException).code())
        assertEquals("This scan has already been handled.", error.userMessage())
    }

    @Test
    fun `skipping posts an empty resolve body`() = runBlocking {
        server.enqueue(fixture("resolve_dismiss"))
        repo.skip(1, 2)
        val request = next()
        assertEquals("/api/cards/recognize/jobs/1/items/2/resolve", request.path)
        assertEquals("{}", request.bodyText())
    }

    @Test
    fun `retrying an item and deleting a job use their endpoints`() = runBlocking {
        server.enqueue(fixture("resolve_dismiss"))
        server.enqueue(json("""{"deleted": 1}"""))
        repo.retry(1, 4)
        repo.deleteScanJob(1)
        val retry = next()
        assertEquals("POST", retry.method)
        assertEquals("/api/cards/recognize/jobs/1/items/4/retry", retry.path)
        val delete = next()
        assertEquals("DELETE", delete.method)
        assertEquals("/api/cards/recognize/jobs/1", delete.path)
    }

    @Test
    fun `the queued photo comes back as raw bytes`() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3, 4, -1, -40)
        server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(bytes)))
        assertEquals(bytes.toList(), repo.scanPhotoBytes(1, 1).toList())
        assertEquals("/api/cards/recognize/jobs/1/items/1/image", next().path)
    }

    @Test
    fun `an owner photo is uploaded as a file part`() = runBlocking {
        server.enqueue(json("""{"collection_item_id": 7, "bytes": 3}"""))
        repo.uploadOwnerPhoto(7, "abc".toByteArray())
        val request = next()
        assertEquals("/api/collection/7/photo", request.path)
        val body = request.bodyText()
        assertTrue(body, body.contains("name=\"file\""))
        assertTrue(body, body.contains("abc"))
    }

    @Test
    fun `forced password change puts the new password`() = runBlocking {
        server.enqueue(json("""{"message": "Password changed"}"""))
        repo.changeRequiredPassword("a-new-password")
        val request = next()
        assertEquals("PUT", request.method)
        assertEquals("/api/auth/me/force-password", request.path)
        assertEquals("""{"new_password":"a-new-password"}""", request.bodyText())
    }

    // --- caching --------------------------------------------------------------------------------

    @Test
    fun `card art is cached for a month and private photos for a few minutes`() {
        server.enqueue(MockResponse().setHeader("Cache-Control", "public, max-age=86400").setBody("art"))
        server.enqueue(MockResponse().setHeader("Cache-Control", "no-store").setHeader("Vary", "Authorization, Cookie").setBody("mine"))
        server.enqueue(MockResponse().setHeader("Cache-Control", "no-store").setBody("{}"))

        fun get(path: String) = client.newCall(Request.Builder().url(server.url(path)).build()).execute().use { it.header("Cache-Control") }

        assertEquals("private, max-age=2592000", get("/api/images/card/sv3-125_en/small"))
        assertEquals("private, max-age=600", get("/api/collection/7/photo"))
        assertEquals("Other responses are left alone", "no-store", get("/api/settings/"))
    }
}
