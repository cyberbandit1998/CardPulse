package app.cardpulse.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import app.cardpulse.android.core.CollectionItemDto
import app.cardpulse.android.core.DisplayPrefs
import app.cardpulse.android.core.Fixtures
import app.cardpulse.android.core.WishlistItemDto
import app.cardpulse.android.core.WishlistPriority
import app.cardpulse.android.ui.screens.WishlistScreen
import app.cardpulse.android.ui.theme.CardPulseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Uses the Wishlist the way a person would: reads the cards and what is said about each, puts them in another order, narrows
 * them to the missing or the owned ones, opens a card to set a target price and a priority, and takes a card off. The data is
 * what the server really sent for a wishlist of six cards (`wishlist.json`) with the collection of `collection.json`: three of
 * the cards are owned (one of them twice over), three are missing, and one has no price. Like the screen pictures it only runs
 * when asked for (`-Pscreenshots`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xxhdpi")
class WishlistBehaviourTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val wishlist = Fixtures.decode<List<WishlistItemDto>>("wishlist")
    private val collection = Fixtures.decode<List<CollectionItemDto>>("collection")

    private val state = AppState(
        booting = false,
        serverUrl = "https://cards.example.com/",
        signedIn = true,
        prefs = DisplayPrefs(currency = "USD", rateFromEur = 1.1),
        collection = collection,
        collectionLoaded = true,
        wishlist = wishlist,
        wishlistLoaded = true,
    )

    /** The line under each card's name: how the rows are told apart (two of them are called Charizard ex). */
    private val subtitles = mapOf(
        "sv3-125_en" to "Obsidian Flames · #125 · Double Rare",
        "sv3-223_en" to "Obsidian Flames · #223 · Special Illustration Rare",
        "sv2-001_en" to "Paldea Evolved · #001 · Common",
        "sv2-003_en" to "Paldea Evolved · #003 · Rare",
        "sv3-125_de" to "Obsidian-Flammen · #125 · Double Rare",
        "sv1-198_en" to "Scarlet & Violet · #198 · Double Rare",
    )

    private val toggled = mutableListOf<String>()

    private fun item(cardId: String): WishlistItemDto = wishlist.first { it.cardId == cardId }

    private fun show(
        shown: AppState = state,
        onBack: () -> Unit = {},
        onLoad: (Boolean) -> Unit = {},
        onSetTarget: (WishlistItemDto, Double?, (String?) -> Unit) -> Unit = { _, _, done -> done(null) },
        onSetPriority: (String, WishlistPriority?) -> Unit = { _, _ -> },
    ) {
        val controls = WishlistControls(ready = true, listed = shown.wishlist.map { it.cardId }.toSet(), toggle = { toggled += it })
        compose.setContent {
            CardPulseTheme {
                CompositionLocalProvider(LocalWishlist provides controls) {
                    WishlistScreen(
                        state = shown,
                        onBack = onBack,
                        onLoad = onLoad,
                        onSetTarget = onSetTarget,
                        onSetPriority = onSetPriority,
                    )
                }
            }
        }
    }

    /** The cards on the screen, top to bottom, by their ids. */
    private fun shownCards(): List<String> = subtitles.entries
        .filter { compose.onAllNodesWithText(it.value).fetchSemanticsNodes().isNotEmpty() }
        .sortedBy { compose.onNodeWithText(it.value).fetchSemanticsNode().boundsInRoot.top }
        .map { it.key }

    private fun open(cardId: String) {
        compose.onNodeWithText(subtitles.getValue(cardId)).performClick()
    }

    // --- what each card says -------------------------------------------------------------------------

    @Test
    fun listsEveryCardWithItsSetNumberAndRarity() {
        show()
        assertEquals(subtitles.keys, shownCards().toSet())
        compose.onAllNodesWithText("Charizard ex").assertCountEquals(2)
        for (name in listOf("Glurak-ex", "Hoppip", "Jumpluff", "Miraidon ex")) compose.onNodeWithText(name).assertExists()
    }

    @Test
    fun showsWhatEachCardCostsNowInTheCurrencyYouSee() {
        show()
        // The server's prices are euros (trend 8.5, 61.0, 7.9, 2.4, 0.12); the screen is in dollars at 1.1.
        for (price in listOf("\$9.35", "\$67.10", "\$8.69", "\$2.64", "\$0.13")) compose.onNodeWithText(price).assertExists()
        compose.onNodeWithText("No price").assertExists() // Jumpluff: the market has no figure for it
    }

    @Test
    fun saysInWordsWhichCardsAreOwnedAndWhichAreMissing() {
        show()
        compose.onAllNodesWithText("Missing").assertCountEquals(3)
        compose.onAllNodesWithText("Owned ×2").assertCountEquals(2) // Charizard ex (two rows of one) and Miraidon ex
        compose.onAllNodesWithText("Owned").assertCountEquals(1) // Glurak-ex, the German one
    }

    @Test
    fun anOwnedCardStaysOnTheListBecauseYouMayWantMoreCopies() {
        show()
        // Charizard ex #125 is owned twice and still listed, wanted ×2.
        compose.onNodeWithText(subtitles.getValue("sv3-125_en")).assertExists()
        compose.onNodeWithText("Wants ×2").assertExists()
    }

    @Test
    fun showsTheTargetPriceAndWhetherThePriceHasReachedIt() {
        show()
        compose.onNodeWithText("Target \$8.25").assertExists() // 7.50 euros: the price is still above it
        compose.onNodeWithText("Reached \$77.00").assertExists() // 70 euros: the price is under it
    }

    @Test
    fun showsAPriorityKeptOnThePhone() {
        show(state.copy(wishlistPriorities = mapOf("sv3-223_en" to WishlistPriority.HIGH, "sv2-001_en" to WishlistPriority.LOW)))
        compose.onNodeWithText("High priority").assertExists()
        compose.onNodeWithText("Low priority").assertExists()
    }

    @Test
    fun aRowReadsAsOnePhraseToAScreenReader() {
        show()
        compose.onNodeWithContentDescription("Jumpluff, Paldea Evolved, number 003, Rare, no price, missing").assertExists()
        compose.onNodeWithContentDescription(
            "Charizard ex, Obsidian Flames, number 125, Double Rare, price \$9.35, owned 2, wants 2, target price \$8.25",
        ).assertExists()
    }

    @Test
    fun theTitleCountsTheCardsAndHowManyAreMissing() {
        show()
        compose.onNodeWithText("Wishlist").assertExists()
        compose.onNodeWithText("6 cards · 3 missing").assertExists()
    }

    // --- order and filters -----------------------------------------------------------------------------

    @Test
    fun startsWithTheCardsYouAddedLastAndCanBePutInOrderOfNameSetOrPrice() {
        show()
        assertEquals(listOf("sv3-125_en", "sv3-223_en", "sv2-001_en", "sv2-003_en", "sv3-125_de", "sv1-198_en"), shownCards())
        compose.onNodeWithText("Price").performClick()
        // Most expensive first; the card with no price last.
        assertEquals(listOf("sv3-223_en", "sv3-125_en", "sv3-125_de", "sv1-198_en", "sv2-001_en", "sv2-003_en"), shownCards())
        compose.onNodeWithText("Name").performClick()
        assertEquals(listOf("sv3-125_en", "sv3-223_en", "sv3-125_de", "sv2-001_en", "sv2-003_en", "sv1-198_en"), shownCards())
        compose.onNodeWithText("Set").performClick()
        assertEquals(listOf("sv3-125_en", "sv3-223_en", "sv3-125_de", "sv2-001_en", "sv2-003_en", "sv1-198_en"), shownCards())
        compose.onNodeWithText("Recent").performClick()
        assertEquals(listOf("sv3-125_en", "sv3-223_en", "sv2-001_en", "sv2-003_en", "sv3-125_de", "sv1-198_en"), shownCards())
    }

    @Test
    fun missingShowsOnlyTheCardsYouLackAndOwnedOnlyTheOnesYouHave() {
        show()
        compose.onNodeWithText("Missing (3)").performClick()
        assertEquals(setOf("sv3-223_en", "sv2-001_en", "sv2-003_en"), shownCards().toSet())
        compose.onNodeWithText("Owned (3)").performClick()
        assertEquals(setOf("sv3-125_en", "sv3-125_de", "sv1-198_en"), shownCards().toSet())
        compose.onNodeWithText("All (6)").performClick()
        assertEquals(subtitles.keys, shownCards().toSet())
    }

    @Test
    fun theFiltersAndTheOrderWorkTogether() {
        show()
        compose.onNodeWithText("Price").performClick()
        compose.onNodeWithText("Missing (3)").performClick()
        assertEquals(listOf("sv3-223_en", "sv2-001_en", "sv2-003_en"), shownCards())
    }

    @Test
    fun saysSoWhenNothingIsMissing() {
        // Every card owned: the collection has a copy of each.
        val all = wishlist.mapIndexed { i, w -> CollectionItemDto(id = 100 + i, cardId = w.cardId, card = w.card) }
        show(state.copy(collection = all))
        compose.onNodeWithText("Missing (0)").performClick()
        compose.onNodeWithText("Nothing is missing: you own every card on your wishlist.").assertExists()
        assertEquals(emptyList<String>(), shownCards())
    }

    @Test
    fun saysSoWhenNoCardOnTheListIsOwned() {
        show(state.copy(collection = emptyList()))
        compose.onNodeWithText("Owned (0)").performClick()
        compose.onNodeWithText("You don't own any card from your wishlist yet.").assertExists()
    }

    @Test
    fun beforeTheCollectionHasLoadedOwnedAndMissingCannotBeChosenOrSaid() {
        show(state.copy(collection = emptyList(), collectionLoaded = false))
        compose.onNodeWithText("All (6)").assertIsEnabled()
        compose.onNodeWithText("Missing").assertIsNotEnabled()
        compose.onNodeWithText("Owned").assertIsNotEnabled()
        compose.onAllNodesWithText("Missing").assertCountEquals(1) // the chip only: no card is called missing yet
        compose.onNodeWithText("6 cards").assertExists()
    }

    // --- an empty list, loading, trouble -------------------------------------------------------------

    @Test
    fun anEmptyWishlistSaysWhatItIsFor() {
        show(state.copy(wishlist = emptyList()))
        compose.onNodeWithText("Your wishlist is empty").assertExists()
        compose.onNodeWithText(
            "Tap the heart on a card to keep it here: in a set's checklist, in a card's details, or in the results when you add a " +
                "card by typing its name.",
        ).assertExists()
    }

    @Test
    fun whileTheListIsOnItsWayItSaysSo() {
        show(state.copy(wishlist = emptyList(), wishlistLoaded = false, wishlistLoading = true))
        compose.onNodeWithText("Loading your wishlist…").assertExists()
    }

    @Test
    fun ifTheListCannotBeHadItSaysWhyAndOffersToTryAgain() {
        val asked = mutableListOf<Boolean>()
        show(
            state.copy(wishlist = emptyList(), wishlistLoaded = false, wishlistError = "Can't connect to the server. Check the address and that it is running."),
            onLoad = { asked += it },
        )
        compose.onNodeWithText("Couldn't load your wishlist.").assertExists()
        compose.onNodeWithText("Can't connect to the server. Check the address and that it is running.").assertExists()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(listOf(false, true), asked)
    }

    @Test
    fun aFailedRefreshKeepsTheListItHas() {
        show(state.copy(wishlistError = "The server took too long to answer. Try again."))
        assertEquals(subtitles.keys, shownCards().toSet())
        compose.onNodeWithText("Couldn't refresh the wishlist. The server took too long to answer. Try again.").assertExists()
    }

    @Test
    fun saysHowManyEntriesTheAppCouldNotRead() {
        show(state.copy(wishlistUnreadable = 2))
        compose.onNodeWithText("2 entries from the server couldn't be read by this app and are hidden.").assertExists()
    }

    @Test
    fun asksForTheListWhenOpenedAndAgainOnRefresh() {
        val asked = mutableListOf<Boolean>()
        show(onLoad = { asked += it })
        compose.waitForIdle()
        assertEquals(listOf(false), asked)
        compose.onNodeWithContentDescription("Refresh the wishlist").performClick()
        assertEquals(listOf(false, true), asked)
    }

    @Test
    fun backGoesBack() {
        var back = 0
        show(onBack = { back++ })
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, back)
    }

    // --- opening a card ------------------------------------------------------------------------------

    @Test
    fun pressingACardOpensItWithItsFactsAndTheTargetPriceAndPriority() {
        show()
        open("sv2-001_en")
        compose.onNodeWithText("Price now").assertExists()
        compose.onAllNodesWithText("\$0.13").assertCountEquals(2) // on the row, and in the card
        compose.onNodeWithText("You own").assertExists()
        compose.onNodeWithText("Target price (USD)").assertExists()
        compose.onNodeWithText("Priority").assertExists()
        for (level in listOf("None", "Low", "Medium", "High")) compose.onNodeWithText(level).assertExists()
        compose.onNodeWithText("Remove from wishlist").assertExists()
        compose.onNodeWithText("Save").assertIsNotEnabled() // nothing has been changed
    }

    @Test
    fun aTargetPriceIsTypedInYourCurrencyAndSavedInEuros() {
        val saved = mutableListOf<Pair<Int, Double?>>()
        show(onSetTarget = { row, euros, done ->
            saved += row.id to euros
            done(null)
        })
        open("sv2-001_en")
        compose.onNode(hasSetTextAction()).performTextInput("1.10") // 1.10 dollars is 1 euro at 1.1
        compose.onNodeWithText("Save").assertIsEnabled()
        compose.onNodeWithText("Save").performClick()
        assertEquals(listOf(item("sv2-001_en").id to 1.0), saved)
        compose.onNodeWithText("Remove from wishlist").assertDoesNotExist() // saved: the card closes
    }

    @Test
    fun aTargetShowsInYourCurrencyAndEmptyingItClearsIt() {
        val saved = mutableListOf<Pair<Int, Double?>>()
        show(onSetTarget = { row, euros, done ->
            saved += row.id to euros
            done(null)
        })
        open("sv3-125_en") // its target is 7.50 euros
        compose.onNodeWithText("8.25").assertExists()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNodeWithText("Save").performClick()
        assertEquals(listOf(item("sv3-125_en").id to null), saved)
    }

    @Test
    fun somethingThatIsNotAnAmountCannotBeSaved() {
        show()
        open("sv2-001_en")
        compose.onNode(hasSetTextAction()).performTextInput("abc")
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun ifTheTargetCannotBeSavedTheCardStaysOpenAndSaysWhy() {
        show(onSetTarget = { _, _, done -> done("Can't connect to the server. Check the address and that it is running.") })
        open("sv2-001_en")
        compose.onNode(hasSetTextAction()).performTextInput("1.10")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Can't connect to the server. Check the address and that it is running.").assertExists()
        compose.onNodeWithText("Remove from wishlist").assertExists()
    }

    @Test
    fun aPriorityIsChosenAndSavedWithoutTouchingTheTarget() {
        val priorities = mutableListOf<Pair<String, WishlistPriority?>>()
        var targets = 0
        show(onSetTarget = { _, _, done ->
            targets++
            done(null)
        }, onSetPriority = { card, level -> priorities += card to level })
        open("sv2-001_en")
        compose.onNodeWithText("High").performClick()
        compose.onNodeWithText("Save").performClick()
        assertEquals(listOf("sv2-001_en" to WishlistPriority.HIGH), priorities)
        assertEquals(0, targets)
    }

    @Test
    fun noneTakesAPriorityAway() {
        val priorities = mutableListOf<Pair<String, WishlistPriority?>>()
        show(state.copy(wishlistPriorities = mapOf("sv2-001_en" to WishlistPriority.MEDIUM)), onSetPriority = { card, level -> priorities += card to level })
        compose.onNodeWithText("Medium priority").assertExists()
        open("sv2-001_en")
        compose.onNodeWithText("None").performClick()
        compose.onNodeWithText("Save").performClick()
        assertEquals(listOf<Pair<String, WishlistPriority?>>("sv2-001_en" to null), priorities)
    }

    @Test
    fun theWayToTakeACardOffIsInTheCardAndNothingElseDoesIt() {
        show()
        assertEquals(emptyList<String>(), toggled) // owning a copy, opening a card, none of it takes a card off
        open("sv3-125_en")
        compose.onNodeWithText("Remove from wishlist").performClick()
        assertEquals(listOf("sv3-125_en"), toggled)
    }
}
