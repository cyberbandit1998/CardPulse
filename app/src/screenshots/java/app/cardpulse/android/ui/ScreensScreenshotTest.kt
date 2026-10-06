package app.cardpulse.android.ui

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.cardpulse.android.camera.CardGuide
import app.cardpulse.android.core.AddEdits
import app.cardpulse.android.core.AddedNote
import app.cardpulse.android.core.CardDto
import app.cardpulse.android.core.CollectionIndex
import app.cardpulse.android.core.CollectionItemDto
import app.cardpulse.android.core.CustomCardForm
import app.cardpulse.android.core.DashboardDto
import app.cardpulse.android.core.DisplayPrefs
import app.cardpulse.android.core.Fixtures
import app.cardpulse.android.core.ManualAddState
import app.cardpulse.android.core.ManualMode
import app.cardpulse.android.core.MoverDto
import app.cardpulse.android.core.Ownership
import app.cardpulse.android.core.ScanEntry
import app.cardpulse.android.core.ScanItemDto
import app.cardpulse.android.core.ScanJobDto
import app.cardpulse.android.core.ScanMatchDto
import app.cardpulse.android.core.ScanOutcome
import app.cardpulse.android.core.SetDto
import app.cardpulse.android.core.SnapshotDto
import app.cardpulse.android.core.ThemeMode
import app.cardpulse.android.core.Upload
import app.cardpulse.android.core.UserDto
import app.cardpulse.android.core.lookup
import app.cardpulse.android.core.ownershipOf
import app.cardpulse.android.core.toChartPoints
import app.cardpulse.android.data.ScanPrefs
import app.cardpulse.android.ui.screens.CollectionScreen
import app.cardpulse.android.ui.screens.HomeScreen
import app.cardpulse.android.ui.screens.LoginScreen
import app.cardpulse.android.ui.screens.ManualAddActions
import app.cardpulse.android.ui.screens.ManualAddContent
import app.cardpulse.android.ui.screens.MostValuableScreen
import app.cardpulse.android.ui.screens.PasswordScreen
import app.cardpulse.android.ui.screens.PortfolioScreen
import app.cardpulse.android.ui.screens.RapidActions
import app.cardpulse.android.ui.screens.RapidScreenContent
import app.cardpulse.android.ui.screens.RemoveChoices
import app.cardpulse.android.ui.screens.SettingsScreen
import app.cardpulse.android.ui.screens.SetsScreen
import app.cardpulse.android.ui.theme.CardPulseTheme
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws every screen with realistic fake data and saves a picture of each, so layout and colour problems can be
 * seen without a phone. Only runs when asked for (`-Pscreenshots`): see app/build.gradle.kts and the CI workflow.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class ScreensScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shoot(name: String, dark: Boolean = true, content: @Composable () -> Unit) {
        compose.setContent { CardPulseTheme(darkTheme = dark) { content() } }
        capture(name)
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

    // --- fake data -----------------------------------------------------------------------------------

    private val collection = Fixtures.decode<List<CollectionItemDto>>("collection")
    private val index = CollectionIndex(collection)
    private val job = Fixtures.decode<ScanJobDto>("scan_job_detail")

    private val signedIn = AppState(
        booting = false,
        serverUrl = "https://cards.example.com/",
        signedIn = true,
        user = UserDto(id = 1, username = "admin", role = "admin"),
        prefs = DisplayPrefs(currency = "USD", rateFromEur = 1.1, priceField = "price_trend"),
        dashboard = Fixtures.decode<DashboardDto>("dashboard"),
        collection = collection,
        collectionLoaded = true,
        history = Fixtures.decode<List<SnapshotDto>>("investment_tracker_1m").toChartPoints(),
        movers = Fixtures.decode<List<MoverDto>>("top_movers"),
    )

    private val signedOut = AppState(booting = false)

    private fun recognized(name: String, local: String, total: String, set: String): JsonObject = buildJsonObject {
        put("name", name)
        put("number_local", local)
        put("number_total", total)
        put("set_code", set)
    }

    private fun match(id: String, name: String, set: String = "Obsidian Flames", number: String = "125", rarity: String = "Double Rare") =
        ScanMatchDto(
            id = id, tcgCardId = id.substringBeforeLast('_'), name = name, set = set, number = number, rarity = rarity,
            image = "https://img.example/$id.png", lang = id.substringAfterLast('_'),
        )

    private fun done(id: Int, vararg matches: ScanMatchDto, read: JsonObject? = null) =
        ScanItemDto(id = id, status = "done", matches = matches.toList(), recognized = read)

    private fun entry(
        id: Long,
        item: ScanItemDto? = null,
        upload: Upload = Upload.SENT,
        outcome: ScanOutcome? = null,
        edits: AddEdits? = null,
        error: String? = null,
        uploadError: String? = null,
        candidate: Int = 0,
        busy: Boolean = false,
    ) = ScanEntry(
        id = id, photo = null, upload = upload, uploadError = uploadError, jobId = if (upload == Upload.SENT) id.toInt() else null,
        item = item, candidate = candidate, edits = edits, outcome = outcome, busy = busy, error = error,
    )

    /** What each kind of result looks like in the tray. */
    private val trayEntries: List<ScanEntry> = listOf(
        entry(9, upload = Upload.SENDING),
        entry(8, item = ScanItemDto(id = 8, status = "processing")).copy(slow = true), // has shown no progress for a long time
        entry(7, item = ScanItemDto(id = 7, status = "retrying", retryReason = "rate_limit")),
        entry(6, item = done(6, match("sv3-125_en", "Charizard ex"), match("sv3-125_de", "Glurak ex"))), // owned
        entry(5, item = done(5, match("sv9-5_en", "Pikachu", set = "Journey Together", number = "5", rarity = "Common"))), // new
        entry(4, item = done(4)), // nothing matched
        entry(3, item = ScanItemDto(id = 3, status = "failed", error = "The scanner could not read this photo.")),
        entry(2, upload = Upload.FAILED, uploadError = "Can't connect to the server. Check the address and that it is running."),
        entry(1, item = done(1, match("sv3-125_en", "Charizard ex")), outcome = ScanOutcome.Added("Charizard ex")),
        entry(0, item = done(0), outcome = ScanOutcome.Skipped),
    )

    private fun ownership(entry: ScanEntry): Ownership = entry.match?.let { index.ownershipOf(it) } ?: Ownership.Unknown

    /** [fontScale] above 1 is a phone set to a larger text size. */
    private fun rapid(
        entries: List<ScanEntry>,
        openId: Long? = null,
        message: String? = null,
        currency: String = "USD",
        fontScale: Float = 1f,
        dark: Boolean = true,
    ) {
        compose.setContent {
            CardPulseTheme(darkTheme = dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    RapidScreenContent(
                        entries = entries,
                        openId = openId,
                        message = message,
                        serverUrl = "https://cards.example.com/",
                        currency = currency,
                        rateFromEur = 1.1,
                        ownership = { ownership(it) },
                        capturing = false,
                        canShoot = true,
                        preview = { CameraStandIn() },
                        actions = RapidActions(),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun CameraStandIn() {
        Box(Modifier.fillMaxSize().background(Color(0xFF2B3A33)), contentAlignment = Alignment.Center) {
            Text("(live camera)", color = Color(0x88FFFFFF))
            CardGuide(Modifier.fillMaxSize())
        }
    }

    // --- signing in -------------------------------------------------------------------------------------

    @Test
    fun login() = shoot("01-login") {
        LoginScreen(state = signedOut, onTest = {}, onSignIn = { _, _, _ -> }, onDismissMessage = {})
    }

    @Test
    fun loginLight() = shoot("01-login-light", dark = false) {
        LoginScreen(state = signedOut, onTest = {}, onSignIn = { _, _, _ -> }, onDismissMessage = {})
    }

    @Test
    fun loginWithError() = shoot("02-login-error") {
        LoginScreen(
            state = signedOut.copy(serverUrl = "https://cards.example.com/", message = "Incorrect username or password"),
            onTest = {}, onSignIn = { _, _, _ -> }, onDismissMessage = {},
        )
    }

    @Test
    fun forcedPassword() = shoot("03-password") {
        PasswordScreen(state = signedIn, onChange = {}, onSignOut = {}, onDismissMessage = {})
    }

    // --- the tabs -----------------------------------------------------------------------------------------

    // --- the Home screen, in both themes --------------------------------------------------------------------

    private val celebration = SetDto(id = "cel_en", tcgSetId = "cel", name = "30th Celebration", total = 132, printedTotal = 132)
    private val journey = SetDto(id = "jt_en", tcgSetId = "jt", name = "Journey Together", total = 190, printedTotal = 190)

    private fun owned(id: Int, cardId: String, name: String, set: SetDto, number: String) = CollectionItemDto(
        id = id, cardId = cardId, card = CardDto(id = cardId, name = name, number = number, setId = set.tcgSetId, setRef = set),
    )

    /** The fixture's rows plus two sets that are well under way, so the progress bars have something to show. */
    private val richCollection: List<CollectionItemDto> = collection +
        (1..18).map { owned(100 + it, "cel-$it", "Card $it", celebration, "%03d".format(it)) } +
        (1..7).map { owned(200 + it, "jt-$it", "Card $it", journey, "%03d".format(it)) }

    private val homeState = signedIn.copy(collection = richCollection)

    // For the Sets tab: one set whose name needs two lines, and one that is complete (its bar turns green).
    private val paldea = SetDto(id = "sv2_en", tcgSetId = "sv2", name = "Scarlet & Violet—Paldea Evolved", total = 279, printedTotal = 193)
    private val trickOrTrade = SetDto(id = "tot_en", tcgSetId = "tot", name = "Trick or Trade", total = 6, printedTotal = 6)
    private val setsState = homeState.copy(
        collection = richCollection +
            (1..40).map { owned(300 + it, "sv2-$it", "Card $it", paldea, "%03d".format(it)) } +
            (1..6).map { owned(400 + it, "tot-$it", "Card $it", trickOrTrade, "%03d".format(it)) },
    )

    @Composable
    private fun HomeWithBar(state: AppState, waiting: Int = 0) {
        Scaffold(bottomBar = { CardPulseBottomBar(selected = MainTab.HOME, onSelect = {}, onScanNow = {}, waiting = waiting) }) { padding ->
            HomeScreen(state = state, onRefresh = {}, onOpenSettings = {}, modifier = Modifier.padding(padding))
        }
    }

    @Composable
    private fun SetsWithBar(state: AppState) {
        Scaffold(bottomBar = { CardPulseBottomBar(selected = MainTab.SETS, onSelect = {}, onScanNow = {}) }) { padding ->
            SetsScreen(state = state, onOpenSet = {}, modifier = Modifier.padding(padding))
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h1300dp-xxhdpi")
    fun home() = shoot("10-home") {
        HomeScreen(state = homeState, onRefresh = {}, onOpenSettings = {})
    }

    @Test
    @Config(qualifiers = "w360dp-h1300dp-xxhdpi")
    fun homeLight() = shoot("10-home-light", dark = false) {
        HomeScreen(state = homeState, onRefresh = {}, onOpenSettings = {})
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun homeOnAPhone() = shoot("1a-home-phone") { HomeWithBar(homeState) }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun homeOnAPhoneLight() = shoot("1a-home-phone-light", dark = false) { HomeWithBar(homeState) }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun homeOfflineAndLoading() = shoot("1b-home-offline") {
        HomeWithBar(homeState.copy(offline = true, dashboardLoading = true))
    }

    @Test
    @Config(qualifiers = "w320dp-h780dp-xxhdpi")
    fun homeWithBigNumbersOnASmallPhone() = shoot("1c-home-big-numbers") {
        val dashboard = homeState.dashboard!!.copy(totalValue = 12345.67, totalCost = 4321.0, unrealizedPnl = -321.5, totalCards = 1234, ownedSets = 87)
        HomeWithBar(homeState.copy(dashboard = dashboard))
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun homeWithScannedCardsWaiting() = shoot("1f-home-cards-waiting") { HomeWithBar(homeState, waiting = 3) }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun homeWithManyScannedCardsWaiting() = shoot("1f-home-many-waiting") { HomeWithBar(homeState, waiting = 12) }

    /** A finger held down on the Top Card tile: it should sink a little and show the ripple. */
    private fun pressedTile(name: String, dark: Boolean) {
        compose.setContent { CardPulseTheme(darkTheme = dark) { HomeWithBar(homeState) } }
        compose.onNodeWithText("Top Card").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(400)
        capture(name)
        compose.onNodeWithText("Top Card").performTouchInput { cancel() } // let go without pressing it
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun homeWhileATileIsPressed() = pressedTile("1g-home-tile-pressed", dark = true)

    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun homeWhileATileIsPressedInTheLightTheme() = pressedTile("1g-home-tile-pressed-light", dark = false)

    @Test
    fun mostValuableList() = shoot("1d-most-valuable") {
        MostValuableScreen(homeState, onBack = {}, onRemove = { _, _, _ -> })
    }

    @Test
    fun mostValuableListLight() = shoot("1d-most-valuable-light", dark = false) {
        MostValuableScreen(homeState, onBack = {}, onRemove = { _, _, _ -> })
    }

    // --- the Sets tab ----------------------------------------------------------------------------------------

    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun setsTab() = shoot("1e-sets") { SetsWithBar(setsState) }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun setsTabLight() = shoot("1e-sets-light", dark = false) { SetsWithBar(setsState) }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun setsTabBeforeAnythingIsOwned() = shoot("1e-sets-empty") { SetsWithBar(homeState.copy(collection = emptyList())) }

    @Test
    fun collectionTab() = shoot("11-collection") {
        CollectionScreen(state = signedIn, onRefresh = {}, onRemove = { _, _, _ -> }, onAddCard = {})
    }

    @Test
    fun collectionTabLight() = shoot("11-collection-light", dark = false) {
        CollectionScreen(state = signedIn, onRefresh = {}, onRemove = { _, _, _ -> }, onAddCard = {})
    }

    // --- taking a card out of the collection -------------------------------------------------------

    private val oneCopy = collection.first { it.quantity == 1 && it.card != null }
    private val severalCopies = collection.first { it.quantity > 1 && it.card != null }

    @Test
    fun removeOneCard() = shoot("14-remove-one") {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            RemoveChoices(oneCopy, photoGoesToo = true, busy = false, problem = null, onRemove = {})
        }
    }

    @Test
    fun removeFromSeveralCopies() = shoot("15-remove-several") {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            RemoveChoices(severalCopies, photoGoesToo = false, busy = false, problem = null, onRemove = {})
        }
    }

    @Test
    fun removeRefusedByTheServer() = shoot("16-remove-refused") {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            RemoveChoices(
                severalCopies, photoGoesToo = false, busy = true,
                problem = "This collection item has 1 copy allocated to Card Lists. Release that copy first.",
                onRemove = {},
            )
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h1300dp-xxhdpi")
    fun portfolio() = shoot("12-portfolio") {
        PortfolioScreen(state = signedIn, onShowHistory = { _, _ -> })
    }

    @Test
    @Config(qualifiers = "w360dp-h1300dp-xxhdpi")
    fun portfolioLight() = shoot("12-portfolio-light", dark = false) {
        PortfolioScreen(state = signedIn, onShowHistory = { _, _ -> })
    }

    @Test
    @Config(qualifiers = "w360dp-h1100dp-xxhdpi")
    fun settings() = shoot("13-settings") {
        SettingsScreen(
            app = signedIn, scan = ScanState(prefs = ScanPrefs()), onBack = {}, onSignOut = {}, onSavePhotos = {}, onLookUpPrices = {},
        )
    }

    @Test
    @Config(qualifiers = "w360dp-h1100dp-xxhdpi")
    fun settingsLight() = shoot("13-settings-light", dark = false) {
        SettingsScreen(
            app = signedIn.copy(themeMode = ThemeMode.LIGHT), scan = ScanState(prefs = ScanPrefs()),
            onBack = {}, onSignOut = {}, onSavePhotos = {}, onLookUpPrices = {},
        )
    }

    // --- scanning -----------------------------------------------------------------------------------------

    @Test
    fun rapidScanBeforeTheFirstPhoto() {
        rapid(emptyList())
        capture("30-rapid-empty")
    }

    @Test
    fun rapidScanWithManyCardsToReview() {
        // The badge at the top counts them; the button only says what it does.
        val ready = (1L..12L).map { id -> entry(id, item = done(id.toInt(), match("sv3-125_en", "Charizard ex"))) }
        rapid(ready)
        capture("3d-rapid-many-to-review")
    }

    @Test
    fun rapidScanWithLargeText() {
        // A phone set to a bigger text size: the labels at the bottom must still be read in full.
        rapid(trayEntries, fontScale = 1.5f)
        capture("3e-rapid-large-text")
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun rapidScanWithLargeTextOnASmallPhone() {
        val ready = (1L..12L).map { id -> entry(id, item = done(id.toInt(), match("sv3-125_en", "Charizard ex"))) }
        rapid(ready, fontScale = 1.3f)
        capture("3f-rapid-large-text-small-phone")
    }

    @Test
    fun rapidScanTrayShowsEveryKindOfResult() {
        rapid(trayEntries)
        capture("31-rapid-tray")
    }

    @Test
    fun rapidScanTrayInTheLightTheme() {
        rapid(trayEntries, dark = false)
        capture("31-rapid-tray-light")
    }

    @Test
    fun confirmDuplicateCardInTheLightTheme() {
        rapid(trayEntries, openId = 6, currency = "USD", dark = false)
        capture("32-confirm-duplicate-light")
    }

    @Test
    fun confirmDuplicateCard() {
        // Charizard ex is in the collection three times over (two English rows and a German one).
        rapid(trayEntries, openId = 6, currency = "USD")
        capture("32-confirm-duplicate")
    }

    @Test
    fun confirmNewCardWithChoicesChangedAndAnError() {
        val item = done(
            5,
            match("sv9-5_en", "Pikachu", set = "Journey Together", number = "5", rarity = "Common"),
            match("sv9-5_de", "Pikachu", set = "Reisegefährten", number = "5", rarity = "Common"),
            read = recognized("Pikachu", "5", "159", "JTG"),
        )
        val chosen = entry(
            5, item = item, edits = AddEdits(quantity = 2, condition = "LP", variant = "Reverse Holo", lang = "ja", purchasePrice = 1.5),
            error = "Card is no longer available locally.",
        )
        rapid(listOf(chosen) + trayEntries.filter { it.id != 5L }, openId = 5)
        capture("33-confirm-new")
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun confirmOnASmallPhone() {
        val item = done(
            5,
            match("sv9-5_en", "Pikachu", set = "Journey Together", number = "5", rarity = "Common"),
            match("sv9-5_de", "Pikachu", set = "Reisegefährten", number = "5", rarity = "Common"),
        )
        val chosen = entry(5, item = item, edits = AddEdits(quantity = 12, condition = "MP", variant = "First Edition", lang = "zh-tw"))
        rapid(listOf(chosen) + trayEntries.filter { it.id != 5L }, openId = 5)
        capture("38-confirm-small-phone")
    }

    // --- giving up on a scan that is still spinning ----------------------------------------------

    @Test
    fun cancelWhileTheServerIsReading() {
        val reading = entry(8, item = ScanItemDto(id = 8, status = "processing"))
        rapid(listOf(reading) + trayEntries.filter { it.id != 8L }, openId = 8)
        capture("39-cancel-reading")
    }

    @Test
    fun cancelWhenItLooksStuck() {
        rapid(trayEntries, openId = 8) // entry 8 has shown no progress for a long time
        capture("3a-cancel-stuck")
    }

    @Test
    fun cancelWhileWaitingOutALimit() {
        val waiting = entry(
            7, item = ScanItemDto(id = 7, status = "retrying", retryReason = "rate_limit", nextAttemptAt = "2026-10-02T14:32:00Z"),
        )
        rapid(listOf(waiting) + trayEntries.filter { it.id != 7L }, openId = 7)
        capture("3b-cancel-waiting")
    }

    @Test
    fun cancelWhileSending() {
        rapid(trayEntries, openId = 9) // entry 9 is still being sent
        capture("3c-cancel-sending")
    }

    @Test
    fun confirmWhileTheCollectionHasNotLoaded() {
        compose.setContent {
            CardPulseTheme {
                RapidScreenContent(
                    entries = trayEntries, openId = 6, message = null, serverUrl = "https://cards.example.com/",
                    currency = "EUR", rateFromEur = 1.0, ownership = { Ownership.Unknown },
                    capturing = false, canShoot = true, preview = { CameraStandIn() }, actions = RapidActions(),
                )
            }
        }
        compose.waitForIdle()
        capture("34-confirm-unknown")
    }

    @Test
    fun confirmNothingMatched() {
        rapid(trayEntries, openId = 4)
        capture("35-confirm-no-match")
    }

    @Test
    fun confirmPhotoNeverSent() {
        rapid(trayEntries, openId = 2)
        capture("36-confirm-send-failed")
    }

    @Test
    fun rapidScanWithAProblemMessage() {
        rapid(trayEntries.take(5), message = "Couldn't take the photo: camera in use")
        capture("37-rapid-message")
    }

    // --- adding a card by typing its name and number ---------------------------------------------------

    private val obsidianFlames = SetDto(id = "sv3_en", tcgSetId = "sv3", name = "Obsidian Flames", series = "Scarlet & Violet", abbreviation = "OBF", lang = "en")
    private val journeyTogether = SetDto(id = "sv9_en", tcgSetId = "sv9", name = "Journey Together", series = "Scarlet & Violet", abbreviation = "JTG", lang = "en")
    private val baseSet = SetDto(id = "base1_en", tcgSetId = "base1", name = "Base Set", series = "Base", abbreviation = "BS", lang = "en")
    private val sets = listOf(journeyTogether, obsidianFlames, baseSet, obsidianFlames.copy(id = "sv3_de", name = "Obsidian Flammen", lang = "de"))

    private fun types(vararg names: String) = JsonArray(names.map { JsonPrimitive(it) })

    private val charizardEx = CardDto(
        id = "sv3-125_en", name = "Charizard ex", setId = "sv3", number = "125", rarity = "Double Rare", lang = "en", setRef = obsidianFlames,
        types = types("Fire"), hp = "330", artist = "5ban Graphics",
        variantsNormal = true, variantsReverse = true, variantsHolo = true, variantsFirstEdition = false,
    )
    private val pikachuJourney = CardDto(
        id = "sv9-5_en", name = "Pikachu", setId = "sv9", number = "5", rarity = "Common", lang = "en", setRef = journeyTogether,
        types = types("Lightning"), hp = "60", artist = "Mitsuhiro Arita",
        variantsNormal = true, variantsReverse = true, variantsHolo = false, variantsFirstEdition = false,
    )
    private val pikachuBase = CardDto(
        id = "base1-58_en", name = "Pikachu", setId = "base1", number = "58", rarity = "Common", lang = "en", setRef = baseSet,
        types = types("Lightning"), hp = "40", artist = "Mitsuhiro Arita",
        variantsNormal = true, variantsReverse = false, variantsHolo = false, variantsFirstEdition = true,
    )
    private val pikachuGerman = pikachuJourney.copy(
        id = "sv9-5_de", lang = "de", setRef = journeyTogether.copy(id = "sv9_de", name = "Reisegefährten", lang = "de"),
    )

    private fun manual(state: ManualAddState, currency: String = "USD", dark: Boolean = true) {
        compose.setContent {
            CardPulseTheme(darkTheme = dark) {
                ManualAddContent(
                    state = state,
                    serverUrl = "https://cards.example.com/",
                    currency = currency,
                    rateFromEur = 1.1,
                    ownership = { index.ownershipOf(it) },
                    actions = ManualAddActions(),
                )
            }
        }
        compose.waitForIdle()
    }

    private fun typed(name: String, number: String = "", vararg found: CardDto, picked: CardDto? = null, edits: AddEdits = AddEdits()): ManualAddState {
        val form = CustomCardForm(name = name, number = number)
        return ManualAddState(
            form = form, results = found.toList(), matches = found.size, resultsFor = form.lookup,
            selectedId = picked?.id, edits = edits,
        )
    }

    @Test
    fun manualAddBeforeAnythingIsTyped() {
        manual(ManualAddState())
        capture("40-manual-empty")
    }

    @Test
    fun manualAddFindsTheCardFromItsNameAndNumber() {
        // One match is picked by itself: its picture, set, rarity, type, hit points and artist are filled in.
        manual(typed("Charizard ex", "125/197", charizardEx, picked = charizardEx, edits = AddEdits(quantity = 2, condition = "LP", variant = "Holo")))
        capture("41-manual-found")
    }

    @Test
    fun manualAddFindsTheCardInTheLightTheme() {
        manual(
            typed("Charizard ex", "125/197", charizardEx, picked = charizardEx, edits = AddEdits(quantity = 2, condition = "LP", variant = "Holo")),
            dark = false,
        )
        capture("41-manual-found-light")
    }

    @Test
    fun manualAddListsSeveralMatches() {
        manual(typed("Pikachu", "", pikachuJourney, pikachuBase, pikachuGerman, charizardEx.copy(id = "sv3-26_en", name = "Pikachu ex", number = "26")))
        capture("42-manual-several")
    }

    @Test
    fun manualAddWithOneOfSeveralPicked() {
        manual(
            typed(
                "Pikachu", "", pikachuJourney, pikachuBase, pikachuGerman, picked = pikachuBase,
                edits = AddEdits(variant = "First Edition", purchasePrice = 12.0),
            ),
        )
        capture("43-manual-picked")
    }

    @Test
    fun manualAddWhenTheCatalogueHasNoSuchCard() {
        manual(typed("Missingno", "999"))
        capture("44-manual-no-match")
    }

    @Test
    fun manualAddWhenTheSearchFails() {
        val form = CustomCardForm(name = "Pikachu")
        manual(ManualAddState(form = form, searchError = "Can't connect to the server. Check the address and that it is running."))
        capture("47-manual-search-error")
    }

    @Test
    fun manualAddRemembersWhatWasJustAdded() {
        manual(ManualAddState(lastAdded = AddedNote("Charizard ex", 2)))
        capture("46-manual-added")
    }

    @Test
    fun manualAddWhenAddingFails() {
        manual(
            typed("Charizard ex", "125", charizardEx, picked = charizardEx)
                .copy(error = "That wasn't found on the server."),
        )
        capture("48-manual-add-error")
    }

    @Test
    @Config(qualifiers = "w360dp-h1500dp-xxhdpi")
    fun manualAddByHandStartingBlank() {
        manual(
            ManualAddState(
                form = CustomCardForm(name = "Missingno", number = "999"), mode = ManualMode.BY_HAND, sets = sets,
            ),
        )
        capture("45-manual-by-hand")
    }

    @Test
    @Config(qualifiers = "w360dp-h1500dp-xxhdpi")
    fun manualAddByHandFilledInWithTheSetsOfferedAsYouType() {
        manual(
            ManualAddState(
                form = CustomCardForm(
                    name = "Charizard ex", number = "025", otherSetId = "obs", rarity = "Rare Holo", types = setOf("Fire", "Dragon"),
                    hp = "200", artist = "Mitsuhiro Arita", imageUrl = "https://example.com/c.png", shareAsTemplate = true,
                ),
                mode = ManualMode.BY_HAND, sets = sets,
            ),
        )
        capture("49-manual-by-hand-filled")
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun manualAddOnASmallPhone() {
        manual(typed("Charizard ex", "125", charizardEx, picked = charizardEx, edits = AddEdits(quantity = 12, condition = "MP", variant = "Holo", lang = "zh-tw")))
        capture("4a-manual-small-phone")
    }

    @Test
    fun jobFixtureIsUsable() {
        // Guards the fake data above: the fixture must still contain the shapes the screens are drawn with.
        check(job.items.isNotEmpty())
    }
}
