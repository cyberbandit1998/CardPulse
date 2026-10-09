package app.cardpulse.android.api

import app.cardpulse.android.core.AppJson
import app.cardpulse.android.core.CardSearchParams
import app.cardpulse.android.core.CatalogSearchSession
import app.cardpulse.android.core.CatalogSearchState
import app.cardpulse.android.core.Fixtures
import app.cardpulse.android.core.SearchScope
import app.cardpulse.android.core.artistName
import app.cardpulse.android.core.priceFor
import app.cardpulse.android.core.printedNumber
import app.cardpulse.android.core.searchSubtitle
import app.cardpulse.android.core.userMessage
import app.cardpulse.android.data.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The catalogue search over the real HTTP stack: what the app puts on the wire for each part of a search, how it reads what
 * PokéCollector's own search route answered (captured from the route running against a database; see the fixtures), and the
 * whole search, from the typed text to the cards, against a server that replays those answers.
 */
class CatalogSearchHttpTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: Repository
    private val seen = CopyOnWriteArrayList<RecordedRequest>()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val session = SessionHolder().apply { serverUrl = server.url("/").toString() }
        val client = HttpClientFactory.create(session, debug = false) {}
        repo = Repository(HttpClientFactory.retrofit(client, AppJson).create(PokeApi::class.java), session)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun fixture(name: String) = json(Fixtures.text(name))

    private fun next(): RecordedRequest = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS)) { "no request arrived" }

    // --- what is put on the wire --------------------------------------------------------------

    @Test
    fun `a name search sends the name and the page, and leaves out every other filter and the language`() = runBlocking {
        server.enqueue(fixture("card_search_name"))
        repo.searchCatalog(CardSearchParams(q = "pikachu"), page = 1, pageSize = 30)

        val request = next()
        assertEquals("GET", request.method)
        assertEquals("/api/cards/search", request.requestUrl!!.encodedPath)
        // Without lang the server answers in the language the user chose there: one version of each card, not one per language.
        assertEquals(setOf("q", "page", "page_size"), request.requestUrl!!.queryParameterNames)
        assertEquals("pikachu", request.requestUrl!!.queryParameter("q"))
        assertEquals("1", request.requestUrl!!.queryParameter("page"))
        assertEquals("30", request.requestUrl!!.queryParameter("page_size"))
    }

    @Test
    fun `an artist, a set with a number, and a rarity are sent as their own filters`() = runBlocking {
        server.enqueue(fixture("card_search_artist"))
        server.enqueue(fixture("card_search_number_in_set"))
        server.enqueue(fixture("card_search_rarity"))
        repo.searchCatalog(CardSearchParams(artist = "Mitsuhiro Arita"), 2, 30)
        repo.searchCatalog(CardSearchParams(setId = "tst1_en", number = "130"), 1, 30)
        repo.searchCatalog(CardSearchParams(rarity = "illustration"), 1, 30)

        val artist = next().requestUrl!!
        assertEquals(setOf("artist", "page", "page_size"), artist.queryParameterNames)
        assertEquals("Mitsuhiro Arita", artist.queryParameter("artist"))
        assertEquals("2", artist.queryParameter("page"))

        val inSet = next().requestUrl!!
        assertEquals(setOf("set_id", "number", "page", "page_size"), inSet.queryParameterNames)
        assertEquals("tst1_en", inSet.queryParameter("set_id"))
        assertEquals("130", inSet.queryParameter("number"))

        val rarity = next().requestUrl!!
        assertEquals(setOf("rarity", "page", "page_size"), rarity.queryParameterNames)
        assertEquals("illustration", rarity.queryParameter("rarity"))
    }

    @Test
    fun `a problem on the server is an error to say, not a result`() = runBlocking {
        server.enqueue(json("""{"detail":"boom"}""", 500))
        val error = runCatching { repo.searchCatalog(CardSearchParams(q = "pikachu"), 1, 30) }.exceptionOrNull()
        assertTrue(error is HttpException)
        assertTrue(error!!.userMessage().isNotBlank())
    }

    // --- what the server answered -------------------------------------------------------------

    @Test
    fun `a page of cards is read with their artist, rarity, set, number and price`() = runBlocking {
        server.enqueue(fixture("card_search_artist"))
        val page = repo.searchCatalog(CardSearchParams(artist = "arita"), 1, 30)

        assertEquals(4, page.totalCount)
        assertEquals(listOf("base1-4_en", "sv3-223_en", "base1-58_en", "tst1-130_en"), page.data.map { it.id })
        val pikachu = page.data.first { it.id == "tst1-130_en" }
        assertEquals("Pikachu", pikachu.name)
        assertEquals("Mitsuhiro Arita", pikachu.artistName())
        assertEquals("Illustration Rare", pikachu.rarity)
        assertEquals("30th Celebration", pikachu.setRef?.name)
        assertEquals("130", pikachu.number)
        assertEquals(24.9, pikachu.priceFor("Normal", "price_trend"), 0.0001)
    }

    @Test
    fun `a card the catalogue has no artist or price for is read, with the artist left out`() = runBlocking {
        server.enqueue(fixture("card_search_set"))
        val page = repo.searchCatalog(CardSearchParams(setId = "tst1_en"), 1, 30)

        val detective = page.data.first { it.id == "tst1-002_en" }
        assertNull(detective.artist)
        assertNull(detective.artistName())
        val zubat = page.data.first { it.id == "tst1-003_en" }
        assertEquals("Hasuno", zubat.artistName())
        assertEquals(0.0, zubat.priceFor("Normal", "price_trend"), 0.0) // no price: the screens say so
    }

    @Test
    fun `a number is shown with the size of its set from the sets the server lists`() = runBlocking {
        server.enqueue(fixture("card_search_number_in_set"))
        server.enqueue(fixture("card_search_sets"))
        val card = repo.searchCatalog(CardSearchParams(setId = "tst1_en", number = "130"), 1, 30).data.single()
        val sets = repo.sets()

        assertEquals("130/128", card.printedNumber(sets))
        assertEquals("30th Celebration · #130/128 · Illustration Rare", card.searchSubtitle(sets))
        assertEquals("30th Celebration · #130 · Illustration Rare", card.searchSubtitle(emptyList()))
    }

    @Test
    fun `no match is an empty page, not an error`() = runBlocking {
        server.enqueue(fixture("card_search_none"))
        val page = repo.searchCatalog(CardSearchParams(q = "nosuchcardname"), 1, 30)
        assertTrue(page.data.isEmpty())
        assertEquals(0, page.totalCount)
    }

    @Test
    fun `the server's own flags for what the user owns are ignored, the app asks its own collection`() = runBlocking {
        server.enqueue(fixture("card_search_name"))
        val page = repo.searchCatalog(CardSearchParams(q = "pikachu"), 1, 30)
        // The answer says the user owns 2 of one card and has another wishlisted; the models do not carry them.
        assertEquals(5, page.data.size)
        assertNotNull(page.data.firstOrNull { it.id == "base1-58_en" })
    }

    // --- a whole search -----------------------------------------------------------------------

    /**
     * Answers like PokéCollector would for what was captured: the list of sets, and each search that was captured. Anything else
     * is a search with no match, as it would be. Every request is kept, to see what was asked.
     */
    private fun serveCapturedAnswers() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val url = request.requestUrl!!
                if (url.encodedPath == "/api/sets/") return fixture("card_search_sets")
                check(url.encodedPath == "/api/cards/search") { "unexpected ${url.encodedPath}" }
                val q = url.queryParameter("q")
                check(q == null || !Regex("^([A-Za-z][A-Za-z0-9]*)\\s+(\\d+)$").matches(q)) { "the server would go to the internet for '$q'" }
                return fixture(capturedFor(url))
            }
        }
    }

    private fun capturedFor(url: HttpUrl): String {
        val page = url.queryParameter("page") ?: "1"
        return when {
            url.queryParameter("q")?.lowercase() == "pikachu" -> if (page == "1") "card_search_name" else "card_search_none"
            url.queryParameter("artist")?.lowercase() == "mitsuhiro arita" -> if (page == "1") "card_search_artist" else "card_search_none"
            url.queryParameter("set_id") == "tst1_en" && url.queryParameter("number") == "130" -> "card_search_number_in_set"
            url.queryParameter("set_id") == "tst1_en" && url.queryParameter("number") == null -> "card_search_set"
            url.queryParameter("rarity")?.lowercase() == "illustration" -> "card_search_rarity"
            else -> "card_search_none"
        }
    }

    private suspend fun searchFor(scope: SearchScope, text: String): CatalogSearchState {
        val job = SupervisorJob()
        val search = CatalogSearchSession(backend = repo, scope = CoroutineScope(job + Dispatchers.Default), debounceMs = 10)
        try {
            search.open(scope, text)
            withTimeout(5_000) {
                search.state.first { it.resultsFor?.text == text && !it.searching && it.run?.groups?.none { g -> g.loading } != false }
            }
            // Parts asked for at once have all been answered.
            return withTimeout(5_000) { search.state.first { s -> s.run?.groups?.all { it.total != null || it.failed != null } != false && !s.loadingMore } }
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `searching for a Pokemon in All asks for the name, the artist and the rarity and lists the cards named for it`() = runBlocking {
        serveCapturedAnswers()
        val found = searchFor(SearchScope.ALL, "Pikachu")

        assertEquals(listOf("tst1-002_en", "tst1-001_en", "base1-58_en", "sv1-063_en", "tst1-130_en"), found.results.map { it.id })
        assertEquals(5, found.matches)
        assertFalse(found.hasMore)
        val searches = seen.map { it.requestUrl!! }.filter { it.encodedPath == "/api/cards/search" }
        assertEquals(setOf("q", "artist", "rarity"), searches.flatMap { it.queryParameterNames }.toSet() - setOf("page", "page_size"))
    }

    @Test
    fun `searching for an artist in All finds the cards they illustrated, whatever their names`() = runBlocking {
        serveCapturedAnswers()
        val found = searchFor(SearchScope.ALL, "Mitsuhiro Arita")

        assertEquals(setOf("base1-4_en", "sv3-223_en", "base1-58_en", "tst1-130_en"), found.results.map { it.id }.toSet())
        assertTrue(found.results.all { it.artistName() == "Mitsuhiro Arita" })
    }

    @Test
    fun `searching for a collector number with its set size finds the one card, asking only for it`() = runBlocking {
        serveCapturedAnswers()
        val found = searchFor(SearchScope.ALL, "130/128")

        assertEquals(listOf("tst1-130_en"), found.results.map { it.id })
        val searches = seen.map { it.requestUrl!! }.filter { it.encodedPath == "/api/cards/search" }
        assertEquals(1, searches.size)
        assertEquals("tst1_en", searches.single().queryParameter("set_id"))
        assertEquals("130", searches.single().queryParameter("number"))
        // The number as printed, from the sets the server listed.
        assertEquals("130/128", found.results.single().printedNumber(found.sets))
    }

    @Test
    fun `searching for a set by its name finds every card of it`() = runBlocking {
        serveCapturedAnswers()
        val found = searchFor(SearchScope.ALL, "30th Celebration")

        // The cards of the set it names come first, in the server's order, before anything else the text matched.
        assertEquals(listOf("tst1-002_en", "tst1-001_en", "tst1-130_en", "tst1-003_en"), found.results.map { it.id })
        val asked = seen.map { it.requestUrl!! }.filter { it.encodedPath == "/api/cards/search" }
        assertTrue(asked.any { it.queryParameter("set_id") == "tst1_en" && it.queryParameter("number") == null })
    }

    @Test
    fun `searching for a set code and a number finds that card and never sends the text as a name`() = runBlocking {
        serveCapturedAnswers()
        searchFor(SearchScope.ALL, "OBF 125")

        val searches = seen.map { it.requestUrl!! }.filter { it.encodedPath == "/api/cards/search" }
        assertTrue(searches.isNotEmpty())
        assertTrue(searches.none { it.queryParameter("q") == "OBF 125" })
        assertTrue(searches.any { it.queryParameter("set_id") == "sv3_en" && it.queryParameter("number") == "125" })
    }
}
