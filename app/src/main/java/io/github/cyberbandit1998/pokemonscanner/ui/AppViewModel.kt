package io.github.cyberbandit1998.pokemonscanner.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.cyberbandit1998.pokemonscanner.api.CollectionItem
import io.github.cyberbandit1998.pokemonscanner.api.PortfolioSnapshot
import io.github.cyberbandit1998.pokemonscanner.api.ScanMatch
import io.github.cyberbandit1998.pokemonscanner.data.AppRepository
import io.github.cyberbandit1998.pokemonscanner.data.ServerUrl
import io.github.cyberbandit1998.pokemonscanner.data.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AppState(
    val booting: Boolean = true,
    val loggedIn: Boolean = false,
    val serverUrl: String = "",
    val token: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val collection: List<CollectionItem> = emptyList(),
    val portfolio: List<PortfolioSnapshot> = emptyList(),
    val scanMatches: List<ScanMatch> = emptyList(),
    val scanImageUri: Uri? = null
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SessionStore(app)
    private val repo = AppRepository(app, store)

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val server = repo.serverUrl()
            _state.value = _state.value.copy(
                serverUrl = server,
                token = store.token(),
                loggedIn = repo.isLoggedIn(),
                booting = false
            )
            if (_state.value.loggedIn) refreshAll()
        }
    }

    fun login(server: String, username: String, password: String) {
        val normalizedServer = ServerUrl.normalize(server)
        if (normalizedServer == null) {
            _state.value = _state.value.copy(
                error = "Enter the https:// address of your PokéCollector server."
            )
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            runCatching { repo.login(normalizedServer, username, password) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        loading = false,
                        loggedIn = true,
                        token = store.token(),
                        serverUrl = normalizedServer
                    )
                    refreshAll()
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        loading = false,
                        error = e.message ?: "Login failed"
                    )
                }
        }
    }

    fun logout() {
        viewModelScope.launch {
            repo.logout()
            _state.value = AppState(
                booting = false,
                loggedIn = false,
                serverUrl = _state.value.serverUrl,
                token = null
            )
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val collectionResult = runCatching { repo.collection() }
            val portfolioResult = runCatching { repo.portfolio() }

            _state.value = _state.value.copy(
                loading = false,
                collection = collectionResult.getOrDefault(emptyList()),
                portfolio = portfolioResult.getOrDefault(emptyList()),
                error = collectionResult.exceptionOrNull()?.message
                    ?: portfolioResult.exceptionOrNull()?.message
            )
        }
    }

    fun scan(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                loading = true,
                error = null,
                scanImageUri = uri,
                scanMatches = emptyList()
            )
            runCatching { repo.recognize(uri) }
                .onSuccess { result ->
                    _state.value = _state.value.copy(
                        loading = false,
                        scanMatches = result.matches
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        loading = false,
                        error = e.message ?: "Card recognition failed"
                    )
                }
        }
    }

    fun addMatch(match: ScanMatch) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            runCatching { repo.addMatch(match) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        loading = false,
                        scanMatches = emptyList(),
                        scanImageUri = null
                    )
                    refreshAll()
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        loading = false,
                        error = e.message ?: "Unable to add card"
                    )
                }
        }
    }

    fun clearScan() {
        _state.value = _state.value.copy(scanMatches = emptyList(), scanImageUri = null)
    }
}
