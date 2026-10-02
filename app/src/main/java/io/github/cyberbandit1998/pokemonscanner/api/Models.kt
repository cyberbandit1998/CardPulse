package io.github.cyberbandit1998.pokemonscanner.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class LoginResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("must_change_password") val mustChangePassword: Boolean = false
)

@Serializable
data class CollectionItem(
    val id: Int,
    @SerialName("card_id") val cardId: String? = null,
    val quantity: Int = 1,
    val condition: String? = null,
    val variant: String? = null,
    val lang: String? = null,
    @SerialName("has_scan_photo") val hasScanPhoto: Boolean = false,
    val card: Card? = null
)

@Serializable
data class Card(
    val id: String,
    val name: String,
    val number: String? = null,
    val rarity: String? = null,
    @SerialName("images_small") val imagesSmall: String? = null,
    @SerialName("images_large") val imagesLarge: String? = null,
    @SerialName("set_name") val setName: String? = null,
    @SerialName("set_abbreviation") val setAbbreviation: String? = null,
    val lang: String? = null,
    val image: String? = null
)

@Serializable
data class ScanResult(
    val matches: List<ScanMatch> = emptyList(),
    val recognized: JsonObject? = null
)

@Serializable
data class ScanMatch(
    val id: String,
    @SerialName("tcg_card_id") val tcgCardId: String? = null,
    val name: String,
    val number: String? = null,
    val rarity: String? = null,
    val image: String? = null,
    @SerialName("images_small") val imagesSmall: String? = null,
    @SerialName("images_large") val imagesLarge: String? = null,
    @SerialName("set_abbreviation") val setAbbreviation: String? = null,
    val lang: String? = null
)

@Serializable
data class AddCollectionRequest(
    @SerialName("card_id") val cardId: String,
    val quantity: Int = 1,
    val condition: String = "NM",
    val variant: String? = null,
    @SerialName("printing_details") val printingDetails: List<String> = emptyList(),
    val lang: String = "en",
    @SerialName("purchase_price") val purchasePrice: Double? = null
)

@Serializable
data class PortfolioSnapshot(
    val date: String? = null,
    @SerialName("total_value") val totalValue: Double? = null,
    @SerialName("collection_value") val collectionValue: Double? = null,
    @SerialName("sealed_value") val sealedValue: Double? = null,
    @SerialName("total_cost") val totalCost: Double? = null,
    @SerialName("profit_loss") val profitLoss: Double? = null,
    @SerialName("unrealized_gain") val unrealizedGain: Double? = null
)
