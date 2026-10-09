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
import java.text.Normalizer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

// Searching the server's whole catalogue (every card, not only the ones owned) by what is printed on a card: its name, who drew
// it, the set it is from, its number and its rarity. PokéCollector's `GET api/cards/search` looks for free text in the card's
// name only, and takes the other parts as filters of their own, so one search box is made from several searches: each part is
// asked for separately and the answers are put one after the other, the closest match first. Nothing here holds an Android
// type, so it is tested on the JVM against a fake server.

/** What the search box looks at. [ALL] looks at every part, and puts the closest matches first. */
enum class SearchScope(val label: String, val hint: String) {
    ALL("All", "Search cards, artists, sets…"),
    POKEMON("Pokémon", "Pokémon or card name"),
    ARTIST("Artist", "Artist or illustrator"),
    SET("Set", "Set name or code"),
    NUMBER("Number", "Card number, like 125 or 125/197"),
}

/** What a search asks the server: the parts of `GET api/cards/search` it uses. A null one is left out of the request. */
data class CardSearchParams(
    /** Free text, looked for inside the card's name. */
    val q: String? = null,
    /** Looked for inside the artist's name. */
    val artist: String? = null,
    /** The server's id for the set (such as "sv3_en"). */
    val setId: String? = null,
    /** One card number, leading zeros ignored. */
    val number: String? = null,
    /** Looked for inside the rarity ("Illustration Rare" is found by "illustration"). */
    val rarity: String? = null,
)

/** Why the cards of a [SearchGroup] are in the results. */
enum class MatchedOn { SET, NUMBER, NAME, ARTIST, RARITY }

/** One thing to ask the server for. The cards it sends are one run of results; a search is made of one or several. */
data class SearchGroup(val on: MatchedOn, val params: CardSearchParams)

/** What a search will ask for, in the order the answers are shown, and a word about it when the search could not be as exact as typed. */
data class SearchPlan(val groups: List<SearchGroup>, val note: String? = null)

/** What the server needs from a search. The real one is `Repository`; tests use a fake. */
interface CatalogBackend {
    /** One page of the cards that match [params] (all of them must), in the user's own language, by name. */
    suspend fun searchCatalog(params: CardSearchParams, page: Int, pageSize: Int): CardSearchDto

    /** Every set the server lists, to find a set by its name or code and a card number by the size of its set. */
    suspend fun sets(): List<SetDto>
}

// ---------------------------------------------------------------------------------------------
// What was typed
// ---------------------------------------------------------------------------------------------

private val spaces = Regex("\\s+")
private val combiningMarks = Regex("\\p{Mn}+")

/** The text with single spaces and none at the ends. */
fun tidySearchText(text: String): String = text.trim().replace(spaces, " ")

