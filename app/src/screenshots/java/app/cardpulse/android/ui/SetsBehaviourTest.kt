package app.cardpulse.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.cardpulse.android.core.CardDto
import app.cardpulse.android.core.CollectionItemDto
import app.cardpulse.android.core.DisplayPrefs
import app.cardpulse.android.core.Fixtures
import app.cardpulse.android.core.SetDto
import app.cardpulse.android.ui.screens.SetsScreen
import app.cardpulse.android.ui.theme.CardPulseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Uses the Sets tab the way a person would: looks at the list of every set, narrows it with the chips and the search, puts
 * it in another order and taps a set. The data is what the server really sent: its list of sets (seven English ones) and a
 * collection that owns cards from four sets, one of them the German Obsidian Flames, which is not on the list. The screen is
 * tall so every row is on it at once. Like the screen pictures it only runs when asked for (`-Pscreenshots`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xxhdpi")
class SetsBehaviourTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val catalogue = Fixtures.decode<List<SetDto>>("sets")
    private val collection = Fixtures.decode<List<CollectionItemDto>>("collection")

    /** Nine more Promo Set cards: with the one the fixture owns that is all ten, so the set is complete. */
    private val restOfPromoSet: List<CollectionItemDto> = (1..9).map { n ->
        CollectionItemDto(
            id = 900 + n, cardId = "sv9-$n",
            card = CardDto(id = "sv9-$n", name = "Promo $n", setRef = catalogue.first { it.id == "sv9_en" }),
        )
    }

    private val state = AppState(
        booting = false,
        serverUrl = "https://cards.example.com/",
        signedIn = true,
        prefs = DisplayPrefs(currency = "USD", rateFromEur = 1.1),
        collection = collection,
        collectionLoaded = true,
        sets = catalogue,
        setsLoaded = true,
    )

    private val everySet = listOf(
        "Promo Set", "Obsidian Flames", "Obsidian-Flammen", "Scarlet & Violet",
        "Newly Announced Set", "Paldea Evolved", "Silver Tempest", "Base Set",
    )

    private fun show(shown: AppState = state, onOpenSet: (String) -> Unit = {}, onLoadSets: (Boolean) -> Unit = {}) {
        compose.setContent { CardPulseTheme { SetsScreen(state = shown, onOpenSet = onOpenSet, onLoadSets = onLoadSets) } }
    }

    /** The sets that are on the screen, top to bottom. */
    private fun shownSets(): List<String> = everySet
        .filter { compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() }
        .sortedBy { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }

    private fun search(text: String) {
        compose.onNode(hasSetTextAction()).performTextInput(text)
    }

    // --- every set -----------------------------------------------------------------------------------

    @Test
    fun listsEverySetTheServerHasAndTheOnesYouOwnThatItDoesNot() {
        show()
        // Seven sets on the server's list, and the German Obsidian Flames, which only your collection knows about.
        assertEquals(everySet.toSet(), shownSets().toSet())
    }

    @Test
    fun showsHowMuchOfEachSetIsOwnedAndZeroForTheRest() {
        show()
        for (count in listOf("1 / 10", "2 / 230", "1 / 230", "1 / 258", "0 / —", "0 / 279", "0 / 245", "0 / 102")) {
            compose.onNodeWithText(count).assertExists()
        }
    }

    @Test
    fun startsWithTheSetsYouOwnClosestToFinishedThenTheNewestSets() {
        show()
        assertEquals(everySet, shownSets()) // Promo Set 10%, Flames, Flammen, Scarlet & Violet, then by release date
    }

    @Test
    fun theSubtitleSaysHowManySetsAreStarted() {
        show()
        compose.onNodeWithText("4 of 8 sets started · 5 cards").assertExists()
    }

    // --- the filters --------------------------------------------------------------------------------

    @Test
    fun allFourFiltersAreOnTheScreenAtOnceAndOnOneRow() {
        show()
        val chips = listOf("All", "Owned", "Incomplete", "Complete").map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
        val width = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        assertTrue("A filter is cut off by the edge of the screen: $chips", chips.all { it.left >= 0f && it.right <= width })
        assertEquals("The filters are not on one row: $chips", 1, chips.map { it.top }.distinct().size)
    }

    @Test
    fun ownedShowsOnlySetsWithCards() {
        show()
        compose.onNodeWithText("Owned").performClick()
        assertEquals(listOf("Promo Set", "Obsidian Flames", "Obsidian-Flammen", "Scarlet & Violet"), shownSets())
        compose.onNodeWithText("All").performClick()
        assertEquals(everySet, shownSets())
    }

    @Test
    fun incompleteAndCompleteTellStartedFromFinished() {
        show(state.copy(collection = collection + restOfPromoSet))
        compose.onNodeWithText("Complete").performClick()
        assertEquals(listOf("Promo Set"), shownSets())
        compose.onNodeWithText("Incomplete").performClick()
        assertEquals(listOf("Obsidian Flames", "Obsidian-Flammen", "Scarlet & Violet"), shownSets())
        compose.onNodeWithText("Owned").performClick()
        assertEquals(listOf("Promo Set", "Obsidian Flames", "Obsidian-Flammen", "Scarlet & Violet"), shownSets())
    }

    @Test
    fun saysSoWhenNoSetIsComplete() {
        show()
        compose.onNodeWithText("Complete").performClick()
        assertEquals(emptyList<String>(), shownSets())
        compose.onNodeWithText("No set is complete yet.").assertExists()
    }

    @Test
    fun saysSoWhenNoSetIsPartlyCollected() {
        // Only the Promo Set, and all ten of its cards: it is complete, so nothing is partly collected.
        val onlyThePromoSet = collection.filter { it.card?.setRef?.id == "sv9_en" } + restOfPromoSet
        show(state.copy(collection = onlyThePromoSet, sets = catalogue.filter { it.id == "sv9_en" }))
        compose.onNodeWithText("Incomplete").performClick()
        compose.onNodeWithText("No set is partly collected yet.").assertExists()
    }

    @Test
    fun theSearchAndAFilterWorkTogether() {
        show()
        search("obsidian")
        assertEquals(listOf("Obsidian Flames", "Obsidian-Flammen"), shownSets())
        compose.onNodeWithText("Owned").performClick()
        assertEquals(listOf("Obsidian Flames", "Obsidian-Flammen"), shownSets())
        compose.onNodeWithText("Complete").performClick()
        compose.onNodeWithText("No complete set matches “obsidian”.").assertExists()
    }

    @Test
    fun aFilterAndASearchTogetherSayWhichLeftNothing() {
        show()
        search("paldea")
        compose.onNodeWithText("Owned").performClick()
        compose.onNodeWithText("No owned set matches “paldea”.").assertExists()
    }

    // --- searching and ordering ---------------------------------------------------------------

    @Test
    fun searchFindsASetByItsPrintedCodeOrItsSeries() {
        show()
        search("obf")
        assertEquals(listOf("Obsidian Flames", "Obsidian-Flammen"), shownSets())
    }

    @Test
    fun canBePutInOrderOfName() {
        show()
        compose.onNodeWithText("Name").performClick()
        assertEquals(
            listOf("Base Set", "Newly Announced Set", "Obsidian Flames", "Obsidian-Flammen", "Paldea Evolved", "Promo Set", "Scarlet & Violet", "Silver Tempest"),
            shownSets(),
        )
    }

    @Test
    fun canBePutInOrderOfHowManyCardsAreOwned() {
        show()
        compose.onNodeWithText("Cards").performClick()
        assertEquals(
            listOf("Obsidian Flames", "Promo Set", "Obsidian-Flammen", "Scarlet & Violet", "Base Set", "Newly Announced Set", "Paldea Evolved", "Silver Tempest"),
            shownSets(),
        )
    }

    @Test
    fun saysSoWhenNoSetMatches() {
        show()
        search("zzz")
        assertEquals(emptyList<String>(), shownSets())
        compose.onNodeWithText("No set matches “zzz”.").assertExists()
    }

    // --- opening a set -------------------------------------------------------------------------------

    @Test
    fun tappingASetPassesItsIdWhetherOrNotYouOwnItsCards() {
        val opened = mutableListOf<String>()
        show(onOpenSet = { opened += it })
        compose.onNodeWithText("Promo Set").performClick()
        compose.onNodeWithText("Paldea Evolved").performClick()
        compose.onNodeWithText("Obsidian-Flammen").performClick()
        assertEquals(listOf("sv9_en", "sv2_en", "sv3_de"), opened)
    }

    // --- loading the list --------------------------------------------------------------------------

    @Test
    fun asksForTheListWhenOpenedAndAgainOnRefresh() {
        val asked = mutableListOf<Boolean>()
        show(onLoadSets = { asked += it })
        compose.waitForIdle()
        assertEquals(listOf(false), asked)
        compose.onNodeWithContentDescription("Refresh the sets").performClick()
        assertEquals(listOf(false, true), asked)
    }

    @Test
    fun untilTheListArrivesItShowsTheSetsYouOwn() {
        show(state.copy(sets = emptyList(), setsLoaded = false, setsLoading = true))
        assertEquals(listOf("Promo Set", "Obsidian Flames", "Obsidian-Flammen", "Scarlet & Violet"), shownSets())
        compose.onNodeWithText("Loading every set…").assertExists()
    }

    @Test
    fun ifTheListCannotBeHadItSaysWhyAndOffersToTryAgain() {
        val asked = mutableListOf<Boolean>()
        show(
            state.copy(sets = emptyList(), setsLoaded = false, setsError = "Can't connect to the server. Check the address and that it is running."),
            onLoadSets = { asked += it },
        )
        compose.onNodeWithText("Couldn't load every set. Can't connect to the server. Check the address and that it is running.").assertExists()
        compose.onNodeWithText("Showing only the sets you have cards from").assertExists()
        assertEquals(listOf("Promo Set", "Obsidian Flames", "Obsidian-Flammen", "Scarlet & Violet"), shownSets()) // not empty for no reason
        compose.onNodeWithText("Try again").performClick()
        assertEquals(listOf(false, true), asked)
    }

    @Test
    fun aFailedRefreshKeepsTheListItHas() {
        show(state.copy(setsError = "The server took too long to answer. Try again."))
        assertEquals(everySet, shownSets())
        compose.onNodeWithText("Couldn't refresh the sets. The server took too long to answer. Try again.").assertExists()
    }

    @Test
    fun saysSoWhenTheServerListsNoSets() {
        show(state.copy(sets = emptyList(), collection = emptyList()))
        compose.onNodeWithText("The server lists no sets yet.").assertExists()
    }
}
