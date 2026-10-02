package app.cardpulse.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.cardpulse.android.PokeApp
import app.cardpulse.android.core.ChartPoint
import app.cardpulse.android.core.CollectionItemDto
import app.cardpulse.android.core.DashboardDto
import app.cardpulse.android.core.DisplayPrefs
import app.cardpulse.android.core.MoverDto
import app.cardpulse.android.core.PortfolioRange
import app.cardpulse.android.core.ServerUrl
import app.cardpulse.android.core.UserDto
import app.cardpulse.android.core.attempt
import app.cardpulse.android.core.toChartPoints
import app.cardpulse.android.core.userMessage
import coil3.SingletonImageLoader
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppState(
    val booting: Boolean = true,
    /** Empty until the user has entered a server address. */
    val serverUrl: String = "",
    val signedIn: Boolean = false,
    /** The server has no sign-in (single-user mode): anyone who can reach it is the administrator. */
    val noLogin: Boolean = false,
    val user: UserDto? = null,
    val mustChangePassword: Boolean = false,
    val busy: Boolean = false,
    /** An error or notice for the screen the user is on. */
    val message: String? = null,
    val prefs: DisplayPrefs = DisplayPrefs(),

    val dashboard: DashboardDto? = null,
    val dashboardLoading: Boolean = false,

    val collection: List<CollectionItemDto> = emptyList(),
    val collectionLoaded: Boolean = false,
    val collectionLoading: Boolean = false,
    /** Rows the server sent that this app could not read. */
    val collectionUnreadable: Int = 0,

    val historyRange: PortfolioRange = PortfolioRange.MONTH,
    val history: List<ChartPoint> = emptyList(),
    val historyLoading: Boolean = false,
    val movers: List<MoverDto> = emptyList(),
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val container = getApplication<PokeApp>().container
    private val repo = container.repository
    private val store = container.store
    private val session = container.session

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    /** When each range's history was last fetched. The server stores a snapshot on every fetch, so don't spam it. */
    private val historyFetchedAt = mutableMapOf<PortfolioRange, Long>()
    private val historyCache = mutableMapOf<PortfolioRange, List<ChartPoint>>()
    private var dashboardRefresh: Job? = null

    init {
        viewModelScope.launch { restoreSession() }
        viewModelScope.launch { container.sessionEvents.collect { onSessionExpired() } }
        viewModelScope.launch { container.collectionUpdates.collect { upsertCollectionItem(it) } }
    }

    // --- session ------------------------------------------------------------------------------

    private suspend fun restoreSession() {
        val saved = store.load()
        session.serverUrl = saved.serverUrl
        session.token = saved.token
        val signedIn = saved.serverUrl.isNotEmpty() && (saved.token != null || saved.noLogin)
        _state.update {
            it.copy(
                booting = false,
                serverUrl = saved.serverUrl,
                signedIn = signedIn,
                noLogin = saved.noLogin,
            )
        }
        if (signedIn) loadEverything()
    }

    /** Checks the address and, if the server asks for a login, signs in with the given credentials. */
    fun signIn(serverInput: String, username: String, password: String) {
        val server = ServerUrl.normalize(serverInput)
        if (server == null) {
            _state.update { it.copy(message = "Enter the https:// address of your PokéCollector server.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val outcome = attempt {
                val check = repo.checkServer(server)
                if (check.multiUser) {
                    if (username.isBlank() || password.isEmpty()) error("Enter your username and password.")
                    repo.login(username, password).let { SignInResult(it.accessToken, it.user, noLogin = false) }
                } else {
                    SignInResult(token = null, user = null, noLogin = true)
                }
            }
            outcome.onSuccess { result ->
                // The server accepted the sign-in. If this phone's secure storage can't keep it, stay signed in
                // for this session rather than failing, and say so.
                val saved = attempt {
                    store.saveServer(server)
                    store.saveSignIn(result.token, username.trim().ifEmpty { null }, result.noLogin)
                }
                _state.update {
                    it.copy(
                        busy = false,
                        message = if (saved.isSuccess) null else {
                            "Signed in, but this phone wouldn't store your sign-in securely, so you'll be asked to sign in again next time."
                        },
                        serverUrl = server,
                        signedIn = true,
                        noLogin = result.noLogin,
                        user = result.user,
                        mustChangePassword = result.user?.mustChangePassword == true,
                    )
                }
                loadEverything()
            }.onFailure { error ->
                session.token = null
                _state.update { it.copy(busy = false, message = error.userMessage()) }
            }
        }
    }

    /** Lets the user confirm the address before typing credentials. */
    fun testConnection(serverInput: String) {
        val server = ServerUrl.normalize(serverInput)
        if (server == null) {
            _state.update { it.copy(message = "Enter the https:// address of your PokéCollector server.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            attempt { repo.checkServer(server) }
                .onSuccess { check ->
                    val text = if (check.multiUser) {
                        "Connected to a PokéCollector server. Sign in with your account."
                    } else {
                        "Connected. This server has no sign-in (single-user mode): anyone who can reach it is the " +
                            "administrator. Turn on Multi-User Mode before exposing it to the internet."
                    }
                    _state.update { it.copy(busy = false, message = text) }
                }
                .onFailure { error -> _state.update { it.copy(busy = false, message = error.userMessage()) } }
        }
    }

    fun signOut(message: String? = null) {
        viewModelScope.launch {
            session.token = null
            store.saveSignIn(token = null, username = null, noLogin = false)
            historyCache.clear()
            historyFetchedAt.clear()
            clearImageCaches()
            _state.update {
                AppState(booting = false, serverUrl = it.serverUrl, message = message)
            }
        }
    }

    private fun onSessionExpired() {
        val current = _state.value
        if (!current.signedIn || current.noLogin) return
        signOut("Your session has expired. Sign in again.")
    }

    /** Private photos are cached on this device; don't leave them behind for the next account. */
    private fun clearImageCaches() {
        val loader = SingletonImageLoader.get(getApplication<Application>())
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
    }

    fun changeRequiredPassword(newPassword: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            attempt { repo.changeRequiredPassword(newPassword) }
                .onSuccess { _state.update { it.copy(busy = false, mustChangePassword = false) } }
                .onFailure { error -> _state.update { it.copy(busy = false, message = error.userMessage()) } }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    // --- data ---------------------------------------------------------------------------------

    private fun loadEverything() {
        viewModelScope.launch {
            refreshPrefs()
            val dashboard = async { refreshDashboardNow() }
            val collection = async { refreshCollectionNow() }
            dashboard.await()
            collection.await()
        }
    }

    fun refreshAll() = loadEverything()

    fun refreshDashboard() {
        viewModelScope.launch { refreshDashboardNow() }
    }

    fun refreshCollection() {
        viewModelScope.launch { refreshCollectionNow() }
    }

    private suspend fun refreshPrefs() {
        attempt { repo.loadPrefs() }
            .onSuccess { prefs -> _state.update { it.copy(prefs = prefs) } }
            .onFailure { error -> _state.update { it.copy(message = error.userMessage()) } }
    }

    private suspend fun refreshDashboardNow() {
        _state.update { it.copy(dashboardLoading = true) }
        val priceField = _state.value.prefs.priceField
        attempt { repo.loadDashboard(priceField) }
            .onSuccess { dashboard -> _state.update { it.copy(dashboard = dashboard, dashboardLoading = false) } }
            .onFailure { error -> _state.update { it.copy(dashboardLoading = false, message = error.userMessage()) } }
    }

    private suspend fun refreshCollectionNow() {
        _state.update { it.copy(collectionLoading = true) }
        attempt { repo.loadCollection() }
            .onSuccess { result ->
                _state.update {
                    it.copy(
                        collection = result.items,
                        collectionLoaded = true,
                        collectionLoading = false,
                        collectionUnreadable = result.unreadable,
                    )
                }
            }
            .onFailure { error -> _state.update { it.copy(collectionLoading = false, message = error.userMessage()) } }
    }

    private fun upsertCollectionItem(item: CollectionItemDto) {
        _state.update { state ->
            val without = state.collection.filterNot { it.id == item.id }
            state.copy(collection = listOf(item) + without)
        }
        // The portfolio total changed. Wait for a quiet moment so adding a whole batch triggers one refetch.
        dashboardRefresh?.cancel()
        dashboardRefresh = viewModelScope.launch {
            delay(DASHBOARD_REFRESH_DELAY_MS)
            refreshDashboardNow()
        }
    }

    // --- portfolio history --------------------------------------------------------------------

    /** Loads the chart for [range]; reuses a recent fetch unless [force] is set. */
    fun showHistory(range: PortfolioRange, force: Boolean = false) {
        _state.update { it.copy(historyRange = range, history = historyCache[range].orEmpty()) }
        val age = System.currentTimeMillis() - (historyFetchedAt[range] ?: 0L)
        if (!force && historyCache.containsKey(range) && age < HISTORY_MAX_AGE_MS) return
        viewModelScope.launch {
            _state.update { it.copy(historyLoading = true) }
            val priceField = _state.value.prefs.priceField
            val movers = if (force || _state.value.movers.isEmpty()) attempt { repo.loadMovers(priceField) }.getOrNull() else null
            attempt { repo.loadHistory(range, priceField).toChartPoints() }
                .onSuccess { points ->
                    historyCache[range] = points
                    historyFetchedAt[range] = System.currentTimeMillis()
                    _state.update {
                        it.copy(
                            history = if (it.historyRange == range) points else it.history,
                            historyLoading = false,
                            movers = movers ?: it.movers,
                        )
                    }
                }
                .onFailure { error -> _state.update { it.copy(historyLoading = false, message = error.userMessage()) } }
        }
    }

    private data class SignInResult(val token: String?, val user: UserDto?, val noLogin: Boolean)

    private companion object {
        const val HISTORY_MAX_AGE_MS = 10 * 60 * 1000L
        const val DASHBOARD_REFRESH_DELAY_MS = 1500L
    }
}