/** Lower case with the accents taken off, so "Pokémon" and "pokemon" are the same word. */
internal fun foldForSearch(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(combiningMarks, "").lowercase()

/** What is typed and where it is looked for. [text] is already tidied ([tidySearchText]). */
data class CatalogQuery(val scope: SearchScope, val text: String) {
    /** Enough is typed to look for something: one letter would match half the catalogue, but a card number can be one digit. */
    val searchable: Boolean
        get() = if (scope == SearchScope.NUMBER) text.isNotEmpty() else text.length >= MIN_LENGTH

    companion object {
        const val MIN_LENGTH = 2
    }
}

/** A card number as it is written: the number, and how many cards the set prints when that is given too ("130/128"). */
data class CollectorNumber(val number: String, val printedTotal: Int?)

private val collectorNumber = Regex("^#?\\s*([A-Za-z]{0,4}\\d{1,4}[A-Za-z]?)(?:\\s*/\\s*([A-Za-z]{0,4}\\d{1,4}))?$")

/**
 * "125", "#125", "TG05", "74a" or "130/128": a collector number, with the size of its set after the slash when that is a plain
 * number (the "TG30" of "TG05/TG30" is the size of a subset, which no set lists). Null for anything else, such as a name.
 */
fun parseCollectorNumber(text: String): CollectorNumber? {
    val match = collectorNumber.matchEntire(text.trim()) ?: return null
    return CollectorNumber(match.groupValues[1], match.groupValues[2].toIntOrNull())
}

/** A word and a number, such as "OBF 125": what the server itself reads as a set code and a card number. */
private val codeAndNumber = Regex("^([A-Za-z][A-Za-z0-9]*)\\s+(\\d+)$")

/** The server takes text like this for a set code and goes to the internet to look for the set, so it is never handed over as a name. */
private fun readsAsCodeAndNumber(text: String): Boolean = codeAndNumber.matches(text.trim())

private val trailingNumber = Regex("^(.*\\S)\\s+#?(\\d{1,4})$")

/**
 * "OBF 125" is ("OBF", "125") and "Pikachu 58" is ("Pikachu", "58"): other text, then a card number. Null when there is no such
 * number, or when what comes before it is only digits ("130 128" is not a name and a number).
 */
fun parseTrailingNumber(text: String): Pair<String, String>? {
    val match = trailingNumber.matchEntire(text.trim()) ?: return null
    val lead = match.groupValues[1].trim()
    if (lead.isEmpty() || lead.all { it.isDigit() || it == '/' || it == '#' }) return null
    return lead to match.groupValues[2]
}

// ---------------------------------------------------------------------------------------------
// Sets
// ---------------------------------------------------------------------------------------------

/** The codes a set is known by: its printed abbreviation ("OBF") and its ids ("sv3", "sv3_en"), folded. */
private fun SetDto.codes(): List<String> = listOfNotNull(abbreviation, tcgSetId, id).map(::foldForSearch).filter { it.isNotEmpty() }

/** The sets a typed name or code names: the ones it is exactly, and the ones whose name merely holds all of its words. */
data class SetMatches(val exact: List<SetDto>, val partial: List<SetDto>) {
    val all: List<SetDto> get() = exact + partial
}

/**
 * The sets that [term] names. A set is matched exactly when [term] is its whole name or one of its codes; otherwise when every
 * word of [term] is somewhere in its name (accents and case ignored), so "celebration" finds "30th Celebration". The order of
 * the list (newest first, as the server sends it) is kept within each group.
 */
fun List<SetDto>.matchingSets(term: String): SetMatches {
    val wanted = foldForSearch(tidySearchText(term))
    if (wanted.isEmpty()) return SetMatches(emptyList(), emptyList())
    val words = wanted.split(' ')
    val exact = ArrayList<SetDto>()
    val partial = ArrayList<SetDto>()
    for (set in this) {
        val name = foldForSearch(set.name)
        when {
            name == wanted || wanted in set.codes() -> exact += set
            words.all { name.contains(it) } -> partial += set
        }
    }
    return SetMatches(exact, partial)
}

// ---------------------------------------------------------------------------------------------
// What to ask for
// ---------------------------------------------------------------------------------------------

private fun nameGroup(term: String) = SearchGroup(MatchedOn.NAME, CardSearchParams(q = term))
private fun artistGroup(term: String) = SearchGroup(MatchedOn.ARTIST, CardSearchParams(artist = term))
private fun rarityGroup(term: String) = SearchGroup(MatchedOn.RARITY, CardSearchParams(rarity = term))
private fun setGroup(set: SetDto) = SearchGroup(MatchedOn.SET, CardSearchParams(setId = set.id))
private fun numberGroup(number: String) = SearchGroup(MatchedOn.NUMBER, CardSearchParams(number = number))
private fun numberInSetGroup(set: SetDto, number: String) = SearchGroup(MatchedOn.NUMBER, CardSearchParams(setId = set.id, number = number))

/**
 * What to ask for, in the order the answers are shown, to search for [CatalogQuery.text] in [CatalogQuery.scope]. [sets] are the
 * server's sets, or null when they could not be had (then no set is found, and a number is searched for without its set's size).
 *
 * Text that ends in a number ("OBF 125", "Pikachu 58", "Obsidian Flames 125") is a set or a name and that number. The server reads
 * a word and a number as a set code and, when no set has that code, goes to the internet to look for one, so such text is never
 * handed over as a name: it is asked for as the set's card, or as a name and a number, which the server answers from what it holds.
 */
fun CatalogQuery.plan(sets: List<SetDto>?): SearchPlan {
    if (!searchable) return SearchPlan(emptyList())
    val known = sets.orEmpty()
    val named = known.matchingSets(text)
    val number = parseCollectorNumber(text)
    val split = parseTrailingNumber(text)

    /** That number in each set the text before it names: "OBF 125" is Obsidian Flames' card 125. */
    fun setNumbers(): List<SearchGroup> = split?.let { (lead, n) -> known.matchingSets(lead).all.map { numberInSetGroup(it, n) } }.orEmpty()

    /** That number among the cards whose name holds the text before it: "Pikachu 58". */
    fun nameNumber(): List<SearchGroup> = split?.let { (lead, n) ->
        if (lead.length >= CatalogQuery.MIN_LENGTH && !readsAsCodeAndNumber(lead)) {
            listOf(SearchGroup(MatchedOn.NAME, CardSearchParams(q = lead, number = n)))
        } else {
            emptyList()
        }
    }.orEmpty()

    /** The whole text as a name, unless the server would take it for a set code. */
    fun plainName(): List<SearchGroup> = if (readsAsCodeAndNumber(text)) emptyList() else listOf(nameGroup(text))

    return when (scope) {
        SearchScope.POKEMON -> SearchPlan(if (split != null) nameNumber() + setNumbers() + plainName() else plainName())
        SearchScope.ARTIST -> SearchPlan(listOf(artistGroup(text)))
        SearchScope.SET -> SearchPlan(if (named.all.isNotEmpty()) named.all.map(::setGroup) else setNumbers())
        SearchScope.NUMBER -> when {
            split != null -> {
                val groups = setNumbers() + nameNumber()
                if (groups.isEmpty()) SearchPlan(emptyList(), "No set is called “${split.first}”.") else SearchPlan(groups)
            }
            number != null -> numberPlan(number, sets)
            else -> SearchPlan(emptyList(), "That doesn't look like a card number. Try 125, TG05 or 125/197.")
        }
        SearchScope.ALL -> {
            val groups = ArrayList<SearchGroup>()
            var note: String? = null
            // A set named in full ("sv3", "Base Set") comes first: it is most likely what was meant.
            groups += named.exact.map(::setGroup)
            if (split != null) {
                groups += setNumbers()
                groups += nameNumber()
            } else if (number != null) {
                val numbers = numberPlan(number, sets)
                groups += numbers.groups
                note = numbers.note
            }
            // A bare number is no name, an artist or a rarity; "TG05" might be, so it is asked for as one as well.
            val bareNumber = number != null && text.none { it.isLetter() }
            if (!bareNumber) {
                groups += plainName()
                if (split == null) groups += artistGroup(text)
            }
            groups += named.partial.map(::setGroup)
            if (split == null && number == null && text.length >= RARITY_MIN_LENGTH) groups += rarityGroup(text)
            SearchPlan(groups, note)
        }
    }
}

/**
 * A card number: with the size of its set ("130/128") the cards of that number in the sets that print that many, and without it
 * (or when no set prints that many, which is said) the cards of that number in every set.
 */
private fun numberPlan(number: CollectorNumber, sets: List<SetDto>?): SearchPlan {
    val total = number.printedTotal ?: return SearchPlan(listOf(numberGroup(number.number)))
    if (sets == null) {
        return SearchPlan(listOf(numberGroup(number.number)), "The sets couldn't be checked, so these are the cards numbered ${number.number} in every set.")
    }
    val inSets = sets.filter { it.printedTotal == total }
    return if (inSets.isNotEmpty()) {
        SearchPlan(inSets.map { numberInSetGroup(it, number.number) })
    } else {
        SearchPlan(listOf(numberGroup(number.number)), "No set prints $total cards, so these are the cards numbered ${number.number} in every set.")
    }
}

/** A rarity is a word or two: shorter text would only match by accident. */
private const val RARITY_MIN_LENGTH = 3

// ---------------------------------------------------------------------------------------------
// Results
// ---------------------------------------------------------------------------------------------

/** How far one [SearchGroup] has been read: the cards of the pages that have arrived, and what is known about the rest. */
data class GroupProgress(
    val group: SearchGroup,
    val cards: List<CardDto> = emptyList(),
    val nextPage: Int = 1,
    /** How many cards match in all, as the server counts them; null until its first page has arrived. */
    val total: Int? = null,
    /** Every card has arrived (or the server has no more). */
    val done: Boolean = false,
    /** A page is on its way. */
    val loading: Boolean = false,
    /** Why the last page could not be had. */
    val failed: String? = null,
)

/**
 * One search: what it asked for and how far each part has been read. The answers are shown in the order of the parts, a part's
 * cards only once every part before it is read to the end, so the list only ever grows at its end. A card two parts both found
 * is shown once, where it first appears.
 */
data class CatalogRun(
    val id: Long,
    val query: CatalogQuery,
    val groups: List<GroupProgress>,
    val note: String? = null,
) {
    /** The first part with cards still to read, or -1 when every part has been read to the end. */
    val frontier: Int get() = groups.indexOfFirst { !it.done }

    /** The part that has been looked at (its first page has arrived), or there is none left to read: what is shown next waits for pages only. */
    val ready: Boolean get() = groups.getOrNull(frontier)?.let { it.total != null } ?: true

    /** The cards to show, in order, each once. */
    fun shown(): List<CardDto> {
        val seen = HashSet<String>()
        val shown = ArrayList<CardDto>()
        for (progress in groups) {
            for (card in progress.cards) if (seen.add(card.id)) shown += card
            if (!progress.done) break
        }
        return shown
    }

    /** How many cards match in all, as far as is known: the parts' counts less the cards that more than one part found. */
    fun matches(): Int {
        val loaded = groups.flatMap { it.cards }
        val repeated = loaded.size - loaded.mapTo(HashSet()) { it.id }.size
        return groups.sumOf { it.total ?: it.cards.size } - repeated
    }
}

data class CatalogSearchState(
    val scope: SearchScope = SearchScope.ALL,
    /** What is in the search box. */
    val text: String = "",
    val results: List<CardDto> = emptyList(),
    /** How many cards match in all; more than [results] holds while the rest has not been read. */
    val matches: Int = 0,
    /** What [results] were found for, so results for older text are never taken for what is in the box now. */
    val resultsFor: CatalogQuery? = null,
    /** The first look for what is typed is on its way. */
    val searching: Boolean = false,
    /** More cards of the same search are on their way. */
    val loadingMore: Boolean = false,
    /** There are more cards to read. */
    val hasMore: Boolean = false,
    /** Why the search could not be made at all. */
    val error: String? = null,
    /** Why more cards could not be had; the ones already read stay. */
    val moreError: String? = null,
    /** A word about the results, when the search could not be as exact as typed. */
    val note: String? = null,
    /** The server's sets, once they have arrived: also how a card's number is shown with the size of its set. */
    val sets: List<SetDto> = emptyList(),
    val setsLoaded: Boolean = false,
    /** The search in progress or done: the session's own bookkeeping. */
    val run: CatalogRun? = null,
) {
    val query: CatalogQuery get() = CatalogQuery(scope, tidySearchText(text))

    /** The results shown are for what is typed now (typing is still being waited out, or has just changed it, when this is false). */
    val upToDate: Boolean get() = resultsFor == query

    /** The search has been made, found nothing and there is nothing more to wait for. */
    val noMatch: Boolean get() = upToDate && !searching && error == null && results.isEmpty() && !hasMore
}

/**
 * Searches the catalogue as the user types: one text box and a scope, results one page at a time. A search is [plan]ned into
 * several parts (a name, an artist, a set, a number, a rarity) which are asked for side by side and read as far as the user
 * scrolls. It holds no Android types, so it is tested on the JVM against a fake server.
 */
class CatalogSearchSession(
    private val backend: CatalogBackend,
    private val scope: CoroutineScope,
    private val describe: (Throwable) -> String = { it.message ?: "Something went wrong." },
    /** How long typing has to pause before the catalogue is searched. */
    private val debounceMs: Long = 350,
    private val pageSize: Int = 30,
    /** How many of the parts of a search have their first page asked for at once; the rest are asked for when they are reached. */
    private val parallel: Int = 4,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(CatalogSearchState())
    val state: StateFlow<CatalogSearchState> = _state.asStateFlow()

    private var pending: Job? = null
    private val pages = CopyOnWriteArrayList<Job>()
    private val runIds = AtomicLong()
    private var setsLoadedAt = 0L

    // --- typing ----------------------------------------------------------------------------------

    fun setText(text: String) {
        var moved = false
        _state.update { current ->
            moved = tidySearchText(text) != tidySearchText(current.text)
            current.copy(text = text)
        }
        if (moved) look(immediately = false)
    }

    fun setScope(scope: SearchScope) {
        var moved = false
        _state.update { current ->
            moved = scope != current.scope
            current.copy(scope = scope)
        }
        if (moved) look(immediately = true)
    }

    /** Searches now, without waiting for typing to pause (the keyboard's search key). */
    fun submit() = look(immediately = true)

    /** Starts a search from nothing: the screen was opened, or an artist was tapped. Searches at once when there is text. */
    fun open(scope: SearchScope = SearchScope.ALL, text: String = "") {
        cancelAll()
        _state.update { it.copy(scope = scope, text = text).blank() }
        look(immediately = true)
    }

    /** Forgets everything, the sets included: another account or server may come next. */
    fun reset() {
        cancelAll()
        setsLoadedAt = 0L
        _state.value = CatalogSearchState()
    }

    private fun CatalogSearchState.blank(): CatalogSearchState = copy(
        results = emptyList(), matches = 0, resultsFor = null, searching = false, loadingMore = false, hasMore = false,
        error = null, moreError = null, note = null, run = null,
    )

    private fun cancelAll() {
        pending?.cancel()
        pages.forEach { it.cancel() }
        pages.clear()
    }

    private fun look(immediately: Boolean) {
        pending?.cancel()
        val current = _state.value
        val query = current.query
        if (!query.searchable) {
            pages.forEach { it.cancel() }
            pages.clear()
            _state.update { it.blank() }
            return
        }
        if (!immediately && query == current.resultsFor && current.error == null) {
            // Typed on and then back: what is shown is already right.
            _state.update { it.copy(searching = false) }
            return
        }
        pending = scope.launch {
            if (!immediately) delay(debounceMs)
            search(query)
        }
    }

    // --- searching -------------------------------------------------------------------------------

    private suspend fun search(query: CatalogQuery) {
        pages.forEach { it.cancel() }
        pages.clear()
        val id = runIds.incrementAndGet()
        _state.update { it.copy(searching = true, error = null, moreError = null) }
        val sets = try {
            setsFor(query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The sets are what a search by set is made of: without them there is nothing to ask for.
            _state.update { current ->
                if (current.query != query) current else current.copy(searching = false, error = describe(e), results = emptyList(), matches = 0, resultsFor = null, hasMore = false, run = null)
            }
            return
        }
        val plan = query.plan(sets)
        var started: CatalogRun? = null
        _state.update { current ->
            if (current.query != query) {
                started = null
                current // the text moved on while waiting; a newer search is on its way
            } else if (plan.groups.isEmpty()) {
                started = null
                current.copy(
                    results = emptyList(), matches = 0, resultsFor = query, searching = false, loadingMore = false,
                    hasMore = false, error = null, moreError = null, note = plan.note, run = null,
                )
            } else {
                val run = CatalogRun(id, query, plan.groups.map { GroupProgress(it) }, plan.note)
                started = run
                current.copy(run = run, note = plan.note, searching = true)
            }
        }
        val run = started ?: return
        run.groups.indices.take(parallel).forEach { requestPage(run.id, it) }
    }

    /** The server's sets, kept for a while. Only a search by set cannot go on without them. */
    private suspend fun setsFor(query: CatalogQuery): List<SetDto>? {
        if (query.scope == SearchScope.ARTIST) return _state.value.sets.takeIf { _state.value.setsLoaded }
        val current = _state.value
        if (current.setsLoaded && now() - setsLoadedAt < SETS_MAX_AGE_MS) return current.sets
        return try {
            val sets = backend.sets()
            setsLoadedAt = now()
            _state.update { it.copy(sets = sets, setsLoaded = true) }
            sets
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (query.scope == SearchScope.SET) throw e
            current.sets.takeIf { current.setsLoaded }
        }
    }

    /** Asks for the next page of one part, unless it is on its way, read to the end or has failed (that is [retry]'s). */
    private fun requestPage(runId: Long, index: Int) {
        var request: Pair<SearchGroup, Int>? = null
        _state.update { current ->
            val run = current.run
            val progress = run?.groups?.getOrNull(index)
            if (run == null || run.id != runId || progress == null || progress.done || progress.loading || progress.failed != null) {
                request = null
                current
            } else {
                request = progress.group to progress.nextPage
                current.withRun(run.copy(groups = run.groups.replaced(index) { it.copy(loading = true) }))
            }
        }
        val (group, page) = request ?: return
        pages += scope.launch {
            try {
                val answer = backend.searchCatalog(group.params, page, pageSize)
                _state.update { current -> current.withPage(runId, index, answer) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val problem = describe(e)
                _state.update { current -> current.withProgress(runId, index) { it.copy(loading = false, failed = problem) } }
            }
            continueIfUnseen(runId)
        }
    }

    /**
     * Reads on by itself while the part to read next has not been looked at yet: the parts before it found nothing, or too little
     * to fill a page, and it was not one of the first few asked for. (A part that has been looked at has a full page to show.)
     */
    private fun continueIfUnseen(runId: Long) {
        val run = _state.value.run ?: return
        if (run.id != runId) return
        val frontier = run.frontier
        if (frontier != -1 && !run.ready) requestPage(runId, frontier)
    }

    /** Reads the next page of the cards, when the user has come near the end of the list. */
    fun loadMore() {
        val run = _state.value.run ?: return
        val frontier = run.frontier
        if (frontier != -1) requestPage(run.id, frontier)
    }

    /** Tries again what failed: the search, or the page that could not be had. */
    fun retry() {
        val current = _state.value
        val run = current.run
        if (run == null) {
            look(immediately = true)
            return
        }
        val frontier = run.frontier
        if (frontier == -1) return
        _state.update { it.withProgress(run.id, frontier) { progress -> progress.copy(failed = null) } }
        requestPage(run.id, frontier)
    }

    // --- bookkeeping -----------------------------------------------------------------------------

    private fun List<GroupProgress>.replaced(index: Int, change: (GroupProgress) -> GroupProgress): List<GroupProgress> =
        mapIndexed { i, progress -> if (i == index) change(progress) else progress }

    private fun CatalogSearchState.withProgress(runId: Long, index: Int, change: (GroupProgress) -> GroupProgress): CatalogSearchState {
        val run = run ?: return this
        if (run.id != runId) return this
        return withRun(run.copy(groups = run.groups.replaced(index, change)))
    }

    private fun CatalogSearchState.withPage(runId: Long, index: Int, answer: CardSearchDto): CatalogSearchState =
        withProgress(runId, index) { progress ->
            val cards = (progress.cards + answer.data).distinctBy { it.id }
            val total = maxOf(answer.totalCount, cards.size)
            progress.copy(
                cards = cards,
                nextPage = progress.nextPage + 1,
                total = total,
                done = answer.data.isEmpty() || cards.size >= total || answer.data.size < pageSize,
                loading = false,
                failed = null,
            )
        }

    /**
     * The state with [run] in it and everything a screen reads worked out from it. Until the first look at what to show has
     * arrived the cards of the search before are kept, so typing on does not blank the list at every pause.
     */
    private fun CatalogSearchState.withRun(run: CatalogRun): CatalogSearchState {
        val frontier = run.frontier.let { if (it == -1) null else run.groups[it] }
        val failed = frontier?.failed
        val shown = run.shown()
        // There is something to show when the parts before the one being read have found cards (they are final), or the part
        // being read has been looked at. Until then the list before stays, and a failure is the whole search's.
        return if (shown.isNotEmpty() || run.ready) {
            copy(
                run = run,
                results = shown,
                matches = run.matches(),
                resultsFor = run.query,
                searching = false,
                loadingMore = frontier?.loading == true,
                hasMore = frontier != null,
                error = null,
                moreError = failed,
                note = run.note,
            )
        } else {
            copy(
                run = run,
                searching = failed == null,
                loadingMore = false,
                error = failed,
                moreError = null,
            )
        }
    }

    private companion object {
        /** The list of sets changes when a set comes out, so it is asked for again now and then rather than kept for good. */
        const val SETS_MAX_AGE_MS = 30 * 60 * 1000L
    }
}

// ---------------------------------------------------------------------------------------------
// Showing a card found
// ---------------------------------------------------------------------------------------------

/** The artist's name, or null when the catalogue has none for this card (it is then left out rather than shown blank). */
fun CardDto.artistName(): String? = artist?.trim()?.takeIf { it.isNotEmpty() }

/**
 * The number as printed: "130/128" when the card's set is known to print 128 cards and the number is plain digits, otherwise the
 * number as the server has it ("TG05" is printed with the size of a subset, which the server does not keep). Null for no number.
 */
fun CardDto.printedNumber(sets: List<SetDto>): String? {
    val own = number?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (!own.all { it.isDigit() }) return own
    val set = sets.firstOrNull { it.id == setRef?.id } ?: sets.firstOrNull { it.tcgSetId != null && it.tcgSetId == setId }
    val total = set?.printedTotal?.takeIf { it > 0 } ?: return own
    return "$own/$total"
}

/** "Obsidian Flames · #125/197 · Double Rare": where the card is from, its number and its rarity, whichever it has. */
fun CardDto.searchSubtitle(sets: List<SetDto>): String =
    listOfNotNull(setName(), printedNumber(sets)?.let { "#$it" }, rarity?.trim()?.takeIf { it.isNotEmpty() }).joinToString(" · ")
