package app.cardpulse.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Uses the Sets tab the way a person would: looks at the list, searches it, puts it in another order and taps a set. The
 * screen is tall so every row is on it at once. Like the screen pictures it only runs when asked for (`-Pscreenshots`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xxhdpi")
class SetsBehaviourTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val celebration = SetDto(id = "cel_en", tcgSetId = "cel", name = "30th Celebration", total = 132, printedTotal = 132)

    // The fixture owns Obsidian Flames (2 cards of 230), Obsidian-Flammen (1 of 230), Scarlet & Violet (1 of 258) and Promo Set
    // (1 of 10); the 30th Celebration is added here: 18 of 132.
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
        collection = collection,
        collectionLoaded = true,
    )

    private val everySet = listOf("30th Celebration", "Obsidian Flames", "Obsidian-Flammen", "Promo Set", "Scarlet & Violet")

    private fun show(shown: AppState = state, onOpenSet: (String) -> Unit = {}) {
        compose.setContent { CardPulseTheme { SetsScreen(state = shown, onOpenSet = onOpenSet) } }
    }

    /** The sets that are on the screen, top to bottom. */
    private fun shownSets(): List<String> = everySet
        .filter { compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() }
        .sortedBy { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }

    private fun search(text: String) {
        compose.onNode(hasSetTextAction()).performTextInput(text)
    }

    @Test
    fun listsEverySetTheCollectionHasACardFrom() {
        show()
        assertEquals(everySet.toSet(), shownSets().toSet())
        compose.onNodeWithText("5 sets · 23 different cards").assertExists()
    }

    @Test
    fun startsWithTheSetsClosestToFinished() {
        show()
        assertEquals(
            listOf("30th Celebration", "Promo Set", "Obsidian Flames", "Obsidian-Flammen", "Scarlet & Violet"),
            shownSets(),
        )
    }

    @Test
    fun canBePutInOrderOfName() {
        show()
        compose.onNodeWithText("Name").performClick()
        assertEquals(listOf("30th Celebration", "Obsidian Flames", "Obsidian-Flammen", "Promo Set", "Scarlet & Violet"), shownSets())
    }

    @Test
    fun canBePutInOrderOfHowManyCardsAreOwned() {
        show()
        compose.onNodeWithText("Cards").performClick()
        assertEquals(listOf("30th Celebration", "Obsidian Flames", "Promo Set", "Obsidian-Flammen", "Scarlet & Violet"), shownSets())
        // And back again.
        compose.onNodeWithText("Progress").performClick()
        assertEquals(listOf("30th Celebration", "Promo Set", "Obsidian Flames", "Obsidian-Flammen", "Scarlet & Violet"), shownSets())
    }

    @Test
    fun searchNarrowsTheListAsYouType() {
        show()
        search("obsidian")
        assertEquals(listOf("Obsidian Flames", "Obsidian-Flammen"), shownSets())
        search(" flames")
        assertEquals(listOf("Obsidian Flames"), shownSets())
    }

    @Test
    fun searchAlsoKnowsASetsCode() {
        show()
        search("sv3") // Obsidian Flames, in English and in German
        assertEquals(listOf("Obsidian Flames", "Obsidian-Flammen"), shownSets())
    }

    @Test
    fun saysSoWhenNoSetMatches() {
        show()
        search("zzz")
        assertEquals(emptyList<String>(), shownSets())
        compose.onNodeWithText("No set matches “zzz”.").assertExists()
    }

    @Test
    fun tappingASetPassesItsName() {
        var opened: String? = null
        show(onOpenSet = { opened = it })
        compose.onNodeWithText("Promo Set").performClick()
        assertEquals("Promo Set", opened)
    }

    @Test
    fun aSetFoundBySearchCanBeTappedToo() {
        var opened: String? = null
        show(onOpenSet = { opened = it })
        search("celebration")
        compose.onNodeWithText("30th Celebration").performClick()
        assertEquals("30th Celebration", opened)
    }

    @Test
    fun saysSoBeforeAnyCardIsOwned() {
        show(state.copy(collection = emptyList()))
        compose.onNodeWithText("No sets yet", substring = true).assertExists()
    }

    @Test
    fun saysItIsLoadingWhileTheCollectionIsOnItsWay() {
        show(state.copy(collection = emptyList(), collectionLoaded = false, collectionLoading = true))
        compose.onNodeWithText("Loading your sets…").assertExists()
        compose.onNodeWithText("No sets yet", substring = true).assertDoesNotExist()
    }
}
