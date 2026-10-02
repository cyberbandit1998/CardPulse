package app.cardpulse.android.core

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** What to tell the user about a photo that is still on its way, so a spinner is never unexplained. */
data class ProgressNote(val title: String, val detail: String)

/** Says what a photo that isn't ready yet is waiting for. Meant for photos that are [isInFlight]. */
fun ScanEntry.progressNote(
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): ProgressNote {
    val note = when (tileState()) {
        TileState.SENDING -> ProgressNote(
            "Sending to your server…",
            "The photo is on its way. On a poor connection this can take a while.",
        )

        TileState.WAITING -> ProgressNote("Waiting to try again", waitingDetail(zone, locale))

        else -> when (item?.phase()) {
            ItemPhase.PROCESSING -> ProgressNote("Your server is reading this card", "This usually takes a few seconds.")
            ItemPhase.QUEUED -> ProgressNote("Waiting its turn", "Your server reads a few photos at a time.")
            else -> ProgressNote("Sent to your server", "Waiting to hear back.")
        }
    }
    return if (slow) {
        note.copy(
            detail = note.detail + " Nothing has changed for a while, so it may be stuck. " +
                "You can cancel it and photograph the card again.",
        )
    } else {
        note
    }
}

private fun ScanEntry.waitingDetail(zone: ZoneId, locale: Locale): String {
    val why = retryReasonText(item?.retryReason) ?: "The scanner asked your server to wait before trying again."
    val next = parseServerInstant(item?.nextAttemptAt) ?: return why
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(zone).format(next)
    return "$why It will try again at $time."
}
