package app.cardpulse.android.core

import retrofit2.HttpException

// Friends and trading, as the app thinks of them. The server (CardPulse's update to PokéCollector) decides who may see what and
// checks it on every request; nothing here grants access. This file is about what the app asks for and how it shows what comes
// back: the sharing choices, adding a friend, the cards in a friend's lists, and the For Trade marks on the user's own cards.

// ---------------------------------------------------------------------------------------------
// What the user shares
// ---------------------------------------------------------------------------------------------

/** Who can see one of the user's lists. A user who has not chosen shares nothing, and so does a level the app does not know. */
enum class ShareLevel(val key: String, val label: String, val summary: String) {
    PRIVATE("private", "Private", "Nobody else can see it."),
    FRIENDS("friends", "Friends only", "The friends you have accepted."),
    PUBLIC("public", "Public", "Everyone with an account on this server, but not the internet.");

    companion object {
        /** The level saved under [key]; private for anything else, so that a value this app does not know never shows as shared. */
        fun fromKey(key: String?): ShareLevel = entries.firstOrNull { it.key == key } ?: PRIVATE
    }
}

/** The three lists that can be shared, each on its own. */
enum class ShareSection(val key: String, val title: String, val contents: String) {
    COLLECTION("collection", "Collection", "Every card you own, with copies, condition and variant. Never what you paid."),
    WISHLIST("wishlist", "Wishlist", "The cards you want and how many. Never your target prices."),
    TRADE("trade", "For Trade", "Only the cards you mark For Trade, and how many copies of each."),
}

data class Sharing(
    val collection: ShareLevel = ShareLevel.PRIVATE,
    val wishlist: ShareLevel = ShareLevel.PRIVATE,
    val trade: ShareLevel = ShareLevel.PRIVATE,
) {
    operator fun get(section: ShareSection): ShareLevel = when (section) {
        ShareSection.COLLECTION -> collection
        ShareSection.WISHLIST -> wishlist
        ShareSection.TRADE -> trade
    }

    fun with(section: ShareSection, level: ShareLevel): Sharing = when (section) {
        ShareSection.COLLECTION -> copy(collection = level)
        ShareSection.WISHLIST -> copy(wishlist = level)
        ShareSection.TRADE -> copy(trade = level)
    }

    /** Whether anything at all is shared. */
    val sharesAnything: Boolean get() = ShareSection.entries.any { this[it] != ShareLevel.PRIVATE }
}

fun SharingDto.toSharing(): Sharing = Sharing(
    collection = ShareLevel.fromKey(collection),
    wishlist = ShareLevel.fromKey(wishlist),
    trade = ShareLevel.fromKey(trade),
)

/** The body that changes only [section] to [level]. */
fun sharingUpdate(section: ShareSection, level: ShareLevel): SharingUpdateBody = when (section) {
    ShareSection.COLLECTION -> SharingUpdateBody(collection = level.key)
    ShareSection.WISHLIST -> SharingUpdateBody(wishlist = level.key)
    ShareSection.TRADE -> SharingUpdateBody(trade = level.key)
}

/** What a friend lets the user see, in words: "Collection · Wishlist · For Trade", or that nothing is shared yet. */
fun SharedFlagsDto.summary(): String {
    val shared = listOfNotNull(
        ShareSection.COLLECTION.title.takeIf { collection },
        ShareSection.WISHLIST.title.takeIf { wishlist },
        ShareSection.TRADE.title.takeIf { trade },
    )
    return if (shared.isEmpty()) "Hasn't shared anything with you yet" else "Shares ${shared.joinToString(" · ")}"
}

// ---------------------------------------------------------------------------------------------
// Adding a friend
// ---------------------------------------------------------------------------------------------

/** The invite codes the server makes: ten letters and digits with no I, L, O, U, 0 or 1, written as two groups of five. */
object InviteCode {
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789"
    private const val LENGTH = 10

    /** The code as the server stores it, or null when what was typed cannot be a code. Case, spaces and dashes do not matter. */
    fun normalize(text: String?): String? {
        val code = text.orEmpty().filter { it.isLetterOrDigit() }.uppercase()
        return code.takeIf { it.length == LENGTH && it.all { char -> char in ALPHABET } }
    }

    /** "K7MQ9XWD3H" as "K7MQ9-XWD3H". */
    fun format(code: String): String = if (code.length == LENGTH) "${code.take(LENGTH / 2)}-${code.drop(LENGTH / 2)}" else code
}

