package app.cardpulse.android.ui

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.cardpulse.android.core.CollectionItemDto
import app.cardpulse.android.core.DashboardDto
import app.cardpulse.android.core.DisplayPrefs
import app.cardpulse.android.core.Fixtures
import app.cardpulse.android.core.FriendsOverviewDto
import app.cardpulse.android.core.MoneyFormatter
import app.cardpulse.android.core.ShareLevel
import app.cardpulse.android.core.SharingDto
import app.cardpulse.android.core.UserDto
import app.cardpulse.android.core.copiesByCardId
import app.cardpulse.android.core.httpFailure
import app.cardpulse.android.core.matchCards
import app.cardpulse.android.core.FriendCardKind
import app.cardpulse.android.core.TradeMatchDto
import app.cardpulse.android.ui.screens.CollectionScreen
import app.cardpulse.android.ui.screens.FriendCardDetails
import app.cardpulse.android.ui.screens.FriendsScreen
import app.cardpulse.android.ui.screens.HomeScreen
import app.cardpulse.android.ui.theme.CardPulseTheme
import java.io.File
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws the Friends screens with what the Friends update's own router sent when it was run (see [FriendsWorld]), and saves a picture
 * of each, so layout and colour problems can be seen without a phone. Only runs when asked for (`-Pscreenshots`): see
 * app/build.gradle.kts and the CI workflow.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class FriendsScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val world = FriendsWorld()
    private val fake get() = world.fake
    private val session get() = world.session

    @After
    fun closeTheWorld() = world.close()

    private val collection = Fixtures.decode<List<CollectionItemDto>>("collection")

    private val state = AppState(
        booting = false,
        serverUrl = "https://cards.example.com/",
        signedIn = true,
        user = UserDto(id = 1, username = "ash", role = "user"),
        prefs = DisplayPrefs(currency = "USD", rateFromEur = 1.1, priceField = "price_trend"),
        dashboard = Fixtures.decode<DashboardDto>("dashboard"),
        collection = collection,
        collectionLoaded = true,
    )

    /** The cards that are on the user's own wishlist in these pictures: what Misty has for trade that the user wants. */
    private val listed = setOf("sv3-223_en", "sv2-001_en")

    private fun wishlist() = WishlistControls(ready = true, listed = listed, toggle = {})

    /** Draws [content] with the user's hearts and For Trade marks provided as the app provides them. */
    private fun draw(dark: Boolean = true, fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.setContent {
            val friends by session.state.collectAsState()
            val trade = rememberTradeControls(friends, session) {}
            CardPulseTheme(darkTheme = dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale),
                    LocalTrade provides trade,
                    LocalWishlist provides wishlist(),
                ) { content() }
            }
        }
    }

    private fun drawFriends(dark: Boolean = true, fontScale: Float = 1f) = draw(dark, fontScale) {
        Scaffold { padding -> FriendsScreen(app = state, session = session, onBack = {}, modifier = Modifier.padding(padding)) }
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

    private fun openFriend(name: String) = compose.onNodeWithText(name).performClick()

    // --- the Friends screen ----------------------------------------------------------------------------

    @Test
    fun friends() {
        drawFriends()
        capture("1j-friends")
    }

    @Test
    fun friendsInTheLightTheme() {
        drawFriends(dark = false)
        capture("1j-friends-light")
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun friendsOnASmallPhone() {
        drawFriends()
        capture("1j-friends-small")
    }

    @Test
    fun friendsWithNoneYet() {
        fake.overview = FriendsOverviewDto()
        drawFriends()
        capture("1j-friends-none")
    }

    @Test
    fun friendsAddingSomeoneByInviteCode() {
        drawFriends()
        compose.onNodeWithText("Invite code").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("k7mq9xwd3h")
        capture("1j-friends-add-code")
    }

    @Test
    fun friendsAfterARequestWasSent() {
        drawFriends()
        compose.onNode(hasSetTextAction()).performTextInput("serena")
        compose.onNodeWithText("Send request").performClick()
        capture("1j-friends-requested")
    }

    @Test
    fun friendsWhenARequestIsRefused() {
        fake.errors["sendFriendRequest"] = httpFailure(404, "Nobody on this server has that username")
        drawFriends()
        compose.onNode(hasSetTextAction()).performTextInput("nobody")
        compose.onNodeWithText("Send request").performClick()
        capture("1j-friends-refused")
    }

    @Test
    @Config(qualifiers = "w360dp-h900dp-xxhdpi")
    fun friendsInLargeText() {
        drawFriends(fontScale = 1.5f)
        capture("1j-friends-large-text")
    }

    @Test
    fun requests() {
        drawFriends()
        compose.onNodeWithText("Requests (1)").performClick()
        capture("1j-friends-requests")
    }

    @Test
    fun requestsInTheLightTheme() {
        drawFriends(dark = false)
        compose.onNodeWithText("Requests (1)").performClick()
        capture("1j-friends-requests-light")
    }

    // --- what the user shares ----------------------------------------------------------------------------

    private fun sharedWithFriends() {
        fake.me = fake.me.copy(sharing = SharingDto(collection = "private", wishlist = "friends", trade = "friends"))
    }

    @Test
    @Config(qualifiers = "w360dp-h1300dp-xxhdpi")
    fun sharingStartsPrivate() {
        drawFriends()
        compose.onNodeWithText("Sharing").performClick()
        capture("1j-sharing-private")
    }

    @Test
    @Config(qualifiers = "w360dp-h1300dp-xxhdpi")
    fun sharingWithFriends() {
        sharedWithFriends()
        drawFriends()
        compose.onNodeWithText("Sharing").performClick()
        capture("1j-sharing")
    }

    @Test
    @Config(qualifiers = "w360dp-h1300dp-xxhdpi")
    fun sharingInTheLightTheme() {
        sharedWithFriends()
        drawFriends(dark = false)
        compose.onNodeWithText("Sharing").performClick()
        capture("1j-sharing-light")
    }

    @Test
    @Config(qualifiers = "w320dp-h1200dp-xhdpi")
    fun sharingOnASmallPhone() {
        sharedWithFriends()
        drawFriends()
        compose.onNodeWithText("Sharing").performClick()
        capture("1j-sharing-small")
    }

    @Test
    @Config(qualifiers = "w360dp-h1700dp-xxhdpi")
    fun sharingInLargeText() {
        sharedWithFriends()
        drawFriends(fontScale = 1.5f)
        compose.onNodeWithText("Sharing").performClick()
        capture("1j-sharing-large-text")
    }

    @Test
    @Config(qualifiers = "w360dp-h1300dp-xxhdpi")
    fun sharingWhileMakingANewCode() {
        drawFriends()
        compose.onNodeWithText("Sharing").performClick()
        compose.onNodeWithText("New code…").performClick()
        capture("1j-sharing-new-code")
    }

    @Test
    fun yourForTradeList() {
        sharedWithFriends()
        drawFriends()
        compose.onNodeWithText("Sharing").performClick()
        compose.onNodeWithText("Your For Trade list").performClick()
        capture("1j-my-trade")
    }

    @Test
    fun yourForTradeListInTheLightTheme() {
        sharedWithFriends()
        drawFriends(dark = false)
        compose.onNodeWithText("Sharing").performClick()
        compose.onNodeWithText("Your For Trade list").performClick()
        capture("1j-my-trade-light")
    }

    // --- a server that cannot do this ---------------------------------------------------------------------

    @Test
    @Config(qualifiers = "w360dp-h700dp-xxhdpi")
    fun friendsOnAServerWithoutTheUpdate() {
        fake.errors["friendsMe"] = httpFailure(404, "Not Found")
        drawFriends()
        capture("1j-friends-update")
    }

    @Test
    @Config(qualifiers = "w360dp-h700dp-xxhdpi")
    fun friendsOnAServerWithoutTheUpdateInTheLightTheme() {
        fake.errors["friendsMe"] = httpFailure(404, "Not Found")
        drawFriends(dark = false)
        capture("1j-friends-update-light")
    }

    @Test
    @Config(qualifiers = "w360dp-h700dp-xxhdpi")
    fun friendsOnAServerWithMultiUserModeOff() {
        fake.errors["friendsMe"] = httpFailure(403, "Friends needs multi-user mode.")
        drawFriends()
        capture("1j-friends-multiuser")
    }

    @Test
    @Config(qualifiers = "w360dp-h700dp-xxhdpi")
    fun friendsOnAServerThatCannotBeReached() {
        fake.errors["friendsMe"] = java.net.ConnectException("refused")
        drawFriends()
        capture("1j-friends-unreachable")
    }

    // --- a friend's page -----------------------------------------------------------------------------------

    @Test
    @Config(qualifiers = "w360dp-h1100dp-xxhdpi")
    fun friendTradeMatch() {
        drawFriends()
        openFriend("misty")
        capture("1j-friend-match")
    }

    @Test
    @Config(qualifiers = "w360dp-h1100dp-xxhdpi")
    fun friendTradeMatchInTheLightTheme() {
        drawFriends(dark = false)
        openFriend("misty")
        capture("1j-friend-match-light")
    }

    @Test
    @Config(qualifiers = "w320dp-h1100dp-xhdpi")
    fun friendTradeMatchOnASmallPhone() {
        drawFriends()
        openFriend("misty")
        capture("1j-friend-match-small")
    }

    @Test
    @Config(qualifiers = "w360dp-h1500dp-xxhdpi")
    fun friendTradeMatchInLargeText() {
        drawFriends(fontScale = 1.5f)
        openFriend("misty")
        capture("1j-friend-match-large-text")
    }

    @Test
    @Config(qualifiers = "w360dp-h700dp-xxhdpi")
    fun friendTradeMatchWhenNothingMatches() {
        fake.matches[MISTY] = TradeMatchDto(canSeeTheirTradeList = true, canSeeTheirWishlist = true)
        drawFriends()
        openFriend("misty")
        capture("1j-friend-match-none")
    }

    @Test
    @Config(qualifiers = "w360dp-h900dp-xxhdpi")
    fun friendWhoSharesOnlyTheirTradeList() {
        drawFriends()
        openFriend("brock")
        capture("1j-friend-match-brock")
    }

    @Test
    @Config(qualifiers = "w360dp-h900dp-xxhdpi")
    fun friendWhoSharesNothing() {
        drawFriends()
        openFriend("gary")
        capture("1j-friend-match-gary")
    }

    @Test
    @Config(qualifiers = "w360dp-h700dp-xxhdpi")
    fun friendListThatIsNotShared() {
        drawFriends()
        openFriend("gary")
        compose.onNodeWithText("Wishlist").performClick()
        capture("1j-friend-private")
    }

    @Test
    @Config(qualifiers = "w360dp-h700dp-xxhdpi")
    fun friendListThatIsNotSharedInTheLightTheme() {
        drawFriends(dark = false)
        openFriend("gary")
        compose.onNodeWithText("Wishlist").performClick()
        capture("1j-friend-private-light")
    }

    @Test
    @Config(qualifiers = "w360dp-h900dp-xxhdpi")
    fun friendForTrade() {
        drawFriends()
        openFriend("misty")
        compose.onNodeWithText("For trade").performClick()
        capture("1j-friend-trade")
    }

    @Test
    @Config(qualifiers = "w360dp-h900dp-xxhdpi")
    fun friendForTradeInTheLightTheme() {
        drawFriends(dark = false)
        openFriend("misty")
        compose.onNodeWithText("For trade").performClick()
        capture("1j-friend-trade-light")
    }

    @Test
    @Config(qualifiers = "w360dp-h900dp-xxhdpi")
    fun friendWishlist() {
        drawFriends()
        openFriend("misty")
        compose.onNodeWithText("Wishlist").performClick()
        capture("1j-friend-wishlist")
    }

    @Test
    @Config(qualifiers = "w360dp-h900dp-xxhdpi")
    fun friendCollection() {
        drawFriends()
        openFriend("misty")
        compose.onNodeWithText("Collection").performClick()
        capture("1j-friend-collection")
    }

    @Test
    @Config(qualifiers = "w360dp-h900dp-xxhdpi")
    fun friendCollectionInTheLightTheme() {
        drawFriends(dark = false)
        openFriend("misty")
        compose.onNodeWithText("Collection").performClick()
        capture("1j-friend-collection-light")
    }

    @Test
    @Config(qualifiers = "w360dp-h700dp-xxhdpi")
    fun friendCollectionThatCannotBeLoaded() {
        fake.errors["friendCollection"] = httpFailure(500, "Something went wrong on the server.")
        drawFriends()
        openFriend("misty")
        compose.onNodeWithText("Collection").performClick()
        capture("1j-friend-error")
    }

    // --- a friend's card opened (the dialog is a window of its own, so its content is drawn on its own) --------

    private fun cardDetails(dark: Boolean, name: String, picture: String) {
        val mine = collection.copiesByCardId()
        val money = MoneyFormatter("USD", 1.1)
        val match = Fixtures.decode<TradeMatchDto>("friend_trade_match_misty")
        val card = match.theyHaveYouWant.matchCards(FriendCardKind.THEIR_OFFER, mine, "price_trend").first { it.name == name }
        draw(dark) {
            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(card.name, style = MaterialTheme.typography.headlineSmall)
                    FriendCardDetails(card, "misty", state, money)
                }
            }
        }
        capture(picture)
    }

    @Test
    @Config(qualifiers = "w360dp-h900dp-xxhdpi")
    fun friendCardDetails() = cardDetails(dark = true, name = "Charizard ex", picture = "1j-friend-card")

    @Test
    @Config(qualifiers = "w360dp-h900dp-xxhdpi")
    fun friendCardDetailsInTheLightTheme() = cardDetails(dark = false, name = "Charizard ex", picture = "1j-friend-card-light")

    // --- the user's own cards --------------------------------------------------------------------------------

    @Test
    fun collectionWithTheSwapBadgeOnCardsForTrade() {
        drawCollection(dark = true)
        capture("11-collection-trade")
    }

    @Test
    fun collectionWithTheSwapBadgeInTheLightTheme() {
        drawCollection(dark = false)
        capture("11-collection-trade-light")
    }

    private fun drawCollection(dark: Boolean) {
        session.start()
        draw(dark) { CollectionScreen(state = state, onRefresh = {}, onRemove = { _, _, _ -> }, onAddCard = {}) }
    }

    /** The "For trade" line of a card's details, drawn on its own: the details are a dialog, which the pictures cannot see. */
    private fun tradeLine(dark: Boolean, visibleTo: ShareLevel, held: Int, offered: Int, picture: String) {
        val row = collection.first { it.id == 3 }.copy(quantity = held)
        val controls = TradeControls(ready = true, marks = mapOf(row.id to offered).filterValues { it > 0 }, visibleTo = visibleTo)
        compose.setContent {
            CardPulseTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalTrade provides controls) {
                    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Oddish", style = MaterialTheme.typography.headlineSmall)
                            TradeSection(row)
                        }
                    }
                }
            }
        }
        capture(picture)
    }

    @Test
    @Config(qualifiers = "w360dp-h300dp-xxhdpi")
    fun forTradeLineWithTwoOfFourOffered() = tradeLine(true, ShareLevel.FRIENDS, 4, 2, "11-trade-line")

    @Test
    @Config(qualifiers = "w360dp-h300dp-xxhdpi")
    fun forTradeLineWithNothingOffered() = tradeLine(true, ShareLevel.PRIVATE, 4, 0, "11-trade-line-none")

    @Test
    @Config(qualifiers = "w360dp-h300dp-xxhdpi")
    fun forTradeLineInTheLightTheme() = tradeLine(false, ShareLevel.PUBLIC, 4, 4, "11-trade-line-light")

    // --- Home ---------------------------------------------------------------------------------------------

    private fun home(dark: Boolean, note: String, requests: Int, picture: String) {
        compose.setContent {
            CardPulseTheme(darkTheme = dark) {
                HomeScreen(state = state, onRefresh = {}, onOpenSettings = {}, friendsNote = note, friendsRequests = requests, onOpenFriends = {})
            }
        }
        capture(picture)
    }

    @Test
    @Config(qualifiers = "w360dp-h1300dp-xxhdpi")
    fun homeWithFriendsAndTrading() = home(true, "3 friends · 1 request waiting", 1, "10-home-friends")

    @Test
    @Config(qualifiers = "w360dp-h1300dp-xxhdpi")
    fun homeWithFriendsAndTradingInTheLightTheme() = home(false, "3 friends · 1 request waiting", 1, "10-home-friends-light")

    @Test
    @Config(qualifiers = "w360dp-h1300dp-xxhdpi")
    fun homeOnAServerWithoutTheUpdate() = home(true, "Needs an update on your server", 0, "10-home-friends-update")
}
