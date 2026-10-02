package app.cardpulse.android.core

import kotlinx.serialization.serializer

/**
 * Responses captured from PokéCollector's own routers running against a seeded database
 * (see app/src/test/resources/fixtures). Synthetic data only.
 */
object Fixtures {
    fun text(name: String): String =
        checkNotNull(Fixtures::class.java.getResource("/fixtures/$name.json")) { "Missing fixture: $name" }.readText()

    inline fun <reified T> decode(name: String): T = AppJson.decodeFromString(serializer<T>(), text(name))
}
