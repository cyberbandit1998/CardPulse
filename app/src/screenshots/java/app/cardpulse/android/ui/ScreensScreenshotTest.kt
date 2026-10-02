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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import app.cardpulse.android.camera.CardGuide
import app.cardpulse.android.core.AddEdits
import app.cardpulse.android.core.CollectionIndex
import app.cardpulse.android.core.CollectionItemDto
import app.cardpulse.android.core.DashboardDto
import app.cardpulse.android.core.DisplayPrefs
import app.cardpulse.android.core.Fixtures
import app.cardpulse.android.core.MoverDto
import app.cardpulse.android.core.Ownership
import app.cardpulse.android.core.ScanEntry
import app.cardpulse.android.core.ScanItemDto
import app.cardpulse.android.core.ScanJobDto
import app.cardpulse.android.core.ScanMatchDto
import app.cardpulse.android.core.ScanOutcome
import app.cardpulse.android.core.SnapshotDto
import app.cardpulse.android.core.Upload
import app.cardpulse.android.core.UserDto
import app.cardpulse.android.core.ownershipOf
import app.cardpulse.android.core.toChartPoints
import app.cardpulse.android.data.ScanPrefs
import app.cardpulse.android.ui.screens.CollectionScreen
import app.cardpulse.android.ui.screens.HomeScreen
import app.cardpulse.android.ui.screens.LoginScreen
import app.cardpulse.android.ui.screens.PasswordScreen
import app.cardpulse.android.ui.screens.PortfolioScreen
import app.cardpulse.android.ui.screens.RapidActions
import app.cardpulse.android.ui.screens.RapidScreenContent
import app.cardpulse.android.ui.screens.RemoveChoices
import app.cardpulse.android.ui.screens.ScanHomeContent
import app.cardpulse.android.ui.screens.SettingsScreen
import app.cardpulse.android.ui.theme.CardPulseTheme
import kotlinx.serialization.json.JsonObject
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

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent { CardPulseTheme { content() } }
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

    private fun rapid(entries: List<ScanEntry>, openId: Long? = null, message: String? = null, currency: String = "USD") {
        compose.setContent {
            CardPulseTheme {
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

    @Test
    @Config(qualifiers = "w360dp-h1300dp-xxhdpi")
    fun home() = shoot("10-home") {
        HomeScreen(state = signedIn, onRefresh = {}, onOpenSettings = {})
    }

    @Test
    fun collectionTab() = shoot("11-collection") {
        CollectionScreen(state = signedIn, onRefresh = {}, onRemove = { _, _, _ -> })
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
    @Config(qualifiers = "w360dp-h1000dp-xxhdpi")
    fun settings() = shoot("13-settings") {
        SettingsScreen(
            app = signedIn, scan = ScanState(prefs = ScanPrefs()), onBack = {}, onSignOut = {}, onSavePhotos = {},
        )
    }

    // --- scanning -----------------------------------------------------------------------------------------

    @Test
    fun scanHomeNothingWaiting() = shoot("20-scan-home") {
        ScanHomeContent(
            ready = 0, reading = 0, attention = 0, message = null, savePhotos = false,
            onStart = {}, onReview = {}, onSavePhotos = {}, onDismissMessage = {},
        )
    }

    @Test
    fun scanHomeWithResultsWaiting() = shoot("21-scan-home-waiting") {
        ScanHomeContent(
            ready = 3, reading = 2, attention = 1, message = "A scan is no longer on the server (scans expire after 14 days).",
            savePhotos = true, onStart = {}, onReview = {}, onSavePhotos = {}, onDismissMessage = {},
        )
    }

    @Test
    fun rapidScanBeforeTheFirstPhoto() {
        rapid(emptyList())
        capture("30-rapid-empty")
    }

    @Test
    fun rapidScanTrayShowsEveryKindOfResult() {
        rapid(trayEntries)
        capture("31-rapid-tray")
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

    @Test
    fun jobFixtureIsUsable() {
        // Guards the fake data above: the fixture must still contain the shapes the screens are drawn with.
        check(job.items.isNotEmpty())
    }
}