/** How a friend is looked for: by the name they sign in with, or by the invite code they gave. */
enum class AddFriendMode(val label: String, val field: String, val hint: String) {
    USERNAME("Username", "Their username", "The name they sign in to PokéCollector with."),
    CODE("Invite code", "Their invite code", "Ten letters and digits, like K7MQ9-XWD3H."),
}

private const val MAX_USERNAME_LENGTH = 64

/** The request for what was typed, or null when it cannot be sent yet (nothing typed, or not shaped like a code). */
fun AddFriendMode.requestFor(text: String): FriendRequestBody? = when (this) {
    AddFriendMode.USERNAME -> text.trim().takeIf { it.isNotEmpty() && it.length <= MAX_USERNAME_LENGTH }?.let { FriendRequestBody(username = it) }
    AddFriendMode.CODE -> InviteCode.normalize(text)?.let { FriendRequestBody(inviteCode = InviteCode.format(it)) }
}

/** What to tell the user after a request: sent and waiting, or friends already because they had asked first. */
fun FriendRequestResultDto.message(): String =
    if (accepted) "You and ${user.username} are friends now." else "Request sent to ${user.username}. They will see it on their Friends screen."

/** A short message for the screen it is shown on, in the colour of good news or of a problem. */
data class Notice(val text: String, val isError: Boolean = false)

/** The friends in name order, as the list shows them. */
fun List<FriendDto>.byName(): List<FriendDto> = sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.username })

// ---------------------------------------------------------------------------------------------
// Whether the server has Friends
// ---------------------------------------------------------------------------------------------

enum class FriendsAvailability {
    /** Not asked yet. */
    UNKNOWN,

    /** The server has Friends. */
    SUPPORTED,

    /** The server answered as one without Friends does: the update has not been installed on it. */
    MISSING,

    /** The server has Friends but is not asking anyone to sign in, so nothing could be kept private. */
    NEEDS_MULTI_USER,

    /** The server could not be asked (no connection, an error): worth trying again. */
    FAILED,
}

/** What an error from the first Friends request says about the server. */
fun Throwable.friendsAvailability(): FriendsAvailability = when {
    this is HttpException && code() == 404 -> FriendsAvailability.MISSING
    this is HttpException && code() == 403 -> FriendsAvailability.NEEDS_MULTI_USER
    else -> FriendsAvailability.FAILED
}

/** The line under "Friends & trading" on Home: what is going on, or why the feature is not usable yet. */
fun FriendsState.homeNote(): String = when (availability) {
    FriendsAvailability.UNKNOWN -> "Swap cards with friends"
    FriendsAvailability.MISSING -> "Needs an update on your server"
    FriendsAvailability.NEEDS_MULTI_USER -> "Needs multi-user mode on your server"
    FriendsAvailability.FAILED -> "Couldn't reach the server"
    FriendsAvailability.SUPPORTED -> when {
        !loaded -> "Swap cards with friends"
        friends.isEmpty() && incoming.isEmpty() -> "Add friends to compare wishlists and swap cards"
        else -> listOfNotNull(
            friends.size.takeIf { it > 0 }?.let { if (it == 1) "1 friend" else "$it friends" },
            incoming.size.takeIf { it > 0 }?.let { if (it == 1) "1 request waiting" else "$it requests waiting" },
        ).joinToString(" · ")
    }
}

/** Requests from other people waiting for an answer: the number on Home's row. */
val FriendsState.requestsWaiting: Int get() = if (supported) incoming.size else 0

// ---------------------------------------------------------------------------------------------
// Cards in a friend's lists
// ---------------------------------------------------------------------------------------------

/** Which list, or which half of a trade match, a card is shown in. */
enum class FriendCardKind {
    COLLECTION,
    TRADE,
    WISHLIST,

    /** In a trade match: a card they have For Trade that is on your wishlist. */
    THEIR_OFFER,

    /** In a trade match: a card you have For Trade that is on their wishlist. */
    YOUR_OFFER,
}

enum class TagTone { NEUTRAL, GOOD }

/** A small label on a card's row. Said in words so that colour is never the only sign. */
data class FriendTag(val text: String, val tone: TagTone = TagTone.NEUTRAL)

/**
 * A card in another person's list, with what a screen needs to draw it. Their shared cards come in the shapes the app already
 * reads, so a [FriendCard] is built from a collection row, a For Trade row, a wishlist row or a match row, and the screens
 * draw them all alike.
 */
