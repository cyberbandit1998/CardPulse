package app.cardpulse.android.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// Shapes returned by PokéCollector's API. Every field has a default and unknown fields are ignored
// (see AppJson), so a newer or older server degrades gracefully instead of failing to parse.
// The tests decode real responses captured from the server's own routers.

// ---------------------------------------------------------------------------------------------
// Connection and sign-in
// ---------------------------------------------------------------------------------------------

@Serializable
data class HealthDto(
    val status: String = "",
    val service: String = "",
)

@Serializable
data class AuthModeDto(
    @SerialName("multi_user") val multiUser: Boolean = true,
    val locked: Boolean = false,
)

@Serializable
data class UserDto(
    val id: Int = 0,
    val username: String = "",
    val role: String = "trainer",
    @SerialName("avatar_id") val avatarId: Int? = null,
    @SerialName("must_change_password") val mustChangePassword: Boolean = false,
)

@Serializable
data class LoginResponseDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
    val user: UserDto = UserDto(),
)

@Serializable
data class ForcePasswordRequest(
    @SerialName("new_password") val newPassword: String,
)

@Serializable
data class ExchangeRateDto(
    val from: String = "",
    val to: String = "",
    val rate: Double = 1.0,
    val fallback: Boolean = false,
)

// ---------------------------------------------------------------------------------------------
// Collection
// ---------------------------------------------------------------------------------------------

@Serializable
data class SetDto(
    val id: String = "",
    @SerialName("tcg_set_id") val tcgSetId: String? = null,
    val name: String = "",
    val series: String? = null,
    val abbreviation: String? = null,
    val total: Int = 0,
    @SerialName("printed_total") val printedTotal: Int = 0,
    val lang: String = "en",
    /** When the set came out, as text that sorts in date order ("2023-08-11"); the server leaves it out for some sets. */
    @SerialName("release_date") val releaseDate: String? = null,
    /**
     * How many different cards of this set the user owns. Only the list of sets (`GET /api/sets/`) fills it in; a set that
     * comes inside a card says 0.
     */
    @SerialName("owned_count") val ownedCount: Int = 0,
)

/** One owned row of a card in a set's checklist (a condition and variant of it). */
@Serializable
data class ChecklistOwnedDto(
    val id: Int = 0,
    val quantity: Int = 0,
    val condition: String = "NM",
    val variant: String = "Normal",
    val lang: String = "en",
)

/** One card of a set's checklist: the card, and whether the user owns it. */
@Serializable
data class ChecklistCardDto(
    val id: String,
    val name: String = "",
    val number: String? = null,
    val rarity: String? = null,
    @SerialName("images_small") val imagesSmall: String? = null,
    @SerialName("images_large") val imagesLarge: String? = null,
    val lang: String = "en",
    /** The server's own answer, as of when it was asked: whether any copy is owned, and how many in all. */
    val owned: Boolean = false,
    @SerialName("owned_quantity") val ownedQuantity: Int = 0,
    @SerialName("owned_items") val ownedItems: List<ChecklistOwnedDto> = emptyList(),
)

/** `GET /api/sets/{id}/checklist`: every card the server has for a set, in card-number order, with ownership. */
@Serializable
data class SetChecklistDto(
    val set: SetDto = SetDto(),
    val cards: List<ChecklistCardDto> = emptyList(),
    @SerialName("owned_count") val ownedCount: Int = 0,
    @SerialName("total_count") val totalCount: Int = 0,
)

