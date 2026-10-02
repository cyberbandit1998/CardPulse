package app.cardpulse.android.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What adding a card by typing needs from the server. The real one is `Repository`; tests use a fake. */
interface ManualAddBackend {
    /** Cards whose name contains [name] (and whose number is [number], when given), in every language the server has. */
    suspend fun searchCards(name: String, number: String?, pageSize: Int): CardSearchDto

    suspend fun sets(): List<SetDto>
    suspend fun createCustomCard(request: CustomCardRequest): CardDto
    suspend fun addToCollection(request: AddToCollectionRequest): CollectionItemDto
}

// ---------------------------------------------------------------------------------------------
// What was typed, and what is known about the card it names
// ---------------------------------------------------------------------------------------------

/** The two boxes as the server wants them: the name tidied up, and the number without what is printed after the slash. */
data class LookupKey(val name: String, val number: String?) {
    /** Enough is typed to look for something: a single letter would match half the catalogue. */
    val searchable: Boolean get() = name.length >= MIN_NAME_LENGTH

    companion object {
        const val MIN_NAME_LENGTH = 2
    }
}

private val whitespace = Regex("\\s+")

/**
 * The card number as the server stores it. Cards print "125/197" (number and set size) and people write "#125", so
 * both are cut down to "125". A number with letters ("TG01/TG30", "H04") keeps them.
 */
fun cardNumberForSearch(input: String): String? =
    input.trim().removePrefix("#").substringBefore('/').trim().ifEmpty { null }

val CustomCardForm.lookup: LookupKey
    get() = LookupKey(name.trim().replace(whitespace, " "), cardNumberForSearch(number))

/** Cards named exactly what was typed come first; otherwise the server's order (by name) is kept. */
fun List<CardDto>.bestFirst(name: String): List<CardDto> = sortedByDescending { it.name.equals(name, ignoreCase = true) }

/** The card's id without a language suffix: how the same card is recognised across languages. */
fun CardDto.plainCardId(): String = tcgCardId ?: CardLanguages.splitCardId(id).first

/** The language this card is in, as the server writes it. */
fun CardDto.ownLanguage(): String = CardLanguages.normalize(lang) ?: CardLanguages.splitCardId(id).second ?: "en"

/** The set's name, or whatever of it the server gave. */
fun CardDto.setName(): String? = listOfNotNull(setRef?.name, setRef?.abbreviation, setId).firstOrNull { it.isNotBlank() }

/** "Obsidian Flames · #125 · Double Rare" */
fun CardDto.subtitle(): String = listOfNotNull(setName(), number?.takeIf { it.isNotBlank() }?.let { "#$it" }, rarity).joinToString(" · ")

/** "Fire · HP 330 · Illus. 5ban Graphics": what the server knows about the card besides where it is from. */
fun CardDto.facts(): String = listOfNotNull(
    typeNames.takeIf { it.isNotEmpty() }?.joinToString(" / "),
    hp?.takeIf { it.isNotBlank() }?.let { "HP $it" },
    artist?.takeIf { it.isNotBlank() }?.let { "Illus. $it" },
).joinToString(" · ")

/** The variants the card comes in, named as the collection names them; empty when the server doesn't say. */
fun CardDto.variantNames(): List<String> = listOfNotNull(
    "Normal".takeIf { variantsNormal == true },
    "Holo".takeIf { variantsHolo == true },
    "Reverse Holo".takeIf { variantsReverse == true },
    "First Edition".takeIf { variantsFirstEdition == true },
)

/** [preferred] when the card comes that way (or nothing is known), otherwise the first variant it does come in. */
fun CardDto.suggestedVariant(preferred: String): String {
    val own = variantNames()
    return if (own.isEmpty() || preferred in own) preferred else own.first()
}

/** What to say about a result: unknown until the collection has loaded, then new or owned. */
fun CollectionIndex?.ownershipOf(card: CardDto): Ownership =
    this?.ownershipOf(card.plainCardId()) ?: Ownership.Unknown

/**
 * The body for adding [this] to the collection. A catalogue card can be added in another language (the server then
 * adds that language's version of the card, so the id's suffix changes), the way a scanned card can. A card made by
 * hand has the one language it was made in, whatever is asked for.
 */
fun CardDto.toAddRequest(edits: AddEdits): AddToCollectionRequest {
    val own = ownLanguage()
    val madeByHand = isCustom || id.startsWith("custom-")
    val language = if (madeByHand) own else CardLanguages.normalize(edits.lang) ?: own
    return AddToCollectionRequest(
        cardId = if (language == own) id else "${plainCardId()}_$language",
        quantity = edits.quantity.coerceIn(1, 999),
        condition = edits.condition,
        variant = edits.variant,
        purchasePrice = edits.purchasePrice?.takeIf { it >= 0.0 },
        lang = language,
    )
}

// ---------------------------------------------------------------------------------------------
// The session
// ---------------------------------------------------------------------------------------------

