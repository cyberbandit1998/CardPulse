package app.cardpulse.android.core

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlin.math.abs

/**
 * Formats the server's prices in the currency chosen in PokéCollector.
 *
 * The server stores Cardmarket prices in euros and its own web app converts them for display, so
 * every amount passed in here is euros and [rateFromEur] converts to [currencyCode].
 */
class MoneyFormatter(
    currencyCode: String = "EUR",
    private val rateFromEur: Double = 1.0,
    private val locale: Locale = Locale.getDefault(),
) {
    private val currencyFormat: NumberFormat = NumberFormat.getCurrencyInstance(locale).apply {
        runCatching { currency = Currency.getInstance(currencyCode.uppercase(Locale.ROOT)) }
    }

    fun format(eurAmount: Double): String = currencyFormat.format(eurAmount * rateFromEur)

    /** An amount that is already in the display currency, without cents: "$5" rather than "$5.00". For round figures such as filter steps. */
    fun wholeDisplayAmount(amount: Double): String =
        (currencyFormat.clone() as NumberFormat).apply { minimumFractionDigits = 0; maximumFractionDigits = 0 }.format(amount)

    /** "+€1.23", "−€1.23", or just "€0.00" when the change rounds to nothing. */
    fun signed(eurAmount: Double): String {
        val converted = eurAmount * rateFromEur
        val text = currencyFormat.format(abs(converted))
        return when {
            converted >= 0.005 -> "+$text"
            converted <= -0.005 -> "−$text"
            else -> text
        }
    }

    fun percent(value: Double): String = String.format(locale, "%+.1f%%", value)
}

/**
 * Converts between what the user types (in their display currency) and what the server stores (euros),
 * the same way the PokéCollector web app does.
 */
object MoneyInput {
    /** The text to show in an input box for a stored euro amount; empty when there is none. */
    fun toInput(eurAmount: Double?, rateFromEur: Double): String =
        if (eurAmount == null) "" else String.format(Locale.US, "%.2f", eurAmount * rateFromEur)

    /** Euros to store for what the user typed, or null when it is blank, not a number, or negative. */
    fun toEuros(input: String, rateFromEur: Double): Double? {
        val amount = input.trim().replace(',', '.').toDoubleOrNull() ?: return null
        if (!amount.isFinite() || amount < 0.0) return null
        val rate = if (rateFromEur > 0.0) rateFromEur else 1.0
        return Math.round(amount / rate * 10_000.0) / 10_000.0
    }
}
