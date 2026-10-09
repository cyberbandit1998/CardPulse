package app.cardpulse.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.cardpulse.android.camera.CardGuide
import app.cardpulse.android.camera.cardGuideBounds
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

/**
 * Uses the camera screen the way a person would when the server limits how many cards a day may be scanned: reads how many are
 * used, sees the word Unlimited instead of numbers for an unlimited user, and at the limit is told so, told when scans start
 * again, and not offered a shutter that would only produce a photo the server turns away. The numbers are what the server's own
 * routes answered (`scan_limit_*.json`). It also checks where things are: the count at the top and the instructions under the
 * camera must stay clear of the card outline, on a tall status bar and with larger text too. Like the pictures it only runs when
 * asked for (`-Pscreenshots`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w420dp-h900dp-xxhdpi")
class ScanLimitBehaviourTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val twentyThree: ScanAllowanceDto = Fixtures.decode("scan_limit_me")
    private val unlimited: ScanAllowanceDto = Fixtures.decode("scan_limit_me_unlimited")
    private val reached: ScanAllowanceDto = Fixtures.decode("scan_limit_reached")

    /** Noon in Berlin on the 9th: twelve hours before the day starts over there. */
    private val noon = Instant.parse("2026-10-09T10:00:00Z")

    /** The start of the instructions under the camera: they are one long text, so a part of it finds it. */
    private val instructions = "Fill the outline"

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

    private val shutter = SemanticsMatcher("is the shutter") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Take photo" }

    /** The camera screen on a [phone], with the card outline drawn over the camera as it is on the phone, and a larger text if asked. */
    private fun camera(
        allowance: ScanAllowanceDto?,
        now: Instant = noon,
        entries: List<ScanEntry> = emptyList(),
        openId: Long? = null,
        actions: RapidActions = RapidActions(),
        phone: Phone = plainPhone,
        fontScale: Float = 1f,
    ) {
        compose.setContent {
            CardPulseTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    // The screen has the phone's size whatever window the test runs in, so the outline is where it is on the phone.
                    Box(Modifier.requiredSize(phone.width, phone.height).testTag("screen")) {
                        RapidScreenContent(
                            entries = entries, openId = openId, message = null, serverUrl = "https://cards.example.com/",
                            currency = "USD", rateFromEur = 1.1, ownership = { Ownership.Unknown },
                            capturing = false, canShoot = true,
                            preview = { Box(Modifier.fillMaxSize().background(Color(0xFF2B3A33))) { CardGuide(Modifier.fillMaxSize()) } },
                            actions = actions, allowance = allowance, now = now,
                            statusBar = WindowInsets(top = phone.statusBar),
                            navigationBar = WindowInsets(bottom = phone.navigationBar),
                        )
                    }
                }
            }
        }
    }

    private fun failedEntry(id: Long, text: String) = ScanEntry(
        id = id, photo = null, upload = Upload.SENT, jobId = id.toInt(),
        item = ScanItemDto(id = id.toInt(), status = "failed", error = text),
    )

    // --- how many are used ------------------------------------------------------------------------------------------------

    @Test
    fun theCameraSaysHowManyScansAreUsedToday() {
        camera(twentyThree)

        compose.onNodeWithText("23 of 100 scans used today").assertExists()
        compose.onNodeWithText("Daily scan limit reached").assertDoesNotExist()
        compose.onNode(shutter).assertIsEnabled()
    }

    @Test
    fun anUnlimitedUserSeesTheWordUnlimitedInsteadOfNumbers() {
        camera(unlimited)

        compose.onNodeWithText("Unlimited scans").assertExists()
        compose.onNodeWithText("12 of", substring = true).assertDoesNotExist()
        compose.onNode(shutter).assertIsEnabled()
    }

    @Test
    fun aServerWithoutTheDailyScanLimitsShowsNothingAboutThem() {
        camera(allowance = null)

        compose.onNodeWithText("scans used today", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Unlimited scans").assertDoesNotExist()
        compose.onNodeWithText("Daily scan limit reached").assertDoesNotExist()
        compose.onNode(shutter).assertIsEnabled()
    }

    @Test
    fun theLastScanLeftIsStillAScan() {
        camera(twentyThree.copy(used = 99, remaining = 1))

        compose.onNodeWithText("99 of 100 scans used today").assertExists()
        compose.onNodeWithText("Daily scan limit reached").assertDoesNotExist()
        compose.onNode(shutter).assertIsEnabled()
    }

    // --- the limit ----------------------------------------------------------------------------------------------------------

    @Test
    fun atTheLimitTheCameraSaysSoAndWhenScansStartAgain() {
        camera(reached)

        compose.onNodeWithText("Daily scan limit reached").assertExists()
        compose.onNodeWithText("Scans reset at midnight (in 12 h)").assertExists()
    }

    @Test
    fun atTheLimitTheNoticeTakesThePlaceOfTheCountSoTheTopStaysShort() {
        camera(reached)

        compose.onNodeWithText("100 of 100 scans used today").assertDoesNotExist()
        compose.onNodeWithText("Daily scan limit reached").assertExists()
    }

    @Test
    fun theTimeLeftFollowsTheClock() {
        camera(reached, now = Instant.parse("2026-10-09T16:48:00Z")) // 18:48 in Berlin

        compose.onNodeWithText("Scans reset at midnight (in 5 h 12 min)").assertExists()
    }

    @Test
    fun atTheLimitThereIsNoShutterToPressForAPhotoTheServerWouldTurnAway() {
        var shots = 0
        camera(reached, actions = RapidActions(shutter = { shots++ }))

        compose.onNode(shutter).assertIsNotEnabled()
        compose.onNode(shutter).performClick()
        assertEquals(0, shots)
    }

    @Test
    fun theShutterWorksBelowTheLimit() {
        var shots = 0
        camera(twentyThree, actions = RapidActions(shutter = { shots++ }))

        compose.onNode(shutter).performClick()

        assertEquals(1, shots)
    }

    @Test
    fun onceTheDayHasStartedOverTheOldNoticeNoLongerHoldsTheShutter() {
        camera(reached, now = Instant.parse("2026-10-09T22:00:01Z")) // a second past midnight in Berlin

        compose.onNodeWithText("Daily scan limit reached").assertDoesNotExist()
        compose.onNode(shutter).assertIsEnabled()
    }

    @Test
    fun aLimitOfZeroIsReachedFromTheStart() {
        camera(ScanAllowanceDto(dailyLimit = 0, used = 0, remaining = 0, resetsAt = "2026-10-10T00:00:00+02:00"))

        compose.onNodeWithText("Daily scan limit reached").assertExists()
        compose.onNodeWithText("0 of 0 scans used today").assertDoesNotExist()
        compose.onNode(shutter).assertIsNotEnabled()
    }

    @Test
    fun anUnlimitedUserIsNeverHeldBackWhateverTheyHaveUsed() {
        camera(unlimited.copy(used = 5_000))

        compose.onNodeWithText("Daily scan limit reached").assertDoesNotExist()
        compose.onNode(shutter).assertIsEnabled()
    }

    // --- a photo the limit turned away --------------------------------------------------------------------------------------------

    @Test
    fun aPhotoTheQueueRefusedForTheLimitIsNotCalledAnUnreadableCard() {
        val message = "Daily scan limit reached. Scans reset at midnight (Europe/Berlin)."
        camera(allowance = null, entries = listOf(failedEntry(5, message)), openId = 5)

        compose.onNodeWithText("Daily scan limit reached").assertExists()
        compose.onNodeWithText(message).assertExists()
        compose.onNodeWithText("The scanner couldn't read this card").assertDoesNotExist()
        compose.onNodeWithText("Try again").assertExists()
    }

    @Test
    fun aPhotoTheScannerReallyCouldNotReadStillSaysSo() {
        camera(allowance = null, entries = listOf(failedEntry(5, "The scanner gave up on this photo.")), openId = 5)

        compose.onNodeWithText("The scanner couldn't read this card").assertExists()
        compose.onNodeWithText("Daily scan limit reached").assertDoesNotExist()
    }

    @Test
    fun aPhotoThatWasNeverSentBecauseOfTheLimitSaysSoAsWell() {
        val message = "Daily scan limit reached. Scans reset at midnight (Europe/Berlin)."
        val entry = ScanEntry(id = 3, photo = null, upload = Upload.FAILED, uploadError = message)
        camera(allowance = null, entries = listOf(entry), openId = 3)

        compose.onNodeWithText("Daily scan limit reached").assertExists()
        compose.onNodeWithText(message).assertExists()
        compose.onNodeWithText("This photo hasn't reached your server").assertDoesNotExist()
        compose.onNodeWithText("Send again").assertExists()
    }

    // --- where things are: nothing sits over the card outline --------------------------------------------------------------------

    /** The card outline on the screen, in the screen's own pixels: from the same function that draws it. */
    private fun outline(): Rect {
        val screen = compose.onNodeWithTag("screen").fetchSemanticsNode().boundsInRoot
        return cardGuideBounds(screen.width, screen.height).translate(screen.topLeft)
    }

    private fun boundsOf(text: String, substring: Boolean = false): Rect =
        compose.onNodeWithText(text, substring = substring).fetchSemanticsNode().boundsInRoot

    private fun assertAboveTheOutline(outline: Rect, vararg texts: String) {
        for (text in texts) {
            val bounds = boundsOf(text)
            assertTrue("\"$text\" ends at ${bounds.bottom}, and the card outline starts at ${outline.top}", bounds.bottom <= outline.top)
        }
    }

    private fun assertBelowTheOutline(outline: Rect, text: String) {
        val bounds = boundsOf(text, substring = true)
        assertTrue("\"$text\" starts at ${bounds.top}, and the card outline ends at ${outline.bottom}", bounds.top >= outline.bottom)
    }

    @Test
    fun onThePhoneItWasReportedOnTheCountIsAboveTheOutlineAndTheInstructionsAreBelowIt() {
        camera(twentyThree, phone = tallPhone)

        assertAboveTheOutline(outline(), "Rapid scan", "23 of 100 scans used today")
        assertBelowTheOutline(outline(), instructions)
    }

    @Test
    fun onAPlainerPhoneToo() {
        camera(twentyThree, phone = plainPhone)

        assertAboveTheOutline(outline(), "Rapid scan", "23 of 100 scans used today")
        assertBelowTheOutline(outline(), instructions)
    }

    @Test
    fun withLargerText() {
        camera(twentyThree, phone = plainPhone, fontScale = 1.3f)

        assertAboveTheOutline(outline(), "Rapid scan", "23 of 100 scans used today")
        assertBelowTheOutline(outline(), instructions)
    }

    @Test
    fun forAnUnlimitedUser() {
        camera(unlimited, phone = tallPhone)

        assertAboveTheOutline(outline(), "Rapid scan", "Unlimited scans")
        assertBelowTheOutline(outline(), instructions)
    }

    @Test
    fun forAServerThatSaysNothingAboutLimits() {
        camera(allowance = null, phone = tallPhone)

        assertAboveTheOutline(outline(), "Rapid scan")
        assertBelowTheOutline(outline(), instructions)
    }

    @Test
    fun atTheLimitTheNoticeIsAboveTheOutlineAndThereAreNoInstructionsForAShutterThatWaits() {
        camera(reached, phone = tallPhone)

        assertAboveTheOutline(outline(), "Rapid scan", "Daily scan limit reached", "Scans reset at midnight (in 12 h)")
        compose.onNodeWithText(instructions, substring = true).assertDoesNotExist()
    }

    @Test
    fun atTheLimitOnAPlainerPhoneToo() {
        camera(reached, phone = plainPhone)

        assertAboveTheOutline(outline(), "Rapid scan", "Daily scan limit reached", "Scans reset at midnight (in 12 h)")
        compose.onNodeWithText(instructions, substring = true).assertDoesNotExist()
    }

    @Test
    fun theInstructionsGiveWayToTheResultsOfThePhotosTaken() {
        camera(twentyThree, entries = listOf(failedEntry(1, "The scanner gave up on this photo.")), phone = tallPhone)

        compose.onNodeWithText(instructions, substring = true).assertDoesNotExist()
        assertAboveTheOutline(outline(), "Rapid scan", "23 of 100 scans used today")
    }

    @Test
    fun theInstructionsSayWhereTheResultsComeUp() {
        camera(twentyThree, phone = tallPhone)

        compose.onNodeWithText("Results appear here as they are read.", substring = true).assertExists()
    }
}
