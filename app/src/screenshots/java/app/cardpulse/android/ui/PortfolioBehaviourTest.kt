package app.cardpulse.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.cardpulse.android.core.ChartPoint
import app.cardpulse.android.core.DashboardDto
import app.cardpulse.android.core.DisplayPrefs
import app.cardpulse.android.core.Fixtures
import app.cardpulse.android.core.MoneyFormatter
import app.cardpulse.android.core.PortfolioRange
import app.cardpulse.android.ui.screens.PortfolioScreen
import app.cardpulse.android.ui.theme.CardPulseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * Checks what the Portfolio screen says about how the value moved over the chosen range: the amount always, and the
 * percentage only when it means something. Like the screen pictures it only runs when asked for (`-Pscreenshots`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h1300dp-xxhdpi")
class PortfolioBehaviourTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val money = MoneyFormatter("USD", 1.1)

    private fun history(start: Double, end: Double) = listOf(
        ChartPoint(Instant.parse("2026-09-06T00:00:00Z"), start),
        ChartPoint(Instant.parse("2026-10-06T00:00:00Z"), end),
    )

    private fun show(start: Double, end: Double) {
        val state = AppState(
            booting = false,
            serverUrl = "https://cards.example.com/",
            signedIn = true,
            prefs = DisplayPrefs(currency = "USD", rateFromEur = 1.1),
            dashboard = Fixtures.decode<DashboardDto>("dashboard"),
            history = history(start, end),
            historyRange = PortfolioRange.MONTH,
        )
        compose.setContent { CardPulseTheme { PortfolioScreen(state = state, onShowHistory = { _, _ -> }) } }
    }

    @Test
    fun aStartThatWasTinyShowsTheAmountAndNoPercentage() {
        // 17 cents to 115 euros: +68246.7% would be true and useless.
        show(start = 0.1685, end = 115.1885)
        compose.onNodeWithText("${money.signed(115.02)} over 1M").assertExists()
        compose.onNodeWithText("%", substring = true).assertDoesNotExist()
    }

    @Test
    fun aStartOfZeroShowsTheAmountAndNoPercentage() {
        show(start = 0.0, end = 40.0)
        compose.onNodeWithText("${money.signed(40.0)} over 1M").assertExists()
        compose.onNodeWithText("%", substring = true).assertDoesNotExist()
    }

    @Test
    fun aRealStartShowsBoth() {
        show(start = 100.0, end = 110.0)
        compose.onNodeWithText("${money.signed(10.0)} (${money.percent(10.0)}) over 1M").assertExists()
    }

    @Test
    fun aLossShowsBoth() {
        show(start = 100.0, end = 75.0)
        compose.onNodeWithText("${money.signed(-25.0)} (${money.percent(-25.0)}) over 1M").assertExists()
    }
}
