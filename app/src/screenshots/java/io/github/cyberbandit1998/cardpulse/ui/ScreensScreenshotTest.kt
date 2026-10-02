package io.github.cyberbandit1998.cardpulse.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import io.github.cyberbandit1998.cardpulse.core.AddEdits
import io.github.cyberbandit1998.cardpulse.core.CollectionIndex
import io.github.cyberbandit1998.cardpulse.core.CollectionItemDto
import io.github.cyberbandit1998.cardpulse.core.DashboardDto
import io.github.cyberbandit1998.cardpulse.core.DisplayPrefs
import io.github.cyberbandit1998.cardpulse.core.Fixtures
import io.github.cyberbandit1998.cardpulse.core.MoverDto
import io.github.cyberbandit1998.cardpulse.core.Ownership
import io.github.cyberbandit1998.cardpulse.core.ScanEntry
import io.github.cyberbandit1998.cardpulse.core.ScanJobDto
import io.github.cyberbandit1998.cardpulse.core.ScanMatchDto
import io.github.cyberbandit1998.cardpulse.core.ScanItemDto
import io.github.cyberbandit1998.cardpulse.core.ScanOutcome
import io.github.cyberbandit1998.cardpulse.core.SnapshotDto
import io.github.cyberbandit1998.cardpulse.core.Upload
import io.github.cyberbandit1998.cardpulse.core.UserDto
import io.github.cyberbandit1998.cardpulse.core.ownershipOf
import io.github.cyberbandit1998.cardpulse.core.toChartPoints
import io.github.cyberbandit1998.cardpulse.data.ScanPrefs
import io.github.cyberbandit1998.cardpulse.ui.screens.CollectionScreen
import io.github.cyberbandit1998.cardpulse.ui.screens.HomeScreen
import io.github.cyberbandit1998.cardpulse.ui.screens.LoginScreen
import io.github.cyberbandit1998.cardpulse.ui.screens.PasswordScreen
import io.github.cyberbandit1998.cardpulse.ui.screens.PortfolioScreen
import io.github.cyberbandit1998.cardpulse.ui.screens.RapidActions
import io.github.cyberbandit1998.cardpulse.ui.screens.RapidScreenContent
import io.github.cyberbandit1998.cardpulse.ui.screens.ScanHomeContent
import io.github.cyberbandit1998.cardpulse.ui.screens.SettingsScreen
import io.github.cyberbandit1998.cardpulse.ui.theme.CardPulseTheme
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
    val compose = createComposeRule()

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent { CardPulseTheme { content() } }
        compose.waitForIdle()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
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
        entry(8, item = ScanItemDto(id = 8, status = "processing")),
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
        }
    }

    private fun capture(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(System.getProperty("screens.dir") ?: "build/screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
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
        CollectionScreen(state = signedIn, onRefresh = {})
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
