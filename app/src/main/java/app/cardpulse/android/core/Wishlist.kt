package app.cardpulse.android.core

/** How much the user wants a card on their wishlist. Stored by name, so the order here can change freely. */
enum class WishlistPriority(val label: String) {
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
}

/** The order the wishlist is shown in. [key] is what the database query sorts by (see WishlistDao). */
enum class WishlistSort(val label: String, val key: String) {
    RECENT("Recently added", "RECENT"),
    NAME("Name", "NAME"),
    SET("Set", "SET"),
    PRICE("Price", "PRICE"),
}

/** Which wishlist entries are shown: all of them, only the ones not owned yet, or only the ones owned by now. */
enum class WishlistFilter(val label: String) {
    ALL("All"),
    MISSING("Missing only"),
    OWNED("Owned"),
    ;

    /** The value the database query filters the "owned" column on; null for no filtering. */
    val ownedOrNull: Boolean?
        get() = when (this) {
            ALL -> null
            MISSING -> false
            OWNED -> true
        }
}

/**
 * The cards of the collection that count as owned, by card id: any row of the card with at least one copy. The wishlist
 * marks its entries owned from this, and never removes them: a card stays on the list until the user takes it off.
 */
fun List<CollectionItemDto>.ownedCardIds(): Set<String> =
    asSequence().filter { it.quantity > 0 }.mapNotNull { it.wishlistCardId() }.filter { it.isNotBlank() }.toSet()

/** The card a collection row is of, as the wishlist knows cards: by the server's card id. */
fun CollectionItemDto.wishlistCardId(): String? = cardId ?: card?.id

/**
 * The price a wishlist entry shows for a card, in euros, for the user's price field: one Normal copy, priced the way the
 * collection prices it ([priceFor]). Null when the server has no price, so "no price" never reads as "free".
 */
fun CardDto.wishlistPrice(priceField: String): Double? = priceFor("Normal", priceField).takeIf { it > 0.0 }

/** Whether the card's current price has come down to the user's target (both in euros). False without either. */
fun isAtOrBelowTarget(price: Double?, target: Double?): Boolean =
    price != null && target != null && price > 0.0 && price <= target
