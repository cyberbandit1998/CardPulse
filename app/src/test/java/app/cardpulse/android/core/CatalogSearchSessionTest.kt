package app.cardpulse.android.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A server with a small catalogue that answers a search the way PokéCollector's does: every filter given has to match, text is
 * looked for inside the name, the artist and the rarity, a number ignores leading zeros, and the answer is a page of the matches
 * by name. It keeps a record of what it was asked, and refuses what the real one would send to the internet.
 */
private class FakeSearchServer : CatalogBackend {
    @Volatile var cards: List<CardDto> = emptyList()
    @Volatile var setList: List<SetDto> = emptyList()
    val searches = CopyOnWriteArrayList<Triple<CardSearchParams, Int, Int>>()
    val setCalls = AtomicInteger()
    @Volatile var setsError: Throwable? = null

    /** Fails a page: given what was asked and which page, the error to throw, or null. */
    @Volatile var failure: (CardSearchParams, Int) -> Throwable? = { _, _ -> null }

    /** While a gate is set for a question, its answer waits here, like a slow connection. */
    val gates = ConcurrentHashMap<CardSearchParams, CompletableDeferred<Unit>>()

    private val serverReadsAsCode = Regex("^([A-Za-z][A-Za-z0-9]*)\\s+(\\d+)$")

    override suspend fun searchCatalog(params: CardSearchParams, page: Int, pageSize: Int): CardSearchDto {
        searches += Triple(params, page, pageSize)
        check(params.q == null || !serverReadsAsCode.matches(params.q)) { "the real server would go to the internet for '${params.q}'" }
        gates[params]?.await()
        failure(params, page)?.let { throw it }
        val hits = cards.filter { it.matches(params) }.sortedBy { it.name }
        return CardSearchDto(data = hits.drop((page - 1) * pageSize).take(pageSize), totalCount = hits.size, page = page, pageSize = pageSize)
    }

    private fun CardDto.matches(params: CardSearchParams): Boolean {
        if (params.q != null && !name.contains(params.q, ignoreCase = true)) return false
        if (params.artist != null && artist?.contains(params.artist, ignoreCase = true) != true) return false
        if (params.rarity != null && rarity?.contains(params.rarity, ignoreCase = true) != true) return false
        if (params.number != null && number?.trimStart('0').equals(params.number.trimStart('0'), ignoreCase = true).not()) return false
        if (params.setId != null) {
            val set = setList.firstOrNull { it.id == params.setId }
            if (set == null || setId != (set.tcgSetId ?: set.id)) return false
        }
        return true
    }

    override suspend fun sets(): List<SetDto> {
        setCalls.incrementAndGet()
        setsError?.let { throw it }
        return setList
    }

    /** What was asked for, without the page. */
    fun asked(): List<CardSearchParams> = searches.map { it.first }
}

class CatalogSearchSessionTest {
    private lateinit var scope: CoroutineScope
    private lateinit var backend: FakeSearchServer
    private lateinit var session: CatalogSearchSession
    private val clock = AtomicLong(1_000_000L)

    private val sets = listOf(
        SetDto(id = "tst1_en", tcgSetId = "tst1", name = "30th Celebration", abbreviation = "30C", printedTotal = 128),
        SetDto(id = "sv3_en", tcgSetId = "sv3", name = "Obsidian Flames", abbreviation = "OBF", printedTotal = 197),
        SetDto(id = "base1_en", tcgSetId = "base1", name = "Base Set", abbreviation = "BS", printedTotal = 102),
    )

    private fun card(id: String, name: String, set: String = "base1", number: String = "1", rarity: String? = "Common", artist: String? = null) =
        CardDto(
            id = id, name = name, setId = set, number = number, rarity = rarity, artist = artist, lang = "en",
            setRef = sets.firstOrNull { it.tcgSetId == set },
        )

