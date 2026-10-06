package app.cardpulse.android.core

/** How far the collection has got towards completing one set. A set in another language is a set of its own. */
data class SetProgress(
    /** The server's id for the set, such as "sv3_en". The set's logo is fetched with it. */
    val id: String,
    val name: String,
    /** Different cards owned from this set. */
    val owned: Int,
    /** Cards in the set. */
    val total: Int,
) {
    /** 0 to 1: the part of the set owned. */
    val fraction: Float get() = if (total <= 0) 0f else (owned.toFloat() / total).coerceIn(0f, 1f)

    val isComplete: Boolean get() = total > 0 && owned >= total
}

/**
 * One entry per set the collection holds a card from, the set closest to finished first.
 *
 * It counts different cards, as the PokéCollector web app's Sets page does: a card owned twice, or in two conditions, is
 * one card. A set the server gave no size for is left out, as there is nothing to measure it against, and so is a custom
 * card, which belongs to no set.
 */
fun List<CollectionItemDto>.setProgress(): List<SetProgress> {
    val bySet = LinkedHashMap<String, Pair<SetDto, MutableSet<String>>>()
    for (item in this) {
        if (item.quantity <= 0) continue
        val card = item.card ?: continue
        val set = card.setRef?.takeIf { it.id.isNotBlank() } ?: continue
        bySet.getOrPut(set.id) { set to mutableSetOf() }.second.add(item.cardId ?: card.id)
    }
    return bySet.values
        .mapNotNull { (set, cards) ->
            val size = set.total.takeIf { it > 0 } ?: set.printedTotal
            if (size <= 0) {
                null
            } else {
                // A set that has since grown past the size the server last heard of is shown full, not "12 of 10".
                SetProgress(set.id, set.name.ifBlank { set.abbreviation ?: set.id }, owned = cards.size, total = maxOf(size, cards.size))
            }
        }
        .sortedWith(compareByDescending<SetProgress> { it.fraction }.thenByDescending { it.owned }.thenBy { it.name.lowercase() })
}

/** How the Sets screen orders the sets. */
enum class SetOrder(val label: String) {
    /** Closest to finished first. */
    PROGRESS("Progress"),
    NAME("Name"),
    /** The most cards owned first. */
    CARDS("Cards"),
}

/** The sets in [order]; ties are settled by name, so the order never jumps about between two looks. */
fun List<SetProgress>.ordered(order: SetOrder): List<SetProgress> = when (order) {
    SetOrder.PROGRESS -> sortedWith(compareByDescending<SetProgress> { it.fraction }.thenByDescending { it.owned }.thenBy { it.name.lowercase() })
    SetOrder.NAME -> sortedBy { it.name.lowercase() }
    SetOrder.CARDS -> sortedWith(compareByDescending<SetProgress> { it.owned }.thenByDescending { it.fraction }.thenBy { it.name.lowercase() })
}

/**
 * The sets whose name or code (such as "sv3") holds every word of [query], whatever the case; all of them when the query
 * is blank. "obsidian fl" finds Obsidian Flames, and so does "sv3".
 */
fun List<SetProgress>.matching(query: String): List<SetProgress> {
    val words = query.trim().split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return this
    return filter { set -> words.all { word -> set.name.contains(word, ignoreCase = true) || set.id.contains(word, ignoreCase = true) } }
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
