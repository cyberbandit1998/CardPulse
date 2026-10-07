package app.cardpulse.android.core

/** How far the collection has got towards completing one set. A set in another language is a set of its own. */
data class SetProgress(
    /** The server's id for the set, such as "sv3_en". The set's logo is fetched with it. */
    val id: String,
    val name: String,
    /** Different cards owned from this set. */
    val owned: Int,
    /** Cards in the set; 0 when the server has not said yet. */
    val total: Int,
    /** The code printed on the set's cards, such as "OBF". */
    val code: String? = null,
    val series: String? = null,
    /** When the set came out, as text that sorts in date order ("2023-08-11"). */
    val releaseDate: String? = null,
    /** The language of this edition of the set, as the server writes it ("en", "de"). */
    val lang: String = "en",
) {
    /** 0 to 1: the part of the set owned. */
    val fraction: Float get() = if (total <= 0) 0f else (owned.toFloat() / total).coerceIn(0f, 1f)

    val isComplete: Boolean get() = total > 0 && owned >= total

    /** The size of the set as text: "132", or a dash while it is not known. */
    val totalText: String get() = if (total > 0) total.toString() else "—"

    /** "18 / 132", "0 / 230" for a set with none owned yet. */
    val countText: String get() = "$owned / $totalText"
}

/** The cards in a set: the full count if the server gave one, else the number printed on the cards. */
private val SetDto.size: Int get() = total.takeIf { it > 0 } ?: printedTotal

private class OwnedSet(val set: SetDto, val cards: MutableSet<String> = mutableSetOf())

/** The sets the collection holds a card from, by the set's id, with the different cards held from each. */
private fun List<CollectionItemDto>.ownedSets(): Map<String, OwnedSet> {
    val bySet = LinkedHashMap<String, OwnedSet>()
    for (item in this) {
        if (item.quantity <= 0) continue
        val card = item.card ?: continue
        val set = card.setRef?.takeIf { it.id.isNotBlank() } ?: continue
        bySet.getOrPut(set.id) { OwnedSet(set) }.cards.add(item.cardId ?: card.id)
    }
    return bySet
}

private fun SetDto.toProgress(owned: Int): SetProgress = SetProgress(
    id = id,
    name = name.ifBlank { abbreviation ?: id },
    owned = owned,
    // A set that has since grown past the size the server last heard of is shown full, not "12 of 10". A set whose size is
    // not known stays unknown, rather than looking complete.
    total = if (size > 0) maxOf(size, owned) else 0,
    code = abbreviation,
    series = series,
    releaseDate = releaseDate,
    lang = lang,
)

/**
 * One entry per set the collection holds a card from, the set closest to finished first (the order of [SetOrder.PROGRESS]).
 *
 * It counts different cards, as the PokéCollector web app's Sets page does: a card owned twice, or in two conditions, is
 * one card. A set the server gave no size for is left out, as there is nothing to measure it against, and so is a custom
 * card, which belongs to no set.
 */
fun List<CollectionItemDto>.setProgress(): List<SetProgress> =
    ownedSets().values
        .filter { it.set.size > 0 }
        .map { it.set.toProgress(it.cards.size) }
        .ordered(SetOrder.PROGRESS)

/**
 * Every set to browse: all of [catalogue] (the server's list of sets), each with how many different cards of it the
 * collection holds, which is 0 for a set with none of its cards. A set the collection holds cards from that the list does
 * not have, such as one in another language, is added, so nothing owned is ever missing. The order is up to the caller
 * (see [ordered]).
 *
 * Ownership comes from [collection] once it has loaded ([collectionLoaded]), so it follows every card added or removed
 * at once and always agrees with Home; until then it is the count the server put on the list.
 */
fun browseSets(catalogue: List<SetDto>, collection: List<CollectionItemDto>, collectionLoaded: Boolean = true): List<SetProgress> {
    val held = if (collectionLoaded) collection.ownedSets() else emptyMap()
    val listed = catalogue
        .filter { it.id.isNotBlank() }
        .distinctBy { it.id }
        .map { set -> set.toProgress(if (collectionLoaded) held[set.id]?.cards?.size ?: 0 else set.ownedCount) }
    val listedIds = listed.mapTo(HashSet()) { it.id }
    val others = held.values
        .filter { it.set.id !in listedIds && it.set.size > 0 }
        .map { it.set.toProgress(it.cards.size) }
    return listed + others
}

