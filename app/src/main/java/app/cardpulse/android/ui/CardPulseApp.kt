package app.cardpulse.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.cardpulse.android.ui.screens.CollectionScreen
import app.cardpulse.android.ui.screens.HomeScreen
import app.cardpulse.android.ui.screens.LoginScreen
import app.cardpulse.android.ui.screens.PasswordScreen
import app.cardpulse.android.ui.screens.PortfolioScreen
import app.cardpulse.android.ui.screens.ScanScreen
import app.cardpulse.android.ui.screens.SettingsScreen

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    SCAN("Scan", Icons.Default.CameraAlt),
    COLLECTION("Collection", Icons.Default.GridView),
    PORTFOLIO("Portfolio", Icons.AutoMirrored.Filled.ShowChart),
}

@Composable
fun CardPulseApp(
    appVm: AppViewModel = viewModel(),
    scanVm: ScanViewModel = viewModel(),
) {
    val app by appVm.state.collectAsState()
    val scan by scanVm.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(Tab.HOME.ordinal) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    when {
        app.booting -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

        !app.signedIn -> LoginScreen(
            state = app,
            onTest = appVm::testConnection,
            onSignIn = appVm::signIn,
            onDismissMessage = appVm::dismissMessage,
        )

        app.mustChangePassword -> PasswordScreen(
            state = app,
            onChange = appVm::changeRequiredPassword,
            onSignOut = { appVm.signOut() },
            onDismissMessage = appVm::dismissMessage,
        )

        showSettings -> {
            BackHandler { showSettings = false }
            Scaffold { padding ->
                SettingsScreen(
                    app = app,
                    scan = scan,
                    onBack = { showSettings = false },
                    onSignOut = { showSettings = false; appVm.signOut() },
                    onSavePhotos = scanVm::setSavePhotos,
                    onLookUpPrices = scanVm::setLookUpPrices,
                    modifier = Modifier.padding(padding),
                )
            }
        }

        else -> {
            // The open camera uses the whole screen: no tab bar, and it shows its own messages.
            val fullScreenCamera = tab == Tab.SCAN.ordinal && scan.stage == ScanStage.RAPID
            val inCameraOrReview = fullScreenCamera
            Scaffold(
                bottomBar = {
                    // The camera and the review screen use the whole screen.
                    if (!fullScreenCamera) {
                        NavigationBar {
                            Tab.entries.forEach { entry ->
                                NavigationBarItem(
                                    selected = tab == entry.ordinal,
                                    onClick = { tab = entry.ordinal },
                                    icon = { Icon(entry.icon, contentDescription = null) },
                                    label = { Text(entry.label) },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                Column(Modifier.fillMaxSize().then(if (fullScreenCamera) Modifier else Modifier.padding(padding))) {
                    // Errors from background loads show above whichever tab is open (the scanner shows its own).
                    if (!inCameraOrReview) {
                        app.message?.let { Banner(it, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), isError = true, onDismiss = appVm::dismissMessage) }
                    }
                    if (!inCameraOrReview && app.lookingUpPrices) PricesNote()
                    val contentModifier = Modifier.weight(1f)
                    when (Tab.entries[tab]) {
                        Tab.HOME -> HomeScreen(
                            state = app,
                            onRefresh = appVm::refreshAll,
                            onOpenSettings = { showSettings = true },
                            modifier = contentModifier,
                        )
                        Tab.SCAN -> ScanScreen(app, scanVm, contentModifier)
                        Tab.COLLECTION -> CollectionScreen(
                            app,
                            onRefresh = appVm::refreshCollection,
                            onRemove = appVm::removeFromCollection,
                            modifier = contentModifier,
                        )
                        Tab.PORTFOLIO -> PortfolioScreen(
                            state = app,
                            onShowHistory = { range, force -> appVm.showHistory(range, force) },
                            modifier = contentModifier,
                        )
                    }
                }
            }
        }
    }
}

/** A thin line above the tabs while the server looks up prices for cards that were just added. */
@Composable
private fun PricesNote() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(
            "Looking up prices for your new cards…",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
