package io.github.cyberbandit1998.cardpulse.api

import io.github.cyberbandit1998.cardpulse.core.AuthModeDto
import io.github.cyberbandit1998.cardpulse.core.DashboardDto
import io.github.cyberbandit1998.cardpulse.core.ExchangeRateDto
import io.github.cyberbandit1998.cardpulse.core.ForcePasswordRequest
import io.github.cyberbandit1998.cardpulse.core.HealthDto
import io.github.cyberbandit1998.cardpulse.core.LoginResponseDto
import io.github.cyberbandit1998.cardpulse.core.MoverDto
import io.github.cyberbandit1998.cardpulse.core.ResolveAndAddRequest
import io.github.cyberbandit1998.cardpulse.core.ResolveAndAddResponse
import io.github.cyberbandit1998.cardpulse.core.ResolveRequest
import io.github.cyberbandit1998.cardpulse.core.ScanItemDto
import io.github.cyberbandit1998.cardpulse.core.ScanJobDto
import io.github.cyberbandit1998.cardpulse.core.ScanJobListDto
import io.github.cyberbandit1998.cardpulse.core.SnapshotDto
import io.github.cyberbandit1998.cardpulse.core.UserDto
import kotlinx.serialization.json.JsonArray
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

    @DELETE("api/cards/recognize/jobs/{job}")
    suspend fun deleteScanJob(@Path("job") jobId: Int): JsonObject

    /** The sanitized JPEG of a queued photo. The server deletes it once the item is resolved. */
    @Streaming
    @GET("api/cards/recognize/jobs/{job}/items/{item}/image")
    suspend fun scanItemImage(
        @Path("job") jobId: Int,
        @Path("item") itemId: Int,
    ): ResponseBody
}
