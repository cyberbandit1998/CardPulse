package io.github.cyberbandit1998.pokemonscanner.data

import android.content.Context
import android.net.Uri
import io.github.cyberbandit1998.pokemonscanner.api.*
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

class AppRepository(
    private val context: Context,
    private val store: SessionStore
) {
    suspend fun login(serverUrl: String, username: String, password: String) {
        val api = ApiClient.create(serverUrl)
        val result = api.login(username, password)
        store.saveSession(result.accessToken, serverUrl)
    }

    suspend fun logout() = store.clearToken()

    suspend fun serverUrl() = store.serverUrl()

    suspend fun isLoggedIn() = !store.token().isNullOrBlank()

    private suspend fun api(): PokeApi =
        ApiClient.create(store.serverUrl(), store.token())

    suspend fun collection(): List<CollectionItem> {
        val raw = api().collection()
        val array = when (raw) {
            is JsonArray -> raw
            is JsonObject -> {
                (raw["items"] ?: raw["collection"] ?: raw["results"])?.jsonArray ?: JsonArray(emptyList())
            }
            else -> JsonArray(emptyList())
        }
        return array.mapNotNull { element ->
            runCatching {
                ApiClient.json.decodeFromJsonElement(CollectionItem.serializer(), element)
            }.getOrNull()
        }
    }

    suspend fun portfolio(): List<PortfolioSnapshot> =
        api().investmentTracker()

    suspend fun recognize(uri: Uri): ScanResult {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "image/jpeg"
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Unable to read captured photo")
        val body = bytes.toRequestBody(mime.toMediaTypeOrNull())
        val part = MultipartBody.Part.createFormData("file", "scan.jpg", body)
        return api().recognize(part)
    }

    suspend fun addMatch(match: ScanMatch): CollectionItem =
        api().addToCollection(
            AddCollectionRequest(
                cardId = match.id,
                quantity = 1,
                condition = "NM",
                variant = null,
                lang = match.lang ?: "en"
            )
        )
}