@Serializable
data class CardDto(
    val id: String,
    @SerialName("tcg_card_id") val tcgCardId: String? = null,
    val name: String = "",
    @SerialName("set_id") val setId: String? = null,
    val number: String? = null,
    val rarity: String? = null,
    @SerialName("images_small") val imagesSmall: String? = null,
    @SerialName("images_large") val imagesLarge: String? = null,
    @SerialName("custom_image_url") val customImageUrl: String? = null,
    @SerialName("is_custom") val isCustom: Boolean = false,
    @SerialName("variants_normal") val variantsNormal: Boolean? = null,
    @SerialName("variants_reverse") val variantsReverse: Boolean? = null,
    @SerialName("variants_holo") val variantsHolo: Boolean? = null,
    @SerialName("variants_first_edition") val variantsFirstEdition: Boolean? = null,
    @SerialName("set_ref") val setRef: SetDto? = null,
    /** The card's own language, as the search and custom-card endpoints send it. */
    val lang: String? = null,
    /** A list of type names; kept as loose JSON so an odd value on one card can never make a whole response unreadable. */
    val types: JsonElement? = null,
    val hp: String? = null,
    val artist: String? = null,
    // The Cardmarket prices the server keeps for the card, in euros. Which one is "the price" is the user's choice (see
    // DisplayPrefs.priceField) and Reverse Holo has its own: [priceFor] makes the choice as the server does.
    @SerialName("price_market") val priceMarket: Double? = null,
    @SerialName("price_low") val priceLow: Double? = null,
    @SerialName("price_trend") val priceTrend: Double? = null,
    @SerialName("price_avg1") val priceAvg1: Double? = null,
    @SerialName("price_avg7") val priceAvg7: Double? = null,
    @SerialName("price_avg30") val priceAvg30: Double? = null,
    @SerialName("price_market_holo") val priceMarketHolo: Double? = null,
    @SerialName("price_low_holo") val priceLowHolo: Double? = null,
    @SerialName("price_trend_holo") val priceTrendHolo: Double? = null,
    @SerialName("price_avg1_holo") val priceAvg1Holo: Double? = null,
    @SerialName("price_avg7_holo") val priceAvg7Holo: Double? = null,
    @SerialName("price_avg30_holo") val priceAvg30Holo: Double? = null,
) {
    /** "Fire", "Water": the types as text, whatever shape the server stored them in. */
    val typeNames: List<String>
        get() = (types as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }.orEmpty()
}

/** One page of `GET /api/cards/search`. */
@Serializable
data class CardSearchDto(
    val data: List<CardDto> = emptyList(),
    @SerialName("total_count") val totalCount: Int = 0,
    val page: Int = 1,
    @SerialName("page_size") val pageSize: Int = 20,
)

@Serializable
data class CollectionItemDto(
    val id: Int,
    @SerialName("card_id") val cardId: String? = null,
    val quantity: Int = 1,
    val condition: String = "NM",
    val variant: String = "Normal",
    /** Tag objects (`{id, name, ...}`) on the collection endpoints, plain strings on the dashboard. */
    @SerialName("printing_details") val printingDetails: List<JsonElement> = emptyList(),
    @SerialName("purchase_price") val purchasePrice: Double? = null,
    val lang: String = "en",
    @SerialName("added_at") val addedAt: String? = null,
    /** True when the owner has a private photo of this card (shared by every copy of the card). */
    @SerialName("has_scan_photo") val hasScanPhoto: Boolean = false,
    /**
     * Only in someone else's shared collection, and only when they share their For Trade list with you: how many of the copies
     * are For Trade. Null everywhere else: the signed-in user's own marks come from `GET /api/friends/trade-list`.
     */
    @SerialName("for_trade_quantity") val forTradeQuantity: Int? = null,
    val card: CardDto? = null,
) {
    val printingDetailNames: List<String>
        get() = printingDetails.mapNotNull { element ->
            when (element) {
                is JsonObject -> (element["name"] as? JsonPrimitive)?.contentOrNull
                is JsonPrimitive -> element.contentOrNull
                else -> null
            }
        }
}

/** The body of `POST /api/cards/custom`: a card made by hand, the way the website's "Create card manually" sends it. */
@Serializable
data class CustomCardRequest(
    val name: String,
    @SerialName("set_id") val setId: String? = null,
    val number: String? = null,
    val rarity: String? = null,
    val types: List<String>? = null,
    val hp: String? = null,
    val artist: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val lang: String? = null,
    @SerialName("is_shared_template") val isSharedTemplate: Boolean = false,
)

/** The body of `POST /api/collection/`: add a card that already exists on the server, by its id. */
@Serializable
data class AddToCollectionRequest(
    @SerialName("card_id") val cardId: String,
    val quantity: Int = 1,
    val condition: String = "NM",
    val variant: String = "Normal",
    @SerialName("printing_details") val printingDetails: List<String> = emptyList(),
    @SerialName("purchase_price") val purchasePrice: Double? = null,
    val lang: String = "en",
)

/** One run of a server sync, as listed by `GET /api/sync/status`. */
@Serializable
data class SyncLogDto(
    val status: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
    @SerialName("cards_updated") val cardsUpdated: Int = 0,
)

