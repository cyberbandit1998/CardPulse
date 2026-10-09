package app.cardpulse.android.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import retrofit2.HttpException
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * What a user may still scan today, as the server counts it (`GET api/scan-limits/me`, and the `scan_limit` that comes with a
 * scan job). The limit is the server's: an admin sets it on the PokéCollector website, and the server refuses a scan with no
 * scan left. This only says how much is left.
 *
 * A server without the daily scan limits has no such route, so none of this is shown for it.
 */
@Serializable
data class ScanAllowanceDto(
    /** Scans a day; null is unlimited. */
    @SerialName("daily_limit") val dailyLimit: Int? = null,
    val unlimited: Boolean = false,
    val used: Int = 0,
    /** Scans left today; null is unlimited. */
    val remaining: Int? = null,
    /** When the day starts over, with the server's offset ("2026-10-10T00:00:00+02:00"). */
    @SerialName("resets_at") val resetsAt: String? = null,
    @SerialName("resets_in_seconds") val resetsInSeconds: Long? = null,
    /** The server's time zone, such as "Europe/Berlin". */
    val timezone: String? = null,
)

/** What the server calls a 429 that is the daily scan limit. The rate limiter's 429 (worth retrying in a moment) has none. */
const val SCAN_LIMIT_CODE = "scan_limit_reached"

/** How the server's messages about the limit begin, which is how a failed photo tells the limit from any other failure. */
const val DAILY_LIMIT_MESSAGE = "Daily scan limit reached"

val ScanAllowanceDto.isUnlimited: Boolean get() = unlimited || dailyLimit == null

/** The scans left, as of what the server last said. Null when unlimited. */
val ScanAllowanceDto.scansLeft: Int?
    get() = if (isUnlimited) null else (remaining ?: ((dailyLimit ?: 0) - used)).coerceAtLeast(0)

/** When the day starts over. */
fun ScanAllowanceDto.resetInstant(now: Instant): Instant? =
    parseServerInstant(resetsAt) ?: resetsInSeconds?.let { now.plusSeconds(it) }

/**
 * True when there is no scan left, as of [now]. Once the reset has passed the server counts from zero again, whatever was last
 * heard, so a notice that was true last night does not hold a user back this morning.
 */
fun ScanAllowanceDto.isUsedUp(now: Instant): Boolean {
    if (isUnlimited || scansLeft != 0) return false
    val reset = parseServerInstant(resetsAt)
    return reset == null || now.isBefore(reset)
}

/** "23 of 100 scans used today", or "Unlimited scans" when there is no limit. */
fun ScanAllowanceDto.usageText(): String =
    if (isUnlimited) "Unlimited scans" else "$used of $dailyLimit scans used today"

/**
 * When scans start again: "Scans reset at midnight (in 5 h 12 min)", "Scans reset at 6:00 PM (in 3 h)" or "Scans reset
 * tomorrow at 6:00 AM (in 9 h)", in the phone's own time zone and format. Null when the server did not say.
 */
fun ScanAllowanceDto.resetText(now: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String? {
    val reset = resetInstant(now) ?: return null
    val local = reset.atZone(zone)
    val today = now.atZone(zone).toLocalDate()
    val clock = if (local.toLocalTime() == LocalTime.MIDNIGHT) {
        "midnight"
    } else {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(local)
    }
    val when_ = when {
        local.toLocalTime() == LocalTime.MIDNIGHT -> "at $clock"
        local.toLocalDate() == today -> "at $clock"
        local.toLocalDate() == today.plusDays(1) -> "tomorrow at $clock"
        else -> "on ${DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(local)} at $clock"
    }
    return "Scans reset $when_ (${untilText(Duration.between(now, reset))})"
}

/** "in 5 h 12 min", "in 42 min", "in less than a minute", "now". */
fun untilText(left: Duration): String {
    val minutes = left.toMinutes()
    return when {
        left.isNegative || left.isZero -> "now"
        minutes < 1 -> "in less than a minute"
        minutes < 60 -> "in $minutes min"
        minutes % 60 == 0L -> "in ${minutes / 60} h"
        else -> "in ${minutes / 60} h ${minutes % 60} min"
    }
}

/**
 * The allowance inside a 429 from the server's daily scan limit, or null when this error is anything else, the rate limiter's
 * 429 included. The body is `{"detail": "...", "code": "scan_limit_reached", "daily_limit": 100, "used": 100, ...}`.
 */
fun HttpException.scanLimitAllowance(): ScanAllowanceDto? {
    if (code() != 429) return null
    val body = runCatching { response()?.errorBody()?.source()?.peek()?.readUtf8().orEmpty() }.getOrNull().orEmpty()
    return scanLimitAllowanceFromBody(body)
}

/** [scanLimitAllowance] for a body that is already text. */
fun scanLimitAllowanceFromBody(body: String): ScanAllowanceDto? {
    if (body.isBlank()) return null
    val root = runCatching { AppJson.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
    if ((root["code"] as? JsonPrimitive)?.contentOrNull != SCAN_LIMIT_CODE) return null
    return runCatching { AppJson.decodeFromJsonElement(ScanAllowanceDto.serializer(), root) }.getOrNull()
}

/** True for a photo whose failure was the daily limit, so it is not described as a card the scanner could not read. */
fun isDailyLimitMessage(text: String?): Boolean = text?.startsWith(DAILY_LIMIT_MESSAGE) == true
