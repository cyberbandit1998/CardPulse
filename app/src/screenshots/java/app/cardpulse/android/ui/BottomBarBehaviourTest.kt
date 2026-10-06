package app.cardpulse.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.cardpulse.android.ui.theme.CardPulseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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

    private fun show(onSelect: (MainTab) -> Unit = {}, onScanNow: () -> Unit = {}) {
        compose.setContent {
            CardPulseTheme {
                Box(Modifier.fillMaxSize()) {
                    CardPulseBottomBar(MainTab.HOME, onSelect, onScanNow, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }

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
        listOf("Home", "Scan", "Collection", "Portfolio").forEach { label -> compose.onNodeWithText(label).performClick() }
        assertEquals(MainTab.entries.toList(), picked)
        assertEquals(0, opened) // the Scan tab and the round button are two different things
    }
}
