package app.cardpulse.android.core

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The wishlist is PokéCollector's own (`/api/wishlist/`), so it is shared with the website and with every phone. A row comes with
// its card, prices included, and is priced by the same choice (`DisplayPrefs.priceField`) as the rest of the app. Which cards
// are owned comes from the collection, as in a set's checklist. Owning a copy never takes a card off the list.

// ---------------------------------------------------------------------------------------------
// One row of the wishlist
// ---------------------------------------------------------------------------------------------

/** How much the user wants a card. PokéCollector's wishlist has no such field, so it is kept on this phone only. */
enum class WishlistPriority(val key: String, val label: String) {
    LOW("low", "Low"),
    MEDIUM("medium", "Medium"),
    HIGH("high", "High");

    companion object {
        /** The priority saved under [key]; null for none, or for a value a newer version of the app wrote. */
        fun fromKey(key: String?): WishlistPriority? = entries.firstOrNull { it.key == key }
    }
}

/** A wishlist row with what a screen needs to draw it. */
data class WishlistEntry(
    val item: WishlistItemDto,
    /** Copies of this exact card the collection holds; null until the collection has loaded, when nothing can be said. */
    val copies: Int?,
    /** What one copy is worth now, in euros, by the user's chosen price; 0 when the card has no price. */
    val priceEur: Double,
    val priority: WishlistPriority? = null,
) {
    val card: CardDto? get() = item.card
    val cardId: String get() = item.cardId

    val name: String get() = card?.name?.takeIf { it.isNotBlank() } ?: "Unnamed card"

    /** The set's name, or "" when the card has none. */
    val setName: String get() = card?.setName().orEmpty()

    /** "#125", or nothing for a card with no number. */
    val numberText: String get() = card?.number?.trim()?.takeIf { it.isNotEmpty() }?.let { "#$it" }.orEmpty()

    val rarity: String? get() = card?.rarity?.trim()?.takeIf { it.isNotEmpty() }

    /** "Obsidian Flames · #125 · Double Rare": the set, the number and the rarity, whichever the card has. */
    val subtitle: String get() = card?.subtitle()?.takeIf { it.isNotEmpty() } ?: item.cardId

    /** Whether any copy is owned. A card that is not known to be owned counts as not owned only once the collection has loaded. */
    val owned: Boolean get() = (copies ?: 0) > 0
    val missing: Boolean get() = copies == 0

    /** "Missing", "Owned" or "Owned ×2": said in words so that colour is never the only sign. Nothing until the collection loads. */
    val statusText: String
        get() = when {
            copies == null -> ""
            copies <= 0 -> "Missing"
            copies == 1 -> "Owned"
            else -> "Owned ×$copies"
        }

    val hasPrice: Boolean get() = priceEur > 0.0

    /** The price the user wants to pay or less, in euros; null when none is set. It is the server's "alert below" price. */
    val targetEur: Double? get() = item.priceAlertBelow?.takeIf { it > 0.0 }

    /** The card costs no more than the target now: the moment to buy. */
    val atTarget: Boolean get() = hasPrice && targetEur?.let { priceEur <= it } == true

    /** What a screen reader says for the whole row. */
    fun describe(money: MoneyFormatter): String = buildString {
        append(name)
        if (setName.isNotEmpty()) append(", ").append(setName)
        if (numberText.isNotEmpty()) append(", number ").append(numberText.removePrefix("#"))
        rarity?.let { append(", ").append(it) }
        append(if (hasPrice) ", price ${money.format(priceEur)}" else ", no price")
        when {
            copies == null -> Unit
            copies <= 0 -> append(", missing")
            copies == 1 -> append(", owned")
            else -> append(", owned $copies")
        }
        if (item.quantity > 1) append(", wants ${item.quantity}")
        targetEur?.let { target ->
            append(", target price ").append(money.format(target))
            if (atTarget) append(", reached")
        }
        priority?.let { append(", ${it.label.lowercase()} priority") }
    }
}

/**
 * The wishlist as rows. [collection] says which cards are owned once it has loaded ([collectionLoaded]); until then ownership
 * is unknown, not "missing". The price is the card's price for [priceField] ([priceFor], the variant being the plain one: a
 * wishlist row names no variant), in euros. [priorities] are the levels kept on this phone, by card id.
 */
fun List<WishlistItemDto>.entries(
    collection: List<CollectionItemDto>,
    collectionLoaded: Boolean,
    priceField: String,
    priorities: Map<String, WishlistPriority> = emptyMap(),
): List<WishlistEntry> {
    val copies = if (collectionLoaded) collection.copiesByCardId() else null
    return map { item ->
        WishlistEntry(
            item = item,
            copies = copies?.let { it[item.cardId] ?: 0 },
            priceEur = item.card?.priceFor("Normal", priceField) ?: 0.0,
            priority = priorities[item.cardId],
        )
    }
}

/** The ids of the cards on the list. */
fun List<WishlistItemDto>.cardIds(): Set<String> = mapTo(HashSet()) { it.cardId }

// ---------------------------------------------------------------------------------------------
// Keeping the list in step with what the server said
// ---------------------------------------------------------------------------------------------

/** The list with [item] at the top (the newest); a row of the same card or id that was there is replaced. */
fun List<WishlistItemDto>.withAdded(item: WishlistItemDto): List<WishlistItemDto> =
    listOf(item) + filterNot { it.id == item.id || it.cardId == item.cardId }

