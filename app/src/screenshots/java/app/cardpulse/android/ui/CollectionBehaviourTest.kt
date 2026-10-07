package app.cardpulse.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.cardpulse.android.core.CollectionItemDto
import app.cardpulse.android.core.DisplayPrefs
import app.cardpulse.android.core.Fixtures
import app.cardpulse.android.ui.screens.CollectionScreen
import app.cardpulse.android.ui.theme.CardPulseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Uses the Collection tab's Filter the way a person would: opens it, chooses things, reads how many entries are left, and
 * clears it. The data is the collection the server really sent: six entries, of which Charizard ex is there twice (normal
 * and holo), Oddish four times over as reverse holo, and only two have a purchase price. Like the screen pictures it only
 * runs when asked for (`-Pscreenshots`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xxhdpi")
class CollectionBehaviourTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val collection = Fixtures.decode<List<CollectionItemDto>>("collection")

    private val state = AppState(
        booting = false,
        serverUrl = "https://cards.example.com/",
        signedIn = true,
        prefs = DisplayPrefs(currency = "USD", rateFromEur = 1.1, priceField = "price_trend"),
        collection = collection,
        collectionLoaded = true,
    )

    private fun show(shown: AppState = state) {
        compose.setContent {
            CardPulseTheme {
                CollectionScreen(state = shown, onRefresh = {}, onRemove = { _, _, done -> done(null) }, onAddCard = {})
            }
        }
    }

    private fun countOf(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    private val everyName = listOf("Charizard ex", "Glurak-ex", "Miraidon ex", "Oddish", "Promo Without Art")

    /** The names of the entries on the screen, with Charizard ex counted once for each of its entries. */
    private fun shownEntries(): Int = everyName.sumOf { countOf(it) }

    private fun openFilter() = compose.onNodeWithText("Filter").performClick()

    private fun closeFilter(label: String) = compose.onNodeWithText(label).performClick()

    // --- the chip ---------------------------------------------------------------------------------

    @Test
    fun theFilterChipSitsBesideTheSortChips() {
        show()
        for (chip in listOf("Recent", "Name", "Set", "Filter")) compose.onNodeWithText(chip).assertExists()
        compose.onNodeWithText("6 entries · 10 cards").assertExists()
    }

    @Test
    fun theDialogOffersWhatTheCollectionHas() {
        show()
        openFilter()
        for (group in listOf("Rarity", "Condition", "Variant (holo, reverse holo…)", "Worth at least, each", "Copies", "Cost basis")) {
            compose.onNodeWithText(group).assertExists()
        }
        // Rarities, conditions and variants that are in the collection.
        for (chip in listOf("Double Rare", "Common", "Promo", "NM", "LP", "Mint", "Normal", "Holo", "Reverse Holo", "No purchase price (4)")) {
            compose.onNodeWithText(chip).assertExists()
        }
        // Steps up to the most valuable card, which is worth $9.35 here (8.50 euros at 1.1).
        compose.onNodeWithText("$1+").assertExists()
        compose.onNodeWithText("$5+").assertExists()
        compose.onNodeWithText("$20+").assertDoesNotExist()
        compose.onNodeWithText("Show 6 entries").assertExists()
        compose.onNodeWithText("Clear all").assertExists()
    }

    // --- choosing --------------------------------------------------------------------------------

    @Test
    fun aRarityNarrowsTheCollectionAndTheChipCountsIt() {
        show()
        openFilter()
        compose.onNodeWithText("Common").performClick()
        compose.onNodeWithText("Show 1 entry").performClick()
        assertEquals(1, shownEntries())
        compose.onNodeWithText("Oddish").assertExists()
        compose.onNodeWithText("Filter 1").assertExists()
        compose.onNodeWithText("1 of 6 entries · 4 cards").assertExists()
    }

    @Test
    fun severalValuesOfOneKindShowEntriesWithEither() {
        show()
        openFilter()
        compose.onNodeWithText("Common").performClick()
        compose.onNodeWithText("Promo").performClick()
        compose.onNodeWithText("Show 2 entries").performClick()
        compose.onNodeWithText("Oddish").assertExists()
        compose.onNodeWithText("Promo Without Art").assertExists()
        assertEquals(2, shownEntries())
        compose.onNodeWithText("Filter 1").assertExists() // one kind of choice, however many values
    }

    @Test
    fun differentKindsAllHaveToHold() {
        show()
        openFilter()
        compose.onNodeWithText("Double Rare").performClick()
        compose.onNodeWithText("No purchase price (4)").performClick()
        // Double Rare is Charizard ex twice, Miraidon ex and Glurak-ex; without a purchase price leaves all but the first Charizard ex.
        compose.onNodeWithText("Show 3 entries").performClick()
        assertEquals(3, shownEntries())
        compose.onNodeWithText("Filter 2").assertExists()
    }

    @Test
    fun holoIsAVariantYouCanPick() {
        show()
        openFilter()
        compose.onNodeWithText("Holo").performClick()
        compose.onNodeWithText("Show 1 entry").performClick()
        compose.onNodeWithText("Charizard ex").assertExists()
        assertEquals(1, shownEntries())
    }

    @Test
    fun aValueIsPerCardInTheCurrencyYouSee() {
        show()
        openFilter()
        compose.onNodeWithText("$5+").performClick()
        // Charizard ex twice (9.35) and Glurak-ex (8.69); Miraidon ex is 2.64 each.
        compose.onNodeWithText("Show 3 entries").performClick()
        assertEquals(3, shownEntries())
        compose.onNodeWithText("Miraidon ex").assertDoesNotExist()
    }

    @Test
    fun copiesTellsOneFromSeveral() {
        show()
        openFilter()
        compose.onNodeWithText("2 or more").performClick()
        compose.onNodeWithText("Show 2 entries").performClick() // Oddish x4 and Miraidon ex x2
        compose.onNodeWithText("Oddish").assertExists()
        compose.onNodeWithText("Miraidon ex").assertExists()
        assertEquals(2, shownEntries())
    }

    @Test
    fun noPurchasePriceFindsTheEntriesBehindTheWarningOnHome() {
        show()
        openFilter()
        compose.onNodeWithText("No purchase price (4)").performClick()
        compose.onNodeWithText("Show 4 entries").performClick()
        assertEquals(4, shownEntries())
        compose.onNodeWithText("4 of 6 entries · 5 cards").assertExists()
    }

    // --- undoing ---------------------------------------------------------------------------------

    @Test
    fun clearAllShowsEverythingAgain() {
        show()
        openFilter()
        compose.onNodeWithText("Common").performClick()
        compose.onNodeWithText("Clear all").performClick()
        compose.onNodeWithText("Show 6 entries").performClick()
        assertEquals(6, shownEntries())
        compose.onNodeWithText("Filter").assertExists()
    }

    @Test
    fun aFilterThatLeavesNothingSaysSoAndCanBeClearedFromThere() {
        show()
        openFilter()
        compose.onNodeWithText("Common").performClick()
        compose.onNodeWithText("2 or more").performClick()
        compose.onNodeWithText("$5+").performClick()
        compose.onNodeWithText("Show 0 entries").performClick()
        compose.onNodeWithText("Nothing matches this filter.").assertExists()
        compose.onNodeWithText("Clear the filter").performClick()
        assertEquals(6, shownEntries())
    }

    @Test
    fun theSearchAndTheFilterWorkTogether() {
        show()
        compose.onNode(hasSetTextAction()).performTextInput("charizard")
        assertEquals(2, shownEntries())
        openFilter()
        compose.onNodeWithText("Holo").performClick()
        compose.onNodeWithText("Show 1 entry").performClick()
        assertEquals(1, shownEntries())
    }

    @Test
    fun withoutAFilterTheCountsAreAsTheyAlwaysWere() {
        show()
        compose.onNodeWithText("6 entries · 10 cards").assertExists()
        compose.onNodeWithText("Filter").assertExists()
    }
}
