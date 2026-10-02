package io.github.cyberbandit1998.pokemonscanner.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore("pokecollector_settings")

class SessionStore(private val context: Context) {
    private val tokenKey = stringPreferencesKey("token")
    private val serverKey = stringPreferencesKey("server_url")

    suspend fun token(): String? = context.dataStore.data.first()[tokenKey]

    /** The saved server address, or an empty string until the user has entered one. */
    suspend fun serverUrl(): String = context.dataStore.data.first()[serverKey].orEmpty()

    /** [serverUrl] must already be normalized with [ServerUrl.normalize]. */
    suspend fun saveSession(token: String, serverUrl: String) {
        context.dataStore.edit {
            it[tokenKey] = token
            it[serverKey] = serverUrl
        }
    }

    suspend fun saveServerUrl(serverUrl: String) {
        context.dataStore.edit { it[serverKey] = serverUrl }
    }

    suspend fun clearToken() {
        context.dataStore.edit { it.remove(tokenKey) }
    }
}
