package io.github.cyberbandit1998.pokemonscanner.data

import io.github.cyberbandit1998.pokemonscanner.api.PokeApi
import io.github.cyberbandit1998.pokemonscanner.api.SessionHolder
import io.github.cyberbandit1998.pokemonscanner.core.AppJson
import io.github.cyberbandit1998.pokemonscanner.core.CollectionItemDto
import io.github.cyberbandit1998.pokemonscanner.core.DashboardDto
import io.github.cyberbandit1998.pokemonscanner.core.DisplayPrefs
import io.github.cyberbandit1998.pokemonscanner.core.ForcePasswordRequest
import io.github.cyberbandit1998.pokemonscanner.core.LoginResponseDto
import io.github.cyberbandit1998.pokemonscanner.core.MoverDto
import io.github.cyberbandit1998.pokemonscanner.core.NotPokeCollectorException
import io.github.cyberbandit1998.pokemonscanner.core.PortfolioRange
import io.github.cyberbandit1998.pokemonscanner.core.ResolveAndAddRequest
import io.github.cyberbandit1998.pokemonscanner.core.ResolveAndAddResponse
import io.github.cyberbandit1998.pokemonscanner.core.ResolveRequest
import io.github.cyberbandit1998.pokemonscanner.core.ScanItemDto
import io.github.cyberbandit1998.pokemonscanner.core.ScanJobDto
import io.github.cyberbandit1998.pokemonscanner.core.SnapshotDto
import io.github.cyberbandit1998.pokemonscanner.core.attempt
import io.github.cyberbandit1998.pokemonscanner.core.displayPrefsFrom
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/** What the server told us about itself before anyone signed in. */
data class ServerCheck(val multiUser: Boolean)

data class CollectionResult(
    val items: List<CollectionItemDto>,
    /** Rows the server sent that this app could not read. Shown to the user instead of silently dropped. */
    val unreadable: Int,
)

/** All server calls the UI needs, with the small amount of glue logic between them. No Android types. */
class Repository(
    private val api: PokeApi,
    private val session: SessionHolder,
    private val json: Json = AppJson,
) {
    // --- connection and sign-in ---------------------------------------------------------------

    /** Points the app at [serverUrl] and confirms it really is a PokéCollector server. */
    suspend fun checkServer(serverUrl: String): ServerCheck {
        session.serverUrl = serverUrl
        val health = api.health()
        if (health.status != "ok" || health.service != EXPECTED_SERVICE) {
            throw NotPokeCollectorException("That address answered, but it doesn't look like a PokéCollector server.")
        }
        return ServerCheck(multiUser = api.authMode().multiUser)
    }

    suspend fun login(username: String, password: String): LoginResponseDto {
        // A token left over from an earlier session must not ride along with the login request.
        session.token = null
        val response = api.login(username.trim(), password)
        session.token = response.accessToken
        return response
    }

    suspend fun changeRequiredPassword(newPassword: String) {
        api.forcePassword(ForcePasswordRequest(newPassword))
    }

    // --- preferences ---------------------------------------------------------------------------

    suspend fun loadPrefs(): DisplayPrefs {
        val settings = api.settings()
        val wanted = displayPrefsFrom(settings)
        if (wanted.currency == "EUR") return wanted
        // The server's prices are euros. If the rate can't be fetched, show euros rather than mislabel them.
        val rate = attempt { api.exchangeRate("EUR", wanted.currency).rate }.getOrNull()
        return if (rate != null && rate > 0.0) displayPrefsFrom(settings, rate) else displayPrefsFrom(settings, 1.0, "EUR")
    }

    // --- collection and portfolio --------------------------------------------------------------

    suspend fun loadCollection(): CollectionResult {
        var unreadable = 0
        val items = api.collection().mapNotNull { element ->
            try {
                json.decodeFromJsonElement(CollectionItemDto.serializer(), element)
            } catch (_: IllegalArgumentException) {
                unreadable++
                null
            }
        }
        return CollectionResult(items, unreadable)
    }

    suspend fun loadDashboard(priceField: String): DashboardDto = api.dashboard(priceField)

    suspend fun loadHistory(range: PortfolioRange, priceField: String): List<SnapshotDto> =
        api.investmentTracker(range.apiPeriod, priceField)

    suspend fun loadMovers(priceField: String): List<MoverDto> =
        api.topMovers(days = 7, priceField = priceField, sortBy = "percentage")

    // --- scanning ------------------------------------------------------------------------------

    /**
     * Uploads photos as one job. With [individual] every photo is read on its own (more accurate, more
     * provider calls); otherwise the server may combine two to four photos into one request to save quota.
     */
    suspend fun enqueueScan(photos: List<File>, individual: Boolean): ScanJobDto {
        val parts = ArrayList<MultipartBody.Part>(photos.size + 1)
        photos.forEach { photo ->
            parts += MultipartBody.Part.createFormData("files", photo.name, photo.asRequestBody(JPEG))
        }
        val positions = if (individual) photos.indices.joinToString(separator = ",", prefix = "[", postfix = "]") else "[]"
        parts += MultipartBody.Part.createFormData("individual_positions", positions)
        return api.enqueueScan(parts)
    }

    suspend fun scanJobs(): List<ScanJobDto> = api.scanJobs().jobs

    suspend fun scanJob(jobId: Int): ScanJobDto = api.scanJob(jobId)

    suspend fun resolveAndAdd(jobId: Int, itemId: Int, request: ResolveAndAddRequest): ResolveAndAddResponse =
        api.resolveAndAdd(jobId, itemId, request)

    /** Marks a photo handled without adding anything. */
    suspend fun skip(jobId: Int, itemId: Int): ScanItemDto = api.resolve(jobId, itemId, ResolveRequest(cardId = null))

    suspend fun retry(jobId: Int, itemId: Int): ScanItemDto = api.retry(jobId, itemId)

    suspend fun deleteScanJob(jobId: Int) {
        api.deleteScanJob(jobId)
    }

    /** The server's sanitized copy of a queued photo. It is deleted once the item is resolved. */
    suspend fun scanPhotoBytes(jobId: Int, itemId: Int): ByteArray =
        api.scanItemImage(jobId, itemId).use { it.bytes() }

    suspend fun uploadOwnerPhoto(collectionItemId: Int, jpeg: ByteArray) {
        val part = MultipartBody.Part.createFormData("file", "photo.jpg", jpeg.toRequestBody(JPEG))
        api.uploadCollectionPhoto(collectionItemId, part)
    }

    private companion object {
        const val EXPECTED_SERVICE = "pokemon-tcg-collection"
        val JPEG = "image/jpeg".toMediaType()
    }
}
