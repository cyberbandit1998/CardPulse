package app.cardpulse.android.core

import kotlinx.coroutines.CompletableDeferred
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

internal fun httpFailure(code: Int, detail: String) =
    HttpException(Response.error<Any>(code, """{"detail":"$detail"}""".toResponseBody("application/json".toMediaType())))

/**
 * A server whose answers the test controls. Every call is recorded; a call named in [errors] fails, and one named in [gates]
 * waits until the test opens the gate, like a slow connection.
 */
internal class FakeFriends : FriendsBackend {
    var me: FriendsMeDto = Fixtures.decode("friends_me")
    var overview: FriendsOverviewDto = Fixtures.decode("friends_overview")
    var marks: OwnTradeListDto = Fixtures.decode("friends_own_trade_list")
    var requestResult: FriendRequestResultDto = Fixtures.decode("friends_request_pending")
    var sharingAnswer: SharingDto = SharingDto("private", "private", "friends")
    var nextCode: String = "M4PQ7-RSTV2"
    var tradeAnswer: (Int, Int) -> TradeEntryDto = { id, quantity -> TradeEntryDto(id, quantity, owned = 4) }

    val collections = mutableMapOf<Int, FriendRows<CollectionItemDto>>()
    val wishlists = mutableMapOf<Int, FriendRows<WishlistItemDto>>()
    val trades = mutableMapOf<Int, FriendRows<TradeItemDto>>()
    val matches = mutableMapOf<Int, TradeMatchDto>()

    val calls = mutableListOf<String>()
    val errors = mutableMapOf<String, Throwable>()
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
    val sentRequests = mutableListOf<FriendRequestBody>()
    val sharingBodies = mutableListOf<SharingUpdateBody>()
    val tradeCalls = mutableListOf<Pair<Int, Int>>()

    fun count(name: String) = calls.count { it == name }

    fun hold(name: String): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { gates[name] = it }

    private suspend fun enter(name: String) {
        calls += name
        gates[name]?.await()
        errors[name]?.let { throw it }
    }

    override suspend fun friendsMe(): FriendsMeDto {
        val snapshot = me // what the server said when asked, even if the answer arrives much later
        enter("friendsMe")
        return snapshot
    }

    override suspend fun friendsOverview(): FriendsOverviewDto {
        val snapshot = overview
        enter("friendsOverview")
        return snapshot
    }

    override suspend fun sendFriendRequest(body: FriendRequestBody): FriendRequestResultDto {
        sentRequests += body
        enter("sendFriendRequest")
        return requestResult
    }

    override suspend fun acceptFriendRequest(requestId: Int): FriendDto {
        enter("acceptFriendRequest")
        val request = overview.incoming.first { it.id == requestId }
        overview = overview.copy(
            friends = overview.friends + FriendDto(id = request.user.id, username = request.user.username),
            incoming = overview.incoming.filterNot { it.id == requestId },
        )
        return FriendDto(id = request.user.id, username = request.user.username)
    }

    override suspend fun declineFriendRequest(requestId: Int) {
        enter("declineFriendRequest")
        overview = overview.copy(incoming = overview.incoming.filterNot { it.id == requestId })
    }

    override suspend fun cancelFriendRequest(requestId: Int) {
        enter("cancelFriendRequest")
        overview = overview.copy(outgoing = overview.outgoing.filterNot { it.id == requestId })
    }

    override suspend fun removeFriend(friendId: Int) {
        enter("removeFriend")
        overview = overview.copy(friends = overview.friends.filterNot { it.id == friendId })
    }

    override suspend fun updateSharing(update: SharingUpdateBody): SharingDto {
        sharingBodies += update
        enter("updateSharing")
        return sharingAnswer
    }

    override suspend fun newInviteCode(): String {
        enter("newInviteCode")
        return nextCode
    }

    override suspend fun ownTradeList(): OwnTradeListDto {
        enter("ownTradeList")
        return marks
    }

    override suspend fun setTradeQuantity(itemId: Int, quantity: Int): TradeEntryDto {
        tradeCalls += itemId to quantity
        enter("setTradeQuantity")
        return tradeAnswer(itemId, quantity)
    }

    override suspend fun friendCollection(friendId: Int): FriendRows<CollectionItemDto> {
        enter("friendCollection")
        return collections[friendId] ?: FriendRows(emptyList())
    }

    override suspend fun friendWishlist(friendId: Int): FriendRows<WishlistItemDto> {
        enter("friendWishlist")
        return wishlists[friendId] ?: FriendRows(emptyList())
    }

    override suspend fun friendTradeList(friendId: Int): FriendRows<TradeItemDto> {
        enter("friendTradeList")
        return trades[friendId] ?: FriendRows(emptyList())
    }

    override suspend fun friendTradeMatch(friendId: Int): TradeMatchDto {
        enter("friendTradeMatch")
        return matches[friendId] ?: TradeMatchDto()
    }
}
