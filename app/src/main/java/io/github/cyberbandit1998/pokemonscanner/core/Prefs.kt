package io.github.cyberbandit1998.pokemonscanner.core

import kotlinx.serialization.json.JsonObject

/** How the user's PokéCollector account wants prices and photos shown. Read from `/api/settings/`. */
data class DisplayPrefs(
    /** ISO code the amounts are shown in. The server's prices are euros; see [MoneyFormatter]. */
    val currency: String = "EUR",
    val rateFromEur: Double = 1.0,
    /** The `price_field` query value the web app would send for the chosen primary price. */
    val priceField: String = "price_trend",
    /** Show my own card photos instead of official artwork wherever I have one. */
    val preferOwnPhotos: Boolean = false,
)

/** The same mapping as the web app's `PRICE_PRIMARY_TO_FIELD`, defaulting to the Cardmarket trend price. */
fun priceFieldFor(primary: String?): String = when (primary) {
    "market", "avg" -> "price_market"
    "trend" -> "price_trend"
    "avg1" -> "price_avg1"
    "avg7" -> "price_avg7"
    "avg30" -> "price_avg30"
    "low" -> "price_low"
    else -> "price_trend"
}

/** Settings that don't need the exchange rate (the rate is a separate network call). */
fun displayPrefsFrom(settings: JsonObject, rateFromEur: Double = 1.0, currency: String? = null): DisplayPrefs =
    DisplayPrefs(
        currency = currency ?: (settings.text("currency")?.uppercase() ?: "EUR"),
        rateFromEur = rateFromEur,
        priceField = priceFieldFor(settings.text("price_primary")),
        preferOwnPhotos = settings.text("prefer_own_card_photos") == "true",
    )
