package app.cardpulse.android.core

data class CardLanguage(val code: String, val name: String)

/**
 * The card languages PokéCollector accepts (TCGdex codes). The server rejects anything else, so the
 * language picker only offers these. Listed with the most common first.
 */
object CardLanguages {
    val ALL = listOf(
        CardLanguage("en", "English"),
        CardLanguage("de", "German"),
        CardLanguage("fr", "French"),
        CardLanguage("es", "Spanish"),
        CardLanguage("it", "Italian"),
        CardLanguage("pt", "Portuguese"),
        CardLanguage("ja", "Japanese"),
        CardLanguage("ko", "Korean"),
        CardLanguage("nl", "Dutch"),
        CardLanguage("pl", "Polish"),
        CardLanguage("ru", "Russian"),
        CardLanguage("zh-cn", "Chinese (Simplified)"),
        CardLanguage("zh-tw", "Chinese (Traditional)"),
        CardLanguage("es-mx", "Spanish (Mexico)"),
        CardLanguage("pt-br", "Portuguese (Brazil)"),
        CardLanguage("pt-pt", "Portuguese (Portugal)"),
        CardLanguage("id", "Indonesian"),
        CardLanguage("th", "Thai"),
    )

    private val byCode = ALL.associateBy { it.code }

    /** The code as the server writes it (lower case, hyphenated), or null if it isn't a language the server accepts. */
    fun normalize(code: String?): String? {
        val clean = code?.trim()?.lowercase()?.replace('_', '-') ?: return null
        return clean.takeIf { it in byCode }
    }

    /** "German" for `de`; the upper-cased code for anything unfamiliar, so nothing is ever blank. */
    fun name(code: String?): String = normalize(code)?.let { byCode.getValue(it).name } ?: code.orEmpty().uppercase()

    /** The short form used on chips and badges, e.g. "EN" or "ZH-CN". */
    fun label(code: String?): String = (normalize(code) ?: code.orEmpty()).uppercase()

    /** Splits a composite card id such as `sv3-125_de` into its card and language parts. */
    fun splitCardId(cardId: String): Pair<String, String?> {
        val cut = cardId.lastIndexOf('_')
        if (cut <= 0) return cardId to null
        val language = normalize(cardId.substring(cut + 1)) ?: return cardId to null
        return cardId.substring(0, cut) to language
    }
}
