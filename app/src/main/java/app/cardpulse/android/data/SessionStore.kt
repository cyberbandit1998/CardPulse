package app.cardpulse.android.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.cardpulse.android.core.ThemeMode
import app.cardpulse.android.core.WishlistPriorities
import app.cardpulse.android.core.WishlistPriority
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore("cardpulse_settings")

data class StoredSession(
    /** Empty until the user has entered a server address. */
    val serverUrl: String = "",
    val token: String? = null,
    val username: String? = null,
    /** The server is in single-user mode: there is no sign-in and no token. */
    val noLogin: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.DEFAULT,
)

/** Choices that carry over between scans and typed-in cards. */
data class ScanPrefs(
    /** Keep my photo with every card I add, not only cards that have no official artwork. */
    val savePhotos: Boolean = false,
    val condition: String = "NM",
    val variant: String = "Normal",
    /** After a card new to the collection is added, ask the server to look up prices and show them. */
    val lookUpPrices: Boolean = true,
)

/** On-device settings. The sign-in token is encrypted with a Keystore key before it is written. */
class SessionStore(private val context: Context, private val cipher: TokenCipher) {
    private object Keys {
        val server = stringPreferencesKey("server_url")
        val token = stringPreferencesKey("token_encrypted")
        val username = stringPreferencesKey("username")
        val noLogin = booleanPreferencesKey("no_login")
        val savePhotos = booleanPreferencesKey("scan_save_photos")
        val condition = stringPreferencesKey("scan_condition")
        val variant = stringPreferencesKey("scan_variant")
        val lookUpPrices = booleanPreferencesKey("look_up_prices")
        val themeMode = stringPreferencesKey("theme_mode")
        val wishlistPriorities = stringPreferencesKey("wishlist_priorities")
    }

    suspend fun load(): StoredSession {
        val prefs = context.dataStore.data.first()
        return StoredSession(
            serverUrl = prefs[Keys.server].orEmpty(),
            token = prefs[Keys.token]?.let(cipher::decrypt),
            username = prefs[Keys.username],
            noLogin = prefs[Keys.noLogin] == true,
            themeMode = ThemeMode.fromKey(prefs[Keys.themeMode]),
        )
    }

    /** Light, dark or the phone's setting. Kept when signing out: it is about this phone, not the account. */
    suspend fun saveThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.themeMode] = mode.key }
    }

    /**
     * How much each wishlist card is wanted, by card id, for the account that is signed in. PokéCollector's wishlist has no
     * priority, so these live on this phone only; another account's (or server's) levels are not returned.
     */
    suspend fun wishlistPriorities(): Map<String, WishlistPriority> {
        val prefs = context.dataStore.data.first()
        return WishlistPriorities.decode(wishlistAccount(prefs), prefs[Keys.wishlistPriorities])
    }

    suspend fun saveWishlistPriorities(levels: Map<String, WishlistPriority>) {
        context.dataStore.edit { prefs ->
            if (levels.isEmpty()) {
                prefs.remove(Keys.wishlistPriorities)
            } else {
                prefs[Keys.wishlistPriorities] = WishlistPriorities.encode(wishlistAccount(prefs), levels)
            }
        }
    }

    /** Whose priorities are kept: the server and the user the phone is signed in as. */
    private fun wishlistAccount(prefs: Preferences): String =
        WishlistPriorities.accountKey(prefs[Keys.server].orEmpty(), prefs[Keys.username])

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
            savePhotos = prefs[Keys.savePhotos] ?: defaults.savePhotos,
            condition = prefs[Keys.condition] ?: defaults.condition,
            variant = prefs[Keys.variant] ?: defaults.variant,
            lookUpPrices = prefs[Keys.lookUpPrices] ?: defaults.lookUpPrices,
        )
    }

    suspend fun saveScanPrefs(scan: ScanPrefs) {
        context.dataStore.edit { prefs ->
            prefs[Keys.savePhotos] = scan.savePhotos
            prefs[Keys.condition] = scan.condition
            prefs[Keys.variant] = scan.variant
            prefs[Keys.lookUpPrices] = scan.lookUpPrices
        }
    }
}
