package app.cardpulse.android.api

import app.cardpulse.android.core.NoServerException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** Retrofit needs a base URL when it is built; the user's real server address is swapped in per request. */
const val PLACEHOLDER_HOST = "pokecollector.invalid"

/** The server address and sign-in token every request uses. Updated as the user signs in or out. */
class SessionHolder {
    @Volatile
    var serverUrl: String = ""

    @Volatile
    var token: String? = null
}

object HttpClientFactory {
    private const val THIRTY_DAYS = 30 * 24 * 3600
    private const val TEN_MINUTES = 10 * 60

    /**
     * One client for the whole app (API calls and Coil's image loads share its connections).
     * [onUnauthorized] is called when the server rejects a token the app sent, i.e. it expired.
     */
    fun create(session: SessionHolder, debug: Boolean, onUnauthorized: () -> Unit): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // Photo uploads can be tens of megabytes on a slow connection.
            .writeTimeout(120, TimeUnit.SECONDS)
            .addInterceptor(ServerInterceptor(session))
            .addInterceptor(SessionExpiryInterceptor(onUnauthorized))
            .addInterceptor(ImageCacheInterceptor())
        if (debug) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                    redactHeader("Authorization")
                },
            )
        }
        return builder.build()
    }

    fun retrofit(client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://$PLACEHOLDER_HOST/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json; charset=UTF-8".toMediaType()))
            .build()

    /**
     * Points API calls at the user's server and attaches the sign-in token, but only to requests
     * that go to that server. Image loads from other hosts (for example a card-art CDN) never carry it.
     */
    private class ServerInterceptor(private val session: SessionHolder) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val base = session.serverUrl.toHttpUrlOrNull()

            val target: HttpUrl = if (request.url.host == PLACEHOLDER_HOST) {
                if (base == null) throw NoServerException()
                base.newBuilder()
                    .encodedPath(base.encodedPath.trimEnd('/') + request.url.encodedPath)
                    .encodedQuery(request.url.encodedQuery)
                    .build()
            } else {
                request.url
            }

            val builder = request.newBuilder().url(target)
            val token = session.token
            val goesToServer = base != null &&
                target.scheme == base.scheme &&
                target.host == base.host &&
                target.port == base.port
            // The login call must never carry a stale token.
            if (goesToServer && !token.isNullOrBlank() && !target.encodedPath.endsWith("/api/auth/login")) {
                builder.header("Authorization", "Bearer $token")
            }
            return chain.proceed(builder.build())
        }
    }

    private class SessionExpiryInterceptor(private val onUnauthorized: () -> Unit) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            if (response.code == 401 && request.header("Authorization") != null) onUnauthorized()
            return response
        }
    }

    /**
     * Card artwork never changes for a given id, but the server only allows a day of caching, and its
     * private photo endpoint forbids caching entirely. Keep artwork for a month and private photos for
     * a few minutes on this device so scrolling the collection doesn't re-download everything.
     */
    private class ImageCacheInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            if (request.method != "GET" || !response.isSuccessful) return response
            // A redirect means the server answered with its placeholder card back; don't pin that.
            if (response.priorResponse != null) return response

            val path = request.url.encodedPath
            val seconds = when {
                path.contains("/api/images/") -> THIRTY_DAYS
                path.contains("/api/collection/") && path.endsWith("/photo") -> TEN_MINUTES
                else -> return response
            }
            return response.newBuilder()
                .header("Cache-Control", "private, max-age=$seconds")
                .removeHeader("Pragma")
                .removeHeader("Vary")
                .build()
        }
    }
}