data class FriendCard(
    val kind: FriendCardKind,
    /** Unique within its list, so a list can keep its place. */
    val key: String,
    val cardId: String,
    val card: CardDto?,
    /** Condition, variant and language of the copies; null for a wishlist card, which names none. */
    val condition: String?,
    val variant: String?,
    val lang: String?,
    /** Copies they own: only for their collection. */
    val owned: Int?,
    /** Copies they have For Trade. Null in their collection when their For Trade list is not shared with the user. */
    val offered: Int?,
    /** Copies wanted: by them for their wishlist, by the other side in a trade match. */
    val wanted: Int?,
    /** Copies of this exact card in the user's own collection; null until it has loaded, when nothing can be said. */
    val mine: Int?,
    /** What one copy is worth now, in euros, by the user's chosen price; 0 when the card has no price. */
    val priceEur: Double,
    /** Its place in the list the server sent, which is newest first. */
    val position: Int,
) {
    val name: String get() = card?.name?.takeIf { it.isNotBlank() } ?: "Unnamed card"

    val setName: String get() = card?.setName().orEmpty()

    val numberText: String get() = card?.number?.trim()?.takeIf { it.isNotEmpty() }?.let { "#$it" }.orEmpty()

    val rarity: String? get() = card?.rarity?.trim()?.takeIf { it.isNotEmpty() }

    /** "Obsidian Flames · #125 · Double Rare". */
    val subtitle: String get() = card?.subtitle()?.takeIf { it.isNotEmpty() } ?: cardId

    /** "NM", "LP · Reverse Holo", "NM · Holo · DE": the condition, then the variant and language when they are not the usual. */
    val copyText: String
        get() = listOfNotNull(
            condition?.takeIf { it.isNotBlank() },
            variant?.takeIf { it.isNotBlank() && it != "Normal" },
            lang?.takeIf { it.isNotBlank() && !it.equals("en", ignoreCase = true) }?.let { CardLanguages.label(it) },
        ).joinToString(" · ")

    val hasPrice: Boolean get() = priceEur > 0.0

    /** The labels under the name. */
    val tags: List<FriendTag>
        get() = buildList {
            when (kind) {
                FriendCardKind.COLLECTION -> {
                    if ((owned ?: 0) > 1) add(FriendTag("Has ×$owned"))
                    if ((offered ?: 0) > 0) add(FriendTag("For trade ×$offered", TagTone.GOOD))
                }
                FriendCardKind.TRADE -> add(FriendTag("For trade ×${offered ?: 1}", TagTone.GOOD))
                FriendCardKind.WISHLIST -> if ((wanted ?: 1) > 1) add(FriendTag("Wants ×$wanted"))
                FriendCardKind.THEIR_OFFER -> {
                    add(FriendTag("Offers ×${offered ?: 1}", TagTone.GOOD))
                    if ((wanted ?: 1) > 1) add(FriendTag("You want ×$wanted"))
                }
                FriendCardKind.YOUR_OFFER -> {
                    add(FriendTag("You offer ×${offered ?: 1}", TagTone.GOOD))
                    if ((wanted ?: 1) > 1) add(FriendTag("They want ×$wanted"))
                }
            }
            // Whether the user has the card: for a card they want, having it is the point.
            when {
                mine == null -> Unit
                mine > 0 -> add(FriendTag("You own ×$mine", if (kind == FriendCardKind.WISHLIST) TagTone.GOOD else TagTone.NEUTRAL))
                kind != FriendCardKind.THEIR_OFFER && kind != FriendCardKind.YOUR_OFFER -> add(FriendTag("You don't own it"))
            }
        }

    /** What a screen reader says for the whole row. */
    fun describe(money: MoneyFormatter): String = buildString {
        append(name)
        if (setName.isNotEmpty()) append(", ").append(setName)
        if (numberText.isNotEmpty()) append(", number ").append(numberText.removePrefix("#"))
        rarity?.let { append(", ").append(it) }
        copyText.takeIf { it.isNotEmpty() }?.let { append(", ").append(it) }
        tags.forEach { append(", ").append(it.text.lowercase()) }
        append(if (hasPrice) ", price ${money.format(priceEur)}" else ", no price")
    }

    /** The card as a collection row, so that the existing card details can show it. */
    fun asCollectionItem(): CollectionItemDto = CollectionItemDto(
        id = 0,
        cardId = cardId,
        quantity = owned ?: offered ?: wanted ?: 1,
        condition = condition ?: "NM",
        variant = variant ?: "Normal",
        lang = lang ?: card?.ownLanguage() ?: "en",
        forTradeQuantity = offered,
        card = card,
    )
}

