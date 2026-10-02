package io.github.cyberbandit1998.pokemonscanner.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.cyberbandit1998.pokemonscanner.ui.AppState
import java.text.NumberFormat

@Composable
fun HomeScreen(
    state: AppState,
    onRefresh: () -> Unit,
    onLogout: () -> Unit
) {
    val latest = state.portfolio.lastOrNull()
    val value = latest?.totalValue ?: latest?.collectionValue ?: 0.0
    val cards = state.collection.sumOf { it.quantity }

    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("PokéCollector", style = MaterialTheme.typography.headlineMedium)
                Text(state.serverUrl, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onLogout) { Text("Log out") }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("Portfolio value", style = MaterialTheme.typography.labelLarge)
                Text(
                    NumberFormat.getCurrencyInstance().format(value),
                    style = MaterialTheme.typography.displaySmall
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ElevatedCard(Modifier.weight(1f)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Cards")
                    Text("$cards", style = MaterialTheme.typography.headlineMedium)
                }
            }
            ElevatedCard(Modifier.weight(1f)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Unique entries")
                    Text("${state.collection.size}", style = MaterialTheme.typography.headlineMedium)
                }
            }
        }

        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Button(onClick = onRefresh) { Text("Refresh") }
    }
}
