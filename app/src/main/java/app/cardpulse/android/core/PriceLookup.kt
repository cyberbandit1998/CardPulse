package app.cardpulse.android.core

import kotlinx.coroutines.delay
import retrofit2.HttpException

/** What the price lookup needs from the server. The real one is `Repository`; tests use a fake. */
interface PriceBackend {
    /** Starts a price sync that runs in the background on the server. Only admin accounts may. */
    suspend fun startPriceSync()

    suspend fun syncStatus(): SyncStatusDto
}

enum class PriceLookup {
    /** The server finished a price sync; whatever it found is there to be shown. */
    DONE,

    /** This account isn't an admin on the server, so it can't ask for a price sync. */
    NOT_ALLOWED,

    /** The server didn't report the sync as finished in time. */
    GAVE_UP,
}

/**
 * Asks the server to look up prices and waits for that to finish, so what is shown next includes them. The server
 * decides which cards get looked up (new ones without a price first), so one call serves a whole batch of new cards.
 *
 * A sync that is already running may have begun before the new card was added, so it is waited out first and a fresh
 * one started after it.
 */
suspend fun lookUpPrices(
    backend: PriceBackend,
    pollMs: Long = 2_000,
    giveUpAfterMs: Long = 90_000,
): PriceLookup {
    val polls = (giveUpAfterMs / pollMs).toInt().coerceAtLeast(1)

    var status = backend.syncStatus()
    var waited = 0
    while (status.isPriceSyncRunning) {
        if (waited++ >= polls) return PriceLookup.GAVE_UP
        delay(pollMs)
        status = backend.syncStatus()
    }
    val before = status.lastPriceSync?.startedAt

    try {
        backend.startPriceSync()
    } catch (error: HttpException) {
        if (error.code() == 403) return PriceLookup.NOT_ALLOWED
        throw error
    }

    // The sync starts in the background, so wait for a run that wasn't there before, and for it to end.
    repeat(polls) {
        delay(pollMs)
        val now = backend.syncStatus()
        if (!now.isPriceSyncRunning && now.lastPriceSync?.startedAt != before) return PriceLookup.DONE
    }
    return PriceLookup.GAVE_UP
}
