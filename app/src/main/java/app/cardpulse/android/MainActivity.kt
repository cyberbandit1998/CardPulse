package app.cardpulse.android

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.cardpulse.android.ui.AppViewModel
import app.cardpulse.android.ui.CardPulseApp
import app.cardpulse.android.ui.theme.CardPulseTheme

class MainActivity : ComponentActivity() {
    private val appVm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Both bars are see-through. The icons on them start light, for the dark theme the app opens in, and
        // CardPulseApp switches them with the theme the user picked (the phone's own setting no longer decides).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            val state by appVm.state.collectAsState()
            val dark = state.themeMode.isDark(phoneIsDark = isSystemInDarkTheme())
            CardPulseTheme(darkTheme = dark) {
                CardPulseApp(appVm = appVm)
            }
        }
    }
}