/** The list with [item] in the place of the row of the same id (as the server now has it); the list as it was if there is none. */
fun List<WishlistItemDto>.withUpdated(item: WishlistItemDto): List<WishlistItemDto> =
    map { if (it.id == item.id) item else it }

fun List<WishlistItemDto>.withoutCard(cardId: String): List<WishlistItemDto> = filterNot { it.cardId == cardId }

// ---------------------------------------------------------------------------------------------
// Sorting and filtering
// ---------------------------------------------------------------------------------------------

enum class WishlistOrder(val label: String) {
    RECENT("Recent"),
    NAME("Name"),
    SET("Set"),
    PRICE("Price"),
}

enum class WishlistFilter(val label: String) {
    ALL("All"),
    MISSING("Missing"),
    OWNED("Owned"),
}

/** How many entries each filter shows. Missing and owned are 0 until the collection has loaded. */
data class WishlistCounts(val all: Int, val missing: Int, val owned: Int) {
    fun of(filter: WishlistFilter): Int = when (filter) {
        WishlistFilter.ALL -> all
        WishlistFilter.MISSING -> missing
        WishlistFilter.OWNED -> owned
    }
}

fun List<WishlistEntry>.counts(): WishlistCounts = WishlistCounts(all = size, missing = count { it.missing }, owned = count { it.owned })

fun List<WishlistEntry>.filtered(filter: WishlistFilter): List<WishlistEntry> = when (filter) {
    WishlistFilter.ALL -> this
    WishlistFilter.MISSING -> filter { it.missing }
    WishlistFilter.OWNED -> filter { it.owned }
}

/**
 * Newest first for [WishlistOrder.RECENT]; A to Z by name or by set (a set's cards in card-number order); and the most
 * expensive first by price, with the cards that have no price at the end. Equal entries keep a steady order.
 */
fun List<WishlistEntry>.ordered(order: WishlistOrder): List<WishlistEntry> {
    val byName = compareBy<WishlistEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
    val bySet = compareBy<WishlistEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.setName }
    // Zero-padded, as in the Collection tab, so that #9 comes before #10.
    val byNumber = compareBy<WishlistEntry> { it.card?.number?.trim().orEmpty().padStart(4, '0') }
    return when (order) {
        WishlistOrder.RECENT -> sortedWith(
            compareByDescending<WishlistEntry> { parseServerInstant(it.item.createdAt) ?: Instant.EPOCH }.thenByDescending { it.item.id },
        )
        WishlistOrder.NAME -> sortedWith(byName.then(bySet).then(byNumber))
        WishlistOrder.SET -> sortedWith(bySet.then(byNumber).then(byName))
        WishlistOrder.PRICE -> sortedWith(compareByDescending<WishlistEntry> { it.priceEur }.then(byName).then(bySet).then(byNumber))
    }
}

// ---------------------------------------------------------------------------------------------
// Changing a row on the server
// ---------------------------------------------------------------------------------------------

/**
 * The body of `PUT /api/wishlist/{id}` that sets the target price to [targetEur] euros, or with null takes it away. The server
 * changes only the fields the body names, and null has to be named to count, which [AppJson] never does for a class (it sends
 * no nulls): this is built by hand so that a cleared target really is cleared.
 */
fun wishlistTargetBody(targetEur: Double?): JsonObject = buildJsonObject { put("price_alert_below", targetEur) }

/** The body of `PUT /api/wishlist/{id}` that changes only how many copies are wanted. */
fun wishlistQuantityBody(quantity: Int): JsonObject = buildJsonObject { put("quantity", quantity) }

// ---------------------------------------------------------------------------------------------
// Priorities, kept on the phone
// ---------------------------------------------------------------------------------------------

/** What is saved on the phone: whose priorities these are, and the level of each card by its id. */
@Serializable
internal data class StoredPriorities(
    val account: String = "",
    val levels: Map<String, String> = emptyMap(),
)

/** Reads and writes the priorities as text for the phone's settings. Pure, so it is tested without a phone. */
object WishlistPriorities {
    /** Whose priorities are kept: one account on one server. Another account's levels would mean nothing here. */
    fun accountKey(serverUrl: String, username: String?): String = "${serverUrl.trim()}#${username.orEmpty()}"

    fun encode(account: String, levels: Map<String, WishlistPriority>): String =
        AppJson.encodeToString(StoredPriorities.serializer(), StoredPriorities(account, levels.mapValues { it.value.key }))

    /** The levels saved for [account]; nothing when there are none, the text is unreadable, or it was saved for another account. */
    fun decode(account: String, text: String?): Map<String, WishlistPriority> {
        if (text.isNullOrBlank()) return emptyMap()
        val stored = attempt { AppJson.decodeFromString(StoredPriorities.serializer(), text) }.getOrNull() ?: return emptyMap()
        if (stored.account != account) return emptyMap()
        return stored.levels.mapNotNull { (card, key) -> WishlistPriority.fromKey(key)?.let { card to it } }.toMap()
    }

    /** Only the levels of cards that are still on the list, so a removed card does not keep one for when it comes back. */
    fun keepOnly(levels: Map<String, WishlistPriority>, listed: Set<String>): Map<String, WishlistPriority> =
        levels.filterKeys { it in listed }
}
