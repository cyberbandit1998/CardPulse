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
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private fun httpError(code: Int, body: String) =
    HttpException(Response.error<Any>(code, body.toResponseBody("application/json".toMediaType())))

/** A server with a small catalogue that searches and makes cards when asked, keeping a record of what it was asked. */
private class FakeCatalogue : ManualAddBackend {
    @Volatile var catalogue: List<CardDto> = emptyList()
    val searches = CopyOnWriteArrayList<Triple<String, String?, Int>>()
    @Volatile var searchError: Throwable? = null
    /** While set, a search waits here, like a slow connection. */
    @Volatile var searchGate: CompletableDeferred<Unit>? = null
    /** What the server claims to hold in all, when that is more than it sends. */
    @Volatile var claimedTotal: Int? = null

    val setList = listOf(
        SetDto(id = "sv3_en", tcgSetId = "sv3", name = "Obsidian Flames", lang = "en"),
        SetDto(id = "sv3_de", tcgSetId = "sv3", name = "Obsidian Flammen", lang = "de"),
    )
    val setCalls = AtomicInteger()
    @Volatile var setsError: Throwable? = null

    val created = CopyOnWriteArrayList<CustomCardRequest>()
    @Volatile var createError: Throwable? = null
    @Volatile var createGate: CompletableDeferred<Unit>? = null

    val added = CopyOnWriteArrayList<AddToCollectionRequest>()
    @Volatile var addError: Throwable? = null
    @Volatile var addGate: CompletableDeferred<Unit>? = null
    private val nextId = AtomicInteger(1)

    override suspend fun searchCards(name: String, number: String?, pageSize: Int): CardSearchDto {
        searches += Triple(name, number, pageSize)
        searchGate?.await()
        searchError?.let { throw it }
        val hits = catalogue.filter { card ->
            card.name.contains(name, ignoreCase = true) &&
                (number == null || card.number?.trimStart('0') == number.trimStart('0'))
        }
        return CardSearchDto(data = hits.take(pageSize), totalCount = claimedTotal ?: hits.size, pageSize = pageSize)
    }

    override suspend fun sets(): List<SetDto> {
        setCalls.incrementAndGet()
        setsError?.let { throw it }
        return setList
    }

    override suspend fun createCustomCard(request: CustomCardRequest): CardDto {
        createGate?.await()
        created += request
        createError?.let { throw it }
        return CardDto(
            id = "custom-${nextId.getAndIncrement()}", name = request.name, setId = request.setId, number = request.number,
            rarity = request.rarity, isCustom = true, lang = request.lang ?: "en",
        )
    }

    override suspend fun addToCollection(request: AddToCollectionRequest): CollectionItemDto {
        addGate?.await()
        added += request
        addError?.let { throw it }
        return CollectionItemDto(
            id = 70 + added.size, cardId = request.cardId, quantity = request.quantity, condition = request.condition,
            variant = request.variant, purchasePrice = request.purchasePrice, lang = request.lang,
            card = CardDto(id = request.cardId, name = "added"),
        )
    }
}

class ManualAddSessionTest {
    private lateinit var scope: CoroutineScope
    private lateinit var backend: FakeCatalogue
    private lateinit var session: ManualAddSession
    private val collectionUpdates = CopyOnWriteArrayList<CollectionItemDto>()
    @Volatile private var remembered = AddEdits(condition = "NM", variant = "Normal")

