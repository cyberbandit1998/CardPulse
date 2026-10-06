package app.cardpulse.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.cardpulse.android.ui.theme.CardPulseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Presses the bottom bar the way a finger would. The round camera button rises above the bar, outside the space the bar
 * takes up, so this is what shows that the part of it that sticks out can be pressed too. Like the screen pictures it only
 * runs when asked for (`-Pscreenshots`): see app/build.gradle.kts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class BottomBarBehaviourTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val roundButton = "Scan cards with the camera"

    private fun show(
        selected: MainTab = MainTab.HOME,
        waiting: Int = 0,
        onSelect: (MainTab) -> Unit = {},
        onScanNow: () -> Unit = {},
    ) {
        compose.setContent {
            CardPulseTheme {
                Box(Modifier.fillMaxSize()) {
                    CardPulseBottomBar(selected, onSelect, onScanNow, Modifier.align(Alignment.BottomCenter), waiting = waiting)
                }
            }
        }
    }

    private fun SemanticsNodeInteraction.centreX(): Float = fetchSemanticsNode().boundsInRoot.center.x

    @Test
    fun theRoundButtonOpensTheCamera() {
        var opened = 0
        show(onScanNow = { opened++ })
        compose.onNodeWithContentDescription(roundButton).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun theTopOfTheRoundButtonWorksToo() {
        // A few pixels in from the very top of the circle: that part is above the bar, outside the bar's own space.
        var opened = 0
        show(onScanNow = { opened++ })
        compose.onNodeWithContentDescription(roundButton).performTouchInput { click(Offset(centerX, 6f)) }
        assertEquals(1, opened)
    }

    @Test
    fun theRoundButtonIsNotATab() {
        val picked = mutableListOf<MainTab>()
        show(onSelect = { picked += it })
        compose.onNodeWithContentDescription(roundButton).performClick()
        assertEquals(emptyList<MainTab>(), picked)
    }

    @Test
    fun everyTabSaysWhichOneWasPicked() {
        val picked = mutableListOf<MainTab>()
        var opened = 0
        show(onSelect = { picked += it }, onScanNow = { opened++ })
        listOf("Home", "Sets", "Collection", "Portfolio").forEach { label -> compose.onNodeWithText(label).performClick() }
        assertEquals(listOf(MainTab.HOME, MainTab.SETS, MainTab.COLLECTION, MainTab.PORTFOLIO), picked)
        assertEquals(MainTab.entries.toList(), picked)
        assertEquals(0, opened) // the tabs and the round button are different things
    }

    @Test
    fun theTabsSitAroundTheRoundButtonInTheAgreedOrder() {
        show()
        val places = listOf(
            compose.onNodeWithText("Home").centreX(),
            compose.onNodeWithText("Sets").centreX(),
            compose.onNodeWithContentDescription(roundButton).centreX(),
            compose.onNodeWithText("Collection").centreX(),
            compose.onNodeWithText("Portfolio").centreX(),
        )
        // Home | Sets | [camera] | Collection | Portfolio, left to right ...
        assertTrue("Not left to right: $places", places.zipWithNext().all { (left, right) -> left < right })
        // ... with the round button in the middle of the bar.
        val middle = compose.onRoot().fetchSemanticsNode().boundsInRoot.center.x
        assertTrue("The round button is at ${places[2]}, the middle is $middle", abs(places[2] - middle) < 2f)
    }

    @Test
    fun theRoundButtonIsTheOnlyWayToScan() {
        show()
        compose.onNodeWithText("Scan").assertDoesNotExist() // no tab of that name
        assertEquals(1, compose.onAllNodesWithContentDescription("Scan", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun theOpenTabIsMarkedAndTheOthersAreNot() {
        show(selected = MainTab.SETS)
        compose.onNodeWithText("Sets").assertIsSelected()
        listOf("Home", "Collection", "Portfolio").forEach { label -> compose.onNodeWithText(label).assertIsNotSelected() }
    }

    @Test
    fun theRoundButtonSaysHowManyScansAreWaiting() {
        show(waiting = 3)
        compose.onNodeWithContentDescription("Scan cards with the camera, 3 waiting to be checked").assertExists()
    }

    @Test
    fun theCountOnTheRoundButtonStopsAtNineAndOpensTheCameraToo() {
        var opened = 0
        show(waiting = 12, onScanNow = { opened++ })
        compose.onNodeWithText("9+", useUnmergedTree = true).performClick()
        assertEquals(1, opened)
    }
}
