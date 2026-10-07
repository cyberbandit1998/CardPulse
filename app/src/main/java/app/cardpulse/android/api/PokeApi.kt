package app.cardpulse.android.api

import app.cardpulse.android.core.AddToCollectionRequest
import app.cardpulse.android.core.AuthModeDto
import app.cardpulse.android.core.CardDto
import app.cardpulse.android.core.CardSearchDto
import app.cardpulse.android.core.CollectionItemDto
import app.cardpulse.android.core.CollectionQuantityRequest
import app.cardpulse.android.core.CustomCardRequest
import app.cardpulse.android.core.DashboardDto
import app.cardpulse.android.core.ExchangeRateDto
import app.cardpulse.android.core.ForcePasswordRequest
import app.cardpulse.android.core.HealthDto
import app.cardpulse.android.core.LoginResponseDto
import app.cardpulse.android.core.MoverDto
import app.cardpulse.android.core.ResolveAndAddRequest
import app.cardpulse.android.core.ResolveAndAddResponse
import app.cardpulse.android.core.ResolveRequest
import app.cardpulse.android.core.ScanItemDto
import app.cardpulse.android.core.ScanJobDto
import app.cardpulse.android.core.ScanJobListDto
import app.cardpulse.android.core.SetChecklistDto
import app.cardpulse.android.core.SetDto
import app.cardpulse.android.core.SnapshotDto
import app.cardpulse.android.core.SyncStatusDto
import app.cardpulse.android.core.UserDto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * The slice of PokéCollector's API this app uses. Paths and payloads were checked against the
 * server's own routers (see the tests, which decode responses captured from them).
 */
interface PokeApi {
    // --- connection and sign-in -------------------------------------------------------------
    @GET("api/health")
    suspend fun health(): HealthDto

    @GET("api/auth/mode")
    suspend fun authMode(): AuthModeDto

    /** The login endpoint takes form fields, not JSON. */
    @FormUrlEncoded
    @POST("api/auth/login")
    suspend fun login(
        @Field("username") username: String,
        @Field("password") password: String,
    ): LoginResponseDto

    @GET("api/auth/me")
    suspend fun me(): UserDto

    @PUT("api/auth/me/force-password")
    suspend fun forcePassword(@Body body: ForcePasswordRequest): JsonObject

    // --- settings ---------------------------------------------------------------------------
    /** A flat map of string values (currency, price_primary, prefer_own_card_photos, ...). */
    @GET("api/settings/")
    suspend fun settings(): JsonObject

    @GET("api/settings/exchange-rate")
    suspend fun exchangeRate(
        @Query("from") from: String,
        @Query("to") to: String,
    ): ExchangeRateDto

    // --- collection and portfolio -----------------------------------------------------------
    /** The whole collection in one response; the server does not paginate. Decoded item by item. */
    @GET("api/collection/")
    suspend fun collection(): JsonArray

    /** Starts the server's price sync in the background. Admin accounts only (anyone else gets a 403). */
    @POST("api/sync/prices")
    suspend fun startPriceSync()

    @GET("api/sync/status")
    suspend fun syncStatus(): SyncStatusDto

    /** Removes a collection row with every copy it holds. Any success answer will do, so nothing is parsed. */
    @DELETE("api/collection/{id}")
    suspend fun deleteCollectionItem(@Path("id") itemId: Int)

    /** Changes how many copies a row holds. The answer is the row as the server now has it. */
    @PUT("api/collection/{id}")
    suspend fun updateCollectionItem(@Path("id") itemId: Int, @Body body: CollectionQuantityRequest): JsonElement

    /** Adds a card that is already on the server (by its id). The answer is the row, merged with an identical one if there is one. */
    @POST("api/collection/")
    suspend fun addToCollection(@Body body: AddToCollectionRequest): CollectionItemDto

