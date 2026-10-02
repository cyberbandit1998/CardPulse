package io.github.cyberbandit1998.cardpulse.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The server sends UTC timestamps in three shapes: with a trailing "Z", with an offset, and bare
 * ("2026-10-02T14:29:24", optionally with fractional seconds), which also means UTC.
 */
fun parseServerInstant(text: String?): Instant? {
    val value = text?.trim().orEmpty()
    if (value.isEmpty()) return null
    return runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(value).toInstant(ZoneOffset.UTC) }.getOrNull()
}
