package app.cardpulse.android.ui

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.cardpulse.android.core.CardDto
import app.cardpulse.android.core.CardSearchDto
import app.cardpulse.android.core.CatalogQuery
import app.cardpulse.android.core.CatalogSearchState
import app.cardpulse.android.core.CollectionItemDto
import app.cardpulse.android.core.DashboardDto
import app.cardpulse.android.core.DisplayPrefs
import app.cardpulse.android.core.Fixtures
import app.cardpulse.android.core.SearchScope
import app.cardpulse.android.core.SetDto
import app.cardpulse.android.ui.screens.HomeScreen
import app.cardpulse.android.ui.screens.SearchActions
import app.cardpulse.android.ui.screens.SearchContent
import app.cardpulse.android.ui.theme.CardPulseTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws the search of the whole catalogue and the bar that opens it with what the server's own search route sent when it was run
 * (`card_search_*.json`), and saves a picture of each, so layout and colour problems can be seen without a phone. Only runs when
 * asked for (`-Pscreenshots`): see app/build.gradle.kts and the CI workflow.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class SearchScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val named = Fixtures.decode<CardSearchDto>("card_search_name").data
    private val byArtist = Fixtures.decode<CardSearchDto>("card_search_artist").data
    private val sets = Fixtures.decode<List<SetDto>>("card_search_sets")

    /** Two copies of the Base Set Pikachu, which the search results say so about. */
    private val collection = listOf(
        CollectionItemDto(
            id = 1, cardId = "base1-58_en", quantity = 2, condition = "NM", variant = "Normal", lang = "en",
            card = named.first { it.id == "base1-58_en" },
        ),
    )

    private val app = AppState(
        booting = false,
        serverUrl = "https://cards.example.com/",
        signedIn = true,
        prefs = DisplayPrefs(currency = "USD", rateFromEur = 1.1, priceField = "price_trend"),
        dashboard = Fixtures.decode<DashboardDto>("dashboard"),
        collection = Fixtures.decode<List<CollectionItemDto>>("collection") + collection,
        collectionLoaded = true,
    )

    private fun found(cards: List<CardDto>, scope: SearchScope, text: String) = CatalogSearchState(
        scope = scope,
        text = text,
        results = cards,
        matches = cards.size,
        resultsFor = CatalogQuery(scope, text),
        sets = sets,
        setsLoaded = true,
    )

    /** Draws [content] with the heart and the artist search provided as the app provides them. */
    private fun draw(dark: Boolean = true, content: @Composable () -> Unit) {
        compose.setContent {
            CardPulseTheme(darkTheme = dark) {
                CompositionLocalProvider(
                    // Pikachu of the 30th Celebration is on the wishlist; the rest are not.
                    LocalWishlist provides WishlistControls(ready = true, listed = setOf("tst1-130_en"), toggle = {}),
                    LocalCardSearch provides CardSearchControls(enabled = true),
                ) { content() }
            }
        }
    }

    private fun drawSearch(state: CatalogSearchState, dark: Boolean = true) = draw(dark) {
        Scaffold { padding ->
            SearchContent(state = state, app = app, actions = SearchActions(), modifier = Modifier.padding(padding), focusOnOpen = false)
        }
    }

    /**
     * Saves a picture of the window as it is now. Compose's own captureToImage waits for the phone to draw a frame,
     * which never happens on the JVM, so this asks for the copy of the screen that Robolectric makes synchronously.
     */
    private fun capture(name: String) {
        compose.waitForIdle()
        val window = compose.activity.window
        val decor = window.decorView
        check(decor.width > 0 && decor.height > 0) { "The window has no size yet" }
        val image = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        var result = Int.MIN_VALUE
        val wholeWindow: Rect? = null
        PixelCopy.request(window, wholeWindow, image, { result = it }, Handler(Looper.getMainLooper()))
        check(result == PixelCopy.SUCCESS) { "Copying the screen failed with code $result" }
        val dir = File(System.getProperty("screens.dir") ?: "build/screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // --- the way in, on Home ------------------------------------------------------------------------

    private fun drawHome(dark: Boolean) = draw(dark) {
        Scaffold { padding -> HomeScreen(state = app, onRefresh = {}, onOpenSettings = {}, onOpenSearch = {}, modifier = Modifier.padding(padding)) }
    }

    @Test
    fun homeWithTheSearchBar() {
        drawHome(dark = true)
        capture("1k-search-home")
    }

    @Test
    fun homeWithTheSearchBarInTheLightTheme() {
        drawHome(dark = false)
        capture("1k-search-home-light")
    }

    // --- the search ---------------------------------------------------------------------------------

    @Test
    fun searchBeforeAnythingIsTyped() {
        drawSearch(CatalogSearchState())
        capture("1k-search-start")
    }

    @Test
    fun searchByPokemonName() {
        drawSearch(found(named, SearchScope.ALL, "pikachu"))
        capture("1k-search-name")
    }

    @Test
    fun searchByPokemonNameInTheLightTheme() {
        drawSearch(found(named, SearchScope.ALL, "pikachu"), dark = false)
        capture("1k-search-name-light")
    }

    @Test
    fun searchByArtist() {
        drawSearch(found(byArtist, SearchScope.ARTIST, "mitsuhiro arita"))
        capture("1k-search-artist")
    }

    @Test
    fun searchByArtistInTheLightTheme() {
        drawSearch(found(byArtist, SearchScope.ARTIST, "mitsuhiro arita"), dark = false)
        capture("1k-search-artist-light")
    }

    @Test
    fun searchWithNothingFound() {
        drawSearch(found(emptyList(), SearchScope.ALL, "zzzz"))
        capture("1k-search-none")
    }

    @Test
    fun searchThatFailed() {
        drawSearch(CatalogSearchState(text = "pikachu", error = "Couldn't reach your server."))
        capture("1k-search-error")
    }

    // --- one card -----------------------------------------------------------------------------------

    @Test
    fun aCardOpened() {
        drawSearch(found(named, SearchScope.ALL, "pikachu"))
        compose.onNodeWithText("Base Set · #58/102 · Common").performClick()
        capture("1k-search-card")
    }

    @Test
    fun aCardOpenedInTheLightTheme() {
        drawSearch(found(named, SearchScope.ALL, "pikachu"), dark = false)
        compose.onNodeWithText("Base Set · #58/102 · Common").performClick()
        capture("1k-search-card-light")
    }
}