/** The set with this id: from the server's list, or else from the cards of the collection; null if neither has it. */
fun findSet(setId: String, catalogue: List<SetDto>, collection: List<CollectionItemDto>): SetDto? =
    catalogue.firstOrNull { it.id == setId }
        ?: collection.firstNotNullOfOrNull { item -> item.card?.setRef?.takeIf { it.id == setId } }

/** Which sets the Sets screen shows. */
enum class SetFilter(val label: String) {
    /** Every set the server lists. */
    ALL("All"),

    /** Sets with at least one card owned. */
    OWNED("Owned"),

    /** Sets with at least one card owned, but not every card. */
    INCOMPLETE("Incomplete"),

    /** Sets with every card owned. */
    COMPLETE("Complete"),
}

/** Whether this set belongs under [filter]. A set whose size is not known yet can never be complete. */
fun SetProgress.matches(filter: SetFilter): Boolean = when (filter) {
    SetFilter.ALL -> true
    SetFilter.OWNED -> owned > 0
    SetFilter.INCOMPLETE -> owned > 0 && !isComplete
    SetFilter.COMPLETE -> isComplete
}

/** The sets under [filter], in the order they were in. */
fun List<SetProgress>.filtered(filter: SetFilter): List<SetProgress> =
    if (filter == SetFilter.ALL) this else filter { it.matches(filter) }

/** How the Sets screen orders the sets. */
enum class SetOrder(val label: String) {
    /** Closest to finished first, then the most cards owned, then the newest set. */
    PROGRESS("Progress"),
    NAME("Name"),
    /** The most cards owned first. */
    CARDS("Cards"),
}

/** The sets in [order]; ties are settled by name, so the order never jumps about between two looks. */
fun List<SetProgress>.ordered(order: SetOrder): List<SetProgress> = when (order) {
    SetOrder.PROGRESS -> sortedWith(
        compareByDescending<SetProgress> { it.fraction }
            .thenByDescending { it.owned }
            .thenByDescending { it.releaseDate.orEmpty() }
            .thenBy { it.name.lowercase() },
    )
    SetOrder.NAME -> sortedBy { it.name.lowercase() }
    SetOrder.CARDS -> sortedWith(compareByDescending<SetProgress> { it.owned }.thenByDescending { it.fraction }.thenBy { it.name.lowercase() })
}

/**
 * The sets whose name, series, printed code ("OBF") or the server's code for it ("sv3") holds every word of [query],
 * whatever the case; all of them when the query is blank. "obsidian fl" finds Obsidian Flames, and so do "sv3" and "obf";
 * "scarlet" finds the whole Scarlet & Violet series.
 */
fun List<SetProgress>.matching(query: String): List<SetProgress> {
    val words = query.trim().split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return this
    return filter { set ->
        // The language ending of the id ("_en") is left out: it is on every set and would match "en" everywhere.
        val serverCode = CardLanguages.splitCardId(set.id).first
        words.all { word ->
            set.name.contains(word, ignoreCase = true) ||
                serverCode.contains(word, ignoreCase = true) ||
                set.code?.contains(word, ignoreCase = true) == true ||
                set.series?.contains(word, ignoreCase = true) == true
        }
    }
}

/**
 * Up to two characters that stand for a set's name where its logo is missing: the first letters of its first two words
 * ("Journey Together" is "JT", "30th Celebration" is "3C"), or the first two of a single word ("Base" is "BA").
 */
fun String.monogram(): String {
    val words = split(' ', '-', '–', '&', ':', '/').filter { it.isNotEmpty() && it.first().isLetterOrDigit() }
    val letters = when {
        words.size >= 2 -> "${words[0].first()}${words[1].first()}"
        words.size == 1 -> words[0].filter { it.isLetterOrDigit() }.take(2)
        else -> ""
    }
    return letters.uppercase().ifEmpty { "?" }
}
