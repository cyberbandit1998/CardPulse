package io.github.cyberbandit1998.pokemonscanner

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.cyberbandit1998.pokemonscanner.ui.PokeCollectorApp
import io.github.cyberbandit1998.pokemonscanner.ui.theme.PokeCollectorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The theme is always dark, so the system bars use light icons whatever the phone's own setting is.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            PokeCollectorTheme {
                PokeCollectorApp()
            }
        }
    }
}
