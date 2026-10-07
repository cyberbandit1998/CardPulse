package app.cardpulse.android.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException

/** What Friends needs from the server. The real one is `Repository`; tests use a fake. */
interface FriendsBackend {
    suspend fun friendsMe(): FriendsMeDto
    suspend fun friendsOverview(): FriendsOverviewDto
    suspend fun sendFriendRequest(body: FriendRequestBody): FriendRequestResultDto
    suspend fun acceptFriendRequest(requestId: Int): FriendDto
    suspend fun declineFriendRequest(requestId: Int)
    suspend fun cancelFriendRequest(requestId: Int)
    suspend fun removeFriend(friendId: Int)
    suspend fun updateSharing(update: SharingUpdateBody): SharingDto
    suspend fun newInviteCode(): String
    suspend fun ownTradeList(): OwnTradeListDto
    suspend fun setTradeQuantity(itemId: Int, quantity: Int): TradeEntryDto
    suspend fun friendCollection(friendId: Int): FriendRows<CollectionItemDto>
    suspend fun friendWishlist(friendId: Int): FriendRows<WishlistItemDto>
    suspend fun friendTradeList(friendId: Int): FriendRows<TradeItemDto>
    suspend fun friendTradeMatch(friendId: Int): TradeMatchDto
}

/** The rows of a friend's list, and how many the server sent that this app could not read (shown, not silently dropped). */
data class FriendRows<T>(val items: List<T>, val unreadable: Int = 0)

/** One thing being loaded for a screen: what has come, whether more is on its way, and why it failed. */
data class Loadable<T>(
    val value: T? = null,
    val loading: Boolean = false,
    val error: String? = null,
    /** The friend does not share this list with the user, as the server said. That is not a failure to try again. */
    val notShared: Boolean = false,
    /** Rows the server sent that could not be read. */
    val unreadable: Int = 0,
) {
    val loaded: Boolean get() = value != null
}

/** A friend's four pages. */
enum class FriendTab(val label: String) {
    MATCH("Trade match"),
    TRADE("For trade"),
    WISHLIST("Wishlist"),
    COLLECTION("Collection"),
}

/** What has been loaded of one friend. Dropped when the user leaves the friend, so other people's lists do not linger. */
data class FriendView(
    val match: Loadable<TradeMatchDto> = Loadable(),
    val trade: Loadable<List<TradeItemDto>> = Loadable(),
    val wishlist: Loadable<List<WishlistItemDto>> = Loadable(),
    val collection: Loadable<List<CollectionItemDto>> = Loadable(),
)

data class FriendsState(
    val availability: FriendsAvailability = FriendsAvailability.UNKNOWN,
    /** The server's reason (or the connection's) when [availability] is not [FriendsAvailability.SUPPORTED]. */
    val availabilityNote: String? = null,
    /** The first request, which finds out whether the server has Friends, is on its way. */
    val checking: Boolean = false,
    val me: FriendsMeDto? = null,
    val friends: List<FriendDto> = emptyList(),
    val incoming: List<FriendRequestDto> = emptyList(),
    val outgoing: List<FriendRequestDto> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    /** Why the friends and requests could not be loaded. */
    val error: String? = null,
    /** A request to someone is on its way. */
    val sending: Boolean = false,
    /** What came of the last request to add someone. */
    val notice: Notice? = null,
    /** Requests with an answer on its way. */
    val answering: Set<Int> = emptySet(),
    /** Why the last accept, decline or cancel did not work. */
    val requestError: String? = null,
    val sharingBusy: Boolean = false,
    val sharingError: String? = null,
    val codeBusy: Boolean = false,
    val codeError: String? = null,
    /** The user's own For Trade marks: collection row id to the copies marked. */
    val marks: Map<Int, Int> = emptyMap(),
    val marksLoaded: Boolean = false,
    /** Why the marks could not be loaded. Until they are, nothing is offered for marking. */
    val marksError: String? = null,
    /** Marks on their way to the server: row id to the number being set. A control shows the number it is about to be. */
    val marking: Map<Int, Int> = emptyMap(),
    /** What has been loaded of each friend, by friend id. */
    val views: Map<Int, FriendView> = emptyMap(),
)

