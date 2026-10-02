package io.github.cyberbandit1998.cardpulse.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE3000B),
    secondary = Color(0xFFFF6A70),
    background = Color(0xFF101014),
    surface = Color(0xFF18181F),
    surfaceVariant = Color(0xFF22222B),
    onPrimary = Color.White,
    onBackground = Color(0xFFF5F5F7),
    onSurface = Color(0xFFF5F5F7)
)

@Composable
fun CardPulseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
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
