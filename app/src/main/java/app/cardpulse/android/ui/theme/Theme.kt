package app.cardpulse.android.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE3000B),
    secondary = Color(0xFFFF6A70),
    background = Color(0xFF101014),
    surface = Color(0xFF18181F),
    surfaceVariant = Color(0xFF22222B),
    outlineVariant = Color(0xFF2E2E38),
    onPrimary = Color.White,
    onBackground = Color(0xFFF5F5F7),
    onSurface = Color(0xFFF5F5F7),
)

/**
 * The same brand red on white-ish paper. The lighter coral the dark theme uses for small accent text would be hard to
 * read here, so [androidx.compose.material3.ColorScheme.secondary] is a deeper red in this one.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFFE3000B),
    onPrimary = Color.White,
    secondary = Color(0xFFC8101B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE3E1),
    onSecondaryContainer = Color(0xFF5C0007),
    background = Color(0xFFF6F6F9),
    onBackground = Color(0xFF16161B),
    surface = Color.White,
    onSurface = Color(0xFF16161B),
    surfaceVariant = Color(0xFFEBEBF0),
    onSurfaceVariant = Color(0xFF5B5B66),
    outline = Color(0xFF8D8D99),
    outlineVariant = Color(0xFFDADAE2),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F2F6),
    surfaceContainer = Color(0xFFEDEDF2),
    surfaceContainerHigh = Color(0xFFE8E8EE),
    surfaceContainerHighest = Color(0xFFE2E2E9),
)

/** Colours the Material scheme has no slot for: the Home summary card, "good" green, and the like. */
@Immutable
class CardPulseExtras(
    /** The two ends of the gradient behind the collection value, and its outline. */
    val heroTop: Color,
    val heroBottom: Color,
    val heroBorder: Color,
    /** The warning row inside the summary card sits on a tint of this. */
    val heroRow: Color,
    /** Gains, and "connected". */
    val positive: Color,
    /** Losses, and "offline". The Material error colour is a pale pink in the dark theme, too faint for this. */
    val negative: Color,
)

private val DarkExtras = CardPulseExtras(
    heroTop = Color(0xFF3B1519),
    heroBottom = Color(0xFF1C1215),
    heroBorder = Color(0xFF5B2A2E),
    heroRow = Color(0x40000000),
    positive = Color(0xFF3DDC84),
    negative = Color(0xFFFF6B6B),
)

private val LightExtras = CardPulseExtras(
    heroTop = Color(0xFFFFE2DF),
    heroBottom = Color(0xFFFFF3F1),
    heroBorder = Color(0xFFF2BDB8),
    heroRow = Color(0x1AE3000B),
    positive = Color(0xFF1B8A3E),
    negative = Color(0xFFC62828),
)

private val LocalExtras = staticCompositionLocalOf { DarkExtras }

/** Whether the colours in use are the dark ones. */
val LocalDarkTheme = staticCompositionLocalOf { true }

val MaterialTheme.extras: CardPulseExtras
    @Composable
    @ReadOnlyComposable
    get() = LocalExtras.current

@Composable
fun CardPulseTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalDarkTheme provides darkTheme,
        LocalExtras provides (if (darkTheme) DarkExtras else LightExtras),
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = MaterialTheme.typography,
            content = {
                // Paint the background and set the text colour for the whole app. Screens that aren't inside a
                // Scaffold (sign-in, the forced password change, the loading spinner) would otherwise show the
                // window's own background with text colours meant for a dark one.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                    content = content,
                )
            },
        )
    }
}

/**
 * Sets the icons on the status bar and the navigation bar: light ones when what is behind them is dark, dark ones when
 * it is light. Without this the clock and battery would vanish on a light screen on a phone that is set to dark.
 */
@Composable
fun SystemBarIcons(lightIcons: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !lightIcons
        controller.isAppearanceLightNavigationBars = !lightIcons
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
