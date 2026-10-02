package io.github.cyberbandit1998.cardpulse.core

import java.time.Duration
import java.time.Instant

object Conditions {
    val ALL = listOf("Mint", "NM", "LP", "MP", "HP")
}

object Variants {
    val ALL = listOf("Normal", "Holo", "Reverse Holo", "First Edition")
}

enum class ItemPhase { QUEUED, PROCESSING, WAITING_TO_RETRY, NEEDS_REVIEW, FAILED, HANDLED }

fun ScanItemDto.phase(): ItemPhase = when {
    resolved -> ItemPhase.HANDLED
    status == "done" -> ItemPhase.NEEDS_REVIEW
    status == "failed" -> ItemPhase.FAILED
    status == "retrying" -> ItemPhase.WAITING_TO_RETRY
    status == "processing" -> ItemPhase.PROCESSING
    else -> ItemPhase.QUEUED
}

/** True once the server has nothing left to process for this job (the user may still need to review). */
val ScanJobDto.isSettled: Boolean get() = active == 0

/** Why a photo is waiting, in plain words, with a countdown when the server gave a retry time. */
fun retryNote(reason: String?, nextAttemptAt: String?, now: Instant = Instant.now()): String {
    val seconds = parseServerInstant(nextAttemptAt)
        ?.let { Duration.between(now, it).seconds.coerceAtLeast(0) }
    val wait = when {
        seconds == null -> ""
        seconds <= 0 -> " shortly"
        seconds < 90 -> " in ${seconds}s"
        else -> " in about ${(seconds + 30) / 60} min"
    }
    return when (reason) {
        "daily_quota" -> "The scanner's daily quota is used up. Retrying$wait."
        "rate_limit" -> "The scanner is rate-limited. Retrying$wait."
        "catalogue_unavailable" -> "The card database isn't reachable right now. Retrying$wait."
        else -> "Retrying$wait."
    }
}

/** What the scanner read off the photo, e.g. "Charizard ex · 125/197 · OBF"; null if it read nothing. */
fun ScanItemDto.recognizedSummary(): String? {
    val name = recognized.text("name") ?: recognized.text("name_en")
    val local = recognized.text("number_local")
    val total = recognized.text("number_total")
    val number = if (local != null && total != null) "$local/$total" else local
    return listOfNotNull(name, number, recognized.text("set_code"))
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" · ")
}

/** "Obsidian Flames · #125 · Double Rare" */
fun ScanMatchDto.subtitle(): String =
    listOfNotNull(set ?: setAbbreviation, number?.let { "#$it" }, rarity).joinToString(" · ")

/** The user's choices for one card being added. */
data class AddEdits(
    val quantity: Int = 1,
    val condition: String = "NM",
    val variant: String = "Normal",
    val purchasePrice: Double? = null,
)

/**
 * Builds the body for `resolve-and-add`. The server requires `card_id` (with the language suffix)
 * and `confirmed_card_id` (without it) to describe the same candidate it returned.
 */
fun ScanMatchDto.toAddRequest(edits: AddEdits): ResolveAndAddRequest = ResolveAndAddRequest(
    cardId = id,
    confirmedCardId = tcgCardId ?: id.substringBeforeLast('_'),
    quantity = edits.quantity.coerceIn(1, 999),
    condition = edits.condition,
    variant = edits.variant,
    purchasePrice = edits.purchasePrice?.takeIf { it >= 0.0 },
    lang = lang ?: id.substringAfterLast('_', "en"),
)