val FriendsState.supported: Boolean get() = availability == FriendsAvailability.SUPPORTED

/** What the user shares, as the server last said. Nothing until it has. */
val FriendsState.sharing: Sharing get() = me?.sharing?.toSharing() ?: Sharing()

val FriendsState.inviteCode: String get() = me?.inviteCode.orEmpty()

/** The copies of a row marked For Trade, counting a change that is on its way as done. */
fun FriendsState.markedFor(itemId: Int): Int = marking[itemId] ?: marks[itemId] ?: 0

/**
 * The user's friends, requests, sharing choices and For Trade marks, and what has been loaded of each friend. It holds no
 * Android types, so it is tested on the JVM against a fake server.
 *
 * Nothing here decides who may see what: the server does, on every request. What the user chooses to share is shown only once
 * the server has confirmed it, and a friend's list is shown only as the server sent it. When the user signs out ([reset]),
 * answers still on their way are ignored, so one account's friends never appear for the next.
 */
class FriendsSession(
    private val backend: FriendsBackend,
    private val scope: CoroutineScope,
    private val describe: (Throwable) -> String = { it.message ?: "Something went wrong." },
) {
    private val _state = MutableStateFlow(FriendsState())
    val state: StateFlow<FriendsState> = _state.asStateFlow()

    /** Counts sign-outs: an answer that was asked for before one is not for the account that is here now. */
    private var generation = 0

    private fun change(from: Int, change: (FriendsState) -> FriendsState) {
        if (from == generation) _state.update(change)
    }

    // --- the server and the lists ----------------------------------------------------------------

    /** Finds out whether the server has Friends and, if it does, loads everything. Does nothing once that has worked, unless [force]. */
    fun start(force: Boolean = false) {
        val current = _state.value
        if (current.checking) return
        if (current.availability == FriendsAvailability.SUPPORTED && !force) return
        val from = generation
        change(from) { it.copy(checking = true, availabilityNote = null) }
        scope.launch {
            attempt { backend.friendsMe() }
                .onSuccess { me ->
                    change(from) { it.copy(availability = FriendsAvailability.SUPPORTED, availabilityNote = null, me = me, checking = false) }
                    val overview = async { loadOverview(from) }
                    val marks = async { loadMarks(from) }
                    overview.await()
                    marks.await()
                }
                .onFailure { error ->
                    change(from) {
                        it.copy(availability = error.friendsAvailability(), availabilityNote = describe(error), checking = false)
                    }
                }
        }
    }

    /** Asks again for everything (the refresh button). */
    fun refresh() = start(force = true)

    /** Forgets everything: the user signed out. Answers still on their way are ignored. */
    fun reset() {
        generation++
        _state.value = FriendsState()
    }

    private suspend fun loadOverview(from: Int) {
        change(from) { it.copy(loading = true, error = null) }
        attempt { backend.friendsOverview() }
            .onSuccess { overview ->
                change(from) { state ->
                    val ids = overview.friends.mapTo(HashSet()) { it.id }
                    state.copy(
                        friends = overview.friends.byName(),
                        incoming = overview.incoming,
                        outgoing = overview.outgoing,
                        loaded = true,
                        loading = false,
                        // A person who is no longer a friend leaves nothing of theirs behind.
                        views = state.views.filterKeys { it in ids },
                    )
                }
            }
            .onFailure { error -> change(from) { it.copy(loading = false, error = describe(error)) } }
    }

    private suspend fun loadMarks(from: Int) {
        attempt { backend.ownTradeList() }
            .onSuccess { list -> change(from) { it.copy(marks = list.toMarks(), marksLoaded = true, marksError = null) } }
            .onFailure { error -> change(from) { it.copy(marksError = describe(error)) } }
    }

    /** Loads the user's For Trade marks again, the way to try again after a failure. */
    fun reloadMarks() {
        val from = generation
        scope.launch { loadMarks(from) }
    }

    // --- adding a friend --------------------------------------------------------------------------

    /**
     * Asks someone to be friends, by [text] read as [mode] asks. [done] gets true when the server took the request, so the
     * field can be cleared. A request that cannot be made yet (nothing typed, not shaped like a code) is not sent.
     */
    fun sendRequest(mode: AddFriendMode, text: String, done: (Boolean) -> Unit = {}) {
        val body = mode.requestFor(text)
        if (body == null) {
            val said = if (mode == AddFriendMode.CODE) "That doesn't look like an invite code." else "Type their username."
            _state.update { it.copy(notice = Notice(said, isError = true)) }
            done(false)
            return
        }
        if (_state.value.sending) return
        val from = generation
        change(from) { it.copy(sending = true, notice = null) }
        scope.launch {
            attempt { backend.sendFriendRequest(body) }
                .onSuccess { result ->
                    change(from) { it.copy(sending = false, notice = Notice(result.message())) }
                    loadOverview(from)
                    done(true)
                }
                .onFailure { error ->
                    change(from) { it.copy(sending = false, notice = Notice(describe(error), isError = true)) }
                    done(false)
                }
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    // --- answering requests -----------------------------------------------------------------------

    fun accept(requestId: Int) = answer(requestId) { backend.acceptFriendRequest(requestId) }

    fun decline(requestId: Int) = answer(requestId) { backend.declineFriendRequest(requestId) }

    /** Takes back a request the user made. */
    fun cancel(requestId: Int) = answer(requestId) { backend.cancelFriendRequest(requestId) }

    private fun answer(requestId: Int, call: suspend () -> Unit) {
        if (requestId in _state.value.answering) return
        val from = generation
        change(from) { it.copy(answering = it.answering + requestId, requestError = null) }
        scope.launch {
            attempt { call() }
                .onSuccess {
                    change(from) { it.copy(answering = it.answering - requestId) }
                    loadOverview(from)
                }
                .onFailure { error ->
                    // A request that is not there any more was answered or taken back elsewhere: show how things are now.
                    val gone = error is HttpException && error.code() == 404
                    change(from) { it.copy(answering = it.answering - requestId, requestError = if (gone) null else describe(error)) }
                    if (gone) loadOverview(from)
                }
        }
    }

    /** Ends a friendship. [done] gets null when it worked, or the reason when it did not. */
    fun removeFriend(friendId: Int, done: (String?) -> Unit = {}) {
        val from = generation
        scope.launch {
            attempt { backend.removeFriend(friendId) }
                .onSuccess {
                    change(from) { it.copy(friends = it.friends.filterNot { friend -> friend.id == friendId }, views = it.views - friendId) }
                    done(null)
                }
                .onFailure { error ->
                    if (error is HttpException && error.code() == 404) {
                        // Already ended (by them): that is what was wanted.
                        change(from) { it.copy(friends = it.friends.filterNot { friend -> friend.id == friendId }, views = it.views - friendId) }
                        done(null)
                    } else {
                        done(describe(error))
                    }
                }
        }
    }

    // --- what the user shares ----------------------------------------------------------------------

    /**
     * Chooses who can see [section]. The new choice is shown only once the server has confirmed it, so the screen never says a
     * list is shared (or private) when the server does not.
     */
    fun setSharing(section: ShareSection, level: ShareLevel) {
        val current = _state.value
        if (current.sharingBusy || current.me == null || current.sharing[section] == level) return
        val from = generation
        change(from) { it.copy(sharingBusy = true, sharingError = null) }
        scope.launch {
            attempt { backend.updateSharing(sharingUpdate(section, level)) }
                .onSuccess { saved ->
                    change(from) { it.copy(me = it.me?.copy(sharing = saved), sharingBusy = false) }
                }
                .onFailure { error -> change(from) { it.copy(sharingBusy = false, sharingError = describe(error)) } }
        }
    }

    /** Replaces the invite code. The old one stops working at once; friends already made stay friends. */
    fun newInviteCode() {
        if (_state.value.codeBusy || _state.value.me == null) return
        val from = generation
        change(from) { it.copy(codeBusy = true, codeError = null) }
        scope.launch {
            attempt { backend.newInviteCode() }
                .onSuccess { code -> change(from) { it.copy(me = it.me?.copy(inviteCode = code), codeBusy = false) } }
                .onFailure { error -> change(from) { it.copy(codeBusy = false, codeError = describe(error)) } }
        }
    }

    // --- For Trade ---------------------------------------------------------------------------------

    /**
     * Sets how many copies of a collection row are For Trade (0 takes the row off). Nothing is ever marked except by this call.
     * [done] gets null when it worked, or the reason when it did not. The marks must have loaded first, and a row's change
     * that is on its way is not changed again.
     */
    fun setTrade(itemId: Int, quantity: Int, done: (String?) -> Unit = {}) {
        val current = _state.value
        if (!current.marksLoaded || itemId in current.marking) return
        val from = generation
        change(from) { it.copy(marking = it.marking + (itemId to quantity)) }
        scope.launch {
            attempt { backend.setTradeQuantity(itemId, quantity) }
                .onSuccess { entry ->
                    change(from) { it.copy(marks = it.marks.withMark(itemId, entry.quantity), marking = it.marking - itemId) }
                    done(null)
                }
                .onFailure { error ->
                    change(from) { it.copy(marking = it.marking - itemId) }
                    done(describe(error))
                }
        }
    }

    // --- a friend's lists --------------------------------------------------------------------------

    /** Loads one page of a friend unless it is loaded already (or on its way); [force] loads it again. */
    fun loadFriend(friendId: Int, tab: FriendTab, force: Boolean = false) {
        when (tab) {
            FriendTab.MATCH -> fetch(friendId, force, { it.match }, { view, page -> view.copy(match = page) }) {
                backend.friendTradeMatch(friendId) to 0
            }
            FriendTab.TRADE -> fetch(friendId, force, { it.trade }, { view, page -> view.copy(trade = page) }) {
                backend.friendTradeList(friendId).asPair()
            }
            FriendTab.WISHLIST -> fetch(friendId, force, { it.wishlist }, { view, page -> view.copy(wishlist = page) }) {
                backend.friendWishlist(friendId).asPair()
            }
            FriendTab.COLLECTION -> fetch(friendId, force, { it.collection }, { view, page -> view.copy(collection = page) }) {
                backend.friendCollection(friendId).asPair()
            }
        }
    }

    /** The user left this friend: nothing of theirs is kept. */
    fun forgetFriend(friendId: Int) = _state.update { it.copy(views = it.views - friendId) }

    private fun <T> FriendRows<T>.asPair(): Pair<List<T>, Int> = items to unreadable

    /** [call] gives what was loaded and how many rows could not be read. */
    private fun <R : Any> fetch(
        friendId: Int,
        force: Boolean,
        read: (FriendView) -> Loadable<R>,
        write: (FriendView, Loadable<R>) -> FriendView,
        call: suspend () -> Pair<R, Int>,
    ) {
        val existing = read(_state.value.views[friendId] ?: FriendView())
        if (existing.loading) return
        if (!force && (existing.loaded || existing.notShared)) return
        val from = generation
        fun put(page: Loadable<R>) = change(from) { state ->
            val view = state.views[friendId] ?: FriendView()
            state.copy(views = state.views + (friendId to write(view, page)))
        }
        put(existing.copy(loading = true, error = null, notShared = false))
        scope.launch {
            attempt { call() }
                .onSuccess { (value, unreadable) -> put(Loadable(value = value, unreadable = unreadable)) }
                .onFailure { error ->
                    val forbidden = error is HttpException && error.code() == 403
                    // What was loaded before stays while a refresh fails, so a flaky connection does not blank a list. A list
                    // the friend has stopped sharing goes at once.
                    put(
                        Loadable(
                            value = if (forbidden) null else existing.value,
                            error = describe(error),
                            notShared = forbidden,
                            unreadable = if (forbidden) 0 else existing.unreadable,
                        ),
                    )
                }
        }
    }
}
