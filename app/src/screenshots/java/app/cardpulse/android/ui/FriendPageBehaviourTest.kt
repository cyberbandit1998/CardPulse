package app.cardpulse.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.cardpulse.android.core.CollectionItemDto
import app.cardpulse.android.core.DisplayPrefs
import app.cardpulse.android.core.Fixtures
import app.cardpulse.android.core.FriendRows
import app.cardpulse.android.core.MoneyFormatter
import app.cardpulse.android.core.OwnTradeListDto
import app.cardpulse.android.core.PersonDto
import app.cardpulse.android.core.TradeItemDto
import app.cardpulse.android.core.TradeMatchDto
import app.cardpulse.android.core.httpFailure
import app.cardpulse.android.ui.screens.FriendsScreen
import app.cardpulse.android.ui.theme.CardPulseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Uses a friend's page the way a person would: opens a friend, reads the trade match and each of the friend's lists, opens a card
 * (with the heart that puts it on the user's own wishlist), and sees what is said about a list the friend does not share. The
 * friends are those of [FriendsWorld]: Misty shares everything, Brock only his For Trade list, and Gary nothing. Like the screen
 * pictures it only runs when asked for (`-Pscreenshots`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xxhdpi")
class FriendPageBehaviourTest {
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

    private val money = MoneyFormatter("USD", 1.1)
    private val toggled = mutableListOf<String>()

    @After
    fun closeTheWorld() = world.close()

    /** The Charizard ex #223 and the Hoppip are on the user's wishlist (they are what the match says Misty has that the user wants). */
    private fun show(listed: Set<String> = setOf("sv3-223_en", "sv2-001_en")) {
        compose.setContent {
            val friends by session.state.collectAsState()
            val trade = rememberTradeControls(friends, session) {}
            CardPulseTheme {
                CompositionLocalProvider(
                    LocalTrade provides trade,
                    LocalWishlist provides WishlistControls(ready = true, listed = listed, toggle = { toggled += it }),
                ) {
                    FriendsScreen(app = state, session = session, onBack = {})
                }
            }
        }
    }

    private fun open(name: String, listed: Set<String> = setOf("sv3-223_en", "sv2-001_en")) {
        show(listed)
        compose.onNodeWithText(name).performClick()
        // The page asks the session for the friend's lists as it appears: let that finish before looking at the session.
        compose.waitForIdle()
    }

    private fun openTab(label: String) = compose.onNodeWithText(label).performClick()

    // --- the page --------------------------------------------------------------------------------------

    @Test
    fun aFriendOpensOnThePageOfTheirNameWithTheTradeMatchFirst() {
        open("misty")
        compose.onNodeWithText("misty").assertExists()
        compose.onNodeWithText("Shares Collection · Wishlist · For Trade").assertExists()
        for (tab in listOf("Trade match", "For trade", "Wishlist", "Collection")) compose.onNodeWithText(tab).assertExists()
        compose.onNodeWithText("Trade match").assertIsSelected()
        compose.onNodeWithContentDescription("Back to Friends").assertExists()
    }

    @Test
    fun goingBackReturnsToTheListAndKeepsNothingOfTheFriend() {
        open("misty")
        assertTrue(session.state.value.views.containsKey(MISTY))
        compose.onNodeWithContentDescription("Back to Friends").performClick()
        compose.onNodeWithText("brock").assertExists()
        compose.onNodeWithText("Add a friend").assertExists()
        assertNull(session.state.value.views[MISTY])
    }

    // --- trade match ------------------------------------------------------------------------------------

    @Test
    fun theMatchHasTheCardsTheyHaveThatYouWantAndTheCardsYouHaveThatTheyWant() {
        open("misty")
        compose.onNodeWithText("2 you could get · 2 you could offer").assertExists()
        compose.onNodeWithText("They have · you want (2)").assertExists()
        compose.onNodeWithText("You have · they want (2)").assertExists()
        for (name in listOf("Charizard ex", "Hoppip", "Miraidon ex", "Oddish")) compose.onNodeWithText(name).assertExists()
    }

    @Test
    fun eachCardOfTheMatchSaysHowManyCopiesAreOnOfferAndWhoWantsHowMany() {
        open("misty")
        compose.onNodeWithText("Offers ×1").assertExists() // Charizard ex #223, theirs
        compose.onNodeWithText("Offers ×2").assertExists() // Hoppip, theirs
        compose.onNodeWithText("You offer ×1").assertExists() // Miraidon ex, yours
        compose.onNodeWithText("You offer ×2").assertExists() // Oddish, yours
        compose.onNodeWithText("They want ×2").assertExists()
        // The user owns two Miraidon ex and four Oddish, and none of what Misty has to offer.
        compose.onNodeWithText("You own ×2").assertExists()
        compose.onNodeWithText("You own ×4").assertExists()
    }

    @Test
    fun theCardsOfTheMatchCarryTheirCurrentPrice() {
        open("misty")
        // Charizard ex #223 at its trend price of 61 euros, shown in the user's currency.
        compose.onNodeWithText(money.format(61.0)).assertExists()
    }

    @Test
    fun theHeartOfAMatchedCardShowsWhetherItIsOnYourWishlistAndChangesIt() {
        open("misty")
        compose.onNodeWithContentDescription("Remove Charizard ex from the wishlist").assertExists() // listed
        compose.onNodeWithContentDescription("Add Miraidon ex to the wishlist").performClick()
        assertEquals(listOf("sv1-198_en"), toggled)
    }

    @Test
    fun aMatchWithNobodySharingSaysThereIsNothingToCompare() {
        open("gary")
        compose.onNodeWithText("No matches yet").assertExists()
        compose.onNodeWithText("gary hasn't shared their For Trade list with you, so there is nothing to compare.").assertExists()
        compose.onNodeWithText("gary hasn't shared their wishlist with you, so there is nothing to compare.").assertExists()
        compose.onNodeWithText("They have · you want (0)").assertExists()
        compose.onNodeWithText("You have · they want (0)").assertExists()
    }

    @Test
    fun aFriendWhoSharesOnlyTheirTradeListStillShowsTheCardsTheyHaveThatYouWant() {
        open("brock")
        compose.onNodeWithText("They have · you want (1)").assertExists()
        compose.onNodeWithText("Jumpluff").assertExists()
        compose.onNodeWithText("You have · they want (0)").assertExists()
        compose.onNodeWithText("brock hasn't shared their wishlist with you, so there is nothing to compare.").assertExists()
    }

    @Test
    fun whenNothingMatchesTheMatchSaysSoForEachHalf() {
        fake.matches[MISTY] = TradeMatchDto(user = PersonDto(MISTY, "misty"), canSeeTheirTradeList = true, canSeeTheirWishlist = true)
        open("misty")
        compose.onNodeWithText("No matches yet").assertExists()
        compose.onNodeWithText("None of misty's For Trade cards are on your wishlist.").assertExists()
        compose.onNodeWithText("None of your For Trade cards are on misty's wishlist.").assertExists()
    }

    @Test
    fun withNoCardsOfYoursMarkedForTradeTheMatchSaysHowToMarkSome() {
        fake.marks = OwnTradeListDto()
        fake.matches[MISTY] = TradeMatchDto(user = PersonDto(MISTY, "misty"), canSeeTheirTradeList = true, canSeeTheirWishlist = true)
        open("misty")
        compose.onNodeWithText("You haven't marked any cards for trade yet. Open a card in your Collection and use “For trade”.").assertExists()
    }

    @Test
    fun aMatchThatCannotBeLoadedOffersToTryAgain() {
        fake.errors["friendTradeMatch"] = httpFailure(500, "Boom")
        open("misty")
        compose.onNodeWithText("Couldn't load misty's trade match.").assertExists()
        compose.onNodeWithText("Boom").assertExists()
        fake.errors.clear()
        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithText("They have · you want (2)").assertExists()
    }

    @Test
    fun refreshingAsksForTheFriendsAgainAndTheMatchAgain() {
        open("misty")
        assertEquals(1, fake.count("friendTradeMatch"))
        compose.onNodeWithContentDescription("Refresh misty").performClick()
        assertEquals(2, fake.count("friendTradeMatch"))
        assertEquals(2, fake.count("friendsOverview"))
    }

    @Test
    fun aFriendWhoEndedTheFriendshipTakesTheUserBackToTheList() {
        open("misty")
        fake.overview = fake.overview.copy(friends = fake.overview.friends.filterNot { it.id == MISTY })
        compose.onNodeWithContentDescription("Refresh misty").performClick()
        compose.onNodeWithText("brock").assertExists()
        compose.onNodeWithText("Add a friend").assertExists()
        compose.onAllNodesWithText("misty").assertCountEquals(0)
    }

    // --- their For Trade list ---------------------------------------------------------------------------

    @Test
    fun theForTradeTabShowsTheCardsTheFriendHasMarked() {
        open("misty")
        openTab("For trade")
        compose.onNodeWithText("2 cards").assertExists()
        compose.onNodeWithText("Charizard ex").assertExists()
        compose.onNodeWithText("Hoppip").assertExists()
        compose.onNodeWithText("For trade ×1").assertExists()
        compose.onNodeWithText("For trade ×2").assertExists()
        // The user owns neither.
        compose.onAllNodesWithText("You don't own it").assertCountEquals(2)
    }

    @Test
    fun aFriendWithNothingOnOfferSaysSo() {
        fake.trades[MISTY] = FriendRows(emptyList<TradeItemDto>())
        open("misty")
        openTab("For trade")
        compose.onNodeWithText("misty hasn't put any cards up for trade.").assertExists()
    }

    @Test
    fun aListIsOnlyAskedForOnceWhileThePageIsOpen() {
        open("misty")
        openTab("For trade")
        openTab("Wishlist")
        openTab("For trade")
        assertEquals(1, fake.count("friendTradeList"))
        assertEquals(1, fake.count("friendWishlist"))
    }

    // --- their wishlist ---------------------------------------------------------------------------------

    @Test
    fun theWishlistTabShowsWhatTheFriendWantsAndWhetherYouHaveIt() {
        open("misty")
        openTab("Wishlist")
        compose.onNodeWithText("3 cards").assertExists()
        compose.onNodeWithText("Skiploom").assertExists()
        compose.onNodeWithText("Wants ×2").assertExists() // Oddish
        compose.onNodeWithText("You own ×4").assertExists() // Oddish: the user can give one
        compose.onNodeWithText("You own ×2").assertExists() // Miraidon ex
        compose.onNodeWithText("You don't own it").assertExists() // Skiploom
    }

    // --- their collection -------------------------------------------------------------------------------

    @Test
    fun theCollectionTabShowsTheirCardsAsTilesWithHowManyAndHowManyAreForTrade() {
        open("misty")
        openTab("Collection")
        compose.onNodeWithText("4 entries · 6 cards").assertExists()
        for (name in listOf("Charizard ex", "Hoppip", "Miraidon ex", "Skiploom")) compose.onNodeWithText(name).assertExists()
        compose.onNodeWithText("×3").assertExists() // three Hoppip
        compose.onNodeWithContentDescription("2 for trade").assertExists()
        compose.onNodeWithContentDescription("1 for trade").assertExists()
    }

    @Test
    fun theirCollectionCanBeSearched() {
        open("misty")
        openTab("Collection")
        compose.onNode(hasSetTextAction()).performTextInput("hop")
        compose.onNodeWithText("1 entry · 3 cards").assertExists()
        compose.onNodeWithText("Hoppip").assertExists()
        compose.onAllNodesWithText("Charizard ex").assertCountEquals(0)
        compose.onNode(hasSetTextAction()).performTextInput("zzz")
        compose.onNodeWithText("Nothing matches “hopzzz”.").assertExists()
    }

    @Test
    fun theirCollectionShowsNoTradeBadgesWhenTheirTradeListIsNotSharedWithYou() {
        // The server sends the number of copies for trade only to someone who may see the For Trade list.
        val rows = Fixtures.decode<List<CollectionItemDto>>("friend_collection_misty").map { it.copy(forTradeQuantity = null) }
        fake.collections[MISTY] = FriendRows(rows)
        open("misty")
        openTab("Collection")
        compose.onNodeWithText("Hoppip").assertExists()
        compose.onAllNodesWithContentDescription("2 for trade").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("1 for trade").assertCountEquals(0)
    }

    @Test
    fun theHeartOnATileOfTheirCollectionPutsTheCardOnYourWishlist() {
        open("misty")
        openTab("Collection")
        compose.onNodeWithContentDescription("Remove Hoppip from the wishlist").assertExists() // listed
        compose.onNodeWithContentDescription("Add Skiploom to the wishlist").performClick()
        assertEquals(listOf("sv2-002_en"), toggled)
    }

    // --- opening a card --------------------------------------------------------------------------------

    @Test
    fun tappingACardOfTheMatchOpensItsDetails() {
        open("misty")
        compose.onNodeWithText("Charizard ex").performClick()
        compose.onNodeWithText("Close").assertExists()
        compose.onNodeWithText("Price now").assertExists()
        compose.onNodeWithText("misty offers").assertExists()
        compose.onNodeWithText("You want").assertExists()
        compose.onNodeWithText("You own").assertExists()
        compose.onNodeWithText("On your wishlist · Remove").assertExists() // the details have the heart's button too
        compose.onNodeWithText("Close").performClick()
        compose.onAllNodesWithText("Close").assertCountEquals(0)
    }

    @Test
    fun tappingATileOfTheirCollectionOpensItsDetailsWithHowManyTheyHave() {
        open("misty")
        openTab("Collection")
        compose.onNodeWithText("Hoppip").performClick()
        compose.onNodeWithText("Close").assertExists()
        compose.onNodeWithText("misty has").assertExists()
        compose.onNodeWithText("Add to wishlist").assertDoesNotExist() // Hoppip is on the wishlist already
        compose.onNodeWithText("On your wishlist · Remove").performClick()
        assertEquals(listOf("sv2-001_en"), toggled)
    }

    @Test
    fun theDetailsOfAWishlistCardSayHowManyTheFriendWantsAndHowManyYouOwn() {
        open("misty")
        openTab("Wishlist")
        compose.onNodeWithText("Oddish").performClick()
        compose.onNodeWithText("misty wants").assertExists()
        compose.onNodeWithText("You own").assertExists()
    }

    // --- what the friend does not share -----------------------------------------------------------------

    @Test
    fun aListTheFriendDoesNotShareIsSaidSoAndIsNotAskedFor() {
        open("gary")
        openTab("For trade")
        compose.onNodeWithText("gary hasn't shared their For Trade list with you").assertExists()
        compose.onNodeWithText("They choose who can see it in Friends, under Sharing.").assertExists()
        openTab("Wishlist")
        compose.onNodeWithText("gary hasn't shared their wishlist with you").assertExists()
        openTab("Collection")
        compose.onNodeWithText("gary hasn't shared their collection with you").assertExists()
        // The page did not even ask: the server would have refused, and nothing of Gary's was loaded.
        assertEquals(0, fake.count("friendTradeList") + fake.count("friendWishlist") + fake.count("friendCollection"))
    }

    @Test
    fun aFriendWhoSharesOnlyTheirTradeListShowsThatListAndNotTheOthers() {
        open("brock")
        openTab("For trade")
        compose.onNodeWithText("Jumpluff").assertExists()
        openTab("Wishlist")
        compose.onNodeWithText("brock hasn't shared their wishlist with you").assertExists()
        openTab("Collection")
        compose.onNodeWithText("brock hasn't shared their collection with you").assertExists()
        assertEquals(0, fake.count("friendWishlist") + fake.count("friendCollection"))
    }

    @Test
    fun aListTheFriendHasStoppedSharingSinceTheLastRefreshIsSaidSoToo() {
        // The app still thought it was shared; the server knows better.
        fake.errors["friendWishlist"] = httpFailure(403, "misty hasn't shared their wishlist with you")
        open("misty")
        openTab("Wishlist")
        compose.onNodeWithText("misty hasn't shared their wishlist with you").assertExists()
        compose.onAllNodesWithText("Try again").assertCountEquals(0) // nothing to retry: the answer is no
    }

    @Test
    fun aListThatCouldNotBeLoadedOffersToTryAgain() {
        fake.errors["friendCollection"] = httpFailure(500, "Boom")
        open("misty")
        openTab("Collection")
        compose.onNodeWithText("Couldn't load misty's collection.").assertExists()
        fake.errors.clear()
        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithText("4 entries · 6 cards").assertExists()
    }

    @Test
    fun rowsTheAppCouldNotReadAreSaidSoInsteadOfSilentlyLeftOut() {
        fake.trades[MISTY] = FriendRows(fake.trades.getValue(MISTY).items, unreadable = 2)
        open("misty")
        openTab("For trade")
        compose.onNodeWithText("2 entries from the server couldn't be read by this app and are hidden.").assertExists()
        compose.onNodeWithText("Charizard ex").assertExists() // the rest is shown
    }

    // --- ending a friendship ----------------------------------------------------------------------------

    @Test
    fun removingAFriendAsksFirstAndThenGoesBackToTheList() {
        open("misty")
        compose.onNodeWithContentDescription("Remove misty from your friends").performClick()
        compose.onNodeWithText("Remove misty?").assertExists()
        compose.onNodeWithText("You will stop seeing each other's lists, at once. You can add each other again later.").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, fake.count("removeFriend"))

        compose.onNodeWithContentDescription("Remove misty from your friends").performClick()
        compose.onNodeWithText("Remove").performClick()
        assertEquals(1, fake.count("removeFriend"))
        compose.onNodeWithText("brock").assertExists()
        compose.onAllNodesWithText("misty").assertCountEquals(0)
        compose.onNodeWithText("2 friends · 1 request").assertExists()
        assertNull(session.state.value.views[MISTY])
    }

    @Test
    fun aFriendshipThatCouldNotBeEndedIsSaidSoAndStays() {
        fake.errors["removeFriend"] = httpFailure(500, "Boom")
        open("misty")
        compose.onNodeWithContentDescription("Remove misty from your friends").performClick()
        compose.onNodeWithText("Remove").performClick()
        compose.onNodeWithText("Couldn't remove misty. Boom").assertExists()
        compose.onNodeWithText("Trade match").assertExists() // still on Misty's page
    }
}
