package app.cardpulse.android.core

/** Whether the server has a picture of the card to show (otherwise its number is drawn instead). */
val ChecklistCardDto.hasPicture: Boolean get() = !imagesSmall.isNullOrBlank() || !imagesLarge.isNullOrBlank()

/** A card of a set's checklist with how many copies the collection holds of it. */
data class ChecklistEntry(val card: ChecklistCardDto, val copies: Int) {
    val owned: Boolean get() = copies > 0

    /** "#125", or nothing for a card with no number. */
    val numberText: String get() = card.number?.trim()?.takeIf { it.isNotEmpty() }?.let { "#$it" }.orEmpty()

    /** "Owned", "Owned ×2" or "Missing": said in words as well as shown, so colour is never the only difference. */
    val statusText: String
        get() = when {
            copies <= 0 -> "Missing"
            copies == 1 -> "Owned"
            else -> "Owned ×$copies"
        }

    /** What a screen reader says for the whole tile: "Charizard ex, number 125, owned 2" or "..., missing". */
    val description: String
        get() = buildString {
            append(card.name.ifBlank { "Unnamed card" })
            if (numberText.isNotEmpty()) append(", number ").append(numberText.removePrefix("#"))
            append(", ")
            append(if (copies <= 0) "missing" else if (copies == 1) "owned" else "owned $copies")
        }
}

/** Which cards of a checklist are shown. */
enum class ChecklistFilter(val label: String) {
    ALL("All"),
    OWNED("Owned"),
    MISSING("Missing"),
}

/** How many of a checklist's cards are owned. */
data class ChecklistTally(val owned: Int, val total: Int) {
    val missing: Int get() = (total - owned).coerceAtLeast(0)

    /** 0 to 1: the part of the set owned. */
    val fraction: Float get() = if (total <= 0) 0f else (owned.toFloat() / total).coerceIn(0f, 1f)

    /** "18 / 132". */
    val countText: String get() = "$owned / $total"

    /** Whole percent, rounded down so that a set is never "100%" until it really is complete. */
    val percent: Int get() = if (total <= 0) 0 else (owned * 100) / total

    val isComplete: Boolean get() = total > 0 && owned >= total
}

/**
 * The checklist's cards, in the server's card-number order, each with how many copies of it the collection holds.
 *
 * Ownership comes from [collection] once it has loaded ([collectionLoaded]), so a card that is added or taken out shows at
 * once and the numbers always agree with the Sets list and Home; until then it is what the server said when it sent the
 * checklist. Copies are counted by the card's own id, so a card owned in another language is a different card.
 */
fun SetChecklistDto.entries(collection: List<CollectionItemDto>, collectionLoaded: Boolean): List<ChecklistEntry> {
    if (!collectionLoaded) return cards.map { ChecklistEntry(it, it.ownedQuantity.coerceAtLeast(0)) }
    val copies = HashMap<String, Int>()
    for (item in collection) {
        val id = item.cardId ?: continue
        if (item.quantity > 0) copies[id] = (copies[id] ?: 0) + item.quantity
    }
    return cards.map { ChecklistEntry(it, copies[it.id] ?: 0) }
}

fun List<ChecklistEntry>.tally(): ChecklistTally = ChecklistTally(owned = count { it.owned }, total = size)

fun List<ChecklistEntry>.filtered(filter: ChecklistFilter): List<ChecklistEntry> = when (filter) {
    ChecklistFilter.ALL -> this
    ChecklistFilter.OWNED -> filter { it.owned }
    ChecklistFilter.MISSING -> filter { !it.owned }
}

/**
 * The collection row to show when an owned card of a checklist is tapped: the one the server named first, else any row of
 * that card. Null when the collection has no row for it (it was taken out, or the collection has not loaded).
 */
fun ChecklistEntry.collectionRow(collection: List<CollectionItemDto>): CollectionItemDto? {
    val named = card.ownedItems.map { it.id }
    return named.firstNotNullOfOrNull { id -> collection.firstOrNull { it.id == id && it.quantity > 0 } }
        ?: collection.firstOrNull { it.cardId == card.id && it.quantity > 0 }
}
