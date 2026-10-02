package io.github.cyberbandit1998.pokemonscanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.cyberbandit1998.pokemonscanner.ui.PokeCollectorApp
import io.github.cyberbandit1998.pokemonscanner.ui.theme.PokeCollectorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PokeCollectorTheme {
                PokeCollectorApp()
            }
        }
    }
}
