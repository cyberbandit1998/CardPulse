package app.cardpulse.android.ui

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import app.cardpulse.android.camera.CardGuide
import app.cardpulse.android.core.Fixtures
import app.cardpulse.android.core.Ownership
import app.cardpulse.android.core.ScanAllowanceDto
import app.cardpulse.android.core.ScanEntry
import app.cardpulse.android.core.ScanItemDto
import app.cardpulse.android.core.Upload
import app.cardpulse.android.ui.screens.RapidActions
import app.cardpulse.android.ui.screens.RapidScreenContent
import app.cardpulse.android.ui.theme.CardPulseTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

/**
 * Draws the camera with the daily scan limit in each state (scans left, unlimited, used up, and a photo the limit turned away),
 * with what the server's own routes answered (`scan_limit_*.json`), and saves a picture of each, so layout and colour problems
 * can be seen without a phone. The camera is drawn as it is on a phone: with the card outline over it, and with the status bar
 * and the gesture bar that a phone has (the pictures once left both out, and the instructions ran into the outline on a real
 * phone without any of them showing it). Only runs when asked for (`-Pscreenshots`): see app/build.gradle.kts and the CI workflow.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h867dp-xxhdpi")
class ScanLimitScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val twentyThree: ScanAllowanceDto = Fixtures.decode("scan_limit_me")
    private val unlimited: ScanAllowanceDto = Fixtures.decode("scan_limit_me_unlimited")
    private val reached: ScanAllowanceDto = Fixtures.decode("scan_limit_reached")

    /** 18:48 in Berlin, so "Scans reset at midnight (in 5 h 12 min)". */
    private val evening = Instant.parse("2026-10-09T16:48:00Z")

    private var zone: TimeZone? = null
    private var locale: Locale? = null

    @Before
    fun fixTheClock() {
        zone = TimeZone.getDefault()
        locale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreTheClock() {
        zone?.let { TimeZone.setDefault(it) }
        locale?.let { Locale.setDefault(it) }
    }

    /** [phone] has the size the test's window was given (see its @Config); [fontScale] above 1 is a phone set to a larger text. */
    private fun camera(
        allowance: ScanAllowanceDto?,
        entries: List<ScanEntry> = emptyList(),
        openId: Long? = null,
        dark: Boolean = true,
        phone: Phone = tallPhone,
        fontScale: Float = 1f,
    ) {
        compose.setContent {
            CardPulseTheme(darkTheme = dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    RapidScreenContent(
                        entries = entries, openId = openId, message = null, serverUrl = "https://cards.example.com/",
                        currency = "USD", rateFromEur = 1.1, ownership = { Ownership.Unknown },
                        capturing = false, canShoot = true,
                        preview = {
                            Box(Modifier.fillMaxSize().background(Color(0xFF2B3A33)), contentAlignment = Alignment.Center) {
                                Text("(live camera)", color = Color(0x88FFFFFF))
                                CardGuide(Modifier.fillMaxSize())
                            }
                        },
                        actions = RapidActions(), allowance = allowance, now = evening,
                        statusBar = WindowInsets(top = phone.statusBar),
                        navigationBar = WindowInsets(bottom = phone.navigationBar),
                    )
                }
            }
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

    private val turnedAway = "Daily scan limit reached. Scans reset at midnight (Europe/Berlin)."

    // --- the phone it was reported on (1080 x 2340 px, a tall status bar) -----------------------------------------------------------

    @Test
    fun scansLeft() {
        camera(twentyThree)
        capture("1l-scan-usage")
    }

    @Test
    fun scansLeftInTheLightTheme() {
        camera(twentyThree, dark = false)
        capture("1l-scan-usage-light")
    }

    @Test
    fun unlimited() {
        camera(unlimited)
        capture("1l-scan-unlimited")
    }

    @Test
    fun aServerWithoutTheLimits() {
        camera(allowance = null)
        capture("1l-scan-no-limits")
    }

    @Test
    fun theLimitReached() {
        camera(reached)
        capture("1l-scan-reached")
    }

    @Test
    fun theLimitReachedInTheLightTheme() {
        camera(reached, dark = false)
        capture("1l-scan-reached-light")
    }

    @Test
    fun theLimitReachedWithPhotosTurnedAway() {
        val entries = listOf(
            ScanEntry(id = 3, photo = null, upload = Upload.FAILED, uploadError = turnedAway),
            ScanEntry(id = 2, photo = null, upload = Upload.SENT, jobId = 2, item = ScanItemDto(id = 2, status = "failed", error = turnedAway)),
            ScanEntry(id = 1, photo = null, upload = Upload.SENT, jobId = 1, item = ScanItemDto(id = 1, status = "processing")),
        )
        camera(reached, entries = entries)
        capture("1l-scan-reached-tray")
    }

    @Test
    fun aPhotoTheLimitTurnedAway() {
        val entry = ScanEntry(id = 2, photo = null, upload = Upload.SENT, jobId = 2, item = ScanItemDto(id = 2, status = "failed", error = turnedAway))
        camera(reached, entries = listOf(entry), openId = 2)
        capture("1l-scan-reached-panel")
    }

    @Test
    fun scansLeftWithAPhotoOnItsWay() {
        val entry = ScanEntry(id = 1, photo = null, upload = Upload.SENT, jobId = 1, item = ScanItemDto(id = 1, status = "processing"))
        camera(twentyThree.copy(used = 24, remaining = 76), entries = listOf(entry))
        capture("1l-scan-usage-tray")
    }

    // --- a plainer phone, and a phone set to larger text ---------------------------------------------------------------------------

    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun scansLeftOnAPlainerPhone() {
        camera(twentyThree, phone = plainPhone)
        capture("1l-scan-usage-plain-phone")
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun theLimitReachedOnAPlainerPhone() {
        camera(reached, phone = plainPhone)
        capture("1l-scan-reached-plain-phone")
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun scansLeftWithLargeText() {
        camera(twentyThree, phone = plainPhone, fontScale = 1.3f)
        capture("1l-scan-usage-large-text")
    }
}
