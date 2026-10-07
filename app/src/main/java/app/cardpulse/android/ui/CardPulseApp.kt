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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.cardpulse.android.core.toReview
import app.cardpulse.android.ui.screens.CollectionScreen
import app.cardpulse.android.ui.screens.HomeScreen
import app.cardpulse.android.ui.screens.LoginScreen
import app.cardpulse.android.ui.screens.ManualAddScreen
import app.cardpulse.android.ui.screens.MostValuableScreen
import app.cardpulse.android.ui.screens.PasswordScreen
import app.cardpulse.android.ui.screens.PortfolioScreen
import app.cardpulse.android.ui.screens.ScanScreen
import app.cardpulse.android.ui.screens.SetChecklistScreen
import app.cardpulse.android.ui.screens.SettingsScreen
import app.cardpulse.android.ui.screens.SetsScreen
import app.cardpulse.android.ui.theme.LocalDarkTheme
import app.cardpulse.android.ui.theme.SystemBarIcons

@Composable
fun CardPulseApp(
    appVm: AppViewModel = viewModel(),
    scanVm: ScanViewModel = viewModel(),
) {
    val app by appVm.state.collectAsState()
    val scan by scanVm.state.collectAsState()
    val session by scanVm.session.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(MainTab.HOME.ordinal) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showManualAdd by rememberSaveable { mutableStateOf(false) }
    /** The full list that "See all" under Most valuable opens. */
    var showMostValuable by rememberSaveable { mutableStateOf(false) }
    /** What the Collection tab's search box starts with: a set's name, when the user came from a set's checklist. */
    var collectionSearch by rememberSaveable { mutableStateOf("") }
    /** The id of the set whose checklist is open, if one is: it covers the tabs, and Back closes it. */
    var openSet by rememberSaveable { mutableStateOf<String?>(null) }
    // Signing out (or the session ending) must not leave this screen waiting behind the next sign-in.
    LaunchedEffect(app.signedIn) {
        if (!app.signedIn) {
            showManualAdd = false
            showMostValuable = false
            openSet = null
        }
    }
    // Scans the server still holds from before are looked up once the user is in, so the round camera button can say how
    // many are waiting. Quietly: nobody asked, so an unreachable server is not worth a message.
    LaunchedEffect(app.signedIn, app.mustChangePassword) {
        if (app.signedIn && !app.mustChangePassword) scanVm.refreshQuietly()
    }
    val openManualAdd = { showManualAdd = true }
    val openCollection = { search: String ->
        collectionSearch = search
        tab = MainTab.COLLECTION.ordinal
    }

    // The open camera uses the whole screen on black, over whichever tab is showing: no tab bar, and it shows its own
    // messages. Closing it goes back to that tab.
    val cameraOpen = app.signedIn && !app.mustChangePassword && !showSettings && !showManualAdd && !showMostValuable &&
        scan.stage == ScanStage.RAPID
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

        showMostValuable -> {
            BackHandler { showMostValuable = false }
            Scaffold { padding ->
                MostValuableScreen(
                    state = app,
                    onBack = { showMostValuable = false },
                    onRemove = appVm::removeFromCollection,
                    modifier = Modifier.padding(padding),
                )
            }
        }

        else -> Box(Modifier.fillMaxSize()) {
            // The tabs stay under an open checklist, so the Sets tab is as it was (search, order, place in the list) when the
            // checklist is closed. Screen readers are kept off them while the checklist covers them.
            Box(Modifier.fillMaxSize().then(if (openSet != null) Modifier.clearAndSetSemantics { } else Modifier)) {
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
                                // The round button is the way to the camera, from any tab.
                                onScanNow = { scanVm.startRapid() },
                                waiting = session.toReview.size,
                            )
                        }
                    },
                ) { padding ->
                    Column(Modifier.fillMaxSize().then(if (cameraOpen) Modifier else Modifier.padding(padding))) {
                        // Errors from background loads show above whichever tab is open (the camera shows its own).
                        if (!cameraOpen) {
                            app.message?.let { Banner(it, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), isError = true, onDismiss = appVm::dismissMessage) }
                        }
                        if (!cameraOpen && app.lookingUpPrices) PricesNote()
                        val contentModifier = Modifier.weight(1f)
                        if (cameraOpen) {
                            ScanScreen(app, scanVm, onAddManually = openManualAdd, modifier = contentModifier)
                        } else when (MainTab.entries[tab]) {
                            MainTab.HOME -> HomeScreen(
                                state = app,
                                onRefresh = appVm::refreshAll,
                                onOpenSettings = { showSettings = true },
                                modifier = contentModifier,
                                onOpenPortfolio = { tab = MainTab.PORTFOLIO.ordinal },
                                onOpenCollection = { openCollection("") },
                                onSeeAllValuable = { showMostValuable = true },
                                onOpenSets = { tab = MainTab.SETS.ordinal },
                                onOpenSet = { id -> openSet = id },
                                onRemove = appVm::removeFromCollection,
                            )
                            MainTab.SETS -> SetsScreen(
                                state = app,
                                onOpenSet = { id -> openSet = id },
                                modifier = contentModifier,
                                onLoadSets = appVm::loadSets,
                            )
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
            openSet?.let { setId ->
                BackHandler { openSet = null }
                Scaffold { padding ->
                    SetChecklistScreen(
                        setId = setId,
                        state = app,
                        onBack = { openSet = null },
                        onLoad = { force -> appVm.loadChecklist(setId, force) },
                        onRemove = appVm::removeFromCollection,
                        modifier = Modifier.padding(padding),
                        onShowInCollection = { name ->
                            openSet = null
                            openCollection(name)
                        },
                    )
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
