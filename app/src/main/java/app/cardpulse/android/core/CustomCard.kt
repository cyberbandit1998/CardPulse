package app.cardpulse.android.core

/** The types the website offers when a card is made by hand. */
object CardTypes {
    val ALL = listOf(
        "Fire", "Water", "Grass", "Lightning", "Psychic", "Fighting",
        "Darkness", "Metal", "Dragon", "Colorless", "Fairy", "Stellar",
    )
}

/**
 * What has been typed in to add a card. The name and number are what the catalogue is searched with. The rest only
 * matters for a card the catalogue doesn't have, which is made by hand the way the website's "Create card manually"
 * makes it.
 */
data class CustomCardForm(
    val name: String = "",
    val number: String = "",
    val shareAsTemplate: Boolean = false,
    /** A set picked from the server's list. */
    val set: SetDto? = null,
    /** A set id typed in instead, for a set the list doesn't have. Ignored while a set is picked. */
    val otherSetId: String = "",
    val rarity: String = "",
    val types: Set<String> = emptySet(),
    val hp: String = "",
    val artist: String = "",
    val imageUrl: String = "",
) {
    /** What is wrong with the form, or null when it can be sent. */
    fun problem(): String? = when {
        name.isBlank() -> "Give the card a name."
        imageUrl.isNotBlank() && !imageUrl.trim().startsWith("https://", ignoreCase = true) ->
            "The image link has to start with https://"
        else -> null
    }

    /** How the chosen set reads in the form: "Obsidian Flames (sv3_en)", the typed id, or null. */
    fun setLabel(): String? = set?.let { "${it.name} (${it.id})" } ?: otherSetId.trim().ifEmpty { null }

    /** The form as the server wants it, with the same choices the website makes (no empty strings, English by default). */
    fun toRequest(): CustomCardRequest {
        fun String.orNull() = trim().ifEmpty { null }
        // The server keys cards by the set's original id (sv3), not the per-language row (sv3_en).
        val setId = set?.let { it.tcgSetId ?: it.id } ?: otherSetId.orNull()
        return CustomCardRequest(
            name = name.trim(),
            setId = setId,
            number = number.orNull(),
            rarity = rarity.orNull(),
            types = CardTypes.ALL.filter { it in types }.ifEmpty { null },
            hp = hp.orNull(),
            artist = artist.orNull(),
            imageUrl = imageUrl.orNull(),
            lang = set?.lang ?: "en",
            isSharedTemplate = shareAsTemplate,
        )
    }
}

/** Sets that match what was typed: by name, id, code or series, ignoring case. Every word has to match something. */
fun List<SetDto>.matching(query: String): List<SetDto> {
    val words = query.trim().split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return this
    return filter { set ->
        val haystack = listOfNotNull(set.name, set.id, set.tcgSetId, set.abbreviation, set.series)
        words.all { word -> haystack.any { it.contains(word, ignoreCase = true) } }
    }
}
