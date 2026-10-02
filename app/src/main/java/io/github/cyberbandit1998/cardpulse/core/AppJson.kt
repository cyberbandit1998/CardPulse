package io.github.cyberbandit1998.cardpulse.core

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json

/** JSON settings shared by the HTTP layer and the tests. */
val AppJson: Json = Json {
    // The server adds fields over time; never fail because of one we don't know about.
    ignoreUnknownKeys = true
    // Accept numbers where text is expected and similar harmless looseness.
    isLenient = true
    // A null for a field we declare with a default becomes that default instead of an error.
    coerceInputValues = true
    // Never send "field": null.
    explicitNulls = false
    // Always send every field of a request we declare, so the server never has to guess.
    encodeDefaults = true
}

/** Like [runCatching] but lets coroutine cancellation through instead of swallowing it. */
inline fun <T> attempt(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
