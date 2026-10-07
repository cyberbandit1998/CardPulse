package app.cardpulse.android.data.wishlist

import app.cardpulse.android.core.CardDto
import app.cardpulse.android.core.ChecklistCardDto
import app.cardpulse.android.core.CollectionItemDto
import app.cardpulse.android.core.SetDto
import app.cardpulse.android.core.WishlistFilter
import app.cardpulse.android.core.WishlistPriority
import app.cardpulse.android.core.WishlistSort
import app.cardpulse.android.core.ownedCardIds
import app.cardpulse.android.core.setName
import app.cardpulse.android.core.wishlistPrice
import app.cardpulse.android.data.Repository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The wishlist: what the user wants, kept on the phone. Cards come in from wherever the app shows them (a search result,
 * a set's checklist, a collection entry) and are turned into entries here, with prices chosen the way the rest of the app
 * chooses them ([wishlistPrice], which is the collection's own priceFor).
 */
class WishlistRepository(
    private val dao: WishlistDao,
    private val server: Repository,
) {
    fun observe(sort: WishlistSort, filter: WishlistFilter): Flow<List<WishlistItem>> = dao.observe(sort.key, filter.ownedOrNull)

    val cardIds: Flow<Set<String>> = dao.observeCardIds().map { it.toSet() }

    /** Adds a card the server sent in full (a search result, a collection entry's card). */
    suspend fun add(card: CardDto, priceField: String, owned: Boolean = false) {
        dao.insert(card.toWishlistItem(priceField, owned))
    }

    suspend fun add(item: CollectionItemDto, priceField: String) {
        val card = item.card
        if (card != null) {
            add(card, priceField, owned = item.quantity > 0)
        } else {
            val cardId = item.cardId ?: return
            dao.insert(WishlistItem(cardId = cardId, cardName = cardId, isOwned = item.quantity > 0))
        }
    }

    /**
     * Adds a card of a set's checklist. A checklist doesn't carry prices, so the card is then looked up by name and number
     * (the search the manual add uses) to fill one in; without a match the entry simply shows no price.
     */
    suspend fun add(card: ChecklistCardDto, set: SetDto?, priceField: String) {
        dao.insert(
            WishlistItem(
                cardId = card.id,
                cardName = card.name.ifBlank { card.id },
                setName = set?.name?.takeIf { it.isNotBlank() } ?: set?.abbreviation,
                collectorNumber = card.number,
                rarity = card.rarity,
                imageUrl = card.imagesSmall ?: card.imagesLarge,
                isOwned = card.owned,
            ),
        )
        runCatching {
            val match = server.searchCards(card.name, card.number, 20).data.firstOrNull { it.id == card.id } ?: return
            match.wishlistPrice(priceField)?.let { dao.updateCachedPrice(card.id, it) }
        }
    }

    suspend fun remove(cardId: String) = dao.deleteByCardId(cardId)

    /** Puts back an entry exactly as it was (an undone removal). */
    suspend fun restore(item: WishlistItem) {
        dao.insert(item)
    }

    suspend fun contains(cardId: String): Boolean = dao.find(cardId) != null

    suspend fun setTargetPrice(cardId: String, euros: Double?) = dao.updateTargetPrice(cardId, euros)

    suspend fun setPriority(cardId: String, priority: WishlistPriority) = dao.updatePriority(cardId, priority)

    /**
     * Marks entries owned or not from the collection the server just sent, and refreshes the prices of the cards it holds.
     * Nothing is ever removed: owning a wished-for card only changes how its entry looks.
     */
    suspend fun syncWithCollection(collection: List<CollectionItemDto>, priceField: String) {
        val owned = collection.ownedCardIds()
        if (owned.isEmpty()) dao.clearOwned() else dao.syncOwned(owned.toList())
        collection.asSequence().mapNotNull { it.card }.distinctBy { it.id }.forEach { card ->
            card.wishlistPrice(priceField)?.let { dao.updateCachedPrice(card.id, it) }
        }
    }
}

internal fun CardDto.toWishlistItem(priceField: String, owned: Boolean = false) = WishlistItem(
    cardId = id,
    cardName = name.ifBlank { id },
    setName = setName(),
    collectorNumber = number,
    rarity = rarity,
    cachedPrice = wishlistPrice(priceField),
    imageUrl = imagesSmall ?: imagesLarge ?: customImageUrl,
    isOwned = owned,
)