/** What the server says about its background syncs. */
@Serializable
data class SyncStatusDto(
    @SerialName("is_running") val isRunning: Boolean = false,
    @SerialName("is_price_sync_running") val isPriceSyncRunning: Boolean = false,
    @SerialName("last_price_sync") val lastPriceSync: SyncLogDto? = null,
)

/** The body of `PUT /api/collection/{id}` when only the number of copies changes. */
@Serializable
data class CollectionQuantityRequest(val quantity: Int)

// ---------------------------------------------------------------------------------------------
// Wishlist
// ---------------------------------------------------------------------------------------------

/** One row of `GET /api/wishlist/`: a card the user wants, how many copies, and the price alerts the server can send. */
@Serializable
data class WishlistItemDto(
    val id: Int,
    @SerialName("card_id") val cardId: String = "",
    /** How many copies are wanted (1 to 99). Owning a copy does not take the card off the list. */
    val quantity: Int = 1,
    // The server's two price alerts, in euros. This app uses the lower one as the card's target price and leaves the other as
    // it finds it.
    @SerialName("price_alert_above") val priceAlertAbove: Double? = null,
    @SerialName("price_alert_below") val priceAlertBelow: Double? = null,
    @SerialName("notified_at") val notifiedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    /** The card with its set and prices, so the list needs no second request per card. */
    val card: CardDto? = null,
)

/**
 * The body of `POST /api/wishlist/`. A card that is already listed does not fail: the server adds [quantity] to the number
 * wanted, so a card must only be added when it is known not to be on the list.
 */
@Serializable
data class WishlistAddRequest(
    @SerialName("card_id") val cardId: String,
    val quantity: Int = 1,
)

// ---------------------------------------------------------------------------------------------
// Portfolio
// ---------------------------------------------------------------------------------------------

/** One point of portfolio history (`/api/analytics/investment-tracker`, `value_history`). */
@Serializable
data class SnapshotDto(
    val date: String = "",
    val value: Double = 0.0,
    val cost: Double = 0.0,
    val pnl: Double = 0.0,
    val cards: Int = 0,
    val legacy: Boolean = false,
    @SerialName("cards_value") val cardsValue: Double? = null,
    @SerialName("products_value") val productsValue: Double? = null,
    @SerialName("unrealized_pnl") val unrealizedPnl: Double? = null,
    @SerialName("realized_pnl") val realizedPnl: Double? = null,
)

@Serializable
data class TopCardDto(
    @SerialName("collection_item_id") val collectionItemId: Int = 0,
    @SerialName("card_id") val cardId: String = "",
    val name: String = "",
    @SerialName("display_price") val displayPrice: Double = 0.0,
    val quantity: Int = 1,
    val variant: String = "Normal",
    val condition: String = "NM",
    @SerialName("total_value") val totalValue: Double = 0.0,
    val rarity: String? = null,
    @SerialName("has_scan_photo") val hasScanPhoto: Boolean = false,
)

@Serializable
data class RecentAdditionDto(
    @SerialName("collection_item_id") val collectionItemId: Int = 0,
    @SerialName("card_id") val cardId: String = "",
    val name: String = "",
    val quantity: Int = 1,
    val variant: String = "Normal",
    val condition: String = "NM",
    @SerialName("added_at") val addedAt: String? = null,
    @SerialName("has_scan_photo") val hasScanPhoto: Boolean = false,
)

@Serializable
data class DashboardDto(
    @SerialName("total_cards") val totalCards: Int = 0,
    @SerialName("unique_cards") val uniqueCards: Int = 0,
    @SerialName("total_value") val totalValue: Double = 0.0,
    @SerialName("total_cost") val totalCost: Double = 0.0,
    @SerialName("card_value") val cardValue: Double = 0.0,
    @SerialName("product_value") val productValue: Double = 0.0,
    @SerialName("unrealized_pnl") val unrealizedPnl: Double = 0.0,
    @SerialName("realized_pnl") val realizedPnl: Double = 0.0,
    val pnl: Double = 0.0,
    @SerialName("total_sets") val totalSets: Int = 0,
    @SerialName("owned_sets") val ownedSets: Int = 0,
    @SerialName("top_cards") val topCards: List<TopCardDto> = emptyList(),
    @SerialName("value_history") val valueHistory: List<SnapshotDto> = emptyList(),
    @SerialName("recent_additions") val recentAdditions: List<RecentAdditionDto> = emptyList(),
    @SerialName("price_field") val priceField: String? = null,
)