/** Looking a card up by what is typed, or making one by hand when the catalogue doesn't have it. */
enum class ManualMode { LOOKUP, BY_HAND }

/** What was just added, to say so while the next card is being typed. */
data class AddedNote(val name: String, val quantity: Int)

data class ManualAddState(
    val form: CustomCardForm = CustomCardForm(),
    val mode: ManualMode = ManualMode.LOOKUP,
    val results: List<CardDto> = emptyList(),
    /** How many cards match in all; more than [results] holds when the server sent only a first page. */
    val matches: Int = 0,
    val searching: Boolean = false,
    /** What [results] were found for, so results for older text are never taken for what is in the boxes now. */
    val resultsFor: LookupKey? = null,
    val searchError: String? = null,
    val selectedId: String? = null,
    val edits: AddEdits = AddEdits(),
    /** The server's sets, for the one who makes a card by hand; null until they have arrived. */
    val sets: List<SetDto>? = null,
    val setsLoading: Boolean = false,
    val setsError: String? = null,
    /** A card is being made or added. */
    val busy: Boolean = false,
    /** The last problem making or adding a card. */
    val error: String? = null,
    val lastAdded: AddedNote? = null,
) {
    val lookup: LookupKey get() = form.lookup

    /** The result the user is looking at: the card whose details are filled in. */
    val selected: CardDto? get() = selectedId?.let { id -> results.firstOrNull { it.id == id } }

    /** The catalogue has been searched for exactly what is typed and has nothing by that name. */
    val noMatch: Boolean
        get() = mode == ManualMode.LOOKUP && !searching && searchError == null && lookup.searchable &&
            resultsFor == lookup && results.isEmpty()
}

/**
 * Adds a card by typing its name and number: the catalogue is searched as the user types, and when one card matches it
 * is picked and its details (set, rarity, picture and so on) fill in by themselves. A card the catalogue doesn't have
 * can be made by hand, as the website's "Create card manually" does. It holds no Android types, so it is tested on the
 * JVM against a fake server.
 */
