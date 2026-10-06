package app.cardpulse.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.cardpulse.android.core.CardDto
import app.cardpulse.android.core.CollectionItemDto
import app.cardpulse.android.core.DashboardDto
import app.cardpulse.android.core.DisplayPrefs
import app.cardpulse.android.core.Fixtures
import app.cardpulse.android.core.SetDto
import app.cardpulse.android.core.cardsMissingCost
import app.cardpulse.android.ui.screens.HomeList
import app.cardpulse.android.ui.screens.HomeListScreen
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
 * Presses the Home screen's controls and checks what they do. The screen is tall so every section is on it at once (a
 * list only draws what is in view). Like the screen pictures it only runs when asked for (`-Pscreenshots`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xxhdpi")
class HomeBehaviourTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val celebration = SetDto(id = "cel_en", tcgSetId = "cel", name = "30th Celebration", total = 132, printedTotal = 132)

    private val collection: List<CollectionItemDto> =
        Fixtures.decode<List<CollectionItemDto>>("collection") +
            (1..18).map { n ->
                CollectionItemDto(
                    id = 100 + n, cardId = "cel-$n",
                    card = CardDto(id = "cel-$n", name = "Card $n", number = "$n", setId = "cel", setRef = celebration),
                )
            }

    private val state = AppState(
        booting = false,
        serverUrl = "https://cards.example.com/",
        signedIn = true,
        prefs = DisplayPrefs(currency = "USD", rateFromEur = 1.1),
        dashboard = Fixtures.decode<DashboardDto>("dashboard"),
        collection = collection,
        collectionLoaded = true,
    )

    private fun show(
        shown: AppState = state,
        onRefresh: () -> Unit = {},
        onOpenSettings: () -> Unit = {},
        onOpenPortfolio: () -> Unit = {},
        onOpenCollection: () -> Unit = {},
        onSeeAllValuable: () -> Unit = {},
        onSeeAllSets: () -> Unit = {},
        onOpenSet: (String) -> Unit = {},
    ) {
        compose.setContent {
            CardPulseTheme {
                HomeScreen(
                    state = shown,
                    onRefresh = onRefresh,
                    onOpenSettings = onOpenSettings,
                    onOpenPortfolio = onOpenPortfolio,
                    onOpenCollection = onOpenCollection,
                    onSeeAllValuable = onSeeAllValuable,
                    onSeeAllSets = onSeeAllSets,
                    onOpenSet = onOpenSet,
                )
            }
        }
    }

    @Test
    fun theWarningAboutMissingPricesExplainsItself() {
        show()
        val missing = state.collection.cardsMissingCost()
        compose.onNodeWithText("Cost basis is missing").assertDoesNotExist()
        compose.onNodeWithText("$missing cards missing cost basis").performClick()
        compose.onNodeWithText("Cost basis is missing").assertExists()
        // The same counts the old paragraph gave: entries with a price out of all entries.
        compose.onNodeWithText("Only 2 of 24 collection entries", substring = true).assertExists()
    }

    @Test
    fun noWarningWhenEveryCardHasAPrice() {
        show(state.copy(collection = state.collection.map { it.copy(purchasePrice = 1.0) }))
        compose.onNodeWithText("missing cost basis", substring = true).assertDoesNotExist()
    }

    @Test
    fun noWarningBeforeTheWholeCollectionIsKnown() {
        show(state.copy(collectionLoaded = false))
        compose.onNodeWithText("missing cost basis", substring = true).assertDoesNotExist()
    }

    @Test
    fun theSeeAllLinksGoToTheirOwnLists() {
        var recent = 0
        var valuable = 0
        var sets = 0
        show(onOpenCollection = { recent++ }, onSeeAllValuable = { valuable++ }, onSeeAllSets = { sets++ })
        val links = compose.onAllNodesWithText("See all")
        links[0].performClick()
        links[1].performClick()
        links[2].performClick()
        assertEquals(listOf(1, 1, 1), listOf(recent, valuable, sets))
    }

    @Test
    fun aSetOpensItsCards() {
        var opened: String? = null
        show(onOpenSet = { opened = it })
        compose.onNodeWithText("30th Celebration").performClick()
        assertEquals("30th Celebration", opened)
    }

    @Test
    fun aCardOpensItsDetails() {
        show()
        compose.onNodeWithText("Remove…").assertDoesNotExist()
        compose.onAllNodesWithText("Charizard ex").onFirst().performClick()
        compose.onNodeWithText("Remove…").assertExists()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Remove…").assertDoesNotExist()
    }

    @Test
    fun theHeaderButtonsAndThePortfolioArrowWork() {
        var refreshed = 0
        var settings = 0
        var portfolio = 0
        show(onRefresh = { refreshed++ }, onOpenSettings = { settings++ }, onOpenPortfolio = { portfolio++ })
        compose.onNodeWithContentDescription("Refresh").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithContentDescription("Open the portfolio").performClick()
        assertEquals(listOf(1, 1, 1), listOf(refreshed, settings, portfolio))
    }

    @Test
    fun saysWhetherTheServerWasReached() {
        show()
        compose.onNodeWithText("Connected").assertExists()
        compose.onNodeWithText("Offline").assertDoesNotExist()
    }

    @Test
    fun saysOfflineWhenItWasNot() {
        show(state.copy(offline = true))
        compose.onNodeWithText("Offline").assertExists()
        compose.onNodeWithText("Connected").assertDoesNotExist()
    }

    @Test
    fun aLossIsCalledALossAndAGainAGain() {
        show(state.copy(dashboard = state.dashboard!!.copy(unrealizedPnl = -5.0)))
        compose.onNodeWithText("loss", substring = true).assertExists()
        compose.onNodeWithText("gain", substring = true).assertDoesNotExist()
    }

    @Test
    fun theSectionsAreAllThere() {
        show()
        for (title in listOf("Recently added", "Most valuable", "Set progress", "Cards", "Sets", "Top Card", "Collection value")) {
            compose.onNodeWithText(title).assertExists()
        }
    }

    @Test
    fun nothingLoadedYetSaysSoInsteadOfShowingZeros() {
        show(state.copy(dashboard = null, collection = emptyList(), collectionLoaded = false))
        compose.onNodeWithText("Nothing loaded yet", substring = true).assertExists()
        compose.onNodeWithText("Collection value").assertDoesNotExist()
    }

    @Test
    fun theFullListsShowEverythingTheyHave() {
        compose.setContent {
            CardPulseTheme {
                HomeListScreen(HomeList.SETS, state, onBack = {}, onOpenSet = {}, onRemove = { _, _, _ -> })
            }
        }
        // The fixture's own sets and the 30th Celebration added above.
        compose.onNodeWithText("30th Celebration").assertExists()
        compose.onNodeWithText("Promo Set").assertExists()
        compose.onNodeWithText("Scarlet & Violet").assertExists()
    }
}