@Serializable
data class MoverDto(
    @SerialName("collection_item_id") val collectionItemId: Int = 0,
    @SerialName("card_id") val cardId: String = "",
    val name: String = "",
    val rarity: String? = null,
    @SerialName("has_scan_photo") val hasScanPhoto: Boolean = false,
    @SerialName("current_price") val currentPrice: Double = 0.0,
    @SerialName("old_price") val oldPrice: Double = 0.0,
    @SerialName("change_abs") val changeAbs: Double = 0.0,
    @SerialName("change_pct") val changePct: Double = 0.0,
)

// ---------------------------------------------------------------------------------------------
// Scanning (the persistent job queue)
// ---------------------------------------------------------------------------------------------

/** One candidate card the scanner thinks a photo shows. */
@Serializable
data class ScanMatchDto(
    /** Composite id with a language suffix, e.g. `sv3-125_en`. This is what `card_id` expects. */
    val id: String,
    /** The id without the language suffix, e.g. `sv3-125`. This is what `confirmed_card_id` expects. */
    @SerialName("tcg_card_id") val tcgCardId: String? = null,
    val name: String = "",
    @SerialName("card_type") val cardType: String? = null,
    /** The set's name. */
    val set: String? = null,
    val number: String? = null,
    val image: String? = null,
    @SerialName("image_hd") val imageHd: String? = null,
    val rarity: String? = null,
    val lang: String? = null,
    @SerialName("set_abbreviation") val setAbbreviation: String? = null,
    /** The printed set total on the photo disagrees with this candidate's set. */
    @SerialName("printed_total_mismatch") val printedTotalMismatch: Boolean = false,
)

@Serializable
data class ScanItemDto(
    val id: Int,
    val position: Int = 0,
    @SerialName("batch_mode") val batchMode: Boolean = false,
    /** pending, processing, retrying, done or failed. */
    val status: String = "pending",
    val resolved: Boolean = false,
    val attempts: Int = 0,
    /** What the scanner read off the photo: name, number_local, number_total, set_code, ... */
    val recognized: JsonObject? = null,
    val matches: List<ScanMatchDto> = emptyList(),
    val error: String? = null,
    @SerialName("has_image") val hasImage: Boolean = false,
    @SerialName("next_attempt_at") val nextAttemptAt: String? = null,
    @SerialName("retry_reason") val retryReason: String? = null,
)

/** A scan job. [items] is filled only by the detail endpoint. */
@Serializable
data class ScanJobDto(
    val id: Int,
    val status: String = "pending",
    val total: Int = 0,
    val pending: Int = 0,
    val processing: Int = 0,
    val retrying: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
    val processed: Int = 0,
    /** Items still being worked on; the job is settled when this reaches zero. */
    val active: Int = 0,
    /** Items waiting for the user (done or failed, not yet resolved). */
    val attention: Int = 0,
    @SerialName("failed_attention") val failedAttention: Int = 0,
    @SerialName("next_retry_at") val nextRetryAt: String? = null,
    @SerialName("retry_reason") val retryReason: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("error_message") val errorMessage: String? = null,
    val items: List<ScanItemDto> = emptyList(),
)

@Serializable
data class ScanJobListDto(
    val jobs: List<ScanJobDto> = emptyList(),
)

@Serializable
data class ResolveAndAddRequest(
    @SerialName("card_id") val cardId: String,
    @SerialName("confirmed_card_id") val confirmedCardId: String,
    val quantity: Int = 1,
    val condition: String = "NM",
    val variant: String = "Normal",
    @SerialName("printing_details") val printingDetails: List<String> = emptyList(),
    @SerialName("purchase_price") val purchasePrice: Double? = null,
    val lang: String = "en",
)

@Serializable
data class ResolveAndAddResponse(
    val item: ScanItemDto,
    @SerialName("collection_item") val collectionItem: CollectionItemDto,
)

/** Resolve without adding (skip) when [cardId] is null. */
@Serializable
data class ResolveRequest(
    @SerialName("card_id") val cardId: String? = null,
)

/** Read a string out of a loosely-typed JSON object, accepting numbers as text. */
fun JsonObject?.text(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
