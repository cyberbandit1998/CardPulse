package app.cardpulse.android.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class NoServerException : IOException("No server address is set.")

class NotPokeCollectorException(message: String) : IOException(message)

/** A plain-language explanation of a failure, suitable for showing to the user. */
fun Throwable.userMessage(): String = when (this) {
    is HttpException -> httpMessage()
    is NoServerException -> "Enter your PokéCollector server's address first."
    is NotPokeCollectorException -> message ?: "That doesn't look like a PokéCollector server."
    is UnknownHostException -> "Can't find that server. Check the address and your connection."
    is SSLException -> "The secure (HTTPS) connection failed. Check the server's certificate."
    is SocketTimeoutException -> "The server took too long to answer. Try again."
    is ConnectException -> "Can't connect to the server. Check the address and that it is running."
    is IOException -> message?.takeIf { it.isNotBlank() } ?: "Network error."
    is SerializationException, is IllegalArgumentException ->
        "The server replied with something this app doesn't understand. Is the address a PokéCollector server?"
    else -> message?.takeIf { it.isNotBlank() } ?: "Something went wrong."
}

private fun HttpException.httpMessage(): String {
    // peek(): reading the body normally uses it up, and the same error is often described more than once.
    val detail = runCatching {
        errorDetailFromBody(response()?.errorBody()?.source()?.peek()?.readUtf8().orEmpty())
    }.getOrNull()
    return when (val status = code()) {
        401 -> detail ?: "Your session has expired. Sign in again."
        403 -> detail ?: "You don't have permission to do that."
        404 -> detail ?: "That wasn't found on the server."
        409 -> detail ?: "That conflicts with the server's current state."
        413 -> "That upload is too large for the server."
        422 -> detail ?: "The server rejected that request."
        // The daily scan limit is a 429 too, but waiting a minute does not help: the server's own words say when it starts over.
        429 -> if (scanLimitAllowance() != null) (detail ?: DAILY_LIMIT_MESSAGE) else "Too many requests. Wait a minute and try again."
        502, 503, 504, 521, 522, 523, 524 ->
            "The server is unreachable or too slow (HTTP $status). If you use a tunnel or proxy, check that it is running."
        in 500..599 -> detail ?: "The server had a problem (HTTP $status)."
        else -> detail ?: "Unexpected response from the server (HTTP $status)."
    }
}

/** FastAPI errors are `{"detail": "text"}` or, for validation, `{"detail": [{"msg": "..."}]}`. */
fun errorDetailFromBody(body: String): String? {
    if (body.isBlank()) return null
    val root = runCatching { AppJson.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
    return when (val detail = root["detail"]) {
        is JsonPrimitive -> detail.contentOrNull?.takeIf { it.isNotBlank() }
        is JsonArray -> ((detail.firstOrNull() as? JsonObject)?.get("msg") as? JsonPrimitive)?.contentOrNull
        else -> null
    }
}
