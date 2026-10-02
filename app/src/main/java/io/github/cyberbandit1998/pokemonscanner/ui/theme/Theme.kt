package io.github.cyberbandit1998.pokemonscanner.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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
fun PokeCollectorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = MaterialTheme.typography,
        content = content
    )
}
