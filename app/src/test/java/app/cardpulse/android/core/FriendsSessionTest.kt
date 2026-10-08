package app.cardpulse.android.core

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val MISTY = 2
private const val GARY = 4

class FriendsSessionTest {
    private lateinit var fake: FakeFriends
    private lateinit var scope: CoroutineScope
    private lateinit var session: FriendsSession

    @Before
    fun setUp() {
        fake = FakeFriends()
        // Unconfined: a call that does not wait for the fake has finished by the time the session's function returns.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        session = FriendsSession(fake, scope, describe = { it.userMessage() })
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private val state get() = session.state.value

    private fun started() {
        session.start()
        assertTrue(state.supported && state.loaded && state.marksLoaded)
    }

    // --- finding out about the server --------------------------------------------------------------

    @Test
    fun startingLoadsWhatTheServerSaysAboutFriendsRequestsAndMarks() {
        session.start()

        assertEquals(FriendsAvailability.SUPPORTED, state.availability)
        assertFalse(state.checking)
        assertEquals("K7MQ9-XWD3H", state.inviteCode)
        assertEquals(listOf("brock", "gary", "misty"), state.friends.map { it.username })
        assertEquals(listOf("dawn"), state.incoming.map { it.user.username })
        assertEquals(listOf("may"), state.outgoing.map { it.user.username })
        assertTrue(state.loaded)
        assertFalse(state.loading)
        assertNull(state.error)
        assertTrue(state.marksLoaded)
        assertEquals(mapOf(3 to 2, 4 to 1), state.marks)
    }

    @Test
    fun startingAgainOnceItHasWorkedAsksNothing() {
        session.start()
        session.start()
        assertEquals(1, fake.count("friendsMe"))
        assertEquals(1, fake.count("friendsOverview"))
    }

    @Test
    fun refreshingAsksForEverythingAgain() {
        session.start()
        fake.overview = fake.overview.copy(incoming = emptyList())
        session.refresh()
        assertEquals(2, fake.count("friendsMe"))
        assertTrue(state.incoming.isEmpty())
    }

    @Test
    fun aServerWithoutTheUpdateIsSaidSoAndNothingElseIsAsked() {
        fake.errors["friendsMe"] = httpFailure(404, "Not Found")

        session.start()

        assertEquals(FriendsAvailability.MISSING, state.availability)
        assertEquals("Not Found", state.availabilityNote)
        assertFalse(state.checking)
        assertNull(state.me)
        assertEquals(0, fake.count("friendsOverview") + fake.count("ownTradeList"))
        assertFalse(state.loaded)
    }

    @Test
    fun onceTheServerIsUpdatedAskingAgainWorks() {
        fake.errors["friendsMe"] = httpFailure(404, "Not Found")
        session.start()
        fake.errors.clear()

        session.start()

        assertEquals(FriendsAvailability.SUPPORTED, state.availability)
        assertNull(state.availabilityNote)
        assertTrue(state.loaded)
    }

    @Test
    fun aServerInSingleUserModeSaysItsOwnReason() {
        fake.errors["friendsMe"] = httpFailure(403, "Friends needs multi-user mode. With it off nobody has to sign in, so nothing can be kept private.")
        session.start()
        assertEquals(FriendsAvailability.NEEDS_MULTI_USER, state.availability)
        assertEquals("Friends needs multi-user mode. With it off nobody has to sign in, so nothing can be kept private.", state.availabilityNote)
    }

    @Test
    fun noConnectionIsWorthTryingAgain() {
        fake.errors["friendsMe"] = IOException("offline")
        session.start()
        assertEquals(FriendsAvailability.FAILED, state.availability)
        assertEquals("offline", state.availabilityNote)
        fake.errors.clear()
        session.start()
        assertTrue(state.supported)
    }

    @Test
    fun theFriendsCanFailToLoadWithoutLosingTheRest() {
        fake.errors["friendsOverview"] = httpFailure(500, "boom")
        session.start()
        assertTrue(state.supported)
        assertEquals("boom", state.error)
        assertFalse(state.loaded)
        assertFalse(state.loading)
        assertTrue(state.marksLoaded)
        fake.errors.clear()
        session.refresh()
        assertNull(state.error)
        assertTrue(state.loaded)
    }

    @Test
    fun theMarksCanFailToLoadAndBeLoadedAgain() {
        fake.errors["ownTradeList"] = IOException("offline")
        session.start()
        assertFalse(state.marksLoaded)
        assertEquals("offline", state.marksError)
        // Until they have loaded nothing can be marked.
        session.setTrade(3, 1)
        assertEquals(0, fake.count("setTradeQuantity"))
        fake.errors.clear()
        session.reloadMarks()
        assertTrue(state.marksLoaded)
        assertNull(state.marksError)
        assertEquals(mapOf(3 to 2, 4 to 1), state.marks)
    }

    // --- signing out -------------------------------------------------------------------------------

    @Test
    fun signingOutForgetsEverything() {
        started()
        session.loadFriend(MISTY, FriendTab.TRADE)
        session.reset()
        assertEquals(FriendsState(), state)
    }

    @Test
    fun answersStillOnTheirWayAfterSigningOutAreIgnored() {
        val gate = fake.hold("friendsMe")
        session.start()
        assertTrue(state.checking)
        session.reset()
        assertFalse(state.checking)

        // The next person signs in and gets their own answer...
        fake.gates.clear()
        fake.me = fake.me.copy(user = PersonDto(9, "zed"), inviteCode = "QQQQQ-QQQQQ")
        session.start()
        assertEquals("zed", state.me?.user?.username)

        // ...which the earlier account's late answer does not overwrite.
        gate.complete(Unit)
        assertEquals("zed", state.me?.user?.username)
        assertTrue(state.supported)
    }

    @Test
    fun aLateListAfterSigningOutDoesNotAppearForTheNextAccount() {
        started()
        val gate = fake.hold("friendsOverview")
        session.refresh()
        session.reset()
        gate.complete(Unit)
        assertTrue(state.friends.isEmpty())
        assertFalse(state.loaded)
        assertEquals(FriendsAvailability.UNKNOWN, state.availability)
    }

    // --- adding a friend ---------------------------------------------------------------------------

    @Test
    fun aRequestIsSentAsTypedAndTheListsAreLoadedAgain() {
        started()
        var worked: Boolean? = null

        session.sendRequest(AddFriendMode.USERNAME, "  serena ") { worked = it }

        assertEquals(listOf(FriendRequestBody(username = "serena")), fake.sentRequests)
        assertEquals(Notice("Request sent to serena. They will see it on their Friends screen."), state.notice)
        assertFalse(state.sending)
        assertEquals(true, worked)
        assertEquals(2, fake.count("friendsOverview"))
    }

    @Test
    fun anInviteCodeIsSentInTheServersFormat() {
        started()
        session.sendRequest(AddFriendMode.CODE, "k7mq9xwd3h")
        assertEquals(listOf(FriendRequestBody(inviteCode = "K7MQ9-XWD3H")), fake.sentRequests)
    }

    @Test
    fun askingSomeoneWhoAskedFirstMakesYouFriends() {
        started()
        fake.requestResult = Fixtures.decode("friends_request_accepted_at_once")
        session.sendRequest(AddFriendMode.USERNAME, "dawn")
        assertEquals(Notice("You and dawn are friends now."), state.notice)
    }

    @Test
    fun nothingIsSentForWhatCannotBeRight() {
        started()
        var worked: Boolean? = null
        session.sendRequest(AddFriendMode.USERNAME, "   ") { worked = it }
        assertEquals(Notice("Type their username.", isError = true), state.notice)
        assertEquals(false, worked)
        session.sendRequest(AddFriendMode.CODE, "nope") { worked = it }
        assertEquals(Notice("That doesn't look like an invite code.", isError = true), state.notice)
        assertTrue(fake.sentRequests.isEmpty())
    }

    @Test
    fun theServersWordsOnAFailureAreShownAndTheFieldIsKept() {
        started()
        fake.errors["sendFriendRequest"] = httpFailure(404, "Nobody on this server has that username")
        var worked: Boolean? = null

        session.sendRequest(AddFriendMode.USERNAME, "nobody") { worked = it }

        assertEquals(Notice("Nobody on this server has that username", isError = true), state.notice)
        assertEquals(false, worked)
        assertFalse(state.sending)
        // The lists were not asked for again: nothing changed.
        assertEquals(1, fake.count("friendsOverview"))
    }

    @Test
    fun aSecondTapWhileTheRequestIsOnItsWayDoesNothing() {
        started()
        val gate = fake.hold("sendFriendRequest")
        session.sendRequest(AddFriendMode.USERNAME, "serena")
        assertTrue(state.sending)
        session.sendRequest(AddFriendMode.USERNAME, "serena")
        assertEquals(1, fake.sentRequests.size)
        gate.complete(Unit)
        assertFalse(state.sending)
    }

    @Test
    fun aNoticeCanBeDismissed() {
        started()
        session.sendRequest(AddFriendMode.USERNAME, "serena")
        assertNotNull(state.notice)
        session.dismissNotice()
        assertNull(state.notice)
    }

    // --- answering requests ------------------------------------------------------------------------

    @Test
    fun acceptingMakesTheRequesterAFriend() {
        started()
        session.accept(4)
        assertTrue(state.friends.any { it.username == "dawn" })
        assertTrue(state.incoming.isEmpty())
        assertTrue(state.answering.isEmpty())
        assertNull(state.requestError)
    }

    @Test
    fun decliningAndCancellingRemoveTheRequest() {
        started()
        session.decline(4)
        assertTrue(state.incoming.isEmpty())
        session.cancel(5)
        assertTrue(state.outgoing.isEmpty())
        assertEquals(listOf("brock", "gary", "misty"), state.friends.map { it.username })
    }

    @Test
    fun aRequestThatIsAlreadyGoneIsShownAsItIsNotReportedAsAFailure() {
        started()
        fake.errors["acceptFriendRequest"] = httpFailure(404, "Request not found")
        fake.overview = fake.overview.copy(incoming = emptyList())

        session.accept(4)

        assertNull(state.requestError)
        assertTrue(state.incoming.isEmpty())
        assertTrue(state.answering.isEmpty())
    }

    @Test
    fun anotherFailureIsShown() {
        started()
        fake.errors["declineFriendRequest"] = httpFailure(500, "boom")
        session.decline(4)
        assertEquals("boom", state.requestError)
        assertTrue(state.answering.isEmpty())
        assertEquals(1, state.incoming.size)
    }

    @Test
    fun aSecondTapWhileAnAnswerIsOnItsWayDoesNothing() {
        started()
        val gate = fake.hold("acceptFriendRequest")
        session.accept(4)
        assertEquals(setOf(4), state.answering)
        session.accept(4)
        assertEquals(1, fake.count("acceptFriendRequest"))
        gate.complete(Unit)
        assertTrue(state.answering.isEmpty())
    }

    // --- ending a friendship -----------------------------------------------------------------------

    @Test
    fun endingAFriendshipRemovesThemAndWhateverWasLoadedOfThem() {
        started()
        fake.trades[MISTY] = FriendRows(Fixtures.decode("friend_trade_list_misty"))
        session.loadFriend(MISTY, FriendTab.TRADE)
        assertTrue(state.views.containsKey(MISTY))
        var told: String? = "not called"

        session.removeFriend(MISTY) { told = it }

        assertNull(told)
        assertEquals(listOf("brock", "gary"), state.friends.map { it.username })
        assertFalse(state.views.containsKey(MISTY))
    }

    @Test
    fun aFriendshipAlreadyEndedByTheOtherPersonCountsAsEnded() {
        started()
        fake.errors["removeFriend"] = httpFailure(404, "That person isn't one of your friends")
        var told: String? = "not called"
        session.removeFriend(MISTY) { told = it }
        assertNull(told)
        assertEquals(listOf("brock", "gary"), state.friends.map { it.username })
    }

    @Test
    fun aFailureToEndAFriendshipSaysWhyAndKeepsTheFriend() {
        started()
        fake.errors["removeFriend"] = IOException("offline")
        var told: String? = null
        session.removeFriend(MISTY) { told = it }
        assertEquals("offline", told)
        assertEquals(3, state.friends.size)
    }

    // --- what the user shares ----------------------------------------------------------------------

    @Test
    fun aNewChoiceShowsOnlyOnceTheServerHasConfirmedIt() {
        started()
        val gate = fake.hold("updateSharing")

        session.setSharing(ShareSection.TRADE, ShareLevel.FRIENDS)

        assertEquals(listOf(SharingUpdateBody(trade = "friends")), fake.sharingBodies)
        assertTrue(state.sharingBusy)
        assertEquals(ShareLevel.PRIVATE, state.sharing.trade)
        gate.complete(Unit)
        assertFalse(state.sharingBusy)
        assertEquals(ShareLevel.FRIENDS, state.sharing.trade)
        assertEquals(ShareLevel.PRIVATE, state.sharing.collection)
    }

    @Test
    fun aFailureLeavesTheChoiceAsItWasAndSaysWhy() {
        started()
        fake.errors["updateSharing"] = IOException("offline")
        session.setSharing(ShareSection.COLLECTION, ShareLevel.PUBLIC)
        assertEquals("offline", state.sharingError)
        assertFalse(state.sharingBusy)
        assertEquals(ShareLevel.PRIVATE, state.sharing.collection)
        // The next attempt starts clean.
        fake.errors.clear()
        fake.sharingAnswer = SharingDto("public", "private", "private")
        session.setSharing(ShareSection.COLLECTION, ShareLevel.PUBLIC)
        assertNull(state.sharingError)
        assertEquals(ShareLevel.PUBLIC, state.sharing.collection)
    }

    @Test
    fun choosingWhatIsAlreadyChosenAsksNothing() {
        started()
        session.setSharing(ShareSection.WISHLIST, ShareLevel.PRIVATE)
        assertTrue(fake.sharingBodies.isEmpty())
    }

    @Test
    fun onlyOneChoiceChangesAtATime() {
        started()
        val gate = fake.hold("updateSharing")
        session.setSharing(ShareSection.TRADE, ShareLevel.FRIENDS)
        session.setSharing(ShareSection.WISHLIST, ShareLevel.PUBLIC)
        assertEquals(1, fake.sharingBodies.size)
        gate.complete(Unit)
    }

    @Test
    fun nothingIsAskedBeforeTheUsersOwnSideHasLoaded() {
        session.setSharing(ShareSection.TRADE, ShareLevel.FRIENDS)
        session.newInviteCode()
        assertTrue(fake.sharingBodies.isEmpty())
        assertEquals(0, fake.count("newInviteCode"))
    }

    @Test
    fun aNewInviteCodeReplacesTheOldOne() {
        started()
        session.newInviteCode()
        assertEquals("M4PQ7-RSTV2", state.inviteCode)
        assertFalse(state.codeBusy)
        // Friends already made are untouched.
        assertEquals(3, state.friends.size)
    }

    @Test
    fun aFailureToMakeANewCodeKeepsTheOldOne() {
        started()
        fake.errors["newInviteCode"] = IOException("offline")
        session.newInviteCode()
        assertEquals("K7MQ9-XWD3H", state.inviteCode)
        assertEquals("offline", state.codeError)
        assertFalse(state.codeBusy)
    }

    // --- For Trade ---------------------------------------------------------------------------------

    @Test
    fun aMarkShowsAsItsNewCountWhileOnItsWayAndStaysOnceSaved() {
        started()
        val gate = fake.hold("setTradeQuantity")
        var told: String? = "not called"

        session.setTrade(1, 1) { told = it }

        assertEquals(listOf(1 to 1), fake.tradeCalls)
        assertEquals(1, state.markedFor(1))
        assertNull(state.marks[1])
        gate.complete(Unit)
        assertEquals(1, state.marks[1])
        assertTrue(state.marking.isEmpty())
        assertNull(told)
    }

    @Test
    fun theCountTheServerSavedWinsOverTheOneAskedFor() {
        started()
        fake.tradeAnswer = { id, _ -> TradeEntryDto(id, quantity = 2, owned = 2) }
        session.setTrade(4, 5)
        assertEquals(2, state.marks[4])
    }

    @Test
    fun aFailureShowsTheOldCountAgainAndSaysWhy() {
        started()
        fake.errors["setTradeQuantity"] = httpFailure(422, "You only have 4 of this card")
        var told: String? = null

        session.setTrade(3, 9) { told = it }

        assertEquals("You only have 4 of this card", told)
        assertEquals(2, state.markedFor(3))
        assertEquals(mapOf(3 to 2, 4 to 1), state.marks)
        assertTrue(state.marking.isEmpty())
    }

    @Test
    fun zeroTakesTheRowOffTheList() {
        started()
        session.setTrade(3, 0)
        assertEquals(mapOf(4 to 1), state.marks)
        assertEquals(0, state.markedFor(3))
    }

    @Test
    fun aRowWithAChangeOnItsWayIsNotChangedAgain() {
        started()
        val gate = fake.hold("setTradeQuantity")
        session.setTrade(3, 3)
        session.setTrade(3, 4)
        assertEquals(1, fake.tradeCalls.size)
        gate.complete(Unit)
        assertEquals(3, state.marks[3])
    }

    @Test
    fun otherRowsCanBeChangedWhileOneIsOnItsWay() {
        started()
        val gate = fake.hold("setTradeQuantity")
        session.setTrade(3, 3)
        session.setTrade(4, 2)
        assertEquals(listOf(3 to 3, 4 to 2), fake.tradeCalls)
        gate.complete(Unit)
        assertEquals(mapOf(3 to 3, 4 to 2), state.marks)
    }

    @Test
    fun nothingIsMarkedByAnythingButAnExplicitCall() {
        started()
        // Loading everything, refreshing and opening friends never marks a card.
        session.refresh()
        session.loadFriend(MISTY, FriendTab.MATCH)
        assertEquals(0, fake.count("setTradeQuantity"))
        assertEquals(mapOf(3 to 2, 4 to 1), state.marks)
    }

    // --- a friend's lists --------------------------------------------------------------------------

    private fun trade(): FriendRows<TradeItemDto> = FriendRows(Fixtures.decode<List<TradeItemDto>>("friend_trade_list_misty"))

    @Test
    fun aFriendsPageLoadsOnceAndAgainOnlyWhenAskedTo() {
        started()
        fake.trades[MISTY] = trade()

        session.loadFriend(MISTY, FriendTab.TRADE)
        session.loadFriend(MISTY, FriendTab.TRADE)

        assertEquals(1, fake.count("friendTradeList"))
        assertEquals(listOf("sv3-223_en", "sv2-001_en"), state.views.getValue(MISTY).trade.value?.map { it.cardId })
        session.loadFriend(MISTY, FriendTab.TRADE, force = true)
        assertEquals(2, fake.count("friendTradeList"))
    }

    @Test
    fun aPageIsShownAsLoadingWhileItIsOnItsWay() {
        started()
        val gate = fake.hold("friendWishlist")
        session.loadFriend(MISTY, FriendTab.WISHLIST)
        val loading = state.views.getValue(MISTY).wishlist
        assertTrue(loading.loading)
        assertFalse(loading.loaded)
        // Asking again while it is on its way does nothing.
        session.loadFriend(MISTY, FriendTab.WISHLIST, force = true)
        assertEquals(1, fake.count("friendWishlist"))
        gate.complete(Unit)
        assertFalse(state.views.getValue(MISTY).wishlist.loading)
        assertTrue(state.views.getValue(MISTY).wishlist.loaded)
    }

    @Test
    fun eachPageOfAFriendLoadsOnItsOwn() {
        started()
        fake.matches[MISTY] = Fixtures.decode("friend_trade_match_misty")
        fake.wishlists[MISTY] = FriendRows(Fixtures.decode("friend_wishlist_misty"))
        fake.collections[MISTY] = FriendRows(Fixtures.decode("friend_collection_misty"))

        session.loadFriend(MISTY, FriendTab.MATCH)
        assertEquals(4, state.views.getValue(MISTY).match.value?.total)
        assertFalse(state.views.getValue(MISTY).wishlist.loaded)
        session.loadFriend(MISTY, FriendTab.WISHLIST)
        session.loadFriend(MISTY, FriendTab.COLLECTION)
        assertEquals(3, state.views.getValue(MISTY).wishlist.value?.size)
        assertEquals(4, state.views.getValue(MISTY).collection.value?.size)
        assertFalse(state.views.getValue(MISTY).trade.loaded)
    }

    @Test
    fun aListTheFriendDoesNotShareIsNotAFailureToTryAgain() {
        started()
        fake.errors["friendWishlist"] = httpFailure(403, "gary hasn't shared their wishlist with you")

        session.loadFriend(GARY, FriendTab.WISHLIST)

        val page = state.views.getValue(GARY).wishlist
        assertTrue(page.notShared)
        assertEquals("gary hasn't shared their wishlist with you", page.error)
        assertNull(page.value)
        assertFalse(page.loading)
        // It is not asked for again until the user asks (the server will say the same).
        session.loadFriend(GARY, FriendTab.WISHLIST)
        assertEquals(1, fake.count("friendWishlist"))
        // Once they share it, asking again works.
        fake.errors.clear()
        fake.wishlists[GARY] = FriendRows(Fixtures.decode("friend_wishlist_misty"))
        session.loadFriend(GARY, FriendTab.WISHLIST, force = true)
        assertTrue(state.views.getValue(GARY).wishlist.loaded)
        assertFalse(state.views.getValue(GARY).wishlist.notShared)
        assertNull(state.views.getValue(GARY).wishlist.error)
    }

    @Test
    fun aListThatWasSharedAndIsNowRefusedGoesAtOnce() {
        started()
        fake.wishlists[MISTY] = FriendRows(Fixtures.decode("friend_wishlist_misty"))
        session.loadFriend(MISTY, FriendTab.WISHLIST)
        assertTrue(state.views.getValue(MISTY).wishlist.loaded)

        fake.errors["friendWishlist"] = httpFailure(403, "misty hasn't shared their wishlist with you")
        session.loadFriend(MISTY, FriendTab.WISHLIST, force = true)

        val page = state.views.getValue(MISTY).wishlist
        assertTrue(page.notShared)
        assertNull(page.value)
    }

    @Test
    fun aFailedRefreshKeepsWhatWasLoaded() {
        started()
        fake.trades[MISTY] = trade()
        session.loadFriend(MISTY, FriendTab.TRADE)

        fake.errors["friendTradeList"] = IOException("offline")
        session.loadFriend(MISTY, FriendTab.TRADE, force = true)

        val page = state.views.getValue(MISTY).trade
        assertEquals(2, page.value?.size)
        assertEquals("offline", page.error)
        assertFalse(page.notShared)
        assertFalse(page.loading)
    }

    @Test
    fun aFirstLoadThatFailsCanBeTriedAgain() {
        started()
        fake.errors["friendCollection"] = IOException("offline")
        session.loadFriend(MISTY, FriendTab.COLLECTION)
        assertEquals("offline", state.views.getValue(MISTY).collection.error)
        assertFalse(state.views.getValue(MISTY).collection.loaded)
        fake.errors.clear()
        fake.collections[MISTY] = FriendRows(Fixtures.decode("friend_collection_misty"))
        session.loadFriend(MISTY, FriendTab.COLLECTION)
        assertTrue(state.views.getValue(MISTY).collection.loaded)
        assertNull(state.views.getValue(MISTY).collection.error)
    }

    @Test
    fun rowsThatCouldNotBeReadAreCounted() {
        started()
        fake.collections[MISTY] = FriendRows(Fixtures.decode("friend_collection_misty"), unreadable = 2)
        session.loadFriend(MISTY, FriendTab.COLLECTION)
        assertEquals(2, state.views.getValue(MISTY).collection.unreadable)
    }

    @Test
    fun leavingAFriendKeepsNothingOfTheirs() {
        started()
        fake.trades[MISTY] = trade()
        session.loadFriend(MISTY, FriendTab.TRADE)
        session.forgetFriend(MISTY)
        assertTrue(state.views.isEmpty())
        // Opening them again loads again.
        session.loadFriend(MISTY, FriendTab.TRADE)
        assertEquals(2, fake.count("friendTradeList"))
    }

    @Test
    fun aPersonWhoIsNoLongerAFriendLeavesNothingBehind() {
        started()
        fake.trades[MISTY] = trade()
        fake.trades[GARY] = trade()
        session.loadFriend(MISTY, FriendTab.TRADE)
        session.loadFriend(GARY, FriendTab.TRADE)
        assertEquals(setOf(MISTY, GARY), state.views.keys)

        // Gary removed the friendship from his side.
        fake.overview = fake.overview.copy(friends = fake.overview.friends.filterNot { it.id == GARY })
        session.refresh()

        assertEquals(setOf(MISTY), state.views.keys)
    }

    @Test
    fun aLateAnswerAboutSomeoneWhoIsNoLongerAFriendIsNotKept() {
        started()
        fake.trades[GARY] = trade()
        val gate = fake.hold("friendTradeList")
        session.loadFriend(GARY, FriendTab.TRADE)
        assertTrue(state.views.getValue(GARY).trade.loading)

        // Gary ended the friendship while the page was on its way.
        fake.overview = fake.overview.copy(friends = fake.overview.friends.filterNot { it.id == GARY })
        session.refresh()
        assertNull(state.views[GARY])
        gate.complete(Unit)

        assertNull(state.views[GARY])
    }

    @Test
    fun someoneWhoIsNotAFriendIsNotAskedAbout() {
        started()
        session.loadFriend(99, FriendTab.COLLECTION)
        session.loadFriend(99, FriendTab.MATCH)
        assertEquals(0, fake.count("friendCollection") + fake.count("friendTradeMatch"))
        assertTrue(state.views.isEmpty())
    }

    @Test
    fun theFriendsOwnPagesDoNotDisturbEachOther() {
        started()
        fake.trades[MISTY] = trade()
        session.loadFriend(MISTY, FriendTab.TRADE)
        fake.errors["friendTradeList"] = httpFailure(403, "gary hasn't shared their For Trade list with you")
        session.loadFriend(GARY, FriendTab.TRADE)
        assertTrue(state.views.getValue(MISTY).trade.loaded)
        assertTrue(state.views.getValue(GARY).trade.notShared)
    }
}