    private val pikachuBase = card("base1-58_en", "Pikachu", "base1", "58", artist = "Mitsuhiro Arita")
    private val pikachuCelebration = card("tst1-130_en", "Pikachu", "tst1", "130", "Illustration Rare", "Mitsuhiro Arita")
    private val pikachuDetective = card("tst1-002_en", "Detective Pikachu", "tst1", "2", "Uncommon", artist = null)
    private val charizardBase = card("base1-4_en", "Charizard", "base1", "4", "Rare Holo", "Mitsuhiro Arita")
    private val charizardEx = card("sv3-125_en", "Charizard ex", "sv3", "125", "Double Rare", "5ban Graphics")
    private val pidgeot = card("sv3-130_en", "Pidgeot ex", "sv3", "130", "Double Rare", "PLANETA Mochizuki")
    private val blastoise = card("base1-2_en", "Blastoise", "base1", "2", "Rare Holo", "Ken Sugimori")

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        backend = FakeSearchServer().apply {
            setList = sets
            cards = listOf(pikachuBase, pikachuCelebration, pikachuDetective, charizardBase, charizardEx, pidgeot, blastoise)
        }
        session = newSession()
    }

    private fun newSession(debounceMs: Long = 40, pageSize: Int = 30, parallel: Int = 4) = CatalogSearchSession(
        backend = backend,
        scope = scope,
        describe = { it.message ?: "problem" },
        debounceMs = debounceMs,
        pageSize = pageSize,
        parallel = parallel,
        now = { clock.get() },
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    private val state get() = session.state.value

    private suspend fun await(timeoutMs: Long = 5_000, until: (CatalogSearchState) -> Boolean): CatalogSearchState =
        withTimeout(timeoutMs) { session.state.first(until) }

    /** Waits for something that is not in the state, such as what the fake server has been asked. */
    private suspend fun eventually(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        withTimeout(timeoutMs) { while (!condition()) delay(5) }
    }

    /** Every part of the search that was asked for at once has been answered, and nothing is on its way. */
    private suspend fun settled(): CatalogSearchState = await { s ->
        val run = s.run
        !s.searching && (run == null || (run.groups.take(4).all { it.total != null || it.failed != null } && run.groups.none { it.loading }))
    }

    /** Types something in a scope and waits for the whole first answer to it. */
    private suspend fun look(scopeToUse: SearchScope, text: String): CatalogSearchState {
        session.setScope(scopeToUse)
        session.setText(text)
        val wanted = state.query
        await { it.resultsFor == wanted && !it.searching }
        return settled()
    }

    private fun CatalogSearchState.names() = results.map { it.id }

    // --- searching by what is printed on a card -------------------------------------------------

    @Test
    fun `a name finds every card with that name, across all sets, by name`() = runBlocking {
        val found = look(SearchScope.POKEMON, "pikachu")

        // The server's order: by name (ties are the server's own).
        assertEquals(listOf("tst1-002_en"), found.names().take(1))
        assertEquals(setOf("tst1-002_en", "base1-58_en", "tst1-130_en"), found.names().toSet())
        assertEquals(3, found.matches)
        assertFalse(found.hasMore)
        assertEquals(listOf(CardSearchParams(q = "pikachu")), backend.asked())
        assertEquals(found.query, found.resultsFor)
    }

    @Test
    fun `an artist finds every card they drew, and leaves out the cards the catalogue has no artist for`() = runBlocking {
        val found = look(SearchScope.ARTIST, "mitsuhiro arita")

        assertEquals(setOf("base1-58_en", "base1-4_en", "tst1-130_en"), found.names().toSet())
        assertEquals(listOf(CardSearchParams(artist = "mitsuhiro arita")), backend.asked())
        assertTrue(found.results.all { it.artistName() == "Mitsuhiro Arita" })
        assertTrue(pikachuDetective.id !in found.names())
    }

    @Test
    fun `a collector number with the size of its set finds the card of that set`() = runBlocking {
        val found = look(SearchScope.NUMBER, "130/128")

        assertEquals(listOf("tst1-130_en"), found.names())
        assertEquals(listOf(CardSearchParams(setId = "tst1_en", number = "130")), backend.asked())
        assertNull(found.note)
    }

    @Test
    fun `a collector number alone finds the cards of that number in every set`() = runBlocking {
        val found = look(SearchScope.NUMBER, "#130")

        assertEquals(setOf("tst1-130_en", "sv3-130_en"), found.names().toSet())
        assertEquals(listOf(CardSearchParams(number = "130")), backend.asked())
    }

    @Test
    fun `a size no set prints still finds the number, and says so`() = runBlocking {
        val found = look(SearchScope.NUMBER, "130/99")

        assertEquals(setOf("tst1-130_en", "sv3-130_en"), found.names().toSet())
        assertEquals("No set prints 99 cards, so these are the cards numbered 130 in every set.", found.note)
    }

    @Test
    fun `a set name finds every card of that set`() = runBlocking {
        val found = look(SearchScope.SET, "30th Celebration")

        assertEquals(setOf("tst1-130_en", "tst1-002_en"), found.names().toSet())
        assertEquals(listOf(CardSearchParams(setId = "tst1_en")), backend.asked())
    }

    @Test
    fun `a set code finds every card of that set`() = runBlocking {
        val found = look(SearchScope.SET, "obf")

        assertEquals(setOf("sv3-125_en", "sv3-130_en"), found.names().toSet())
    }

    @Test
    fun `a set code and a number finds that card without the server ever being asked for the text as a name`() = runBlocking {
        val found = look(SearchScope.ALL, "OBF 125")

        assertEquals(listOf("sv3-125_en"), found.names())
        assertTrue(backend.asked().none { it.q == "OBF 125" })
    }

    @Test
    fun `a name and a number finds the card of that name and number`() = runBlocking {
        val found = look(SearchScope.ALL, "Pikachu 58")

        assertEquals(listOf("base1-58_en"), found.names())
        assertEquals(listOf(CardSearchParams(q = "Pikachu", number = "58")), backend.asked())
    }

    @Test
    fun `All looks at the name, the artist, and the rarity, and lists what each finds`() = runBlocking {
        val found = look(SearchScope.ALL, "illustration")

        assertEquals(listOf("tst1-130_en"), found.names())
        assertEquals(
            setOf(CardSearchParams(q = "illustration"), CardSearchParams(artist = "illustration"), CardSearchParams(rarity = "illustration")),
            backend.asked().toSet(),
        )
    }

    @Test
    fun `All lists the name matches first, then the artist matches, then the set and the rarity ones`() = runBlocking {
        // "base" is a name (none), an artist (none) and a set.
        val found = look(SearchScope.ALL, "base set")

        assertEquals(setOf("base1-58_en", "base1-4_en", "base1-2_en"), found.names().toSet())

        // "Arita" is only an artist; a card named for it comes before.
        backend.cards = backend.cards + card("x-1_en", "Arita's Eevee", "base1", "9", artist = "Someone")
        val ordered = look(SearchScope.ALL, "arita")
        assertEquals("x-1_en", ordered.names().first())
        assertEquals(setOf("x-1_en", "base1-58_en", "base1-4_en", "tst1-130_en"), ordered.names().toSet())
    }

    @Test
    fun `a card that more than one part finds is listed once and counted once`() = runBlocking {
        backend.cards = backend.cards + card("x-2_en", "Arita", "base1", "7", artist = "Mitsuhiro Arita")
        val found = look(SearchScope.ALL, "arita")

        assertEquals(1, found.names().count { it == "x-2_en" })
        assertEquals(found.names().toSet().size, found.names().size)
        assertEquals(found.results.size, found.matches)
    }

    @Test
    fun `a set named in full comes before the cards whose names contain the words`() = runBlocking {
        backend.cards = backend.cards + card("x-3_en", "Base Set Hunter", "sv3", "99")
        val found = look(SearchScope.ALL, "base set")

        assertEquals(listOf("base1-2_en", "base1-4_en", "base1-58_en"), found.names().take(3))
        assertEquals("x-3_en", found.names().last())
    }

    @Test
    fun `nothing matching is said as no match, with nothing more to wait for`() = runBlocking {
        val found = look(SearchScope.ALL, "zzzzzz")

        assertTrue(found.results.isEmpty())
        assertTrue(found.noMatch)
        assertFalse(found.hasMore)
        assertNull(found.error)
    }

    // --- paging ---------------------------------------------------------------------------------

    private fun manyNamed(prefix: String, count: Int, artist: String? = null) =
        (1..count).map { n -> card("$prefix-$n", "$prefix ${n.toString().padStart(3, '0')}", "base1", n.toString(), artist = artist) }

    @Test
    fun `cards are read a page at a time as the user scrolls`() = runBlocking {
        backend.cards = manyNamed("Rattata", 70)
        val first = look(SearchScope.POKEMON, "rattata")

        assertEquals(30, first.results.size)
        assertEquals(70, first.matches)
        assertTrue(first.hasMore)

        session.loadMore()
        val second = await { it.results.size == 60 && !it.loadingMore }
        assertTrue(second.hasMore)

        session.loadMore()
        val last = await { it.results.size == 70 && !it.loadingMore }
        assertFalse(last.hasMore)
        assertEquals((1..70).map { "Rattata-$it" }, last.names().sortedBy { it.substringAfter('-').toInt() })

        val asked = backend.searches.size
        session.loadMore() // nothing is left
        delay(100)
        assertEquals(asked, backend.searches.size)
        assertEquals(listOf(1, 2, 3), backend.searches.map { it.second })
    }

    @Test
    fun `asking for more while a page is on its way asks once`() = runBlocking {
        backend.cards = manyNamed("Rattata", 70)
        look(SearchScope.POKEMON, "rattata")
        val gate = CompletableDeferred<Unit>()
        backend.gates[CardSearchParams(q = "rattata")] = gate

        session.loadMore()
        session.loadMore()
        session.loadMore()
        await { it.loadingMore }
        delay(100)
        assertEquals(1, backend.searches.count { it.second == 2 })

        gate.complete(Unit)
        await { it.results.size == 60 && !it.loadingMore }
        assertEquals(1, backend.searches.count { it.second == 2 })
    }

    @Test
    fun `a later part only shows once the part before it has been read to the end`() = runBlocking {
        backend.cards = manyNamed("Zed", 35) + card("z-1_en", "Other", "base1", "50", artist = "Zed Artist")
        val first = look(SearchScope.ALL, "zed")

        // 35 cards are named for it, 30 shown so far: the artist's card waits behind them.
        assertEquals(30, first.results.size)
        assertTrue("z-1_en" !in first.names())
        assertTrue(first.hasMore)

        session.loadMore()
        val all = await { it.results.size == 36 && !it.loadingMore }
        assertEquals("z-1_en", all.names().last())
        assertFalse(all.hasMore)
    }

    @Test
    fun `a part that finds nothing does not leave the list empty, the next part is read and shown`() = runBlocking {
        // Nothing is named for it; the artist's cards are the results, and a part with none must not leave the list empty.
        backend.cards = manyNamed("Plain", 40, artist = "Zed Artist")
        val found = look(SearchScope.ALL, "zed artist")

        assertEquals(30, found.results.size)
        assertEquals(40, found.matches)
        assertTrue(found.hasMore)
    }

    @Test
    fun `parts beyond the first few are only asked for when they are reached`() = runBlocking {
        session = newSession(parallel = 1)
        backend.cards = listOf(card("a-1_en", "Alpha", "base1", "1", artist = "Zed Artist"))
        val found = look(SearchScope.ALL, "zed artist")

        // The name part found nothing, so the artist part was asked for next, by itself.
        assertEquals(listOf("a-1_en"), found.names())
        assertEquals(CardSearchParams(q = "zed artist"), backend.asked().first())
        assertTrue(CardSearchParams(artist = "zed artist") in backend.asked())
    }

    @Test
    fun `a card on two pages of the same part is listed once`() = runBlocking {
        backend.cards = manyNamed("Rattata", 35)
        session = newSession(pageSize = 20)
        look(SearchScope.POKEMON, "rattata")
        session.loadMore()
        val all = await { it.results.size == 35 && !it.loadingMore }
        assertEquals(35, all.names().toSet().size)
    }

    // --- typing ---------------------------------------------------------------------------------

    @Test
    fun `nothing is searched until typing pauses, and a burst of typing is one search`() = runBlocking {
        session.setScope(SearchScope.POKEMON)
        session.setText("Pi")
        session.setText("Pik")
        session.setText("Pika")
        assertTrue(backend.searches.isEmpty())

        await { it.resultsFor != null && !it.searching }
        delay(100)
        assertEquals(listOf(CardSearchParams(q = "Pika")), backend.asked())
    }

    @Test
    fun `the keyboard's search key searches at once`() = runBlocking {
        val patient = newSession(debounceMs = 60_000)
        patient.setScope(SearchScope.POKEMON)
        patient.setText("Pikachu")
        patient.submit()
        withTimeout(5_000) { patient.state.first { it.resultsFor != null && !it.searching } }
        assertEquals(3, patient.state.value.results.size)
    }

    @Test
    fun `a single letter is not searched, and shortening the text clears what was shown`() = runBlocking {
        session.setText("P")
        delay(150)
        assertTrue(backend.searches.isEmpty())

        look(SearchScope.POKEMON, "Pikachu")
        assertEquals(3, state.results.size)

        session.setText("P")
        assertTrue(state.results.isEmpty())
        assertFalse(state.searching)
        assertFalse(state.noMatch) // too little to look for is not "no match"
    }

    @Test
    fun `changing the scope searches the same text again at once`() = runBlocking {
        look(SearchScope.POKEMON, "arita")
        assertTrue(state.results.isEmpty())

        val artist = look(SearchScope.ARTIST, "arita")
        assertEquals(setOf("base1-58_en", "base1-4_en", "tst1-130_en"), artist.names().toSet())
    }

    @Test
    fun `typing on while an earlier search is slow shows only what the newer text found`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        backend.gates[CardSearchParams(q = "Pik")] = gate
        session.setScope(SearchScope.POKEMON)
        session.setText("Pik")
        eventually { backend.searches.isNotEmpty() }

        session.setText("Charizard")
        val found = await { it.resultsFor?.text == "Charizard" && !it.searching }
        assertEquals(setOf("base1-4_en", "sv3-125_en"), found.names().toSet())

        gate.complete(Unit) // the slow answer comes late and is ignored
        delay(150)
        assertEquals(setOf("base1-4_en", "sv3-125_en"), state.names().toSet())
        assertEquals("Charizard", state.resultsFor?.text)
    }

    @Test
    fun `while a newer search is looked for the cards of the one before stay on the screen`() = runBlocking {
        look(SearchScope.POKEMON, "charizard")
        val gate = CompletableDeferred<Unit>()
        backend.gates[CardSearchParams(q = "pikachu")] = gate

        session.setText("pikachu")
        await { it.searching }
        assertEquals(setOf("base1-4_en", "sv3-125_en"), state.names().toSet())
        assertFalse(state.upToDate)

        gate.complete(Unit)
        val found = await { it.resultsFor?.text == "pikachu" && !it.searching }
        assertEquals(3, found.results.size)
    }

    @Test
    fun `opening a search starts from nothing and searches at once when there is text`() = runBlocking {
        look(SearchScope.POKEMON, "charizard")

        session.open(SearchScope.ARTIST, "Ken Sugimori")
        val found = await { it.resultsFor?.scope == SearchScope.ARTIST && !it.searching }

        assertEquals(listOf("base1-2_en"), found.names())
        assertEquals("Ken Sugimori", found.text)
        assertEquals(SearchScope.ARTIST, found.scope)

        session.open(SearchScope.POKEMON)
        assertTrue(state.results.isEmpty())
        assertEquals("", state.text)
        assertNull(state.resultsFor)
        assertFalse(state.searching)
    }

    // --- sets -----------------------------------------------------------------------------------

    @Test
    fun `the sets are asked for once and kept, and again after a while`() = runBlocking {
        look(SearchScope.ALL, "pikachu")
        look(SearchScope.ALL, "charizard")
        assertEquals(1, backend.setCalls.get())
        assertTrue(state.setsLoaded)
        assertEquals(sets, state.sets)

        clock.addAndGet(31 * 60 * 1000L)
        look(SearchScope.ALL, "blastoise")
        assertEquals(2, backend.setCalls.get())
    }

    @Test
    fun `an artist search does not need the sets`() = runBlocking {
        look(SearchScope.ARTIST, "arita")
        assertEquals(0, backend.setCalls.get())
    }

    @Test
    fun `when the sets cannot be had the name, artist and rarity are still searched`() = runBlocking {
        backend.setsError = IOException("no route")
        val found = look(SearchScope.ALL, "pikachu")

        assertEquals(3, found.results.size)
        assertNull(found.error)
        assertFalse(found.setsLoaded)
    }

    @Test
    fun `a search by set cannot be made without the sets, and can be tried again`() = runBlocking {
        backend.setsError = IOException("no route")
        session.setScope(SearchScope.SET)
        session.setText("obf")
        val failed = await { it.error != null }

        assertEquals("no route", failed.error)
        assertFalse(failed.searching)
        assertTrue(failed.results.isEmpty())
        assertTrue(backend.searches.isEmpty())

        backend.setsError = null
        session.retry()
        val found = await { it.resultsFor?.text == "obf" && !it.searching }
        assertEquals(setOf("sv3-125_en", "sv3-130_en"), found.names().toSet())
        assertNull(found.error)
    }

    // --- failing --------------------------------------------------------------------------------

    @Test
    fun `a search the server cannot answer says so, and trying again searches again`() = runBlocking {
        backend.failure = { _, _ -> IOException("unreachable") }
        session.setScope(SearchScope.POKEMON)
        session.setText("pikachu")
        val failed = await { it.error != null }
        assertEquals("unreachable", failed.error)
        assertFalse(failed.searching)
        assertTrue(failed.results.isEmpty())
        assertFalse(failed.noMatch) // not finding out is not "no match"

        backend.failure = { _, _ -> null }
        session.retry()
        val found = await { it.resultsFor?.text == "pikachu" && !it.searching }
        assertEquals(3, found.results.size)
        assertNull(found.error)
    }

    @Test
    fun `a page that cannot be had leaves the cards already read, and trying again reads on`() = runBlocking {
        backend.cards = manyNamed("Rattata", 70)
        look(SearchScope.POKEMON, "rattata")
        backend.failure = { _, page -> if (page == 2) IOException("dropped") else null }

        session.loadMore()
        val failed = await { it.moreError != null }
        assertEquals("dropped", failed.moreError)
        assertEquals(30, failed.results.size) // what was read is kept
        assertNull(failed.error)
        assertTrue(failed.hasMore)

        // Asking for more does not hammer a failing server: that is the retry's.
        val asked = backend.searches.size
        session.loadMore()
        delay(100)
        assertEquals(asked, backend.searches.size)

        backend.failure = { _, _ -> null }
        session.retry()
        val read = await { it.results.size == 60 && !it.loadingMore }
        assertNull(read.moreError)
    }

    @Test
    fun `a part that cannot be had does not hide the cards of the parts before it`() = runBlocking {
        backend.failure = { params, _ -> if (params.artist != null) IOException("artist failed") else null }
        val found = look(SearchScope.ALL, "charizard")

        // The cards named for it are shown; the artist part, which is next, failed.
        assertEquals(setOf("base1-4_en", "sv3-125_en"), found.names().toSet())
        assertEquals("artist failed", found.moreError)
        assertTrue(found.hasMore)

        backend.failure = { _, _ -> null }
        session.retry()
        val read = await { it.moreError == null && !it.loadingMore && it.run?.frontier == -1 }
        assertEquals(setOf("base1-4_en", "sv3-125_en"), read.names().toSet())
        assertFalse(read.hasMore)
    }

    // --- forgetting -----------------------------------------------------------------------------

    @Test
    fun `resetting forgets the results and the sets`() = runBlocking {
        look(SearchScope.ALL, "pikachu")
        assertEquals(1, backend.setCalls.get())

        session.reset()
        assertEquals(CatalogSearchState(), state)

        look(SearchScope.ALL, "pikachu")
        assertEquals(2, backend.setCalls.get())
    }

    @Test
    fun `typing the same text again when it is already shown does not search again`() = runBlocking {
        look(SearchScope.POKEMON, "pikachu")
        val asked = backend.searches.size

        session.setText("pikachu ")
        delay(150)
        assertEquals(asked, backend.searches.size)
        assertNotNull(state.resultsFor)
    }
}
