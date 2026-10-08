package app.cardpulse.android.ui

import app.cardpulse.android.core.CollectionItemDto
import app.cardpulse.android.core.FakeFriends
import app.cardpulse.android.core.Fixtures
import app.cardpulse.android.core.FriendRows
import app.cardpulse.android.core.FriendsSession
import app.cardpulse.android.core.TradeItemDto
import app.cardpulse.android.core.TradeMatchDto
import app.cardpulse.android.core.WishlistItemDto
import app.cardpulse.android.core.userMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

internal const val MISTY = 2
internal const val BROCK = 3
internal const val GARY = 4

/**
 * A server that has the Friends update, as the fixtures captured it from the update's own router: the signed-in user is ash, who has
 * three friends and one request waiting, and has sent one. Misty shares everything with ash, brock only his For Trade list, and gary
 * nothing. Used with a real [FriendsSession], so the screens are driven by the same code the app runs; only the server is made up.
 *
 * The session's work happens at once (its scope is Unconfined and the fake server never waits), so a screen has what it asked for by
 * the time a test looks.
 */
internal class FriendsWorld {
    val fake = FakeFriends()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val session = FriendsSession(fake, scope, describe = { it.userMessage() })

    init {
        fake.collections[MISTY] = FriendRows(Fixtures.decode<List<CollectionItemDto>>("friend_collection_misty"))
        fake.wishlists[MISTY] = FriendRows(Fixtures.decode<List<WishlistItemDto>>("friend_wishlist_misty"))
        fake.trades[MISTY] = FriendRows(Fixtures.decode<List<TradeItemDto>>("friend_trade_list_misty"))
        fake.matches[MISTY] = Fixtures.decode<TradeMatchDto>("friend_trade_match_misty")

        // Brock puts one card up for trade, which ash wants; he shares nothing else.
        val brock = Fixtures.decode<TradeMatchDto>("friend_trade_match_brock")
        fake.matches[BROCK] = brock
        fake.trades[BROCK] = FriendRows(
            brock.theyHaveYouWant.map {
                TradeItemDto(id = it.id, cardId = it.cardId, quantity = it.quantity, condition = it.condition, variant = it.variant, lang = it.lang, card = it.card)
            },
        )

        fake.matches[GARY] = Fixtures.decode<TradeMatchDto>("friend_trade_match_gary")
    }

    fun close() = scope.cancel()
}
