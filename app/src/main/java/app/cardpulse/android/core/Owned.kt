package app.cardpulse.android.core

/** Copies of one card that share a condition, variant and language. */
data class OwnedLine(
    val quantity: Int,
    val condition: String,
    val variant: String,
    val lang: String,
) {
    /** "2× NM · Normal · EN" */
    fun describe(): String = "$quantity× $condition · $variant · ${CardLanguages.label(lang)}"
}

/** Whether a scanned card is already in the collection, and in what form. */
sealed interface Ownership {
    /** The collection hasn't been loaded yet, so nothing honest can be said about it. */
    data object Unknown : Ownership

    data object New : Ownership

    /** [total] counts every language; [lines] break it down, most copies first. */
    data class Owned(val total: Int, val lines: List<OwnedLine>) : Ownership
}

/** How many copies are owned: 0 when the card is new or not known yet. */
val Ownership.copies: Int get() = (this as? Ownership.Owned)?.total ?: 0

fun Ownership.headline(): String = when (this) {
    Ownership.Unknown -> "Checking your collection…"
    Ownership.New -> "New to your collection"
    is Ownership.Owned -> if (total == 1) "You already own 1 copy" else "You already own $total copies"
}

/** The id without a language suffix: how the same card is recognised across languages. */
fun ScanMatchDto.plainCardId(): String = tcgCardId ?: CardLanguages.splitCardId(id).first

/** The language this candidate is in, as the server writes it. */
fun ScanMatchDto.scannedLanguage(): String =
    CardLanguages.normalize(lang) ?: CardLanguages.splitCardId(id).second ?: "en"

private fun CollectionItemDto.plainCardId(): String? =
    card?.tcgCardId ?: cardId?.let { CardLanguages.splitCardId(it).first }

/**
 * The collection indexed by card, so every scan result can be checked without walking the whole list.
 * Rows of the same card that differ only by price or tags are one kind of copy here.
 */
class CollectionIndex(items: List<CollectionItemDto>) {
    private val byCard: Map<String, Ownership.Owned>

    init {
        val rows = HashMap<String, MutableMap<Triple<String, String, String>, Int>>()
        for (item in items) {
            val id = item.plainCardId() ?: continue
            if (item.quantity <= 0) continue
            val kind = Triple(item.condition, item.variant, CardLanguages.normalize(item.lang) ?: item.lang)
            val kinds = rows.getOrPut(id) { LinkedHashMap() }
            kinds[kind] = (kinds[kind] ?: 0) + item.quantity
        }
        byCard = rows.mapValues { (_, kinds) ->
            val lines = kinds.map { (kind, quantity) -> OwnedLine(quantity, kind.first, kind.second, kind.third) }
                .sortedWith(
                    compareByDescending<OwnedLine> { it.quantity }
                        .thenBy { it.lang }
                        .thenBy { it.condition }
                        .thenBy { it.variant },
                )
            Ownership.Owned(total = lines.sumOf { it.quantity }, lines = lines)
        }
    }

    /** [Ownership.New] when no copy is owned in any language. */
    fun ownershipOf(plainCardId: String): Ownership = byCard[plainCardId] ?: Ownership.New
}

/** What to say about a candidate: unknown until the collection has loaded, then new or owned. */
fun CollectionIndex?.ownershipOf(match: ScanMatchDto): Ownership =
    this?.ownershipOf(match.plainCardId()) ?: Ownership.Unknown
