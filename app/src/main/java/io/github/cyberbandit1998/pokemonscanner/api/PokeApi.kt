package io.github.cyberbandit1998.pokemonscanner.api

import kotlinx.serialization.json.JsonElement
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.*

interface PokeApi {
    @FormUrlEncoded
    @POST("api/auth/login")
    suspend fun login(
        @Field("username") username: String,
        @Field("password") password: String
    ): LoginResponse

    @GET("api/auth/me")
    suspend fun me(): JsonElement

    @GET("api/collection/")
    suspend fun collection(): JsonElement

    @GET("api/collection/stats/summary")
    suspend fun collectionStats(): JsonElement

    @GET("api/analytics/investment-tracker")
    suspend fun investmentTracker(
        @Query("period") period: String = "1m",
        @Query("price_field") priceField: String = "price_trend"
    ): List<PortfolioSnapshot>

    @Multipart
    @POST("api/cards/recognize")
    suspend fun recognize(
        @Part file: MultipartBody.Part
    ): ScanResult

    @POST("api/collection/")
    suspend fun addToCollection(
        @Body request: AddCollectionRequest
    ): CollectionItem

    @GET("api/collection/{id}/photo")
    @Streaming
    suspend fun collectionPhoto(
        @Path("id") id: Int
    ): okhttp3.ResponseBody
}
