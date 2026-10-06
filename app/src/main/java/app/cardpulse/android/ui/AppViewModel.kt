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
import app.cardpulse.android.core.NotPokeCollectorException
import app.cardpulse.android.core.PortfolioRange
import app.cardpulse.android.core.PriceLookup
import app.cardpulse.android.core.ServerUrl
import app.cardpulse.android.core.ThemeMode
import app.cardpulse.android.core.UserDto
import app.cardpulse.android.core.attempt
import app.cardpulse.android.core.isCustomCard
import app.cardpulse.android.core.lookUpPrices
import app.cardpulse.android.core.replacing
import app.cardpulse.android.core.toChartPoints
import app.cardpulse.android.core.userMessage
import app.cardpulse.android.core.without
import coil3.SingletonImageLoader
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException

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
    /** The server is looking up prices for newly added cards. */
    val lookingUpPrices: Boolean = false,
    /** The last attempt to reach the server failed for lack of a connection (not because it said no). */
    val offline: Boolean = false,
    /** Light, dark or the phone's setting. */
    val themeMode: ThemeMode = ThemeMode.DEFAULT,
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
    private var priceLookup: Job? = null
    private var pricesNotAllowed = false

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
                themeMode = saved.themeMode,
            )
        }
        if (signedIn) loadEverything()
    }

    /** Switches the colours at once and remembers the choice for next time. */
    fun setThemeMode(mode: ThemeMode) {
        _state.update { it.copy(themeMode = mode) }
        viewModelScope.launch { attempt { store.saveThemeMode(mode) } }
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
                AppState(booting = false, serverUrl = it.serverUrl, message = message, themeMode = it.themeMode)
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
            .onSuccess { prefs -> _state.update { it.copy(prefs = prefs, offline = false) } }
            .onFailure { error -> _state.update { it.copy(message = error.userMessage(), offline = error.isNoConnection()) } }
    }

    private suspend fun refreshDashboardNow() {
        _state.update { it.copy(dashboardLoading = true) }
        val priceField = _state.value.prefs.priceField
        attempt { repo.loadDashboard(priceField) }
            .onSuccess { dashboard -> _state.update { it.copy(dashboard = dashboard, dashboardLoading = false, offline = false) } }
            .onFailure { error ->
                _state.update { it.copy(dashboardLoading = false, message = error.userMessage(), offline = error.isNoConnection()) }
            }
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
                        offline = false,
                    )
                }
            }
            .onFailure { error ->
                _state.update { it.copy(collectionLoading = false, message = error.userMessage(), offline = error.isNoConnection()) }
            }
    }

    /** The server could not be reached at all (no network, wrong address, timed out), as opposed to answering with an error. */
    private fun Throwable.isNoConnection(): Boolean = this is IOException && this !is NotPokeCollectorException

    private fun upsertCollectionItem(item: CollectionItemDto) {
        // A card the collection hasn't had before may not have a price yet; one it already tracked has.
        val newCard = item.cardId != null && !item.isCustomCard() && _state.value.collection.none { it.cardId == item.cardId }
        _state.update { state ->
            val without = state.collection.filterNot { it.id == item.id }
            state.copy(collection = listOf(item) + without)
        }
        refreshDashboardSoon()
        if (newCard) lookUpPricesSoon()
    }

    /**
     * Once a new card has been added (scanned or typed in) and things have been quiet for a moment, asks the server to
     * look up prices and shows what it found. One lookup serves a whole batch of cards.
     */
    private fun lookUpPricesSoon() {
        if (pricesNotAllowed) return
        priceLookup?.cancel()
        priceLookup = viewModelScope.launch {
            delay(PRICE_LOOKUP_DELAY_MS)
            if (!store.scanPrefs().lookUpPrices) return@launch
            _state.update { it.copy(lookingUpPrices = true) }
            try {
                attempt { lookUpPrices(repo) }.onSuccess { if (it == PriceLookup.NOT_ALLOWED) pricesNotAllowed = true }
            } finally {
                _state.update { it.copy(lookingUpPrices = false) }
            }
            // Whatever happened, show what the server has now.
            refreshDashboardNow()
            refreshCollectionNow()
        }
    }

    /** The portfolio total changed. Waits for a quiet moment so a whole batch of changes triggers one refetch. */
    private fun refreshDashboardSoon() {
        dashboardRefresh?.cancel()
        dashboardRefresh = viewModelScope.launch {
            delay(DASHBOARD_REFRESH_DELAY_MS)
            refreshDashboardNow()
        }
    }

    /**
     * Takes a card out of the collection: every copy in the row when [wholeRow] is set (or when there is only one),
     * otherwise just one copy. [done] gets null when it worked, or the server's reason when it didn't (a card that is
     * in a deck or a product can't be removed, for instance).
     */
    fun removeFromCollection(item: CollectionItemDto, wholeRow: Boolean, done: (String?) -> Unit) {
        viewModelScope.launch {
            val removeRow = wholeRow || item.quantity <= 1
            attempt {
                if (removeRow) {
                    repo.removeFromCollection(item.id)
                    null
                } else {
                    repo.setCollectionQuantity(item.id, item.quantity - 1)
                }
            }
                .onSuccess { updated ->
                    when {
                        removeRow -> _state.update { it.copy(collection = it.collection.without(item.id)) }
                        updated != null -> _state.update { it.copy(collection = it.collection.replacing(updated)) }
                        else -> refreshCollectionNow() // it went through, but the server's reply couldn't be read
                    }
                    refreshDashboardSoon()
                    done(null)
                }
                .onFailure { error ->
                    if (removeRow && error is HttpException && error.code() == 404) {
                        // Already gone (removed somewhere else): that is what was wanted.
                        _state.update { it.copy(collection = it.collection.without(item.id)) }
                        done(null)
                    } else {
                        done(error.userMessage())
                    }
                }
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
        /** How long things must stay quiet after a card is added before prices are looked up. */
        const val PRICE_LOOKUP_DELAY_MS = 4000L
    }
}
