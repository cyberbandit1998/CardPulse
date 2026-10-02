package io.github.cyberbandit1998.pokemonscanner.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.cyberbandit1998.pokemonscanner.ui.screens.*

enum class HomeTab { Home, Scan, Collection, Portfolio }

@Composable
fun PokeCollectorApp(vm: AppViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    if (state.booting) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (!state.loggedIn) {
        LoginScreen(
            initialServer = state.serverUrl,
            loading = state.loading,
            error = state.error,
            onLogin = vm::login
        )
        return
    }

    var tab by remember { mutableStateOf(HomeTab.Home) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == HomeTab.Home,
                    onClick = { tab = HomeTab.Home },
                    icon = { Icon(Icons.Default.Home, null) },
                    label = { Text("Home") }
                )
                NavigationBarItem(
                    selected = tab == HomeTab.Scan,
                    onClick = { tab = HomeTab.Scan },
                    icon = { Icon(Icons.Default.CenterFocusStrong, null) },
                    label = { Text("Scan") }
                )
                NavigationBarItem(
                    selected = tab == HomeTab.Collection,
                    onClick = { tab = HomeTab.Collection },
                    icon = { Icon(Icons.Default.GridView, null) },
                    label = { Text("Collection") }
                )
                NavigationBarItem(
                    selected = tab == HomeTab.Portfolio,
                    onClick = { tab = HomeTab.Portfolio },
                    icon = { Icon(Icons.Default.ShowChart, null) },
                    label = { Text("Portfolio") }
                )
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (tab) {
                HomeTab.Home -> HomeScreen(state, vm::refreshAll, vm::logout)
                HomeTab.Scan -> ScanScreen(state, vm::scan, vm::addMatch, vm::clearScan)
                HomeTab.Collection -> CollectionScreen(state, vm::refreshAll)
                HomeTab.Portfolio -> PortfolioScreen(state, vm::refreshAll)
            }

            if (state.loading) {
                LinearProgressIndicator(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                )
            }
        }
    }
}