    private val charizardEn = CardDto(
        id = "sv3-125_en", name = "Charizard ex", setId = "sv3", number = "125", rarity = "Double Rare", lang = "en",
        setRef = SetDto(id = "sv3_en", tcgSetId = "sv3", name = "Obsidian Flames", lang = "en"),
    )
    private val charizardDe = charizardEn.copy(id = "sv3-125_de", name = "Glurak ex", lang = "de")
    private val charizardV = CardDto(id = "swsh3-20_en", name = "Charizard V", setId = "swsh3", number = "20", lang = "en")
    private val pikachu25 = CardDto(id = "base1-25_en", name = "Pikachu", setId = "base1", number = "25", lang = "en")
    private val pikachu58 = CardDto(id = "base1-58_en", name = "Pikachu", setId = "base1", number = "58", lang = "en")

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        backend = FakeCatalogue().apply { catalogue = listOf(charizardEn, charizardDe, charizardV, pikachu25, pikachu58) }
        session = newSession()
    }

    private fun newSession(debounceMs: Long = 60) = ManualAddSession(
        backend = backend,
        scope = scope,
        onCollectionItem = { collectionUpdates += it },
        describe = { it.userMessage() },
        defaultEdits = { remembered },
        debounceMs = debounceMs,
        pageSize = 20,
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    private suspend fun await(timeoutMs: Long = 5_000, until: (ManualAddState) -> Boolean): ManualAddState =
        withTimeout(timeoutMs) { session.state.first(until) }

    private val state get() = session.state.value

    /** Types a card and waits for what the catalogue says about it. */
    private suspend fun typeIn(name: String, number: String = ""): ManualAddState {
        session.setName(name)
        session.setNumber(number)
        val key = state.lookup
        return await { it.resultsFor == key && !it.searching }
    }

    // --- looking a card up ---------------------------------------------------------------------

    @Test
    fun `a name and a number find the card and fill it in without another tap`() = runBlocking {
        val found = typeIn("Charizard ex", "125/197")

        assertEquals(listOf(Triple("Charizard ex", "125", 20)), backend.searches.toList())
        assertEquals(listOf(charizardEn), found.results)
        assertEquals(charizardEn, found.selected) // the only match is the card
        assertFalse(found.searching)
        assertFalse(found.noMatch)
        assertNull(found.searchError)
    }

    @Test
    fun `nothing is searched until typing pauses, and a burst of typing is one search`() = runBlocking {
        session.setName("Ch")
        session.setName("Cha")
        session.setName("Char")
        assertTrue(backend.searches.isEmpty())

        await { it.resultsFor != null }
        delay(120)
        assertEquals(listOf(Triple("Char", null, 20)), backend.searches.toList())
    }

    @Test
    fun `a single letter is not searched, and shortening the text clears what was shown`() = runBlocking {
        session.setName("P")
        delay(150)
        assertTrue(backend.searches.isEmpty())

        typeIn("Pikachu")
        assertEquals(2, state.results.size)

        session.setName("P")
        assertTrue(state.results.isEmpty())
        assertNull(state.selected)
        assertFalse(state.searching)
        assertFalse(state.noMatch) // too little to look for is not "no match"
    }

    @Test
    fun `several matches are listed and none is picked until the user picks one`() = runBlocking {
        val found = typeIn("Pikachu")

        assertEquals(listOf(pikachu25, pikachu58), found.results)
        assertNull(found.selectedId)

        session.select(pikachu58.id)
        assertEquals(pikachu58, state.selected)
    }

    @Test
    fun `the number narrows it down to one and that one is picked`() = runBlocking {
        typeIn("Pikachu")
        assertNull(state.selectedId)

        val narrowed = typeIn("Pikachu", "#58")
        assertEquals(listOf(pikachu58), narrowed.results)
        assertEquals(pikachu58, narrowed.selected)
    }

    @Test
    fun `a card named exactly what was typed is listed first`() = runBlocking {
        backend.catalogue = listOf(charizardV, charizardEn.copy(name = "Charizard"), charizardEn.copy(id = "x_en", name = "Charizard ex Tera"))
        val found = typeIn("Charizard")
        assertEquals("Charizard", found.results.first().name)
    }

    @Test
    fun `a pick survives a refresh that still has the card`() = runBlocking {
        typeIn("Pikachu")
        session.select(pikachu58.id)
        session.setEdits(state.edits.copy(quantity = 3))

        typeIn("Pikach") // still finds both
        assertEquals(pikachu58, state.selected)
        assertEquals(3, state.edits.quantity)
    }

    @Test
    fun `a pick that is no longer among the results is dropped`() = runBlocking {
        typeIn("Pikachu")
        session.select(pikachu58.id)
        val next = typeIn("Pikachu", "25")
        assertEquals(pikachu25, next.selected) // the single match is picked instead
    }

    @Test
    fun `picking a result starts it in its own language`() = runBlocking {
        typeIn("ex")
        assertEquals(2, state.results.size) // the English and the German one
        session.select(charizardEn.id)
        session.setEdits(state.edits.copy(lang = "ja"))
        session.select(charizardDe.id)
        assertNull(state.edits.lang)
    }

    @Test
    fun `a card that only comes as holo starts as holo, whatever was used last`() = runBlocking {
        backend.catalogue = listOf(charizardEn.copy(variantsNormal = false, variantsHolo = true, variantsReverse = false))
        val found = typeIn("Charizard ex", "125")
        assertEquals("Holo", found.edits.variant)
    }

    @Test
    fun `picking another result moves the variant to one that card comes in`() = runBlocking {
        val normalOnly = pikachu25.copy(variantsNormal = true, variantsHolo = false, variantsReverse = false)
        val holoOnly = pikachu58.copy(variantsNormal = false, variantsHolo = true, variantsReverse = false)
        backend.catalogue = listOf(normalOnly, holoOnly)
        typeIn("Pikachu")
        session.select(normalOnly.id)
        assertEquals("Normal", state.edits.variant)
        session.select(holoOnly.id)
        assertEquals("Holo", state.edits.variant)
        session.select(normalOnly.id)
        assertEquals("Normal", state.edits.variant) // Holo is not a variant of this one, so it moves to the one it has
    }

    @Test
    fun `a name the catalogue does not have is said to have no match`() = runBlocking {
        val none = typeIn("Missingno", "999")
        assertTrue(none.noMatch)
        assertTrue(none.results.isEmpty())
        assertNull(none.selected)
    }

    @Test
    fun `when the server holds more than it sent, the count says so`() = runBlocking {
        backend.claimedTotal = 134
        val found = typeIn("Pikachu")
        assertEquals(2, found.results.size)
        assertEquals(134, found.matches)
    }

    @Test
    fun `a search that fails says why and can be asked again`() = runBlocking {
        backend.searchError = IOException("offline")
        session.setName("Pikachu")
        val failed = await { it.searchError != null }
        assertEquals("offline", failed.searchError)
        assertTrue(failed.results.isEmpty())
        assertFalse(failed.searching)
        assertFalse(failed.noMatch) // an error is not "no match"

        backend.searchError = null
        session.search()
        val ok = await { it.resultsFor != null && !it.searching }
        assertNull(ok.searchError)
        assertEquals(2, ok.results.size)
    }

    @Test
    fun `the keyboard's search key searches at once`() = runBlocking {
        val slow = newSession(debounceMs = 60_000)
        slow.setName("Pikachu")
        slow.search()
        val found = withTimeout(5_000) { slow.state.first { it.resultsFor != null } }
        assertEquals(2, found.results.size)
    }

    @Test
    fun `older results never replace newer ones`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        backend.searchGate = gate
        session.setName("Pikachu")
        withTimeout(5_000) { while (backend.searches.isEmpty()) delay(5) } // the first search is on its way and stuck

        backend.searchGate = null
        session.setName("Charizard V") // typed on: the first search is abandoned
        val found = await { it.resultsFor?.name == "Charizard V" && !it.searching }
        gate.complete(Unit)
        delay(100)

        assertEquals(listOf(charizardV), state.results)
        assertEquals("Charizard V", state.resultsFor?.name)
        assertEquals(found.results, state.results)
    }

    @Test
    fun `typing the same thing again does not search again`() = runBlocking {
        typeIn("Pikachu")
        session.setName("Pikachu ")
        session.setName("  Pikachu")
        delay(150)
        assertEquals(1, backend.searches.size)

        // Typed on, then back to where it was before the pause is over.
        session.setName("Pikachu x")
        session.setName("Pikachu")
        delay(150)
        assertEquals(1, backend.searches.size)
        assertFalse(state.searching)
    }

    // --- adding it -----------------------------------------------------------------------------

    @Test
    fun `adding puts the card in the collection with the chosen details`() = runBlocking {
        typeIn("Charizard ex", "125")
        session.setEdits(state.edits.copy(quantity = 2, condition = "LP", variant = "Holo", purchasePrice = 3.25))
        session.add()

        val after = await { it.lastAdded != null }
        assertEquals(
            listOf(AddToCollectionRequest(cardId = "sv3-125_en", quantity = 2, condition = "LP", variant = "Holo", purchasePrice = 3.25, lang = "en")),
            backend.added.toList(),
        )
        // The collection screen is told, so it shows the row without another fetch.
        assertEquals(1, collectionUpdates.size)
        assertEquals("sv3-125_en", collectionUpdates.single().cardId)
        assertEquals(AddedNote("Charizard ex", 2), after.lastAdded)
        assertFalse(after.busy)
    }

    @Test
    fun `after an add the boxes are blank for the next card, with the sets still loaded`() = runBlocking {
        session.loadSets()
        await { it.sets != null }
        typeIn("Charizard ex", "125")
        session.setEdits(state.edits.copy(quantity = 4, condition = "LP", variant = "Holo", lang = "de", purchasePrice = 2.0))
        remembered = AddEdits(condition = "LP", variant = "Holo") // what the screen's owner remembers from this add
        session.add()

        val after = await { it.lastAdded != null }

        assertEquals(CustomCardForm(), after.form)
        assertTrue(after.results.isEmpty())
        assertNull(after.selectedId)
        assertEquals(ManualMode.LOOKUP, after.mode)
        assertEquals(backend.setList, after.sets)
        assertEquals(AddEdits(condition = "LP", variant = "Holo"), after.edits) // one copy, no price, own language
        assertNull(after.error)
    }

    @Test
    fun `the next card starts with the condition and variant used last time`() = runBlocking {
        remembered = AddEdits(condition = "MP", variant = "Reverse Holo")
        val fresh = newSession()
        assertEquals("MP", fresh.state.value.edits.condition)
        assertEquals("Reverse Holo", fresh.state.value.edits.variant)
    }

    @Test
    fun `typing the next card clears the note about the last one`() = runBlocking {
        typeIn("Charizard ex", "125")
        session.add()
        await { it.lastAdded != null }
        session.setName("P")
        assertNull(state.lastAdded)
    }

    @Test
    fun `another language is added as that language's version of the card`() = runBlocking {
        typeIn("Charizard ex", "125")
        session.setEdits(state.edits.copy(lang = "fr"))
        session.add()
        await { it.lastAdded != null }
        assertEquals("sv3-125_fr", backend.added.single().cardId)
        assertEquals("fr", backend.added.single().lang)
    }

    @Test
    fun `an add that fails keeps the card picked and says why, and can be tried again`() = runBlocking {
        typeIn("Charizard ex", "125")
        backend.addError = httpError(409, """{"detail":"Copy this shared template before adding it."}""")
        session.add()

        val failed = await { it.error != null }
        assertEquals("Copy this shared template before adding it.", failed.error)
        assertEquals(charizardEn, failed.selected)
        assertFalse(failed.busy)
        assertTrue(collectionUpdates.isEmpty())

        backend.addError = null
        session.add()
        await { it.lastAdded != null }
        assertEquals(1, collectionUpdates.size)
    }

    @Test
    fun `a double tap on add adds once`() = runBlocking {
        typeIn("Charizard ex", "125")
        val gate = CompletableDeferred<Unit>()
        backend.addGate = gate
        session.add()
        await { it.busy }
        session.add()
        session.add()
        gate.complete(Unit)
        await { it.lastAdded != null }
        delay(50)
        assertEquals(1, backend.added.size)
        assertEquals(1, collectionUpdates.size)
    }

    @Test
    fun `nothing is added while no card is picked`() = runBlocking {
        typeIn("Pikachu") // two matches, none picked
        session.add()
        delay(50)
        assertTrue(backend.added.isEmpty())
        assertFalse(state.busy)
    }

    @Test
    fun `while a card is being added the boxes and the pick are left alone`() = runBlocking {
        typeIn("Charizard ex", "125")
        val gate = CompletableDeferred<Unit>()
        backend.addGate = gate
        session.add()
        await { it.busy }

        session.setName("Something else")
        session.setEdits(state.edits.copy(quantity = 9))
        assertEquals("Charizard ex", state.form.name)
        assertEquals(1, state.edits.quantity)

        gate.complete(Unit)
        assertNotNull(await { it.lastAdded != null }.lastAdded)
    }

    @Test
    fun `details can only be changed for a card that is picked`() = runBlocking {
        session.setEdits(AddEdits(quantity = 5))
        assertEquals(1, state.edits.quantity)
        typeIn("Pikachu") // none picked yet
        session.setEdits(AddEdits(quantity = 5))
        assertEquals(1, state.edits.quantity)
    }

    // --- a card the catalogue doesn't have -----------------------------------------------------

    @Test
    fun `making a card by hand shows its extra details and loads the sets once`() = runBlocking {
        typeIn("Missingno", "999")
        session.startByHand()
        val byHand = await { it.sets != null }
        assertEquals(ManualMode.BY_HAND, byHand.mode)
        assertEquals(backend.setList, byHand.sets)
        assertNull(byHand.selectedId)

        session.startByHand()
        session.loadSets()
        delay(50)
        assertEquals(1, backend.setCalls.get())
    }

    @Test
    fun `the catalogue is not searched while a card is being made by hand`() = runBlocking {
        session.startByHand()
        val before = backend.searches.size
        session.setName("Missingno")
        session.setNumber("999")
        delay(200)
        assertEquals(before, backend.searches.size)
        assertEquals("Missingno", state.form.name)
    }

    @Test
    fun `the extra details are only kept in the by-hand view`() = runBlocking {
        session.setForm(CustomCardForm(name = "x", rarity = "Rare"))
        assertEquals("", state.form.rarity)

        session.startByHand()
        session.setForm(state.form.copy(rarity = "Rare Holo", hp = "200"))
        assertEquals("Rare Holo", state.form.rarity)
        assertEquals("200", state.form.hp)
    }

    @Test
    fun `a card made by hand needs a name`() = runBlocking {
        session.startByHand()
        session.create()
        assertEquals("Give the card a name.", state.error)
        assertEquals(ManualMode.BY_HAND, state.mode)
        delay(50)
        assertTrue(backend.created.isEmpty())
    }

    @Test
    fun `a card made by hand is created, picked, and then added like any other`() = runBlocking {
        session.startByHand()
        await { it.sets != null }
        session.setName("Charizard ex")
        session.setNumber("025")
        session.setForm(state.form.copy(set = backend.setList[1], rarity = "Rare Holo", types = setOf("Fire")))
        session.create()

        val made = await { it.mode == ManualMode.LOOKUP && it.selected != null }
        assertEquals(
            CustomCardRequest(
                name = "Charizard ex", setId = "sv3", number = "025", rarity = "Rare Holo", types = listOf("Fire"),
                lang = "de", isSharedTemplate = false,
            ),
            backend.created.single(),
        )
        assertEquals("custom-1", made.selectedId)
        assertEquals(1, made.results.size)
        assertFalse(made.busy)

        // The new card is not looked up again: the catalogue doesn't hold custom cards.
        delay(200)
        assertTrue(backend.searches.isEmpty())
        assertEquals("custom-1", state.selectedId)

        session.setEdits(state.edits.copy(quantity = 2))
        session.add()
        await { it.lastAdded != null }
        assertEquals(AddToCollectionRequest(cardId = "custom-1", quantity = 2, lang = "de"), backend.added.single())
    }

    @Test
    fun `a card the server refuses stays on the by-hand view with what was typed`() = runBlocking {
        backend.createError = httpError(422, """{"detail":"Image URL host is not publicly reachable"}""")
        session.startByHand()
        session.setName("Charizard ex")
        session.setForm(state.form.copy(imageUrl = "https://10.0.0.5/c.png"))
        session.create()

        val failed = await { it.error != null }
        assertEquals("Image URL host is not publicly reachable", failed.error)
        assertEquals(ManualMode.BY_HAND, failed.mode)
        assertEquals("Charizard ex", failed.form.name)
        assertEquals("https://10.0.0.5/c.png", failed.form.imageUrl)
        assertFalse(failed.busy)

        backend.createError = null
        session.setForm(failed.form.copy(imageUrl = ""))
        session.create()
        assertNotNull(await { it.selected != null }.selected)
    }

    @Test
    fun `a double tap on create makes one card`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        backend.createGate = gate
        session.startByHand()
        session.setName("Charizard ex")
        session.create()
        await { it.busy }
        session.create()
        session.create()
        gate.complete(Unit)
        await { it.selected != null }
        delay(50)
        assertEquals(1, backend.created.size)
    }

    @Test
    fun `going back to the lookup searches for what is typed`() = runBlocking {
        session.startByHand()
        session.setName("Pikachu")
        session.backToLookup()
        val found = await { it.resultsFor != null && !it.searching }
        assertEquals(ManualMode.LOOKUP, found.mode)
        assertEquals(2, found.results.size)
    }

    @Test
    fun `sets that cannot be loaded say why and can be asked for again`() = runBlocking {
        backend.setsError = IOException("offline")
        session.startByHand()
        val failed = await { it.setsError != null }
        assertEquals("offline", failed.setsError)
        assertNull(failed.sets)
        assertFalse(failed.setsLoading)

        backend.setsError = null
        session.loadSets()
        val loaded = await { it.sets != null }
        assertNull(loaded.setsError)
        assertEquals(2, backend.setCalls.get())
    }

    // --- starting again ------------------------------------------------------------------------

    @Test
    fun `reset gives blank boxes and keeps the sets`() = runBlocking {
        session.startByHand()
        await { it.sets != null }
        session.setName("Missingno")
        remembered = AddEdits(condition = "HP", variant = "Holo")
        session.reset()

        assertEquals(CustomCardForm(), state.form)
        assertEquals(ManualMode.LOOKUP, state.mode)
        assertEquals(backend.setList, state.sets)
        assertEquals("HP", state.edits.condition)
        assertNotNull(state.sets)
    }

    @Test
    fun `reset does not interrupt a card being added`() = runBlocking {
        typeIn("Charizard ex", "125")
        val gate = CompletableDeferred<Unit>()
        backend.addGate = gate
        session.add()
        await { it.busy }
        session.reset()
        assertEquals("Charizard ex", state.form.name)
        gate.complete(Unit)
        assertNotNull(await { it.lastAdded != null }.lastAdded)
    }
}