/**
 * The rows of the card details for a card in [owner]'s list: what the card is and costs, the copy it is, and how [owner] has or
 * wants it, then how many the user owns. The same layout as the details of the user's own cards, with the owner's side said.
 */
fun FriendCard.detailLines(owner: String, money: MoneyFormatter): List<Pair<String, String>> = buildList {
    add("Set" to setName.ifBlank { "—" })
    add("Number" to numberText.removePrefix("#").ifBlank { "—" })
    add("Rarity" to (rarity ?: "—"))
    add("Price now" to if (hasPrice) money.format(priceEur) else "No price")
    condition?.takeIf { it.isNotBlank() }?.let { add("Condition" to it) }
    variant?.takeIf { it.isNotBlank() }?.let { add("Variant" to it) }
    lang?.takeIf { it.isNotBlank() }?.let { add("Language" to CardLanguages.label(it)) }
    when (kind) {
        FriendCardKind.COLLECTION -> {
            owned?.let { add("$owner has" to "×$it") }
            offered?.takeIf { it > 0 }?.let { add("For trade" to "×$it") }
        }
        FriendCardKind.TRADE -> add("For trade" to "×${offered ?: 1}")
        FriendCardKind.WISHLIST -> add("$owner wants" to "×${wanted ?: 1}")
        FriendCardKind.THEIR_OFFER -> {
            add("$owner offers" to "×${offered ?: 1}")
            add("You want" to "×${wanted ?: 1}")
        }
        FriendCardKind.YOUR_OFFER -> {
            add("You offer" to "×${offered ?: 1}")
            add("$owner wants" to "×${wanted ?: 1}")
        }
    }
    mine?.let { add("You own" to if (it > 0) "×$it" else "none") }
}

private fun priceOf(card: CardDto?, variant: String?, priceField: String): Double = card?.priceFor(variant ?: "Normal", priceField) ?: 0.0

/** [mine] is how many copies of each card the user's own collection holds ([copiesByCardId]); null while it has not loaded. */
fun List<CollectionItemDto>.collectionCards(mine: Map<String, Int>?, priceField: String): List<FriendCard> = mapIndexed { index, row ->
    val cardId = row.cardId ?: row.card?.id.orEmpty()
    FriendCard(
        kind = FriendCardKind.COLLECTION,
        key = "c${row.id}",
        cardId = cardId,
        card = row.card,
        condition = row.condition,
        variant = row.variant,
        lang = row.lang,
        owned = row.quantity,
        offered = row.forTradeQuantity,
        wanted = null,
        mine = mine?.let { it[cardId] ?: 0 },
        priceEur = priceOf(row.card, row.variant, priceField),
        position = index,
    )
}

fun List<TradeItemDto>.tradeCards(mine: Map<String, Int>?, priceField: String): List<FriendCard> = mapIndexed { index, row ->
    FriendCard(
        kind = FriendCardKind.TRADE,
        key = "t${row.id}",
        cardId = row.cardId,
        card = row.card,
        condition = row.condition,
        variant = row.variant,
        lang = row.lang,
        owned = null,
        offered = row.quantity,
        wanted = null,
        mine = mine?.let { it[row.cardId] ?: 0 },
        priceEur = priceOf(row.card, row.variant, priceField),
        position = index,
    )
}

fun List<WishlistItemDto>.wishlistCards(mine: Map<String, Int>?, priceField: String): List<FriendCard> = mapIndexed { index, row ->
    FriendCard(
        kind = FriendCardKind.WISHLIST,
        key = "w${row.id}",
        cardId = row.cardId,
        card = row.card,
        condition = null,
        variant = null,
        lang = null,
        owned = null,
        offered = null,
        wanted = row.quantity,
        mine = mine?.let { it[row.cardId] ?: 0 },
        priceEur = priceOf(row.card, null, priceField),
        position = index,
    )
}

