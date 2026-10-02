package io.github.cyberbandit1998.cardpulse.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore("cardpulse_settings")

data class StoredSession(
    /** Empty until the user has entered a server address. */
    val serverUrl: String = "",
    val token: String? = null,
    val username: String? = null,
    /** The server is in single-user mode: there is no sign-in and no token. */
    val noLogin: Boolean = false,
)

/** Choices that carry over between scans. */
data class ScanPrefs(
    /** Read each photo on its own (more accurate) instead of letting the server batch them (saves quota). */
    val individual: Boolean = true,
    /** Keep my photo with every card I add, not only cards that have no official artwork. */
    val savePhotos: Boolean = false,
    val condition: String = "NM",
    val variant: String = "Normal",
)

/** On-device settings. The sign-in token is encrypted with a Keystore key before it is written. */
class SessionStore(private val context: Context, private val cipher: TokenCipher) {
    private object Keys {
        val server = stringPreferencesKey("server_url")
        val token = stringPreferencesKey("token_encrypted")
        val username = stringPreferencesKey("username")
        val noLogin = booleanPreferencesKey("no_login")
        val individual = booleanPreferencesKey("scan_individual")
        val savePhotos = booleanPreferencesKey("scan_save_photos")
        val condition = stringPreferencesKey("scan_condition")
        val variant = stringPreferencesKey("scan_variant")
    }

    suspend fun load(): StoredSession {
        val prefs = context.dataStore.data.first()
        return StoredSession(
            serverUrl = prefs[Keys.server].orEmpty(),
            token = prefs[Keys.token]?.let(cipher::decrypt),
            username = prefs[Keys.username],
            noLogin = prefs[Keys.noLogin] == true,
        )
    }

    /** [serverUrl] must already be normalized with `ServerUrl.normalize`. */
    suspend fun saveServer(serverUrl: String) {
        context.dataStore.edit { it[Keys.server] = serverUrl }
    }

    /** Pass a null [token] to sign out. */
    suspend fun saveSignIn(token: String?, username: String?, noLogin: Boolean) {
        context.dataStore.edit { prefs ->
            if (token == null) prefs.remove(Keys.token) else prefs[Keys.token] = cipher.encrypt(token)
            if (username == null) prefs.remove(Keys.username) else prefs[Keys.username] = username
            prefs[Keys.noLogin] = noLogin
        }
    }

    suspend fun scanPrefs(): ScanPrefs {
        val prefs = context.dataStore.data.first()
        val defaults = ScanPrefs()
        return ScanPrefs(
            individual = prefs[Keys.individual] ?: defaults.individual,
            savePhotos = prefs[Keys.savePhotos] ?: defaults.savePhotos,
            condition = prefs[Keys.condition] ?: defaults.condition,
            variant = prefs[Keys.variant] ?: defaults.variant,
        )
    }

    suspend fun saveScanPrefs(scan: ScanPrefs) {
        context.dataStore.edit { prefs ->
            prefs[Keys.individual] = scan.individual
            prefs[Keys.savePhotos] = scan.savePhotos
            prefs[Keys.condition] = scan.condition
            prefs[Keys.variant] = scan.variant
        }
    }
}
