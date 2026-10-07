package app.cardpulse.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import app.cardpulse.android.core.CollectionItemDto
import app.cardpulse.android.core.DisplayPrefs
import app.cardpulse.android.core.Fixtures
import app.cardpulse.android.core.FriendRequestBody
import app.cardpulse.android.core.FriendRequestResultDto
import app.cardpulse.android.core.FriendsOverviewDto
import app.cardpulse.android.core.OwnTradeListDto
import app.cardpulse.android.core.SharingDto
import app.cardpulse.android.core.SharingUpdateBody
import app.cardpulse.android.core.httpFailure
import app.cardpulse.android.ui.screens.FriendsScreen
import app.cardpulse.android.ui.theme.CardPulseTheme
import java.net.ConnectException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Uses the Friends screen the way a person would, against a real [app.cardpulse.android.core.FriendsSession] and a pretend server
 * that answers with what the Friends update's own router sent when it was run (see [FriendsWorld]): reads the friends and the
 * requests, adds someone by username and by invite code, accepts and declines, chooses who can see each list, copies the invite
 * code, and sees what is said on a server that cannot do any of this. Like the screen pictures it only runs when asked for
 * (`-Pscreenshots`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xxhdpi")
class FriendsBehaviourTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val world = FriendsWorld()
    private val fake get() = world.fake
    private val session get() = world.session

    private val state = AppState(
        booting = false,
        serverUrl = "https://cards.example.com/",
        signedIn = true,
        prefs = DisplayPrefs(currency = "USD", rateFromEur = 1.1),
        collection = Fixtures.decode<List<CollectionItemDto>>("collection"),
        collectionLoaded = true,
    )

    private var left = 0
    private val copied = mutableListOf<String>()

    @After
    fun closeTheWorld() = world.close()

    private fun show() {
        compose.setContent {
            val friends by session.state.collectAsState()
            val trade = rememberTradeControls(friends, session) {}
            CardPulseTheme {
                CompositionLocalProvider(LocalTrade provides trade) {
                    FriendsScreen(app = state, session = session, onBack = { left++ }, onCopyCode = { copied += it })
                }
            }
        }
    }

    private fun openRequests() = compose.onNodeWithText("Requests (1)").performClick()

    private fun openSharing() = compose.onNodeWithText("Sharing").performClick()

    private fun typeIntoTheField(text: String) = compose.onNode(hasSetTextAction()).performTextInput(text)

    // --- the list of friends ---------------------------------------------------------------------------

    @Test
    fun theHeaderCountsTheFriendsAndTheRequestsWaiting() {
        show()
        compose.onNodeWithText("3 friends · 1 request").assertExists()
        compose.onNodeWithText("Requests (1)").assertExists()
        compose.onNodeWithText("Sharing").assertExists()
    }

    @Test
    fun eachFriendIsListedWithWhatTheyShareWithYou() {
        show()
        compose.onNodeWithText("brock").assertExists()
        compose.onNodeWithText("Shares For Trade").assertExists()
        compose.onNodeWithText("gary").assertExists()
        compose.onNodeWithText("Hasn't shared anything with you yet").assertExists()
        compose.onNodeWithText("misty").assertExists()
        compose.onNodeWithText("Shares Collection · Wishlist · For Trade").assertExists()
    }

    @Test
    fun withNoFriendsTheScreenSaysHowToAddSome() {
        fake.overview = FriendsOverviewDto()
        show()
        compose.onNodeWithText("No friends yet").assertExists()
        compose.onNodeWithText("0 friends").assertExists()
        compose.onNodeWithText("Add a friend").assertExists()
    }

    @Test
    fun backLeavesTheScreen() {
        show()
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, left)
    }

    @Test
    fun theRefreshButtonAsksTheServerForEverythingAgain() {
        show()
        assertEquals(1, fake.count("friendsMe"))
        fake.overview = fake.overview.copy(incoming = emptyList())
        compose.onNodeWithContentDescription("Refresh your friends").performClick()
        assertEquals(2, fake.count("friendsMe"))
        compose.onNodeWithText("3 friends").assertExists() // the request that was waiting is gone
        compose.onNodeWithText("Requests").assertExists()
    }

    // --- adding a friend ------------------------------------------------------------------------------

    @Test
    fun addingSomeoneByUsernameSendsTheRequestAndSaysSo() {
        show()
        compose.onNodeWithText("Send request").assertIsNotEnabled() // nothing is typed yet
        typeIntoTheField("serena")
        compose.onNodeWithText("Send request").assertIsEnabled().performClick()
        assertEquals(listOf(FriendRequestBody(username = "serena")), fake.sentRequests)
        compose.onNodeWithText("Request sent to serena. They will see it on their Friends screen.").assertExists()
        compose.onNodeWithText("Send request").assertIsNotEnabled() // the field was cleared
    }

    @Test
    fun aUsernameIsTrimmedBeforeItIsSent() {
        show()
        typeIntoTheField("  serena  ")
        compose.onNodeWithText("Send request").performClick()
        assertEquals(listOf(FriendRequestBody(username = "serena")), fake.sentRequests)
    }

    @Test
    fun addingSomeoneByInviteCodeSendsTheCodeAsTheServerKeepsIt() {
        show()
        compose.onNodeWithText("Invite code").performClick()
        compose.onNodeWithText("Their invite code").assertExists()
        compose.onNodeWithText("Send request").assertIsNotEnabled()
        typeIntoTheField("k7mq9 xwd3h") // any case, with or without the dash
        compose.onNodeWithText("Send request").assertIsEnabled().performClick()
        assertEquals(listOf(FriendRequestBody(inviteCode = "K7MQ9-XWD3H")), fake.sentRequests)
        compose.onNodeWithText("Request sent to serena. They will see it on their Friends screen.").assertExists()
    }

    @Test
    fun somethingThatCannotBeAnInviteCodeIsNotSent() {
        show()
        compose.onNodeWithText("Invite code").performClick()
        typeIntoTheField("hello")
        compose.onNodeWithText("Send request").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextClearance()
        typeIntoTheField("0000000000") // ten characters, but 0 is never in a code
        compose.onNodeWithText("Send request").assertIsNotEnabled()
        assertEquals(emptyList<FriendRequestBody>(), fake.sentRequests)
    }

    @Test
    fun aRequestTheServerRefusesIsExplainedAndWhatWasTypedIsKept() {
        fake.errors["sendFriendRequest"] = httpFailure(404, "Nobody on this server has that username")
        show()
        typeIntoTheField("nobody")
        compose.onNodeWithText("Send request").performClick()
        compose.onNodeWithText("Nobody on this server has that username").assertExists()
        compose.onNodeWithText("Send request").assertIsEnabled() // so it can be corrected and sent again
    }

    @Test
    fun askingSomeoneWhoAskedFirstMakesYouFriendsAtOnce() {
        fake.requestResult = Fixtures.decode<FriendRequestResultDto>("friends_request_accepted_at_once")
        show()
        typeIntoTheField("dawn")
        compose.onNodeWithText("Send request").performClick()
        compose.onNodeWithText("You and dawn are friends now.").assertExists()
    }

    @Test
    fun theModeIsChosenWithChipsAndSwitchingClearsWhatWasTyped() {
        show()
        compose.onNodeWithText("Username").assertIsSelected()
        typeIntoTheField("serena")
        compose.onNodeWithText("Invite code").performClick()
        compose.onNodeWithText("Invite code").assertIsSelected()
        compose.onNodeWithText("Username").assertIsNotSelected()
        compose.onNodeWithText("Send request").assertIsNotEnabled() // "serena" is not carried over as a code
    }

    // --- requests -------------------------------------------------------------------------------------

    @Test
    fun theRequestsTabListsWhoAskedAndWhoWasAsked() {
        show()
        openRequests()
        compose.onNodeWithText("Asking to be your friend").assertExists()
        compose.onNodeWithText("dawn").assertExists()
        compose.onNodeWithText("Wants to be your friend").assertExists()
        compose.onNodeWithContentDescription("Accept dawn").assertExists()
        compose.onNodeWithContentDescription("Decline dawn").assertExists()
        compose.onNodeWithText("Waiting for an answer").assertExists()
        compose.onNodeWithText("may").assertExists()
        compose.onNodeWithText("Request sent").assertExists()
        compose.onNodeWithContentDescription("Cancel the request to may").assertExists()
    }

    @Test
    fun acceptingARequestMakesTheRequesterAFriend() {
        show()
        openRequests()
        compose.onNodeWithContentDescription("Accept dawn").performClick()
        assertEquals(1, fake.count("acceptFriendRequest"))
        compose.onNodeWithText("4 friends").assertExists()
        compose.onNodeWithText("Requests").assertExists() // nobody is waiting any more, so no number
        compose.onAllNodesWithContentDescription("Accept dawn").assertCountEquals(0)
        compose.onNode(hasText("Friends") and isSelectable()).performClick()
        compose.onNodeWithText("dawn").assertExists()
    }

    @Test
    fun decliningARequestMakesNobodyAFriend() {
        show()
        openRequests()
        compose.onNodeWithContentDescription("Decline dawn").performClick()
        assertEquals(1, fake.count("declineFriendRequest"))
        compose.onNodeWithText("3 friends").assertExists()
        compose.onAllNodesWithContentDescription("Decline dawn").assertCountEquals(0)
        compose.onNode(hasText("Friends") and isSelectable()).performClick()
        compose.onAllNodesWithText("dawn").assertCountEquals(0)
    }

    @Test
    fun aRequestYouMadeCanBeTakenBack() {
        show()
        openRequests()
        compose.onNodeWithContentDescription("Cancel the request to may").performClick()
        assertEquals(1, fake.count("cancelFriendRequest"))
        compose.onAllNodesWithText("may").assertCountEquals(0)
    }

    @Test
    fun anAnswerThatFailsIsSaidAndTheRequestStaysToBeAnsweredAgain() {
        fake.errors["acceptFriendRequest"] = httpFailure(500, "Something broke")
        show()
        openRequests()
        compose.onNodeWithContentDescription("Accept dawn").performClick()
        compose.onNodeWithText("Something broke").assertExists()
        compose.onNodeWithContentDescription("Accept dawn").assertIsEnabled()
        compose.onNodeWithText("3 friends · 1 request").assertExists()
    }

    @Test
    fun withNoRequestsTheTabSaysSoAndThatARequestSharesNothing() {
        fake.overview = fake.overview.copy(incoming = emptyList(), outgoing = emptyList())
        show()
        compose.onNodeWithText("Requests").performClick()
        compose.onNodeWithText("No requests waiting").assertExists()
        compose.onNodeWithText("When someone asks to be your friend it appears here, and you choose whether to accept. A request shares nothing by itself.")
            .assertExists()
    }

    // --- what you share -------------------------------------------------------------------------------

    @Test
    fun everyListStartsPrivate() {
        show()
        openSharing()
        compose.onNodeWithText("Who can see what").assertExists()
        for (section in listOf("Collection", "Wishlist", "For Trade")) {
            compose.onNodeWithContentDescription("$section: Only me").assertIsSelected()
            compose.onNodeWithContentDescription("$section: Friends").assertIsNotSelected()
            compose.onNodeWithContentDescription("$section: Everyone").assertIsNotSelected()
        }
        compose.onAllNodesWithText("Nobody else can see it.").assertCountEquals(3)
    }

    @Test
    fun choosingWhoCanSeeAListChangesOnlyThatList() {
        fake.sharingAnswer = SharingDto(collection = "private", wishlist = "friends", trade = "private")
        show()
        openSharing()
        compose.onNodeWithContentDescription("Wishlist: Friends").performClick()
        assertEquals(listOf(SharingUpdateBody(wishlist = "friends")), fake.sharingBodies)
        compose.onNodeWithContentDescription("Wishlist: Friends").assertIsSelected()
        compose.onNodeWithContentDescription("Wishlist: Only me").assertIsNotSelected()
        compose.onNodeWithContentDescription("Collection: Only me").assertIsSelected()
        compose.onNodeWithContentDescription("For Trade: Only me").assertIsSelected()
        compose.onNodeWithText("The friends you have accepted.").assertExists()
        compose.onAllNodesWithText("Nobody else can see it.").assertCountEquals(2)
    }

    @Test
    fun everyoneMeansEveryoneWithAnAccountOnThisServer() {
        fake.sharingAnswer = SharingDto(collection = "public", wishlist = "private", trade = "private")
        show()
        openSharing()
        compose.onNodeWithContentDescription("Collection: Everyone").performClick()
        assertEquals(listOf(SharingUpdateBody(collection = "public")), fake.sharingBodies)
        compose.onNodeWithText("Everyone with an account on this server.").assertExists()
    }

    @Test
    fun aChoiceTheServerRefusesIsNotShownAsMade() {
        fake.errors["updateSharing"] = httpFailure(403, "Not allowed")
        show()
        openSharing()
        compose.onNodeWithContentDescription("Collection: Everyone").performClick()
        compose.onNodeWithContentDescription("Collection: Only me").assertIsSelected()
        compose.onNodeWithContentDescription("Collection: Everyone").assertIsNotSelected()
        compose.onNodeWithText("Couldn't save that. Not allowed").assertExists()
    }

    @Test
    fun theSharingChoicesTheServerAlreadyHasAreShown() {
        fake.me = fake.me.copy(sharing = SharingDto(collection = "friends", wishlist = "public", trade = "friends"))
        show()
        openSharing()
        compose.onNodeWithContentDescription("Collection: Friends").assertIsSelected()
        compose.onNodeWithContentDescription("Wishlist: Everyone").assertIsSelected()
        compose.onNodeWithContentDescription("For Trade: Friends").assertIsSelected()
    }

    // --- the invite code --------------------------------------------------------------------------------

    @Test
    fun theInviteCodeIsShownAndCanBeCopied() {
        show()
        openSharing()
        compose.onNodeWithText("K7MQ9-XWD3H").assertExists()
        compose.onNodeWithContentDescription("Your invite code is K 7 M Q 9 dash X W D 3 H").assertExists()
        compose.onNodeWithText("Copy").performClick()
        assertEquals(listOf("K7MQ9-XWD3H"), copied)
        compose.onNodeWithText("Copied").assertExists()
    }

    @Test
    fun aNewInviteCodeIsOnlyMadeOnceTheUserHasConfirmed() {
        show()
        openSharing()
        compose.onNodeWithText("New code…").performClick()
        compose.onNodeWithText("The old code stops working at once. Friends you already have stay your friends.").assertExists()
        assertEquals(0, fake.count("newInviteCode"))
        compose.onNodeWithText("Keep this one").performClick()
        compose.onAllNodesWithText("Make a new code").assertCountEquals(0)
        assertEquals(0, fake.count("newInviteCode"))

        compose.onNodeWithText("New code…").performClick()
        compose.onNodeWithText("Make a new code").performClick()
        assertEquals(1, fake.count("newInviteCode"))
        compose.onNodeWithText("M4PQ7-RSTV2").assertExists()
        compose.onAllNodesWithText("K7MQ9-XWD3H").assertCountEquals(0)
    }

    // --- the user's For Trade list, from here -------------------------------------------------------------

    @Test
    fun theSharingTabCountsTheCopiesMarkedForTrade() {
        show()
        openSharing()
        // Two of the four Oddish and one of the two Miraidon ex.
        compose.onNodeWithText("3 cards are marked for trade").assertExists()
    }

    @Test
    fun withNothingMarkedTheSharingTabSaysSo() {
        fake.marks = OwnTradeListDto()
        show()
        openSharing()
        compose.onNodeWithText("Nothing is marked for trade").assertExists()
    }

    @Test
    fun yourForTradeListShowsOnlyWhatYouMarkedAndHowMany() {
        show()
        openSharing()
        compose.onNodeWithText("Your For Trade list").performClick()
        compose.onNodeWithText("Only you can see this list for now.").assertExists()
        compose.onNodeWithText("Miraidon ex").assertExists()
        compose.onNodeWithText("Oddish").assertExists()
        compose.onNodeWithText("1 of 2 for trade").assertExists()
        compose.onNodeWithText("2 of 4 for trade").assertExists()
        // Charizard ex and the rest are in the collection, but nothing says they are for trade.
        compose.onAllNodesWithText("Charizard ex").assertCountEquals(0)
        compose.onNodeWithText("A card is added here from its details in your Collection. Duplicates are never added for you.").assertExists()
    }

    @Test
    fun theCopiesOnOfferCanBeChangedFromTheListAndOnlyThatRowChanges() {
        show()
        openSharing()
        compose.onNodeWithText("Your For Trade list").performClick()
        // Miraidon ex comes first, then Oddish.
        compose.onAllNodesWithContentDescription("Offer one more copy for trade")[1].performClick()
        assertEquals(listOf(3 to 3), fake.tradeCalls)
        compose.onNodeWithText("3 of 4 for trade").assertExists()
        compose.onNodeWithText("1 of 2 for trade").assertExists() // untouched
    }

    @Test
    fun theListLinksBackToWhoCanSeeIt() {
        show()
        openSharing()
        compose.onNodeWithText("Your For Trade list").performClick()
        compose.onNodeWithText("Change who can see it").performClick()
        compose.onNodeWithText("Who can see what").assertExists()
    }

    // --- a server that cannot do this ----------------------------------------------------------------------

    @Test
    fun aServerWithoutTheUpdateSaysSoAndOffersToCheckAgain() {
        fake.errors["friendsMe"] = httpFailure(404, "Not Found")
        show()
        compose.onNodeWithText("Your server needs the Friends update").assertExists()
        compose.onNodeWithText("Everything else in CardPulse works without it.").assertExists()
        compose.onAllNodesWithText("Sharing").assertCountEquals(0) // no tabs, and nothing was asked for
        assertEquals(0, fake.count("friendsOverview") + fake.count("ownTradeList"))

        fake.errors.clear() // the owner installs the update
        compose.onNodeWithText("Check again").performClick()
        compose.onNodeWithText("Sharing").assertExists()
        compose.onNodeWithText("misty").assertExists()
    }

    @Test
    fun aServerWithMultiUserModeOffSaysWhyNothingCouldBeKeptPrivate() {
        fake.errors["friendsMe"] = httpFailure(403, "Friends needs multi-user mode.")
        show()
        compose.onNodeWithText("Friends needs multi-user mode").assertExists()
        compose.onNodeWithText(
            "With multi-user mode off nobody has to sign in, so nothing could be kept private. " +
                "Turn multi-user mode on in your server's settings, then check again.",
        ).assertExists()
        fake.errors.clear()
        compose.onNodeWithText("Check again").performClick()
        compose.onNodeWithText("misty").assertExists()
    }

    @Test
    fun aServerThatCannotBeReachedOffersToTryAgain() {
        fake.errors["friendsMe"] = ConnectException("refused")
        show()
        compose.onNodeWithText("Couldn't reach your server.").assertExists()
        compose.onNodeWithText("Can't connect to the server. Check the address and that it is running.").assertExists()
        fake.errors.clear()
        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithText("misty").assertExists()
    }

    @Test
    fun friendsThatCouldNotBeLoadedAreSaidSoWhileTheRestOfTheScreenWorks() {
        fake.errors["friendsOverview"] = httpFailure(500, "Boom")
        show()
        compose.onNodeWithText("Couldn't load your friends. Boom").assertExists()
        compose.onNodeWithText("Add a friend").assertExists()
        openSharing()
        compose.onNodeWithText("K7MQ9-XWD3H").assertExists() // what the server said about the user is still there
    }
}