    // --- adding a card by typing ------------------------------------------------------------
    /**
     * Searches the server's catalogue. [query] matches inside the card's name (or is a set code and number such as
     * "OBF 125"); [number] narrows it to one card number, leading zeros ignored; [lang] is a language code or "all".
     */
    @GET("api/cards/search")
    suspend fun searchCards(
        @Query("q") query: String,
        @Query("number") number: String?,
        @Query("lang") lang: String,
        @Query("page") page: Int,
        @Query("page_size") pageSize: Int,
    ): CardSearchDto

    /**
     * Every set the server lists for this user's language, newest first, each with how many different cards of it the
     * user owns (`owned_count`, 0 for a set with none).
     */
    @GET("api/sets/")
    suspend fun sets(): List<SetDto>

    /**
     * Every card the server has for one set, in card-number order, with whether the user owns each. [setId] is the
     * server's own id for the set, such as "sv3_en". The first look at a set the server has no cards for yet can take a
     * moment, as it fetches them.
     */
    @GET("api/sets/{id}/checklist")
    suspend fun setChecklist(@Path("id") setId: String): SetChecklistDto

    /** Makes a card that isn't in the catalogue. It belongs to this user and gets an id starting "custom-". */
    @POST("api/cards/custom")
    suspend fun createCustomCard(@Body body: CustomCardRequest): CardDto

    @GET("api/dashboard/")
    suspend fun dashboard(@Query("price_field") priceField: String): DashboardDto

    /** Note: the server stores a new portfolio snapshot every time this is called. Don't poll it. */
    @GET("api/analytics/investment-tracker")
    suspend fun investmentTracker(
        @Query("period") period: String,
        @Query("price_field") priceField: String,
    ): List<SnapshotDto>

    @GET("api/analytics/top-movers")
    suspend fun topMovers(
        @Query("days") days: Int,
        @Query("price_field") priceField: String,
        @Query("sort_by") sortBy: String,
    ): List<MoverDto>

    @Multipart
    @POST("api/collection/{id}/photo")
    suspend fun uploadCollectionPhoto(
        @Path("id") collectionItemId: Int,
        @Part file: MultipartBody.Part,
    ): JsonObject

    // --- scanning ---------------------------------------------------------------------------
    /** Parts: repeated "files" photos plus an optional "individual_positions" JSON list. */
    @Multipart
    @POST("api/cards/recognize/jobs")
    suspend fun enqueueScan(@Part parts: List<MultipartBody.Part>): ScanJobDto

    @GET("api/cards/recognize/jobs")
    suspend fun scanJobs(): ScanJobListDto

    @GET("api/cards/recognize/jobs/{job}")
    suspend fun scanJob(@Path("job") jobId: Int): ScanJobDto

    @POST("api/cards/recognize/jobs/{job}/items/{item}/resolve-and-add")
    suspend fun resolveAndAdd(
        @Path("job") jobId: Int,
        @Path("item") itemId: Int,
        @Body body: ResolveAndAddRequest,
    ): ResolveAndAddResponse

    @POST("api/cards/recognize/jobs/{job}/items/{item}/resolve")
    suspend fun resolve(
        @Path("job") jobId: Int,
        @Path("item") itemId: Int,
        @Body body: ResolveRequest,
    ): ScanItemDto

    @POST("api/cards/recognize/jobs/{job}/items/{item}/retry")
    suspend fun retry(
        @Path("job") jobId: Int,
        @Path("item") itemId: Int,
    ): ScanItemDto

    /** Removes a scan job, even one that is still being read. Any success answer will do, so nothing is parsed. */
    @DELETE("api/cards/recognize/jobs/{job}")
    suspend fun deleteScanJob(@Path("job") jobId: Int)

    /** The sanitized JPEG of a queued photo. The server deletes it once the item is resolved. */
    @Streaming
    @GET("api/cards/recognize/jobs/{job}/items/{item}/image")
    suspend fun scanItemImage(
        @Path("job") jobId: Int,
        @Path("item") itemId: Int,
    ): ResponseBody
}
