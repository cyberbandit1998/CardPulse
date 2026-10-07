package app.cardpulse.android.data

import app.cardpulse.android.api.PokeApi
import app.cardpulse.android.api.SessionHolder
import app.cardpulse.android.core.AddToCollectionRequest
import app.cardpulse.android.core.AppJson
import app.cardpulse.android.core.CardDto
import app.cardpulse.android.core.CardSearchDto
import app.cardpulse.android.core.CollectionItemDto
import app.cardpulse.android.core.CollectionQuantityRequest
import app.cardpulse.android.core.CustomCardRequest
import app.cardpulse.android.core.DashboardDto
import app.cardpulse.android.core.DisplayPrefs
import app.cardpulse.android.core.ForcePasswordRequest
import app.cardpulse.android.core.LoginResponseDto
import app.cardpulse.android.core.ManualAddBackend
import app.cardpulse.android.core.MoverDto
import app.cardpulse.android.core.NotPokeCollectorException
import app.cardpulse.android.core.PortfolioRange
import app.cardpulse.android.core.PriceBackend
import app.cardpulse.android.core.ResolveAndAddRequest
import app.cardpulse.android.core.ResolveAndAddResponse
import app.cardpulse.android.core.ResolveRequest
import app.cardpulse.android.core.ScanBackend
import app.cardpulse.android.core.ScanItemDto
import app.cardpulse.android.core.ScanJobDto
import app.cardpulse.android.core.SetChecklistDto
import app.cardpulse.android.core.SetDto
import app.cardpulse.android.core.SnapshotDto
import app.cardpulse.android.core.SyncStatusDto
import app.cardpulse.android.core.WishlistAddRequest
import app.cardpulse.android.core.WishlistItemDto
import app.cardpulse.android.core.attempt
import app.cardpulse.android.core.displayPrefsFrom
import app.cardpulse.android.core.wishlistQuantityBody
import app.cardpulse.android.core.wishlistTargetBody
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

data class WishlistResult(
    val items: List<WishlistItemDto>,
    /** Rows the server sent that this app could not read. Shown to the user instead of silently dropped. */
    val unreadable: Int,
)

/** All server calls the UI needs, with the small amount of glue logic between them. No Android types. */
class Repository(
    private val api: PokeApi,
    private val session: SessionHolder,
    private val json: Json = AppJson,
) : ScanBackend, PriceBackend, ManualAddBackend {
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

    override suspend fun startPriceSync() {
        api.startPriceSync()
    }

    override suspend fun syncStatus(): SyncStatusDto = api.syncStatus()

    /** Removes the whole row: every copy of this exact card. The server refuses for cards in a deck or product. */
    suspend fun removeFromCollection(itemId: Int) {
        api.deleteCollectionItem(itemId)
    }

    /** Sets how many copies a row holds. Returns the row as the server now has it, or null if that reply can't be read. */
    suspend fun setCollectionQuantity(itemId: Int, quantity: Int): CollectionItemDto? {
        val updated = api.updateCollectionItem(itemId, CollectionQuantityRequest(quantity))
        return runCatching { json.decodeFromJsonElement(CollectionItemDto.serializer(), updated) }.getOrNull()
    }

    // --- wishlist ------------------------------------------------------------------------------

    suspend fun loadWishlist(): WishlistResult {
        var unreadable = 0
        val items = api.wishlist().mapNotNull { element ->
            try {
                json.decodeFromJsonElement(WishlistItemDto.serializer(), element)
            } catch (_: IllegalArgumentException) {
                unreadable++
                null
            }
        }
        return WishlistResult(items, unreadable)
    }

    /**
     * Puts a card on the wishlist and returns its row. The caller must only add a card it believes is not listed: the server
     * answers a listed card by raising the quantity wanted. If it does (the wishlist was changed elsewhere since it was last
     * loaded) the quantity is put back as it was, so adding from here never changes how many copies are wanted.
     */
    suspend fun addToWishlist(cardId: String): WishlistItemDto {
        val added = api.addToWishlist(WishlistAddRequest(cardId))
        if (added.quantity <= 1) return added
        return attempt { api.updateWishlistItem(added.id, wishlistQuantityBody(added.quantity - 1)) }.getOrDefault(added)
    }

    /** Sets the target price (euros), or with null takes it away. Returns the row as the server now has it. */
    suspend fun setWishlistTarget(itemId: Int, targetEur: Double?): WishlistItemDto =
        api.updateWishlistItem(itemId, wishlistTargetBody(targetEur))

    suspend fun removeFromWishlist(itemId: Int) {
        api.removeFromWishlist(itemId)
    }

    // --- cards typed in -------------------------------------------------------------------------

    /** Every language the server has, so a card can be found whichever language its name is typed in. */
    override suspend fun searchCards(name: String, number: String?, pageSize: Int): CardSearchDto =
        api.searchCards(name, number, lang = "all", page = 1, pageSize = pageSize)

    /** Every set the server lists, each with how many of its cards are owned: the catalogue the Sets tab browses. */
    override suspend fun sets(): List<SetDto> = api.sets()

    /** One set's cards with ownership as the server counts it; the Sets tab opens it when a set is tapped. */
    suspend fun loadChecklist(setId: String): SetChecklistDto = api.setChecklist(setId)

    override suspend fun createCustomCard(request: CustomCardRequest): CardDto = api.createCustomCard(request)

    override suspend fun addToCollection(request: AddToCollectionRequest): CollectionItemDto = api.addToCollection(request)

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

    /** One photo as its own job, so the server starts reading it straight away (rapid scanning). */
    override suspend fun enqueue(photo: File): ScanJobDto = enqueueScan(listOf(photo), individual = true)

    override suspend fun scanJobs(): List<ScanJobDto> = api.scanJobs().jobs

    override suspend fun scanJob(jobId: Int): ScanJobDto = api.scanJob(jobId)

    override suspend fun resolveAndAdd(jobId: Int, itemId: Int, request: ResolveAndAddRequest): ResolveAndAddResponse =
        api.resolveAndAdd(jobId, itemId, request)

    /** Marks a photo handled without adding anything. */
    override suspend fun skip(jobId: Int, itemId: Int): ScanItemDto =
        api.resolve(jobId, itemId, ResolveRequest(cardId = null))

    override suspend fun retry(jobId: Int, itemId: Int): ScanItemDto = api.retry(jobId, itemId)

    override suspend fun deleteScanJob(jobId: Int) {
        api.deleteScanJob(jobId)
    }

    /** The server's sanitized copy of a queued photo. It is deleted once the item is resolved. */
    override suspend fun scanPhotoBytes(jobId: Int, itemId: Int): ByteArray =
        api.scanItemImage(jobId, itemId).use { it.bytes() }

    override suspend fun uploadOwnerPhoto(collectionItemId: Int, jpeg: ByteArray) {
        val part = MultipartBody.Part.createFormData("file", "photo.jpg", jpeg.toRequestBody(JPEG))
        api.uploadCollectionPhoto(collectionItemId, part)
    }

    private companion object {
        const val EXPECTED_SERVICE = "pokemon-tcg-collection"
        val JPEG = "image/jpeg".toMediaType()
    }
}
