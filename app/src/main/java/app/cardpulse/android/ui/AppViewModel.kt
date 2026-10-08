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
import app.cardpulse.android.core.SetChecklistDto
import app.cardpulse.android.core.SetDto
import app.cardpulse.android.core.ThemeMode
import app.cardpulse.android.core.UserDto
import app.cardpulse.android.core.WishlistItemDto
import app.cardpulse.android.core.WishlistPriorities
import app.cardpulse.android.core.WishlistPriority
import app.cardpulse.android.core.attempt
import app.cardpulse.android.core.cardIds
import app.cardpulse.android.core.isCustomCard
import app.cardpulse.android.core.lookUpPrices
import app.cardpulse.android.core.replacing
import app.cardpulse.android.core.toChartPoints
import app.cardpulse.android.core.userMessage
import app.cardpulse.android.core.withAdded
import app.cardpulse.android.core.withUpdated
import app.cardpulse.android.core.without
import app.cardpulse.android.core.withoutCard
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

    /** Every set the server lists for the user's language: the catalogue the Sets tab browses. Loaded when that tab opens. */
    val sets: List<SetDto> = emptyList(),
    val setsLoaded: Boolean = false,
    val setsLoading: Boolean = false,
    /** Why the list of sets could not be loaded. Shown on the Sets tab itself, not as a banner on every tab. */
    val setsError: String? = null,
    /** The checklists opened so far, by set id. The cards of a set hardly ever change, so they are kept. */
    val checklists: Map<String, SetChecklistDto> = emptyMap(),
    val checklistsLoading: Set<String> = emptySet(),
    val checklistErrors: Map<String, String> = emptyMap(),

    /** The wishlist, newest first. Loaded with everything else when the app opens, so a heart can say whether a card is on it. */
    val wishlist: List<WishlistItemDto> = emptyList(),
    val wishlistLoaded: Boolean = false,
    val wishlistLoading: Boolean = false,
    /** Rows the server sent that this app could not read. */
    val wishlistUnreadable: Int = 0,
    /** Why the wishlist could not be loaded. Shown on the Wishlist screen itself, not as a banner on every tab. */
    val wishlistError: String? = null,
    /** Cards whose adding or removing is on its way to the server. A heart shows such a card as it is about to be. */
    val wishlistPending: Set<String> = emptySet(),
    /** How much each wishlist card is wanted, by card id. Kept on this phone: the server's wishlist has no priority. */
    val wishlistPriorities: Map<String, WishlistPriority> = emptyMap(),
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
    /** When the list of sets was last loaded, so opening the Sets tab again soon after does not ask the server again. */
    private var setsLoadedAt = 0L

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
            val wishlist = async { refreshWishlistNow() }
            dashboard.await()
            collection.await()
            wishlist.await()
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

    // --- wishlist -----------------------------------------------------------------------------

    /**
     * Loads the wishlist unless it is loaded already (or on its way); [force] loads it again, which is the way to try again
     * after a failure. A failure is kept for the Wishlist screen to show, not sent to the banner every tab shares.
     */
    fun loadWishlist(force: Boolean = false) {
        val current = _state.value
        if (current.wishlistLoading) return
        if (current.wishlistLoaded && !force) return
        viewModelScope.launch { refreshWishlistNow() }
    }

    private suspend fun refreshWishlistNow() {
        _state.update { it.copy(wishlistLoading = true, wishlistError = null) }
        attempt { repo.loadWishlist() }
            .onSuccess { result ->
                // The priorities kept on this phone, without those of cards that are no longer listed (unless some rows could
                // not be read, which would make a card that is still listed look gone).
                val kept = attempt { store.wishlistPriorities() }.getOrDefault(emptyMap())
                val levels = if (result.unreadable == 0) WishlistPriorities.keepOnly(kept, result.items.cardIds()) else kept
                if (levels.size != kept.size) attempt { store.saveWishlistPriorities(levels) }
                _state.update {
                    it.copy(
                        wishlist = result.items,
                        wishlistLoaded = true,
                        wishlistLoading = false,
                        wishlistUnreadable = result.unreadable,
                        wishlistPriorities = levels,
                        offline = false,
                    )
                }
            }
            .onFailure { error ->
                _state.update { it.copy(wishlistLoading = false, wishlistError = error.userMessage(), offline = error.isNoConnection()) }
            }
    }

    /**
     * Puts a card on the wishlist, or takes it off if it is on it: what pressing a heart does. A card that is already listed is
     * only ever removed here, never added again, because the server answers that by raising the quantity wanted. [done] gets
     * null when it worked, or the reason when it didn't. Nothing happens before the wishlist has loaded (nothing honest can be
     * said about a card until then), nor while the card's last change is still on its way.
     */
    fun toggleWishlist(cardId: String, done: (String?) -> Unit = {}) {
        val current = _state.value
        if (cardId.isBlank() || !current.wishlistLoaded || cardId in current.wishlistPending) return
        val listed = current.wishlist.firstOrNull { it.cardId == cardId }
        _state.update { it.copy(wishlistPending = it.wishlistPending + cardId) }
        viewModelScope.launch {
            attempt {
                if (listed != null) {
                    repo.removeFromWishlist(listed.id)
                    null
                } else {
                    repo.addToWishlist(cardId)
                }
            }
                .onSuccess { added ->
                    // The list and the pending mark change together, so the heart does not flicker.
                    _state.update {
                        it.copy(
                            wishlist = if (added != null) it.wishlist.withAdded(added) else it.wishlist.withoutCard(cardId),
                            wishlistPending = it.wishlistPending - cardId,
                        )
                    }
                    if (added == null) forgetPriority(cardId)
                    done(null)
                }
                .onFailure { error ->
                    if (listed != null && error is HttpException && error.code() == 404) {
                        // Already gone (removed somewhere else): that is what was wanted.
                        _state.update { it.copy(wishlist = it.wishlist.withoutCard(cardId), wishlistPending = it.wishlistPending - cardId) }
                        forgetPriority(cardId)
                        done(null)
                    } else {
                        _state.update { it.copy(wishlistPending = it.wishlistPending - cardId) }
                        done(error.userMessage())
                    }
                }
        }
    }

    /**
     * Sets the target price of a wishlist card, in euros, or with null takes it away. It is the server's "alert below" price, so
     * the website shows it too. [done] gets null when it worked, or the reason when it didn't.
     */
    fun setWishlistTarget(item: WishlistItemDto, targetEur: Double?, done: (String?) -> Unit) {
        viewModelScope.launch {
            attempt { repo.setWishlistTarget(item.id, targetEur) }
                .onSuccess { updated ->
                    // Keep the card the row already has, should an older server answer without one.
                    val row = if (updated.card == null) updated.copy(card = item.card) else updated
                    _state.update { it.copy(wishlist = it.wishlist.withUpdated(row)) }
                    done(null)
                }
                .onFailure { error -> done(error.userMessage()) }
        }
    }

    /** Sets how much a wishlist card is wanted, or with null clears it. Kept on this phone only. */
    fun setWishlistPriority(cardId: String, priority: WishlistPriority?) {
        val levels = _state.value.wishlistPriorities.let { if (priority == null) it - cardId else it + (cardId to priority) }
        _state.update { it.copy(wishlistPriorities = levels) }
        viewModelScope.launch { attempt { store.saveWishlistPriorities(levels) } }
    }

    /** A card taken off the wishlist keeps no priority for the day it is put back. */
    private fun forgetPriority(cardId: String) {
        if (cardId in _state.value.wishlistPriorities) setWishlistPriority(cardId, null)
    }

    // --- sets ---------------------------------------------------------------------------------

    /**
     * Loads the list of every set the server has, unless a recent load already has it; [force] loads it again. A failure
     * is kept for the Sets tab to show (with a way to try again), not sent to the banner every tab shares.
     */
    fun loadSets(force: Boolean = false) {
        val current = _state.value
        if (current.setsLoading) return
        val fresh = current.setsLoaded && System.currentTimeMillis() - setsLoadedAt < SETS_MAX_AGE_MS
        if (fresh && !force) return
        viewModelScope.launch {
            _state.update { it.copy(setsLoading = true, setsError = null) }
            attempt { repo.sets() }
                .onSuccess { sets ->
                    setsLoadedAt = System.currentTimeMillis()
                    _state.update { it.copy(sets = sets, setsLoaded = true, setsLoading = false, offline = false) }
                }
                .onFailure { error ->
                    _state.update { it.copy(setsLoading = false, setsError = error.userMessage(), offline = error.isNoConnection()) }
                }
        }
    }

    /** Loads the cards of one set, unless they are already here; [force] loads them again (the way to try again after a failure). */
    fun loadChecklist(setId: String, force: Boolean = false) {
        val current = _state.value
        if (setId in current.checklistsLoading) return
        if (!force && setId in current.checklists) return
        viewModelScope.launch {
            _state.update { it.copy(checklistsLoading = it.checklistsLoading + setId, checklistErrors = it.checklistErrors - setId) }
            attempt { repo.loadChecklist(setId) }
                .onSuccess { checklist ->
                    _state.update {
                        // Newest last, and only the latest few kept: a big set holds hundreds of cards.
                        val kept = (it.checklists - setId) + (setId to checklist)
                        val trimmed = kept.entries.toList().takeLast(MAX_CHECKLISTS_KEPT).associate { entry -> entry.key to entry.value }
                        it.copy(checklists = trimmed, checklistsLoading = it.checklistsLoading - setId, offline = false)
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            checklistsLoading = it.checklistsLoading - setId,
                            checklistErrors = it.checklistErrors + (setId to error.userMessage()),
                            offline = error.isNoConnection(),
                        )
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
        /** How long the list of sets is reused. New sets appear on the server rarely, and a refresh is one tap away. */
        const val SETS_MAX_AGE_MS = 30 * 60 * 1000L
        const val MAX_CHECKLISTS_KEPT = 12
        const val DASHBOARD_REFRESH_DELAY_MS = 1500L
        /** How long things must stay quiet after a card is added before prices are looked up. */
        const val PRICE_LOOKUP_DELAY_MS = 4000L
    }
}
