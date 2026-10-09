package app.cardpulse.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.cardpulse.android.core.CardDto
import app.cardpulse.android.core.CardSearchDto
import app.cardpulse.android.core.CatalogQuery
import app.cardpulse.android.core.CatalogSearchState
import app.cardpulse.android.core.CollectionItemDto
import app.cardpulse.android.core.DisplayPrefs
import app.cardpulse.android.core.Fixtures
import app.cardpulse.android.core.MoneyFormatter
import app.cardpulse.android.core.SearchScope
import app.cardpulse.android.core.SetDto
import app.cardpulse.android.core.WishlistItemDto
import app.cardpulse.android.ui.screens.CollectionScreen
import app.cardpulse.android.ui.screens.HomeScreen
import app.cardpulse.android.ui.screens.SearchActions
import app.cardpulse.android.ui.screens.SearchContent
import app.cardpulse.android.ui.screens.WishlistScreen
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
 * Uses the search of the whole catalogue the way a person would: opens it from the bar on Home, picks what to look at, reads the cards
 * found (their picture, name, set, number, artist, rarity, price, how many are owned and the heart), opens one, and follows the
 * artist's name to every card they drew. The cards are what the server's own search route sent when it was run (`card_search_*.json`),
 * the user's collection has two copies of one of them, and one of them is on the wishlist. Like the screen pictures it only runs when
 * asked for (`-Pscreenshots`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xxhdpi")
class SearchBehaviourTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Detective Pikachu (no artist), Flying Pikachu ex, and three plain Pikachu from three sets. */
    private val named = Fixtures.decode<CardSearchDto>("card_search_name").data
    private val sets = Fixtures.decode<List<SetDto>>("card_search_sets")

    private val baseSetPikachu = named.first { it.id == "base1-58_en" }
    private val celebrationPikachu = named.first { it.id == "tst1-130_en" }
    private val detective = named.first { it.id == "tst1-002_en" }

    private val prefs = DisplayPrefs(currency = "USD", rateFromEur = 1.1, priceField = "price_trend")
    private val money = MoneyFormatter("USD", 1.1)

    /** Two copies of the Base Set Pikachu, both Near Mint, Normal and English. */
    private val owned = listOf(
        CollectionItemDto(id = 1, cardId = "base1-58_en", quantity = 2, condition = "NM", variant = "Normal", lang = "en", card = baseSetPikachu),
    )

    private val app = AppState(
        booting = false,
        serverUrl = "https://cards.example.com/",
        signedIn = true,
        prefs = prefs,
        collection = owned,
        collectionLoaded = true,
    )

    private fun found(
        cards: List<CardDto> = named,
        scope: SearchScope = SearchScope.ALL,
        text: String = "pikachu",
    ) = CatalogSearchState(
        scope = scope,
        text = text,
        results = cards,
        matches = cards.size,
        resultsFor = CatalogQuery(scope, text),
        sets = sets,
        setsLoaded = true,
    )

    private val toggled = mutableListOf<String>()
    private val searchedArtists = mutableListOf<String>()

    private fun wishlist(listed: Set<String> = setOf("tst1-130_en")) = WishlistControls(ready = true, listed = listed, toggle = { toggled += it })

    private fun artistSearch(enabled: Boolean = true) = CardSearchControls(enabled = enabled, byArtist = { searchedArtists += it })

    private fun showSearch(
        state: CatalogSearchState = found(),
        shownApp: AppState = app,
        actions: SearchActions = SearchActions(),
        search: CardSearchControls = artistSearch(),
    ) {
        compose.setContent {
            CardPulseTheme {
                CompositionLocalProvider(LocalWishlist provides wishlist(), LocalCardSearch provides search) {
                    SearchContent(state = state, app = shownApp, actions = actions, focusOnOpen = false)
                }
            }
        }
    }

    private fun countOf(text: String, substring: Boolean = false) = compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size

    // --- the way in, on Home ------------------------------------------------------------------------

    private fun showHome(onOpenSearch: ((SearchScope) -> Unit)?) {
        compose.setContent {
            CardPulseTheme {
                HomeScreen(state = app, onRefresh = {}, onOpenSettings = {}, onOpenSearch = onOpenSearch)
            }
        }
    }

    @Test
    fun theSearchBarUnderTheHeaderOpensTheSearchOfEverything() {
        val opened = mutableListOf<SearchScope>()
        showHome { opened += it }

        compose.onNodeWithText("Search cards, artists, sets…").performClick()

        assertEquals(listOf(SearchScope.ALL), opened)
    }

    @Test
    fun theSlidersAtTheEndOfTheBarOfferWhatToLookAtAndOpenTheSearchForIt() {
        val opened = mutableListOf<SearchScope>()
        showHome { opened += it }

        compose.onNodeWithContentDescription("Choose what to search").performClick()
        for (scope in SearchScope.entries) compose.onNodeWithText(scope.label).assertExists()
        compose.onNodeWithText("Artist").performClick()

        assertEquals(listOf(SearchScope.ARTIST), opened)
    }

    @Test
    fun homeHasNoSearchBarWhenNoSearchIsOffered() {
        showHome(onOpenSearch = null)
        compose.onNodeWithText("Search cards, artists, sets…").assertDoesNotExist()
    }

    // --- the box and the chips ----------------------------------------------------------------------

    @Test
    fun thereIsOneSearchBoxAndAllIsChosenToStartWith() {
        showSearch(CatalogSearchState())

        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNodeWithText("Search cards, artists, sets…").assertExists()
        for (scope in SearchScope.entries) compose.onNodeWithText(scope.label).assertExists()
        compose.onNodeWithText("All").assertIsSelected()
        compose.onNodeWithText("Artist").assertExists()
    }

    @Test
    fun choosingWhatToLookAtTellsTheSearchAndTheBoxSaysWhatItNowTakes() {
        val chosen = mutableListOf<SearchScope>()
        showSearch(CatalogSearchState(scope = SearchScope.ARTIST), actions = SearchActions(scope = { chosen += it }))

        compose.onNodeWithText("Artist").assertIsSelected()
        compose.onNodeWithText("Artist or illustrator").assertExists()
        compose.onNodeWithText("Set").performClick()
        compose.onNodeWithText("Number").performClick()

        assertEquals(listOf(SearchScope.SET, SearchScope.NUMBER), chosen)
    }

    @Test
    fun typingGoesToTheSearchAndTheCrossClearsTheBox() {
        val typed = mutableListOf<String>()
        showSearch(found(), actions = SearchActions(text = { typed += it }))

        compose.onNode(hasSetTextAction()).performTextInput("x")
        compose.onNodeWithContentDescription("Clear the search").performClick()

        assertTrue(typed.first().contains("x")) // what was typed, joined to what the box held
        assertEquals("", typed.last())
    }

    // --- the cards found ----------------------------------------------------------------------------

    @Test
    fun everyCardShowsItsNameSetNumberRarityArtistAndPrice() {
        showSearch()

        compose.onNodeWithText("5 cards").assertExists()
        // The number is shown as printed, with the size of its set, from the sets the server lists.
        compose.onNodeWithText("Base Set · #58/102 · Common").assertExists()
        compose.onNodeWithText("30th Celebration · #130/128 · Illustration Rare").assertExists()
        compose.onNodeWithText("Illus. Hasuno").assertExists()
        compose.onNodeWithText("Illus. Atsuko Nishida").assertExists()
        assertEquals(2, countOf("Illus. Mitsuhiro Arita"))
        compose.onNodeWithText(money.format(3.5)).assertExists() // the Base Set Pikachu, by the user's price and currency
        compose.onNodeWithText(money.format(24.9)).assertExists()
    }

    @Test
    fun aCardTheCatalogueHasNoArtistForShowsNoArtistLine() {
        showSearch()

        // Five cards, four with an artist: the fifth, Detective Pikachu, has no line for one rather than a blank.
        assertEquals(4, countOf("Illus.", substring = true))
        compose.onNodeWithText("Detective Pikachu").assertExists()
    }

    @Test
    fun aCardWithNoPriceSaysSo() {
        val unpriced = named.first().copy(priceTrend = null, priceMarket = null, priceLow = null, priceAvg1 = null, priceAvg7 = null, priceAvg30 = null)
        showSearch(found(cards = listOf(unpriced)))

        compose.onNodeWithText("No price").assertExists()
    }

    @Test
    fun theCopiesOwnedAreSaidOnTheCardsThatAreOwnedAndOnlyThere() {
        showSearch()

        compose.onAllNodesWithText("Owned", substring = true).assertCountEquals(1)
        compose.onNodeWithText("Owned ×2").assertExists()
    }

    @Test
    fun nothingIsSaidAboutOwnershipBeforeTheCollectionHasLoaded() {
        showSearch(shownApp = app.copy(collectionLoaded = false))

        compose.onAllNodesWithText("Owned", substring = true).assertCountEquals(0)
    }

    @Test
    fun eachCardHasAHeartThatIsFilledForTheOnesOnTheWishlist() {
        showSearch()

        compose.onNodeWithContentDescription("Remove Pikachu from the wishlist").assertExists() // the 30th Celebration one
        compose.onAllNodesWithContentDescription("Add Pikachu to the wishlist").assertCountEquals(2)
        compose.onNodeWithContentDescription("Add Detective Pikachu to the wishlist").performClick()

        assertEquals(listOf("tst1-002_en"), toggled)
    }

    @Test
    fun noHeartIsDrawnBeforeTheWishlistHasLoaded() {
        compose.setContent {
            CardPulseTheme {
                CompositionLocalProvider(LocalWishlist provides WishlistControls(ready = false)) {
                    SearchContent(state = found(), app = app, actions = SearchActions(), focusOnOpen = false)
                }
            }
        }
        compose.onAllNodesWithContentDescription("to the wishlist", substring = true).assertCountEquals(0)
    }

    // --- states -------------------------------------------------------------------------------------

    @Test
    fun beforeAnythingIsTypedItSaysWhatCanBeSearched() {
        showSearch(CatalogSearchState())
        compose.onNodeWithText("Search every card").assertExists()
    }

    @Test
    fun withTooLittleTypedItAsksForMore() {
        showSearch(CatalogSearchState(text = "p"))
        compose.onNodeWithText("Keep typing").assertExists()
    }

    @Test
    fun nothingFoundSaysSoAndSuggestsWhatToTry() {
        showSearch(found(cards = emptyList(), text = "zzzz"))

        compose.onNodeWithText("No card matches “zzzz”").assertExists()
        compose.onNodeWithText("Check the spelling, or try fewer words.").assertExists()
    }

    @Test
    fun nothingFoundInOneScopeSuggestsAll() {
        showSearch(found(cards = emptyList(), scope = SearchScope.ARTIST, text = "zzzz"))
        compose.onNodeWithText("Check the spelling, or choose All to look at names, artists, sets and numbers together.").assertExists()
    }

    @Test
    fun aWordAboutTheResultsIsShownWhenTheSearchCouldNotBeAsExactAsTyped() {
        showSearch(found().copy(note = "No set prints 99 cards, so these are the cards numbered 130 in every set."))
        compose.onNodeWithText("No set prints 99 cards, so these are the cards numbered 130 in every set.").assertExists()
    }

    @Test
    fun aSearchThatFailedSaysSoAndCanBeTriedAgain() {
        var retried = 0
        showSearch(CatalogSearchState(text = "pikachu", error = "Couldn't reach your server."), actions = SearchActions(retry = { retried++ }))

        compose.onNodeWithText("Couldn't search the catalogue.").assertExists()
        compose.onNodeWithText("Couldn't reach your server.").assertExists()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun morePagesOfCardsFailingKeepsWhatWasReadAndCanBeTriedAgain() {
        var retried = 0
        showSearch(found().copy(hasMore = true, moreError = "The connection dropped."), actions = SearchActions(retry = { retried++ }))

        compose.onNodeWithText("Detective Pikachu").assertExists()
        compose.onNodeWithText("Couldn't read more cards. The connection dropped.").assertExists()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun theRestOfTheCardsCanBeAskedForWhenThereAreMore() {
        var asked = 0
        showSearch(found().copy(matches = 90, hasMore = true), actions = SearchActions(loadMore = { asked++ }))

        compose.onNodeWithText("90 cards").assertExists()
        compose.onNodeWithText("Show more").performClick()
        assertTrue(asked >= 1)
    }

    // --- one card, and its artist -------------------------------------------------------------------

    @Test
    fun aCardOpensAsAPageWithItsFactsAndTheCopiesOwned() {
        showSearch()

        compose.onNodeWithText("Base Set · #58/102 · Common").performClick()

        compose.onNodeWithContentDescription("Back to the results").assertExists()
        compose.onNodeWithText("Base Set").assertExists()
        compose.onNodeWithText("58/102").assertExists()
        compose.onNodeWithText("Common").assertExists()
        compose.onNodeWithText(money.format(3.5)).assertExists()
        compose.onNodeWithText("×2").assertExists()
        compose.onNodeWithText("2× NM · Normal · EN").assertExists()
        compose.onNodeWithText("Add to wishlist").assertExists() // this one is not on it
    }

    @Test
    fun aCardThatIsNotOwnedSaysNoneAndAHeartButtonIsOnItsPage() {
        showSearch()

        compose.onNodeWithText("30th Celebration · #130/128 · Illustration Rare").performClick()

        compose.onNodeWithText("none").assertExists()
        compose.onNodeWithText("On your wishlist · Remove").assertExists() // this one is on it
    }

    @Test
    fun theArtistOnACardsPageIsAWayToEveryCardTheyDrew() {
        showSearch()
        compose.onNodeWithText("Base Set · #58/102 · Common").performClick()

        compose.onNodeWithText("Mitsuhiro Arita").assertHasClickAction()
        compose.onNodeWithText("Mitsuhiro Arita").performClick()

        assertEquals(listOf("Mitsuhiro Arita"), searchedArtists)
        // The page closes, as the search for the artist opens under it.
        compose.onNodeWithContentDescription("Back to the results").assertDoesNotExist()
    }

    @Test
    fun aCardWithoutAnArtistHasNoArtistLineOnItsPage() {
        showSearch()
        compose.onNodeWithText("Detective Pikachu").performClick()

        compose.onNodeWithContentDescription("Back to the results").assertExists()
        compose.onNodeWithText("Artist").assertDoesNotExist()
    }

    @Test
    fun theArtistIsPlainTextWhereNothingCanSearch() {
        showSearch(search = CardSearchControls.None)
        compose.onNodeWithText("Base Set · #58/102 · Common").performClick()

        compose.onNodeWithText("Mitsuhiro Arita").assertExists()
        compose.onNodeWithText("Mitsuhiro Arita").assertHasNoClickAction()
    }

    @Test
    fun backFromACardsPageIsTheListAsItWas() {
        showSearch()
        compose.onNodeWithText("Detective Pikachu").performClick()
        compose.onNodeWithContentDescription("Back to the results").performClick()

        compose.onNodeWithText("5 cards").assertExists()
        compose.onNodeWithText("Detective Pikachu").assertExists()
    }

    // --- the artist, from the other cards' details --------------------------------------------------

    private val hasArtist = CardDto(
        id = "tst1-130_en", tcgCardId = "tst1-130", name = "Pikachu", setId = "tst1", number = "130", rarity = "Illustration Rare",
        artist = "Mitsuhiro Arita", lang = "en", setRef = sets.first { it.id == "tst1_en" },
    )

    @Test
    fun theArtistOnACollectionCardIsAWayToTheirCardsAndTheDetailsClose() {
        val shown = app.copy(collection = listOf(CollectionItemDto(id = 7, cardId = "tst1-130_en", quantity = 1, card = hasArtist)))
        compose.setContent {
            CardPulseTheme {
                CompositionLocalProvider(LocalWishlist provides wishlist(), LocalCardSearch provides artistSearch()) {
                    CollectionScreen(state = shown, onRefresh = {}, onRemove = { _, _, done -> done(null) }, onAddCard = {})
                }
            }
        }

        compose.onNodeWithText("Pikachu").performClick()
        compose.onNodeWithText("Remove…").assertExists()
        compose.onNodeWithText("Mitsuhiro Arita").performClick()

        assertEquals(listOf("Mitsuhiro Arita"), searchedArtists)
        compose.onNodeWithText("Remove…").assertDoesNotExist()
    }

    @Test
    fun aCollectionCardWithNoArtistShowsNoArtistLine() {
        val shown = app.copy(collection = listOf(CollectionItemDto(id = 7, cardId = "tst1-130_en", quantity = 1, card = hasArtist.copy(artist = null))))
        compose.setContent {
            CardPulseTheme {
                CompositionLocalProvider(LocalWishlist provides wishlist(), LocalCardSearch provides artistSearch()) {
                    CollectionScreen(state = shown, onRefresh = {}, onRemove = { _, _, done -> done(null) }, onAddCard = {})
                }
            }
        }

        compose.onNodeWithText("Pikachu").performClick()
        compose.onNodeWithText("Remove…").assertExists()
        compose.onNodeWithText("Artist").assertDoesNotExist()
    }

    @Test
    fun theArtistOnAWishlistCardIsAWayToTheirCards() {
        val shown = app.copy(
            wishlist = listOf(WishlistItemDto(id = 3, cardId = "tst1-130_en", quantity = 1, card = hasArtist)),
            wishlistLoaded = true,
        )
        compose.setContent {
            CardPulseTheme {
                CompositionLocalProvider(LocalWishlist provides wishlist(), LocalCardSearch provides artistSearch()) {
                    WishlistScreen(state = shown, onBack = {}, onLoad = {}, onSetTarget = { _, _, done -> done(null) }, onSetPriority = { _, _ -> })
                }
            }
        }

        compose.onNodeWithText("Pikachu").performClick()
        compose.onNodeWithText("Mitsuhiro Arita").performClick()

        assertEquals(listOf("Mitsuhiro Arita"), searchedArtists)
    }
}
