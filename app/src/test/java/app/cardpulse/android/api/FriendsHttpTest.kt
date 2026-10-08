package app.cardpulse.android.api

import app.cardpulse.android.core.AppJson
import app.cardpulse.android.core.FriendRequestBody
import app.cardpulse.android.core.FriendTab
import app.cardpulse.android.core.FriendsAvailability
import app.cardpulse.android.core.FriendsSession
import app.cardpulse.android.core.Fixtures
import app.cardpulse.android.core.ShareLevel
import app.cardpulse.android.core.ShareSection
import app.cardpulse.android.core.SharingUpdateBody
import app.cardpulse.android.core.friendsAvailability
import app.cardpulse.android.core.sharing
import app.cardpulse.android.core.supported
import app.cardpulse.android.core.total
import app.cardpulse.android.core.userMessage
import app.cardpulse.android.data.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import java.util.concurrent.TimeUnit

/**
 * The Friends calls over the real HTTP stack (Retrofit, converters, interceptors) against a local server that replays what the
 * patched PokéCollector's own router returned: the paths, methods and bodies the app puts on the wire, and how it reads what
 * comes back.
 */
class FriendsHttpTest {
    private lateinit var server: MockWebServer
    private lateinit var session: SessionHolder
    private lateinit var client: OkHttpClient
    private lateinit var repo: Repository
    private var expiredCalls = 0

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        session = SessionHolder().apply {
            serverUrl = server.url("/").toString()
            token = "fixture-token-not-a-real-credential"
        }
        expiredCalls = 0
        client = HttpClientFactory.create(session, debug = false) { expiredCalls++ }
        repo = Repository(HttpClientFactory.retrofit(client, AppJson).create(PokeApi::class.java), session)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun fixture(name: String, code: Int = 200) = json(Fixtures.text(name), code)

