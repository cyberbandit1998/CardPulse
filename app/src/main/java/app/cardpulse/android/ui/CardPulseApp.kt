package app.cardpulse.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.cardpulse.android.ui.screens.CollectionScreen
import app.cardpulse.android.ui.screens.HomeList
import app.cardpulse.android.ui.screens.HomeListScreen
import app.cardpulse.android.ui.screens.HomeScreen
import app.cardpulse.android.ui.screens.LoginScreen
import app.cardpulse.android.ui.screens.ManualAddScreen
import app.cardpulse.android.ui.screens.PasswordScreen
import app.cardpulse.android.ui.screens.PortfolioScreen
import app.cardpulse.android.ui.screens.ScanScreen
import app.cardpulse.android.ui.screens.SettingsScreen
import app.cardpulse.android.ui.theme.LocalDarkTheme
import app.cardpulse.android.ui.theme.SystemBarIcons

@Composable
fun CardPulseApp(
    appVm: AppViewModel = viewModel(),
    scanVm: ScanViewModel = viewModel(),
) {
    val app by appVm.state.collectAsState()
    val scan by scanVm.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(MainTab.HOME.ordinal) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showManualAdd by rememberSaveable { mutableStateOf(false) }
    /** Which of Home's full lists is open (an index into [HomeList.entries]), or -1 when none is. */
    var homeList by rememberSaveable { mutableIntStateOf(-1) }
    /** What the Collection tab's search box starts with: a set's name, when the user came from that set's progress. */
    var collectionSearch by rememberSaveable { mutableStateOf("") }
    // Signing out (or the session ending) must not leave this screen waiting behind the next sign-in.
    LaunchedEffect(app.signedIn) {
        if (!app.signedIn) {
            showManualAdd = false
            homeList = -1
        }
    }
    val openManualAdd = { showManualAdd = true }
    val openCollection = { search: String ->
        collectionSearch = search
        tab = MainTab.COLLECTION.ordinal
    }

    // The open camera uses the whole screen on black: no tab bar, and it shows its own messages.
    val cameraOpen = app.signedIn && !app.mustChangePassword && !showSettings && !showManualAdd && homeList < 0 &&
        tab == MainTab.SCAN.ordinal && scan.stage == ScanStage.RAPID
    // Light icons on the status and navigation bars wherever the screen behind them is dark.
    SystemBarIcons(lightIcons = LocalDarkTheme.current || cameraOpen)

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
                    onThemeMode = appVm::setThemeMode,
                )
            }
        }

        showManualAdd -> {
            Scaffold { padding ->
                ManualAddScreen(
                    app = app,
                    vm = scanVm,
                    onClose = { showManualAdd = false },
                    // The padding already leaves room for the system bars; the keyboard's padding must not count them twice.
                    modifier = Modifier.padding(padding).consumeWindowInsets(padding),
                )
            }
        }

        homeList >= 0 -> {
            BackHandler { homeList = -1 }
            Scaffold { padding ->
                HomeListScreen(
                    kind = HomeList.entries[homeList.coerceIn(0, HomeList.entries.lastIndex)],
                    state = app,
                    onBack = { homeList = -1 },
                    onOpenSet = { name ->
                        homeList = -1
                        openCollection(name)
                    },
                    onRemove = appVm::removeFromCollection,
                    modifier = Modifier.padding(padding),
                )
            }
        }

        else -> {
            val inCameraOrReview = cameraOpen
            Scaffold(
                bottomBar = {
                    // The camera and the review screen use the whole screen.
                    if (!cameraOpen) {
                        CardPulseBottomBar(
                            selected = MainTab.entries[tab],
                            onSelect = { chosen ->
                                collectionSearch = ""
                                tab = chosen.ordinal
                            },
                            // The round button goes straight to the camera, from any tab.
                            onScanNow = {
                                collectionSearch = ""
                                tab = MainTab.SCAN.ordinal
                                scanVm.startRapid()
                            },
                        )
                    }
                },
            ) { padding ->
                Column(Modifier.fillMaxSize().then(if (cameraOpen) Modifier else Modifier.padding(padding))) {
                    // Errors from background loads show above whichever tab is open (the scanner shows its own).
                    if (!inCameraOrReview) {
                        app.message?.let { Banner(it, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), isError = true, onDismiss = appVm::dismissMessage) }
                    }
                    if (!inCameraOrReview && app.lookingUpPrices) PricesNote()
                    val contentModifier = Modifier.weight(1f)
                    when (MainTab.entries[tab]) {
                        MainTab.HOME -> HomeScreen(
                            state = app,
                            onRefresh = appVm::refreshAll,
                            onOpenSettings = { showSettings = true },
                            modifier = contentModifier,
                            onOpenPortfolio = { tab = MainTab.PORTFOLIO.ordinal },
                            onOpenCollection = { openCollection("") },
                            onSeeAllValuable = { homeList = HomeList.VALUABLE.ordinal },
                            onSeeAllSets = { homeList = HomeList.SETS.ordinal },
                            onOpenSet = { name -> openCollection(name) },
                            onRemove = appVm::removeFromCollection,
                        )
                        MainTab.SCAN -> ScanScreen(app, scanVm, onAddManually = openManualAdd, modifier = contentModifier)
                        MainTab.COLLECTION -> CollectionScreen(
                            app,
                            onRefresh = appVm::refreshCollection,
                            onRemove = appVm::removeFromCollection,
                            onAddCard = openManualAdd,
                            modifier = contentModifier,
                            initialQuery = collectionSearch,
                        )
                        MainTab.PORTFOLIO -> PortfolioScreen(
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