/** One half of a trade match: [kind] is [FriendCardKind.THEIR_OFFER] or [FriendCardKind.YOUR_OFFER]. */
fun List<TradeMatchRowDto>.matchCards(kind: FriendCardKind, mine: Map<String, Int>?, priceField: String): List<FriendCard> =
    mapIndexed { index, row ->
        FriendCard(
            kind = kind,
            key = "m${kind.ordinal}-${row.id}",
            cardId = row.cardId,
            card = row.card,
            condition = row.condition,
            variant = row.variant,
            lang = row.lang,
            owned = null,
            offered = row.quantity,
            wanted = row.wantedQuantity,
            mine = mine?.let { it[row.cardId] ?: 0 },
            priceEur = priceOf(row.card, row.variant, priceField),
            position = index,
        )
    }

/** Whether every word typed is in the card's name, set, number, rarity, condition or variant. Nothing typed matches all. */
fun FriendCard.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val haystack = listOfNotNull(card?.name, setName, card?.number, card?.rarity, condition, variant)
    return query.trim().split(' ').filter { it.isNotEmpty() }.all { word -> haystack.any { it.contains(word, ignoreCase = true) } }
}

enum class FriendCardOrder(val label: String) {
    RECENT("Recent"),
    NAME("Name"),
    SET("Set"),
    PRICE("Price"),
}

/**
 * The server's own order (newest first) for [FriendCardOrder.RECENT]; A to Z by name or by set (a set's cards in number order);
 * and the most expensive first by price, the cards with no price at the end.
 */
fun List<FriendCard>.ordered(order: FriendCardOrder): List<FriendCard> {
    val byName = compareBy<FriendCard, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
    val bySet = compareBy<FriendCard, String>(String.CASE_INSENSITIVE_ORDER) { it.setName }
    // Zero-padded, as elsewhere in the app, so that #9 comes before #10.
    val byNumber = compareBy<FriendCard> { it.card?.number?.trim().orEmpty().padStart(4, '0') }
    return when (order) {
        FriendCardOrder.RECENT -> sortedBy { it.position }
        FriendCardOrder.NAME -> sortedWith(byName.then(bySet).then(byNumber).thenBy { it.position })
        FriendCardOrder.SET -> sortedWith(bySet.then(byNumber).then(byName).thenBy { it.position })
        FriendCardOrder.PRICE -> sortedWith(compareByDescending<FriendCard> { it.priceEur }.then(byName).then(bySet).then(byNumber).thenBy { it.position })
    }
}

/** "4 entries · 6 cards": how many rows a list of a friend's collection has and how many cards they hold between them. */
fun List<FriendCard>.entriesLine(): String {
    val cards = sumOf { it.owned ?: 0 }
    return "$size ${if (size == 1) "entry" else "entries"} · $cards ${if (cards == 1) "card" else "cards"}"
}

/** How many cards the two halves of a match hold in all. */
val TradeMatchDto.total: Int get() = theyHaveYouWant.size + youHaveTheyWant.size

// ---------------------------------------------------------------------------------------------
// The user's own For Trade marks
// ---------------------------------------------------------------------------------------------

/**
 * How many copies of one collection row are held and how many of them are for trade. Nothing is for trade until the user says
 * so, and never more than they hold.
 */
data class TradeSlot(val held: Int, val offered: Int) {
    private val heldCopies = held.coerceAtLeast(0)
    private val offeredCopies = offered.coerceIn(0, heldCopies)

    /** The count to show, trimmed to what is held. */
    val shown: Int get() = offeredCopies

    val canOfferMore: Boolean get() = offeredCopies < heldCopies
    val canOfferFewer: Boolean get() = offeredCopies > 0

    fun more(): Int = (offeredCopies + 1).coerceAtMost(heldCopies)
    fun fewer(): Int = (offeredCopies - 1).coerceAtLeast(0)

    /** "None for trade", or "2 of 3 for trade". */
    val summary: String get() = if (offeredCopies == 0) "None for trade" else "$offeredCopies of $heldCopies for trade"
}

/** Collection row id -> copies marked For Trade, without rows marked with none. */
fun OwnTradeListDto.toMarks(): Map<Int, Int> =
    items.filter { it.quantity > 0 }.associate { it.collectionItemId to it.quantity }

/** The marks with [itemId] set to [quantity] (0 removes it). */
fun Map<Int, Int>.withMark(itemId: Int, quantity: Int): Map<Int, Int> =
    if (quantity <= 0) this - itemId else this + (itemId to quantity)

/** How many cards (copies, not rows) are marked For Trade, counting no more of a row than it holds. */
fun Map<Int, Int>.copiesForTrade(collection: List<CollectionItemDto>): Int =
    collection.sumOf { row -> TradeSlot(row.quantity, this[row.id] ?: 0).shown }