    private fun next(): RecordedRequest = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS)) { "no request arrived" }

    private fun RecordedRequest.bodyText(): String = body.readUtf8()

    // --- the user's own side -------------------------------------------------------------------------

    @Test
    fun `asking for me is one get and tells the app the server has friends`() = runBlocking {
        server.enqueue(fixture("friends_me"))
        val me = repo.friendsMe()
        val request = next()
        assertEquals("GET", request.method)
        assertEquals("/api/friends/me", request.path)
        assertEquals("K7MQ9-XWD3H", me.inviteCode)
        assertEquals(3, me.counts.friends)
    }

    @Test
    fun `the friends and both kinds of request come in one get`() = runBlocking {
        server.enqueue(fixture("friends_overview"))
        val overview = repo.friendsOverview()
        assertEquals("/api/friends/", next().path)
        assertEquals(3, overview.friends.size)
        assertEquals("dawn", overview.incoming.single().user.username)
    }

    @Test
    fun `choosing who can see a list puts only that section`() = runBlocking {
        server.enqueue(fixture("friends_sharing_set"))
        val saved = repo.updateSharing(SharingUpdateBody(trade = "friends"))
        val request = next()
        assertEquals("PUT", request.method)
        assertEquals("/api/friends/me/sharing", request.path)
        assertEquals("""{"trade":"friends"}""", request.bodyText())
        assertEquals("public", saved.trade)
    }

    @Test
    fun `a new invite code is an empty post and the new code comes back`() = runBlocking {
        server.enqueue(fixture("friends_invite_code_new"))
        assertEquals("M4PQ7-RSTV2", repo.newInviteCode())
        val request = next()
        assertEquals("POST", request.method)
        assertEquals("/api/friends/me/invite-code", request.path)
    }

    // --- requests ------------------------------------------------------------------------------------

    @Test
    fun `a request by username sends the username and nothing else`() = runBlocking {
        server.enqueue(fixture("friends_request_pending"))
        val result = repo.sendFriendRequest(FriendRequestBody(username = "serena"))
        val request = next()
        assertEquals("POST", request.method)
        assertEquals("/api/friends/requests", request.path)
        assertEquals("""{"username":"serena"}""", request.bodyText())
        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("application/json"))
        assertEquals("pending", result.status)
    }

    @Test
    fun `a request by invite code sends the code and nothing else`() = runBlocking {
        server.enqueue(fixture("friends_request_by_code"))
        repo.sendFriendRequest(FriendRequestBody(inviteCode = "K7MQ9-XWD3H"))
        assertEquals("""{"invite_code":"K7MQ9-XWD3H"}""", next().bodyText())
    }

    @Test
    fun `accepting declining cancelling and ending use the paths the server has`() = runBlocking {
        server.enqueue(fixture("friends_accept"))
        server.enqueue(fixture("friends_decline"))
        server.enqueue(fixture("friends_cancel"))
        server.enqueue(fixture("friends_remove"))
        assertEquals("wren", repo.acceptFriendRequest(8).username)
        repo.declineFriendRequest(9)
        repo.cancelFriendRequest(6)
        repo.removeFriend(4)
        val accept = next()
        assertEquals("POST", accept.method)
        assertEquals("/api/friends/requests/8/accept", accept.path)
        val decline = next()
        assertEquals("POST", decline.method)
        assertEquals("/api/friends/requests/9/decline", decline.path)
        val cancel = next()
        assertEquals("DELETE", cancel.method)
        assertEquals("/api/friends/requests/6", cancel.path)
        val remove = next()
        assertEquals("DELETE", remove.method)
        assertEquals("/api/friends/4", remove.path)
    }

    @Test
    fun `an answer counts as done whatever the server replies with`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        repo.declineFriendRequest(9)
        assertEquals("/api/friends/requests/9/decline", next().path)
    }

    // --- For Trade -----------------------------------------------------------------------------------

    @Test
    fun `the users own trade list is one get`() = runBlocking {
        server.enqueue(fixture("friends_own_trade_list"))
        val list = repo.ownTradeList()
        assertEquals("/api/friends/trade-list", next().path)
        assertEquals(2, list.items.size)
    }

    @Test
    fun `marking copies puts only the quantity to the collection row`() = runBlocking {
        server.enqueue(fixture("friends_trade_set"))
        val saved = repo.setTradeQuantity(1, 1)
        val request = next()
        assertEquals("PUT", request.method)
        assertEquals("/api/friends/trade-list/1", request.path)
        assertEquals("""{"quantity":1}""", request.bodyText())
        assertEquals(1, saved.quantity)
    }

    @Test
    fun `offering more copies than are held shows the servers reason`() = runBlocking {
        server.enqueue(fixture("friends_trade_too_many", code = 422))
        val error = runCatching { repo.setTradeQuantity(3, 9) }.exceptionOrNull()
        assertTrue(error is HttpException)
        assertEquals(422, (error as HttpException).code())
        assertEquals("You only have 4 of this card", error.userMessage())
    }

    // --- a friend's lists ----------------------------------------------------------------------------

    @Test
    fun `a friends collection is one get and every row comes with its card`() = runBlocking {
        server.enqueue(fixture("friend_collection_misty"))
        val rows = repo.friendCollection(2)
        assertEquals("/api/friends/2/collection", next().path)
        assertEquals(0, rows.unreadable)
        assertEquals(listOf("Miraidon ex", "Skiploom", "Hoppip", "Charizard ex"), rows.items.map { it.card?.name })
        assertEquals(2, rows.items.first { it.cardId == "sv2-001_en" }.forTradeQuantity)
    }

    @Test
    fun `a friends wishlist and trade list are one get each`() = runBlocking {
        server.enqueue(fixture("friend_wishlist_misty"))
        server.enqueue(fixture("friend_trade_list_misty"))
        assertEquals(3, repo.friendWishlist(2).items.size)
        assertEquals(2, repo.friendTradeList(2).items.size)
        assertEquals("/api/friends/2/wishlist", next().path)
        assertEquals("/api/friends/2/trade-list", next().path)
    }

    @Test
    fun `a trade match is one get with both halves`() = runBlocking {
        server.enqueue(fixture("friend_trade_match_misty"))
        val match = repo.friendTradeMatch(2)
        assertEquals("/api/friends/2/trade-match", next().path)
        assertEquals(2, match.theyHaveYouWant.size)
        assertEquals(2, match.youHaveTheyWant.size)
    }

    @Test
    fun `a row the app cannot read is counted instead of dropped silently or failing the list`() = runBlocking {
        server.enqueue(json("""[{"id": 1, "card_id": "a_en", "quantity": 1}, {"id": "not a number"}, {"id": 3, "card_id": "c_en", "quantity": 2}]"""))
        val rows = repo.friendCollection(2)
        assertEquals(listOf(1, 3), rows.items.map { it.id })
        assertEquals(1, rows.unreadable)
    }

    @Test
    fun `every friends call carries the sign-in like every other call`() = runBlocking {
        repeat(6) { server.enqueue(json("{}")) }
        server.enqueue(json("[]"))
        repo.friendsMe()
        repo.friendsOverview()
        repo.ownTradeList()
        repo.updateSharing(SharingUpdateBody(wishlist = "private"))
        repo.friendTradeMatch(2)
        repo.newInviteCode()
        repo.friendCollection(2)
        repeat(7) { assertEquals("Bearer fixture-token-not-a-real-credential", next().getHeader("Authorization")) }
    }

    // --- refusals ------------------------------------------------------------------------------------

    @Test
    fun `a list a friend does not share is a 403 with the servers words`() = runBlocking {
        server.enqueue(fixture("friend_collection_brock_forbidden", code = 403))
        val error = runCatching { repo.friendCollection(3) }.exceptionOrNull()
        assertTrue(error is HttpException)
        assertEquals(403, (error as HttpException).code())
        assertEquals("brock hasn't shared their collection with you", error.userMessage())
    }

    @Test
    fun `someone who cannot be seen looks like someone who does not exist`() = runBlocking {
        server.enqueue(fixture("friend_collection_not_found", code = 404))
        val error = runCatching { repo.friendCollection(99999) }.exceptionOrNull()
        assertEquals("User not found", error?.userMessage())
    }

    @Test
    fun `a request for a name nobody has shows the servers words`() = runBlocking {
        server.enqueue(fixture("friends_request_not_found", code = 404))
        val error = runCatching { repo.sendFriendRequest(FriendRequestBody(username = "nobody")) }.exceptionOrNull()
        assertEquals("Nobody on this server has that username", error?.userMessage())
    }

    @Test
    fun `a request the server finds invalid shows its first message`() = runBlocking {
        server.enqueue(fixture("friends_request_empty", code = 422))
        val error = runCatching { repo.sendFriendRequest(FriendRequestBody()) }.exceptionOrNull()
        assertEquals("Value error, Send either a username or an invite code", error?.userMessage())
    }

    @Test
    fun `a server without friends answers not found, which is how the app knows`() = runBlocking {
        server.enqueue(fixture("friends_unsupported_server", code = 404))
        val error = runCatching { repo.friendsMe() }.exceptionOrNull()
        assertEquals(FriendsAvailability.MISSING, error?.friendsAvailability())
    }

    @Test
    fun `an expired sign-in is reported once like for every other call`() = runBlocking {
        server.enqueue(fixture("friends_unauthorized", code = 401))
        runCatching { repo.friendsOverview() }
        assertEquals(1, expiredCalls)
    }

    // --- the session over the real stack ----------------------------------------------------------------

    private fun serving(vararg routes: Pair<String, MockResponse>) {
        val byPath = routes.toMap()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                byPath[request.path] ?: MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json").setBody("""{"detail":"Not Found"}""")
        }
    }

    @Test
    fun `starting on an updated server loads friends and marks`() = runBlocking {
        serving(
            "/api/friends/me" to fixture("friends_me"),
            "/api/friends/" to fixture("friends_overview"),
            "/api/friends/trade-list" to fixture("friends_own_trade_list"),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val friends = FriendsSession(repo, scope, describe = { it.userMessage() })
            friends.start()
            // The calls really go over the wire, so the answers arrive a moment later.
            val state = withTimeout(5_000) { friends.state.first { it.supported && it.loaded && it.marksLoaded } }
            assertEquals(listOf("brock", "gary", "misty"), state.friends.map { it.username })
            assertEquals(mapOf(3 to 2, 4 to 1), state.marks)
            assertEquals(ShareLevel.PRIVATE, state.sharing[ShareSection.COLLECTION])
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `starting on a server without the update says so and asks for nothing else`() = runBlocking {
        serving() // every path is a 404
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val friends = FriendsSession(repo, scope, describe = { it.userMessage() })
            friends.start()
            val state = withTimeout(5_000) { friends.state.first { it.availability != FriendsAvailability.UNKNOWN } }
            assertEquals(FriendsAvailability.MISSING, state.availability)
            assertNull(state.me)
            assertEquals(1, server.requestCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `opening a friend loads a page from the server and a refused one is not shared`() = runBlocking {
        serving(
            "/api/friends/me" to fixture("friends_me"),
            "/api/friends/" to fixture("friends_overview"),
            "/api/friends/trade-list" to fixture("friends_own_trade_list"),
            "/api/friends/2/trade-match" to fixture("friend_trade_match_misty"),
            "/api/friends/3/collection" to fixture("friend_collection_brock_forbidden", code = 403),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val friends = FriendsSession(repo, scope, describe = { it.userMessage() })
            friends.start()
            withTimeout(5_000) { friends.state.first { it.supported && it.loaded } }
            friends.loadFriend(2, FriendTab.MATCH)
            friends.loadFriend(3, FriendTab.COLLECTION)
            val views = withTimeout(5_000) {
                friends.state.first { it.views[2]?.match?.loaded == true && it.views[3]?.collection?.notShared == true }
            }.views
            assertEquals(4, views.getValue(2).match.value?.total)
            assertTrue(views.getValue(3).collection.notShared)
            assertEquals("brock hasn't shared their collection with you", views.getValue(3).collection.error)
        } finally {
            scope.cancel()
        }
    }
}
