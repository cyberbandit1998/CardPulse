package app.cardpulse.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.cardpulse.android.core.CollectionItemDto
import app.cardpulse.android.core.DisplayPrefs
import app.cardpulse.android.core.Fixtures
import app.cardpulse.android.core.SetChecklistDto
import app.cardpulse.android.core.SetDto
import app.cardpulse.android.ui.screens.SetChecklistScreen
import app.cardpulse.android.ui.theme.CardPulseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Uses a set's checklist the way a person would: reads which cards are owned and which are missing, narrows the cards with the
 * chips, taps a card and goes back. The data is what the server really sent for Obsidian Flames (six cards, two of them
 * owned, as the collection it sent says). Like the screen pictures it only runs when asked for (`-Pscreenshots`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xxhdpi")
class ChecklistBehaviourTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val catalogue = Fixtures.decode<List<SetDto>>("sets")
    private val collection = Fixtures.decode<List<CollectionItemDto>>("collection")
    private val flames = Fixtures.decode<SetChecklistDto>("set_checklist")
    private val paldea = Fixtures.decode<SetChecklistDto>("set_checklist_unowned_set")

    private val state = AppState(
        booting = false,
        serverUrl = "https://cards.example.com/",
        signedIn = true,
        prefs = DisplayPrefs(currency = "USD", rateFromEur = 1.1),
        collection = collection,
        collectionLoaded = true,
        sets = catalogue,
        setsLoaded = true,
        checklists = mapOf("sv3_en" to flames, "sv2_en" to paldea),
    )

    private fun show(
        shown: AppState = state,
        setId: String = "sv3_en",
        onBack: () -> Unit = {},
        onLoad: (Boolean) -> Unit = {},
        onShowInCollection: ((String) -> Unit)? = null,
    ) {
        compose.setContent {
            CardPulseTheme {
                SetChecklistScreen(
                    setId = setId, state = shown, onBack = onBack, onLoad = onLoad, onRemove = { _, _, done -> done(null) },
                    onShowInCollection = onShowInCollection,
                )
            }
        }
    }

    private fun countOf(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    // --- reading it ----------------------------------------------------------------------------------

    @Test
    fun saysWhichSetItIsAndHowFarAlongYouAre() {
        show()
        compose.onNodeWithText("Obsidian Flames").assertExists()
        compose.onNodeWithText("2 / 6").assertExists()
        compose.onNodeWithText("33%").assertExists()
        compose.onNodeWithText("OBF · Scarlet & Violet · English").assertExists()
    }

    @Test
    fun everyCardSaysInWordsWhetherItIsOwnedOrMissing() {
        show()
        for (line in listOf("#001 · Owned ×4", "#002 · Missing", "#003 · Missing", "#020 · Missing", "#125 · Owned ×2", "#223 · Missing")) {
            compose.onNodeWithText(line).assertExists()
        }
    }

    @Test
    fun aScreenReaderIsToldAboutEachCardInOneBreath() {
        show()
        compose.onNodeWithContentDescription("Oddish, number 001, owned 4").assertExists()
        compose.onNodeWithContentDescription("Gloom, number 002, missing").assertExists()
        compose.onNodeWithContentDescription("Charizard ex, number 125, owned 2").assertExists()
        compose.onNodeWithContentDescription("Charizard ex, number 223, missing").assertExists()
    }

    @Test
    fun aSetYouOwnNothingOfIsAllMissing() {
        show(setId = "sv2_en")
        compose.onNodeWithText("Paldea Evolved").assertExists()
        compose.onNodeWithText("0 / 3").assertExists()
        compose.onNodeWithText("Missing (3)").assertExists()
        compose.onNodeWithText("Owned (0)").assertExists()
        compose.onNodeWithText("#001 · Missing").assertExists()
    }

    @Test
    fun aCardTakenOutOfTheCollectionIsMissingAtOnce() {
        show(state.copy(collection = collection.filter { it.cardId != "sv3-001_en" }))
        compose.onNodeWithText("#001 · Missing").assertExists() // the checklist the server sent still says owned
        compose.onNodeWithText("1 / 6").assertExists()
    }

    @Test
    fun beforeTheCollectionHasLoadedItGoesByWhatTheServerSaid() {
        show(state.copy(collection = emptyList(), collectionLoaded = false))
        compose.onNodeWithText("#001 · Owned ×4").assertExists()
        compose.onNodeWithText("2 / 6").assertExists()
    }

    @Test
    fun aSetWithEveryCardOwnedSaysComplete() {
        val owned = flames.copy(cards = flames.cards.map { it.copy(owned = true, ownedQuantity = 1) })
        val rows = flames.cards.mapIndexed { index, card ->
            CollectionItemDto(id = 500 + index, cardId = card.id, quantity = 1)
        }
        show(state.copy(collection = rows, checklists = mapOf("sv3_en" to owned)))
        compose.onNodeWithText("6 / 6").assertExists()
        compose.onNodeWithText("Complete").assertExists()
        compose.onNodeWithText("Missing (0)").performClick()
        compose.onNodeWithText("Nothing is missing: you own every card of this set.").assertExists()
    }

    // --- the chips -----------------------------------------------------------------------------------

    @Test
    fun theChipsCarryTheirCounts() {
        show()
        for (chip in listOf("All (6)", "Owned (2)", "Missing (4)")) compose.onNodeWithText(chip).assertExists()
    }

    @Test
    fun ownedShowsOnlyTheCardsYouHave() {
        show()
        compose.onNodeWithText("Owned (2)").performClick()
        compose.onNodeWithText("Oddish").assertExists()
        compose.onNodeWithText("Gloom").assertDoesNotExist()
        assertEquals(1, countOf("Charizard ex")) // #125 only, not the one you are missing
    }

    @Test
    fun missingShowsOnlyTheCardsYouLack() {
        show()
        compose.onNodeWithText("Missing (4)").performClick()
        compose.onNodeWithText("Oddish").assertDoesNotExist()
        compose.onNodeWithText("Gloom").assertExists()
        compose.onNodeWithText("Vileplume").assertExists()
        assertEquals(1, countOf("Charizard ex")) // #223 only
        compose.onNodeWithText("All (6)").performClick()
        assertEquals(2, countOf("Charizard ex"))
    }

    @Test
    fun saysSoWhenYouOwnNothingOfASetYetAndOwnedIsChosen() {
        show(setId = "sv2_en")
        compose.onNodeWithText("Owned (0)").performClick()
        compose.onNodeWithText("You don't own any card of this set yet.").assertExists()
    }

    // --- pressing things ----------------------------------------------------------------------------

    @Test
    fun anOwnedCardOpensItsDetails() {
        show()
        compose.onNodeWithText("Remove…").assertDoesNotExist()
        compose.onNodeWithContentDescription("Oddish, number 001, owned 4").performClick()
        compose.onNodeWithText("Remove…").assertExists()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Remove…").assertDoesNotExist()
    }

    @Test
    fun aMissingCardHasNothingToOpen() {
        show()
        compose.onNodeWithContentDescription("Gloom, number 002, missing").performClick()
        compose.onNodeWithText("Remove…").assertDoesNotExist()
    }

    @Test
    fun backGoesBack() {
        var back = 0
        show(onBack = { back++ })
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, back)
    }

    @Test
    fun asksForTheCardsWhenOpenedAndAgainOnRefresh() {
        val asked = mutableListOf<Boolean>()
        show(onLoad = { asked += it })
        compose.waitForIdle()
        assertEquals(listOf(false), asked)
        compose.onNodeWithContentDescription("Refresh this set").performClick()
        assertEquals(listOf(false, true), asked)
    }

    @Test
    fun offersToShowYourCardsInTheCollectionOnlyForASetYouHaveCardsFrom() {
        var shownName: String? = null
        show(onShowInCollection = { shownName = it })
        compose.onNodeWithText("Open my cards in Collection").performClick()
        assertEquals("Obsidian Flames", shownName)
    }

    @Test
    fun hasNoCollectionButtonForASetYouHaveNoCardsFrom() {
        show(setId = "sv2_en", onShowInCollection = {})
        compose.onNodeWithText("Open my cards in Collection").assertDoesNotExist()
    }

    // --- loading and trouble -------------------------------------------------------------------------

    @Test
    fun whileTheCardsComeItSaysSoUnderTheSetsName() {
        show(state.copy(checklists = emptyMap(), checklistsLoading = setOf("sv3_en")))
        compose.onNodeWithText("Obsidian Flames").assertExists()
        compose.onNodeWithText("Loading the cards of this set…").assertExists()
        compose.onNodeWithText("Owned (2)").assertDoesNotExist()
    }

    @Test
    fun ifTheCardsCannotBeHadItSaysWhyAndOffersToTryAgain() {
        val asked = mutableListOf<Boolean>()
        show(state.copy(checklists = emptyMap(), checklistErrors = mapOf("sv3_en" to "Set not found")), onLoad = { asked += it })
        compose.onNodeWithText("Couldn't load this set.").assertExists()
        compose.onNodeWithText("Set not found").assertExists()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(listOf(false, true), asked)
    }

    @Test
    fun aSetTheServerHasNoCardsForYetSaysSo() {
        show(state.copy(checklists = mapOf("sv3_en" to flames.copy(cards = emptyList(), totalCount = 0, ownedCount = 0))))
        compose.onNodeWithText("The server has no cards listed for this set yet. Try again later.").assertExists()
        compose.onNodeWithText("0 / 0").assertExists()
    }
}
