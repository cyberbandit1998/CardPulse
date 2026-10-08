package app.cardpulse.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.cardpulse.android.core.CardDto
import app.cardpulse.android.core.CollectionItemDto
import app.cardpulse.android.core.CustomCardForm
import app.cardpulse.android.core.DashboardDto
import app.cardpulse.android.core.DisplayPrefs
import app.cardpulse.android.core.Fixtures
import app.cardpulse.android.core.ManualAddState
import app.cardpulse.android.core.Ownership
import app.cardpulse.android.core.SetChecklistDto
import app.cardpulse.android.core.SetDto
import app.cardpulse.android.core.lookup
import app.cardpulse.android.ui.screens.CollectionScreen
import app.cardpulse.android.ui.screens.HomeScreen
import app.cardpulse.android.ui.screens.ManualAddActions
import app.cardpulse.android.ui.screens.ManualAddContent
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
 * Uses the hearts the way a person would: finds one on a card of a set's checklist, in a card's details and in the results of
 * adding a card by typing, presses it, and looks for the small heart on the tiles of cards that are on the wishlist. The data is
 * what the server really sent: the checklist of Obsidian Flames (six cards; two of them owned) and the collection. Like the screen
 * pictures it only runs when asked for (`-Pscreenshots`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xxhdpi")
class WishlistHeartsBehaviourTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val catalogue = Fixtures.decode<List<SetDto>>("sets")
    private val collection = Fixtures.decode<List<CollectionItemDto>>("collection")
    private val flames = Fixtures.decode<SetChecklistDto>("set_checklist")

    private val state = AppState(
        booting = false,
        serverUrl = "https://cards.example.com/",
        signedIn = true,
        prefs = DisplayPrefs(currency = "USD", rateFromEur = 1.1),
        collection = collection,
        collectionLoaded = true,
        sets = catalogue,
        setsLoaded = true,
        checklists = mapOf("sv3_en" to flames),
    )

    private val toggled = mutableListOf<String>()

    /** What the wishlist offers the screens: the Charizard ex #125 is on it, nothing else. */
    private fun controls(
        ready: Boolean = true,
        listed: Set<String> = setOf("sv3-125_en"),
        pending: Set<String> = emptySet(),
    ) = WishlistControls(ready = ready, listed = listed, pending = pending, toggle = { toggled += it })

    private fun showChecklist(wishlist: WishlistControls = controls()) {
        compose.setContent {
            CardPulseTheme {
                CompositionLocalProvider(LocalWishlist provides wishlist) {
                    SetChecklistScreen(
                        setId = "sv3_en",
                        state = state,
                        onBack = {},
                        onLoad = {},
                        onRemove = { _, _, done -> done(null) },
                    )
                }
            }
        }
    }

    private fun showCollection(wishlist: WishlistControls = controls()) {
        compose.setContent {
            CardPulseTheme {
                CompositionLocalProvider(LocalWishlist provides wishlist) {
                    CollectionScreen(state = state, onRefresh = {}, onRemove = { _, _, done -> done(null) }, onAddCard = {})
                }
            }
        }
    }

    // --- a set's checklist --------------------------------------------------------------------------

    @Test
    fun everyCardOfAChecklistHasAHeartWhetherOrNotYouOwnIt() {
        showChecklist()
        // Six cards: Oddish, Gloom, Vileplume, Charmander and the two Charizard ex (125 is listed, 223 is not).
        for (name in listOf("Oddish", "Gloom", "Vileplume", "Charmander")) {
            compose.onNodeWithContentDescription("Add $name to the wishlist").assertExists()
        }
        compose.onNodeWithContentDescription("Remove Charizard ex from the wishlist").assertExists() // #125, listed (and owned)
        compose.onNodeWithContentDescription("Add Charizard ex to the wishlist").assertExists() // #223
        // Oddish is owned and Gloom is missing: both can be wished for.
        compose.onNodeWithText("Gloom").assertExists()
    }

    @Test
    fun pressingTheHeartOfAMissingCardAddsIt() {
        showChecklist()
        compose.onNodeWithContentDescription("Add Gloom to the wishlist").performClick()
        assertEquals(listOf("sv3-002_en"), toggled)
    }

    @Test
    fun pressingTheHeartOfAListedCardRemovesIt() {
        showChecklist()
        compose.onNodeWithContentDescription("Remove Charizard ex from the wishlist").performClick()
        assertEquals(listOf("sv3-125_en"), toggled)
    }

    @Test
    fun theHeartDoesNotOpenTheCardUnderIt() {
        showChecklist()
        // Charizard ex #125 is owned: pressing its tile opens its details, pressing its heart must not.
        compose.onNodeWithContentDescription("Remove Charizard ex from the wishlist").performClick()
        compose.onAllNodesWithText("Paid").assertCountEquals(0) // a row of the card's details, which only its dialog has
    }

    @Test
    fun aCardWhoseChangeIsOnItsWayIsDrawnAsItWillBe() {
        // Gloom is being added: it already shows as on the wishlist; Charizard ex #125 is being removed: it shows as off.
        showChecklist(controls(pending = setOf("sv3-002_en", "sv3-125_en")))
        compose.onNodeWithContentDescription("Remove Gloom from the wishlist").assertExists()
        // Both Charizard ex are drawn as not listed now: #125 because it is being taken off, #223 because it never was.
        compose.onAllNodesWithContentDescription("Add Charizard ex to the wishlist").assertCountEquals(2)
        compose.onAllNodesWithContentDescription("Remove Charizard ex from the wishlist").assertCountEquals(0)
    }

    @Test
    fun noHeartIsDrawnBeforeTheWishlistHasLoaded() {
        // Adding a card that is already listed would raise its quantity on the server, so until the list is known: no hearts.
        showChecklist(controls(ready = false))
        compose.onAllNodesWithContentDescription("Add Gloom to the wishlist").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Remove Charizard ex from the wishlist").assertCountEquals(0)
        compose.onNodeWithText("Gloom").assertExists() // the checklist itself is there
    }

    @Test
    fun withoutAnyWishlistProvidedThereAreNoHearts() {
        compose.setContent {
            CardPulseTheme {
                SetChecklistScreen(setId = "sv3_en", state = state, onBack = {}, onLoad = {}, onRemove = { _, _, done -> done(null) })
            }
        }
        compose.onAllNodesWithContentDescription("Add Gloom to the wishlist").assertCountEquals(0)
    }

    // --- a card's details ----------------------------------------------------------------------------

    @Test
    fun theDetailsOfACardInTheCollectionOfferToAddItToTheWishlist() {
        showCollection()
        compose.onNodeWithText("Oddish").performClick()
        compose.onNodeWithText("Add to wishlist").performClick()
        assertEquals(listOf("sv3-001_en"), toggled)
    }

    @Test
    fun theDetailsOfACardThatIsListedSayAndOfferToRemoveIt() {
        showCollection(controls(listed = setOf("sv3-001_en")))
        compose.onNodeWithText("Oddish").performClick()
        compose.onNodeWithText("On your wishlist · Remove").performClick()
        assertEquals(listOf("sv3-001_en"), toggled)
    }

    // --- the small heart on a tile ----------------------------------------------------------------------

    @Test
    fun aCollectionTileOfAListedCardCarriesASmallHeart() {
        showCollection()
        // Charizard ex #125 is in the collection twice (a normal and a holo copy): two tiles, two hearts. Nothing else has one.
        compose.onAllNodesWithContentDescription("On your wishlist").assertCountEquals(2)
    }

    @Test
    fun noTileCarriesAHeartWhenNothingIsListed() {
        showCollection(controls(listed = emptySet()))
        compose.onAllNodesWithContentDescription("On your wishlist").assertCountEquals(0)
    }

    @Test
    fun noTileCarriesAHeartBeforeTheWishlistHasLoaded() {
        showCollection(controls(ready = false))
        compose.onAllNodesWithContentDescription("On your wishlist").assertCountEquals(0)
    }

    @Test
    fun theRecentlyAddedCardsOfHomeCarryTheSmallHeartToo() {
        val home = state.copy(dashboard = Fixtures.decode<DashboardDto>("dashboard"))
        compose.setContent {
            CardPulseTheme {
                CompositionLocalProvider(LocalWishlist provides controls()) {
                    HomeScreen(state = home, onRefresh = {}, onOpenSettings = {})
                }
            }
        }
        // The first two recently added cards are the two rows of Charizard ex #125, which is the card on the wishlist.
        compose.onAllNodesWithContentDescription("On your wishlist").assertCountEquals(2)
    }

    // --- Home's header ----------------------------------------------------------------------------------

    @Test
    fun homeHasAHeartButtonForTheWishlistOnlyWhenTheScreenCanOpenIt() {
        val home = state.copy(dashboard = Fixtures.decode<DashboardDto>("dashboard"))
        var opened = 0
        compose.setContent {
            CardPulseTheme { HomeScreen(state = home, onRefresh = {}, onOpenSettings = {}, onOpenWishlist = { opened++ }) }
        }
        compose.onNodeWithContentDescription("Wishlist").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun withoutTheWishlistHomeHasNoSuchButton() {
        val home = state.copy(dashboard = Fixtures.decode<DashboardDto>("dashboard"))
        compose.setContent { CardPulseTheme { HomeScreen(state = home, onRefresh = {}, onOpenSettings = {}) } }
        compose.onAllNodesWithContentDescription("Wishlist").assertCountEquals(0)
    }

    // --- the results of adding a card by typing ---------------------------------------------------------

    private val skiploom = CardDto(
        id = "sv2-002_en", name = "Skiploom", number = "002", rarity = "Uncommon", lang = "en",
        setRef = SetDto(id = "sv2_en", name = "Paldea Evolved"),
    )
    private val hoppip = skiploom.copy(id = "sv2-001_en", name = "Hoppip", number = "001", rarity = "Common")

    private fun showResults(wishlist: WishlistControls) {
        val form = CustomCardForm(name = "Skip")
        val typed = ManualAddState(form = form, results = listOf(skiploom, hoppip), matches = 2, resultsFor = form.lookup)
        compose.setContent {
            CardPulseTheme {
                CompositionLocalProvider(LocalWishlist provides wishlist) {
                    ManualAddContent(
                        state = typed,
                        serverUrl = "https://cards.example.com/",
                        currency = "USD",
                        rateFromEur = 1.1,
                        ownership = { Ownership.New },
                        actions = ManualAddActions(),
                    )
                }
            }
        }
    }

    @Test
    fun everyFoundCardCanBePutOnTheWishlistInsteadOfAddedToTheCollection() {
        showResults(controls(listed = setOf("sv2-002_en")))
        compose.onNodeWithContentDescription("Remove Skiploom from the wishlist").assertExists()
        compose.onNodeWithContentDescription("Add Hoppip to the wishlist").performClick()
        assertEquals(listOf("sv2-001_en"), toggled)
    }

    @Test
    fun noHeartIsOfferedInTheResultsBeforeTheWishlistHasLoaded() {
        showResults(controls(ready = false))
        compose.onAllNodesWithContentDescription("Add Hoppip to the wishlist").assertCountEquals(0)
    }
}
