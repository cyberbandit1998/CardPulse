package io.github.cyberbandit1998.pokemonscanner.api

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

object ApiClient {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun create(baseUrl: String, token: String? = null): PokeApi {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                if (!token.isNullOrBlank()) {
                    builder.header("Authorization", "Bearer $token")
                }
                chain.proceed(builder.build())
            }
            .addInterceptor(logging)
            .build()

        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val contentType = "application/json".toMediaType()

        return Retrofit.Builder()
            .baseUrl(normalized)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(PokeApi::class.java)
    }
}
