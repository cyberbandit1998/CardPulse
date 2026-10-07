package app.cardpulse.android.data.wishlist

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.cardpulse.android.core.WishlistPriority

/**
 * One card the user wants. Kept on the phone (Room), so the list survives restarts and works without the server.
 *
 * Prices are euros, like every amount the server sends; the screen converts them to the user's currency. [cachedPrice] is
 * the card's price when it was last seen (null when the server had none), [targetPrice] what the user would pay (null for
 * no target). [imageUrl] is the catalogue image the card came with; the screen prefers the server's cached copy of it.
 */
@Entity(tableName = "wishlist", indices = [Index(value = ["cardId"], unique = true)])
data class WishlistItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The server's card id, such as "sv3-125_en": one entry per card. */
    val cardId: String,
    val cardName: String,
    val setName: String? = null,
    val collectorNumber: String? = null,
    val rarity: String? = null,
    val cachedPrice: Double? = null,
    val targetPrice: Double? = null,
    val priority: WishlistPriority = WishlistPriority.MEDIUM,
    val imageUrl: String? = null,
    /** When it was added, in milliseconds since 1970. */
    val dateAdded: Long = System.currentTimeMillis(),
    /** Whether the collection holds a copy by now. Only marks the entry; owning a card never takes it off the list. */
    val isOwned: Boolean = false,
)
