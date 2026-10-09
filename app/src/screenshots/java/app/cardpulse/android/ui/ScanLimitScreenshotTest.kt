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
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
 * can be seen without a phone. Only runs when asked for (`-Pscreenshots`): see app/build.gradle.kts and the CI workflow.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
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

    private fun camera(
        allowance: ScanAllowanceDto?,
        entries: List<ScanEntry> = emptyList(),
        openId: Long? = null,
        dark: Boolean = true,
    ) {
        compose.setContent {
            CardPulseTheme(darkTheme = dark) {
                RapidScreenContent(
                    entries = entries, openId = openId, message = null, serverUrl = "https://cards.example.com/",
                    currency = "USD", rateFromEur = 1.1, ownership = { Ownership.Unknown },
                    capturing = false, canShoot = true,
                    preview = {
                        Box(Modifier.fillMaxSize().background(Color(0xFF2B3A33)), contentAlignment = Alignment.Center) {
                            Text("(live camera)", color = Color(0x88FFFFFF))
                        }
                    },
                    actions = RapidActions(), allowance = allowance, now = evening,
                )
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
        val message = "Daily scan limit reached. Scans reset at midnight (Europe/Berlin)."
        val entries = listOf(
            ScanEntry(id = 3, photo = null, upload = Upload.FAILED, uploadError = message),
            ScanEntry(id = 2, photo = null, upload = Upload.SENT, jobId = 2, item = ScanItemDto(id = 2, status = "failed", error = message)),
            ScanEntry(id = 1, photo = null, upload = Upload.SENT, jobId = 1, item = ScanItemDto(id = 1, status = "processing")),
        )
        camera(reached, entries = entries)
        capture("1l-scan-reached-tray")
    }

    @Test
    fun aPhotoTheLimitTurnedAway() {
        val message = "Daily scan limit reached. Scans reset at midnight (Europe/Berlin)."
        val entry = ScanEntry(id = 2, photo = null, upload = Upload.SENT, jobId = 2, item = ScanItemDto(id = 2, status = "failed", error = message))
        camera(reached, entries = listOf(entry), openId = 2)
        capture("1l-scan-reached-panel")
    }
}
