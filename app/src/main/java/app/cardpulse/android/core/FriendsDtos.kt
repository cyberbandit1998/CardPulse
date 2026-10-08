package app.cardpulse.android.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Shapes returned by the Friends endpoints (`/api/friends`) that CardPulse's server update adds to PokéCollector (the server
// folder of this repository). Like the shapes in Dtos.kt, every field has a default and unknown fields are ignored, and the
// tests decode real responses captured from the update's own router.
//
// What another person shares arrives in the shapes the app already reads: their collection as [CollectionItemDto] (with
// [CollectionItemDto.forTradeQuantity]), their wishlist as [WishlistItemDto], so a card in a friend's list is a card like any
// other and opens the same details.

/** Someone on the server. */
@Serializable
data class PersonDto(
    val id: Int = 0,
    val username: String = "",
    @SerialName("avatar_id") val avatarId: Int? = null,
)

/** What a user shares, section by section: "private", "friends" or "public". */
@Serializable
data class SharingDto(
    val collection: String = "private",
    val wishlist: String = "private",
    val trade: String = "private",
)

/** Which sections of another person the signed-in user may see. */
@Serializable
data class SharedFlagsDto(
    val collection: Boolean = false,
    val wishlist: Boolean = false,
    val trade: Boolean = false,
)

@Serializable
data class FriendCountsDto(
    val friends: Int = 0,
    val incoming: Int = 0,
    val outgoing: Int = 0,
    @SerialName("for_trade_cards") val forTradeCards: Int = 0,
)

/** `GET /api/friends/me`: the signed-in user's invite code, what they share and the counts. It also tells the app the server has Friends. */
@Serializable
data class FriendsMeDto(
    val version: Int = 1,
    val user: PersonDto = PersonDto(),
    @SerialName("invite_code") val inviteCode: String = "",
    val sharing: SharingDto = SharingDto(),
    val counts: FriendCountsDto = FriendCountsDto(),
)

/** An accepted friend, with what they let the signed-in user see. */
@Serializable
data class FriendDto(
    val id: Int = 0,
    val username: String = "",
    @SerialName("avatar_id") val avatarId: Int? = null,
    val since: String? = null,
    val shares: SharedFlagsDto = SharedFlagsDto(),
)

/** A request waiting for an answer; [user] is the other person (the one who asked, or the one who was asked). */
@Serializable
data class FriendRequestDto(
    val id: Int = 0,
    val user: PersonDto = PersonDto(),
    @SerialName("created_at") val createdAt: String? = null,
)

/** `GET /api/friends/`. */
@Serializable
data class FriendsOverviewDto(
    val friends: List<FriendDto> = emptyList(),
    val incoming: List<FriendRequestDto> = emptyList(),
    val outgoing: List<FriendRequestDto> = emptyList(),
)

/**
 * The body of `POST /api/friends/requests`: a username or an invite code, not both. [AppJson] leaves out the one that is null,
 * as the server needs.
 */
@Serializable
data class FriendRequestBody(
    val username: String? = null,
    @SerialName("invite_code") val inviteCode: String? = null,
)

/** The answer to a request: "pending" (waiting for them), or "accepted" (they had asked first, so you are friends now). */
@Serializable
data class FriendRequestResultDto(
    val status: String = "pending",
    @SerialName("request_id") val requestId: Int = 0,
    val user: PersonDto = PersonDto(),
) {
    val accepted: Boolean get() = status == "accepted"
}

/** The body of `PUT /api/friends/me/sharing`: only the sections that change; the others stay as they are. */
@Serializable
data class SharingUpdateBody(
    val collection: String? = null,
    val wishlist: String? = null,
    val trade: String? = null,
)

/** `POST /api/friends/me/invite-code`. */
@Serializable
data class InviteCodeDto(
    @SerialName("invite_code") val inviteCode: String = "",
)

/** A pending request between the signed-in user and someone they are looking at. */
@Serializable
data class ProfileRequestDto(
    val id: Int = 0,
    /** "incoming" (they asked you) or "outgoing" (you asked them). */
    val direction: String = "incoming",
)

@Serializable
data class FriendProfileDto(
    val user: PersonDto = PersonDto(),
    @SerialName("is_friend") val isFriend: Boolean = false,
    val request: ProfileRequestDto? = null,
    val shared: SharedFlagsDto = SharedFlagsDto(),
)

/** One of the signed-in user's collection rows that is marked For Trade (`GET /api/friends/trade-list`). */
@Serializable
data class TradeEntryDto(
    @SerialName("collection_item_id") val collectionItemId: Int = 0,
    /** Copies marked For Trade. */
    val quantity: Int = 0,
    /** Copies the row holds. */
    val owned: Int = 0,
)

@Serializable
data class OwnTradeListDto(
    val items: List<TradeEntryDto> = emptyList(),
)

/** The body of `PUT /api/friends/trade-list/{id}`: how many copies of the row are For Trade (0 takes the row off). */
@Serializable
data class TradeQuantityBody(
    val quantity: Int,
)

/** One card of someone's For Trade list (`GET /api/friends/{id}/trade-list`): [quantity] is how many copies are on offer. */
@Serializable
data class TradeItemDto(
    val id: Int = 0,
    @SerialName("card_id") val cardId: String = "",
    val quantity: Int = 1,
    val condition: String = "NM",
    val variant: String = "Normal",
    val lang: String = "en",
    val card: CardDto? = null,
)

/** A row of a trade match: a For Trade card ([quantity] copies on offer) and how many copies the other side wants. */
@Serializable
data class TradeMatchRowDto(
    val id: Int = 0,
    @SerialName("card_id") val cardId: String = "",
    val quantity: Int = 1,
    val condition: String = "NM",
    val variant: String = "Normal",
    val lang: String = "en",
    @SerialName("wanted_quantity") val wantedQuantity: Int = 1,
    val card: CardDto? = null,
)

/** `GET /api/friends/{id}/trade-match`: the two halves of a comparison with one friend. */
@Serializable
data class TradeMatchDto(
    val user: PersonDto = PersonDto(),
    /** Their For Trade cards that are on the signed-in user's wishlist. */
    @SerialName("they_have_you_want") val theyHaveYouWant: List<TradeMatchRowDto> = emptyList(),
    /** The signed-in user's For Trade cards that are on their wishlist. */
    @SerialName("you_have_they_want") val youHaveTheyWant: List<TradeMatchRowDto> = emptyList(),
    /** Whether they share their For Trade list with you; without it the first half cannot be worked out. */
    @SerialName("can_see_their_trade_list") val canSeeTheirTradeList: Boolean = false,
    /** Whether they share their wishlist with you; without it the second half cannot be worked out. */
    @SerialName("can_see_their_wishlist") val canSeeTheirWishlist: Boolean = false,
)