class ManualAddSession(
    private val backend: ManualAddBackend,
    private val scope: CoroutineScope,
    private val onCollectionItem: (CollectionItemDto) -> Unit = {},
    private val describe: (Throwable) -> String = { it.message ?: "Something went wrong." },
    /** The condition and variant to start each card with (the ones used last time). */
    private val defaultEdits: () -> AddEdits = { AddEdits() },
    /** How long typing has to pause before the catalogue is searched. */
    private val debounceMs: Long = 450,
    private val pageSize: Int = 20,
) {
    private val _state = MutableStateFlow(ManualAddState(edits = defaultEdits()))
    val state: StateFlow<ManualAddState> = _state.asStateFlow()

    private var pending: Job? = null

    /** The details to start a card with: its own language, and a variant it actually comes in. */
    private fun AddEdits.startingOn(card: CardDto?): AddEdits =
        copy(lang = null, variant = card?.suggestedVariant(variant) ?: variant)

    // --- typing ----------------------------------------------------------------------------------

    fun setName(text: String) = typed { it.copy(name = text) }

    fun setNumber(text: String) = typed { it.copy(number = text) }

    /** The details only a card made by hand has. */
    fun setForm(form: CustomCardForm) {
        _state.update { if (it.mode == ManualMode.BY_HAND && !it.busy) it.copy(form = form, error = null) else it }
    }

    private fun typed(change: (CustomCardForm) -> CustomCardForm) {
        var moved = false
        _state.update { current ->
            if (current.busy) {
                moved = false
                current
            } else {
                val form = change(current.form)
                moved = form.lookup != current.form.lookup
                current.copy(form = form, error = null, lastAdded = null)
            }
        }
        if (moved) look(immediately = false)
    }

    /** Searches now, without waiting for typing to pause (the keyboard's search key). */
    fun search() = look(immediately = true)

    private fun look(immediately: Boolean) {
        pending?.cancel()
        val current = _state.value
        if (current.mode != ManualMode.LOOKUP) return
        val key = current.lookup
        if (!key.searchable) {
            _state.update {
                it.copy(results = emptyList(), matches = 0, searching = false, resultsFor = null, searchError = null, selectedId = null)
            }
            return
        }
        if (!immediately && key == current.resultsFor && current.searchError == null) {
            // Typed on and then back: what is shown is already right.
            _state.update { it.copy(searching = false) }
            return
        }
        pending = scope.launch {
            if (!immediately) delay(debounceMs)
            find(key)
        }
    }

    private suspend fun find(key: LookupKey) {
        _state.update { it.copy(searching = true, searchError = null) }
        try {
            val page = backend.searchCards(key.name, key.number, pageSize)
            val found = page.data.bestFirst(key.name)
            _state.update { current ->
                if (current.lookup != key || current.mode != ManualMode.LOOKUP) {
                    current // the text moved on while waiting; a newer search is on its way
                } else {
                    val kept = current.selectedId?.takeIf { id -> found.any { it.id == id } }
                    // One match is the card: pick it, so its details fill in without another tap.
                    val chosen = kept ?: found.singleOrNull()?.id
                    current.copy(
                        results = found,
                        matches = maxOf(page.totalCount, found.size),
                        searching = false,
                        resultsFor = key,
                        searchError = null,
                        selectedId = chosen,
                        edits = if (chosen == current.selectedId) current.edits else current.edits.startingOn(found.firstOrNull { it.id == chosen }),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { current ->
                if (current.lookup != key) {
                    current
                } else {
                    current.copy(
                        results = emptyList(), matches = 0, searching = false, resultsFor = null,
                        selectedId = null, searchError = describe(e),
                    )
                }
            }
        }
    }

    // --- choosing --------------------------------------------------------------------------------

    /** Picks one of the results, so its details are shown and it is the card that gets added. */
    fun select(id: String) {
        _state.update { current ->
            if (current.busy || id == current.selectedId || current.results.none { it.id == id }) {
                current
            } else {
                // Each result is in its own language, and starts there.
                current.copy(selectedId = id, edits = current.edits.startingOn(current.results.first { it.id == id }), error = null)
            }
        }
    }

    /** How many, in what state, in which language, and for how much. */
    fun setEdits(edits: AddEdits) {
        _state.update { if (it.selected != null && !it.busy) it.copy(edits = edits) else it }
    }

    // --- adding ----------------------------------------------------------------------------------

    /** Puts the picked card into the collection, then clears the boxes for the next one. */
    fun add() {
        val started = claim(ManualMode.LOOKUP, needsCard = true) ?: return
        val card = started.selected ?: return
        scope.launch {
            try {
                val item = backend.addToCollection(card.toAddRequest(started.edits))
                onCollectionItem(item)
                _state.update { current ->
                    ManualAddState(
                        sets = current.sets,
                        setsError = current.setsError,
                        edits = defaultEdits(),
                        lastAdded = AddedNote(card.name, started.edits.quantity.coerceIn(1, 999)),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = describe(e)) }
            }
        }
    }

    // --- a card the catalogue doesn't have -------------------------------------------------------

    /** Shows the extra details that make a card by hand, and loads the sets to choose from. */
    fun startByHand() {
        pending?.cancel()
        _state.update {
            if (it.busy) it else it.copy(mode = ManualMode.BY_HAND, searching = false, selectedId = null, error = null)
        }
        loadSets()
    }

    fun backToLookup() {
        var changed = false
        _state.update {
            changed = !it.busy && it.mode != ManualMode.LOOKUP
            if (changed) it.copy(mode = ManualMode.LOOKUP, error = null) else it
        }
        if (changed) look(immediately = false)
    }

    /** Makes the card on the server. It then stands as the one result, picked, ready to be added. */
    fun create() {
        val current = _state.value
        if (current.mode != ManualMode.BY_HAND || current.busy) return
        val problem = current.form.problem()
        if (problem != null) {
            _state.update { it.copy(error = problem) }
            return
        }
        val started = claim(ManualMode.BY_HAND, needsCard = false) ?: return
        scope.launch {
            try {
                val card = backend.createCustomCard(started.form.toRequest())
                _state.update {
                    it.copy(
                        busy = false,
                        mode = ManualMode.LOOKUP,
                        results = listOf(card),
                        matches = 1,
                        searching = false,
                        resultsFor = it.lookup, // nothing to look up again until the text changes
                        searchError = null,
                        selectedId = card.id,
                        edits = it.edits.startingOn(card),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = describe(e)) }
            }
        }
    }

    /** Fetches the server's sets for the picker. Does nothing when they are loaded or on their way. */
    fun loadSets() {
        var start = false
        _state.update { current ->
            start = current.sets == null && !current.setsLoading
            if (start) current.copy(setsLoading = true, setsError = null) else current
        }
        if (!start) return
        scope.launch {
            try {
                val sets = backend.sets()
                _state.update { it.copy(sets = sets, setsLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(setsLoading = false, setsError = describe(e)) }
            }
        }
    }

    // --- starting again --------------------------------------------------------------------------

    /** Blank boxes. A card being made or added is left to finish. */
    fun reset() {
        pending?.cancel()
        _state.update {
            if (it.busy) it else ManualAddState(sets = it.sets, setsError = it.setsError, edits = defaultEdits())
        }
    }

    /** Takes the one go at an action, so a double tap can't make two cards; null when busy or elsewhere. */
    private fun claim(mode: ManualMode, needsCard: Boolean): ManualAddState? {
        var claimed: ManualAddState? = null
        _state.update { current ->
            if (current.busy || current.mode != mode || (needsCard && current.selected == null)) {
                claimed = null
                current
            } else {
                claimed = current
                current.copy(busy = true, error = null)
            }
        }
        return claimed
    }
}
