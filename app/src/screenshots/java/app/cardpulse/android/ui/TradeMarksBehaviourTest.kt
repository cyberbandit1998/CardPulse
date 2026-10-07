package app.cardpulse.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.cardpulse.android.core.CollectionItemDto
import app.cardpulse.android.core.DashboardDto
import app.cardpulse.android.core.DisplayPrefs
import app.cardpulse.android.core.Fixtures
import app.cardpulse.android.core.ShareLevel
import app.cardpulse.android.ui.screens.CollectionScreen
import app.cardpulse.android.ui.screens.HomeScreen
import app.cardpulse.android.ui.theme.CardPulseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Uses the controls for copies For Trade the way a person would: looks for the swap badge on the cards of the Collection, opens a
 * card, and offers one more copy or one fewer from its details; and finds Friends & trading on Home. The data is the collection the
 * server really sent: Oddish held four times over, two Miraidon ex, a Promo held once. Nothing is for trade until the user says so.
 * Like the screen pictures it only runs when asked for (`-Pscreenshots`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xxhdpi")
class TradeMarksBehaviourTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val collection = Fixtures.decode<List<CollectionItemDto>>("collection")

    private val state = AppState(
        booting = false,
        serverUrl = "https://cards.example.com/",
        signedIn = true,
        prefs = DisplayPrefs(currency = "USD", rateFromEur = 1.1),
        collection = collection,
        collectionLoaded = true,
    )

    private val changes = mutableListOf<Pair<Int, Int>>()

    /** Two of the four Oddish (row 3) and one of the two Miraidon ex (row 4) are for trade, as in the server's captured answer. */
    private fun controls(
        ready: Boolean = true,
        marks: Map<Int, Int> = mapOf(3 to 2, 4 to 1),
        visibleTo: ShareLevel = ShareLevel.PRIVATE,
    ) = TradeControls(ready = ready, marks = marks, visibleTo = visibleTo, set = { id, quantity -> changes += id to quantity })

    private fun showCollection(trade: TradeControls = controls(), shown: AppState = state) {
        compose.setContent {
            CardPulseTheme {
                CompositionLocalProvider(LocalTrade provides trade) {
                    CollectionScreen(state = shown, onRefresh = {}, onRemove = { _, _, done -> done(null) }, onAddCard = {})
                }
            }
        }
    }

    // --- the badge on a card -----------------------------------------------------------------------------

    @Test
    fun aCardWithCopiesOnOfferCarriesTheSwapBadgeWithHowMany() {
        showCollection()
        compose.onNodeWithContentDescription("2 for trade").assertExists() // Oddish
        compose.onNodeWithContentDescription("1 for trade").assertExists() // Miraidon ex
        // No other card is for trade.
        compose.onAllNodesWithContentDescription("for trade", substring = true).assertCountEquals(2)
    }

    @Test
    fun noCardCarriesTheBadgeWhenNothingIsMarked() {
        showCollection(controls(marks = emptyMap()))
        compose.onAllNodesWithContentDescription("for trade", substring = true).assertCountEquals(0)
    }

    @Test
    fun noCardCarriesTheBadgeBeforeTheMarksHaveLoaded() {
        showCollection(controls(ready = false))
        compose.onAllNodesWithContentDescription("for trade", substring = true).assertCountEquals(0)
    }

    @Test
    fun theBadgeNeverCountsMoreCopiesThanTheRowHolds() {
        // A mark of nine on a row of two (it cannot happen on the server, but the screen must not say so).
        showCollection(controls(marks = mapOf(4 to 9)))
        compose.onNodeWithContentDescription("2 for trade").assertExists()
        compose.onAllNodesWithContentDescription("9 for trade").assertCountEquals(0)
    }

    // --- a card's details --------------------------------------------------------------------------------

    @Test
    fun theDetailsSayHowManyCopiesAreOnOfferAndOfferOneMoreOrOneFewer() {
        showCollection()
        compose.onNodeWithText("Oddish").performClick()
        compose.onNodeWithText("For trade").assertExists()
        compose.onNodeWithText("2 of 4 for trade").assertExists()
        compose.onNodeWithContentDescription("Offer one more copy for trade").performClick()
        compose.onNodeWithContentDescription("Offer one fewer copy for trade").performClick()
        assertEquals(listOf(3 to 3, 3 to 1), changes)
    }

    @Test
    fun nothingIsOfferedUntilTheUserSaysSo() {
        showCollection(controls(marks = emptyMap()))
        compose.onNodeWithText("Oddish").performClick()
        compose.onNodeWithText("None for trade").assertExists()
        compose.onNodeWithContentDescription("Offer one fewer copy for trade").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Offer one more copy for trade").assertIsEnabled().performClick()
        // One copy, not all four: duplicates are never offered for the user.
        assertEquals(listOf(3 to 1), changes)
    }

    @Test
    fun neverMoreCopiesAreOfferedThanTheRowHolds() {
        showCollection(controls(marks = mapOf(3 to 4)))
        compose.onNodeWithText("Oddish").performClick()
        compose.onNodeWithText("4 of 4 for trade").assertExists()
        compose.onNodeWithContentDescription("Offer one more copy for trade").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Offer one fewer copy for trade").assertIsEnabled()
    }

    @Test
    fun aCardHeldOnceIsOfferedAsOneCopyOrNone() {
        showCollection()
        compose.onNodeWithText("Promo Without Art").performClick()
        compose.onNodeWithText("None for trade").assertExists()
        compose.onNodeWithContentDescription("Offer one more copy for trade").performClick()
        assertEquals(listOf(6 to 1), changes)
    }

    @Test
    fun theDetailsSayWhoCanSeeTheCopiesOnOffer() {
        showCollection(controls(visibleTo = ShareLevel.PRIVATE))
        compose.onNodeWithText("Oddish").performClick()
        compose.onNodeWithText("Only you can see your For Trade list for now. Choose who can in Friends, under Sharing.").assertExists()
    }

    @Test
    fun theDetailsSayTheFriendsCanSeeThemWhenTheyShareThemWithFriends() {
        showCollection(controls(visibleTo = ShareLevel.FRIENDS))
        compose.onNodeWithText("Oddish").performClick()
        compose.onNodeWithText("Your friends can see these copies.").assertExists()
    }

    @Test
    fun theDetailsSayEveryoneOnTheServerCanSeeThemWhenTheyArePublic() {
        showCollection(controls(visibleTo = ShareLevel.PUBLIC))
        compose.onNodeWithText("Oddish").performClick()
        compose.onNodeWithText("Everyone on this server can see these copies.").assertExists()
    }

    @Test
    fun theDetailsOfferNothingBeforeTheMarksHaveLoaded() {
        showCollection(controls(ready = false))
        compose.onNodeWithText("Oddish").performClick()
        compose.onNodeWithText("Close").assertExists() // the details are open
        compose.onAllNodesWithText("For trade").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Offer one more copy for trade").assertCountEquals(0)
    }

    @Test
    fun theDetailsOfferNothingOnAServerThatHasNoFriends() {
        // Nothing provides any controls there, so the screens get none.
        showCollection(trade = TradeControls.None)
        compose.onNodeWithText("Oddish").performClick()
        compose.onNodeWithText("Close").assertExists()
        compose.onAllNodesWithText("For trade").assertCountEquals(0)
    }

    @Test
    fun aCardMadeByHandIsNeverOffered() {
        // A friend could not look it up: it is on this server only, and in no catalogue.
        val byHand = collection.map { row -> if (row.id == 6) row.copy(card = row.card?.copy(isCustom = true)) else row }
        showCollection(shown = state.copy(collection = byHand))
        compose.onNodeWithText("Promo Without Art").performClick()
        compose.onNodeWithText("Close").assertExists()
        compose.onAllNodesWithText("For trade").assertCountEquals(0)
    }

    // --- Home's way in ----------------------------------------------------------------------------------

    private val home get() = state.copy(dashboard = Fixtures.decode<DashboardDto>("dashboard"))

    @Test
    fun homeOffersFriendsAndTradingWithWhatIsWaiting() {
        var opened = 0
        compose.setContent {
            CardPulseTheme {
                HomeScreen(
                    state = home,
                    onRefresh = {},
                    onOpenSettings = {},
                    friendsNote = "3 friends · 1 request waiting",
                    friendsRequests = 1,
                    onOpenFriends = { opened++ },
                )
            }
        }
        compose.onNodeWithText("3 friends · 1 request waiting").assertExists()
        compose.onNodeWithText("Friends & trading").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun homeSaysWhyFriendsCannotBeUsedYet() {
        compose.setContent {
            CardPulseTheme {
                HomeScreen(state = home, onRefresh = {}, onOpenSettings = {}, friendsNote = "Needs an update on your server", onOpenFriends = {})
            }
        }
        compose.onNodeWithText("Needs an update on your server").assertExists()
    }

    @Test
    fun withoutAWayToOpenFriendsHomeHasNoSuchRow() {
        compose.setContent { CardPulseTheme { HomeScreen(state = home, onRefresh = {}, onOpenSettings = {}) } }
        compose.onAllNodesWithText("Friends & trading").assertCountEquals(0)
    }
}
