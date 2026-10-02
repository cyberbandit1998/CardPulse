package io.github.cyberbandit1998.pokemonscanner.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.cyberbandit1998.pokemonscanner.core.MoneyFormatter
import io.github.cyberbandit1998.pokemonscanner.core.costCoverage
import io.github.cyberbandit1998.pokemonscanner.ui.AppState
import io.github.cyberbandit1998.pokemonscanner.ui.Banner
import io.github.cyberbandit1998.pokemonscanner.ui.CARD_ASPECT
import io.github.cyberbandit1998.pokemonscanner.ui.CardArt
import io.github.cyberbandit1998.pokemonscanner.ui.gainColor

@Composable
fun HomeScreen(
    state: AppState,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val money = remember(state.prefs.currency, state.prefs.rateFromEur) {
        MoneyFormatter(state.prefs.currency, state.prefs.rateFromEur)
    }
    val dashboard = state.dashboard
    val byId = remember(state.collection) { state.collection.associateBy { it.id } }
    val coverage = remember(state.collection) { state.collection.costCoverage() }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("CardPulse", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        state.user?.username?.let { "Signed in as $it" } ?: "Connected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
            }
            if (state.dashboardLoading || state.collectionLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }

        if (state.noLogin) {
            item {
                Banner(
                    "This server has no sign-in (single-user mode): anyone who can reach it is the administrator. " +
                        "Turn on Multi-User Mode in PokéCollector before exposing it to the internet.",
                    isError = true,
                )
            }
        }

        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Total value", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        dashboard?.let { money.format(it.totalValue) } ?: "—",
                        style = MaterialTheme.typography.displaySmall,
                    )
                    if (dashboard != null) {
                        Text(
                            "${dashboard.totalCards} cards · ${dashboard.uniqueCards} different · " +
                                "${dashboard.ownedSets} of ${dashboard.totalSets} sets",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (dashboard != null) {
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Gain or loss against what you paid", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            money.signed(dashboard.unrealizedPnl),
                            style = MaterialTheme.typography.headlineMedium,
                            color = gainColor(dashboard.unrealizedPnl),
                        )
                        Text(
                            "Cost basis ${money.format(dashboard.totalCost)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.collectionLoaded && !coverage.isComplete) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Only ${coverage.withCost} of ${coverage.total} collection entries have a purchase price, " +
                                    "and PokéCollector counts a missing price as zero cost, so this gain looks larger " +
                                    "than it really is. Add purchase prices in PokéCollector for a true figure.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        val recents = dashboard?.recentAdditions.orEmpty().mapNotNull { recent -> byId[recent.collectionItemId] }
        if (recents.isNotEmpty()) {
            item { Text("Recently added", style = MaterialTheme.typography.titleMedium) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(recents, key = { it.id }) { entry ->
                        Column(Modifier.width(96.dp)) {
                            CardArt(entry, state.serverUrl, state.prefs, Modifier.fillMaxWidth().height((96 / CARD_ASPECT).dp))
                            Text(
                                entry.card?.name.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }

        val tops = dashboard?.topCards.orEmpty()
        if (tops.isNotEmpty()) {
            item { Text("Most valuable", style = MaterialTheme.typography.titleMedium) }
            items(tops, key = { it.collectionItemId }) { top ->
                val entry = byId[top.collectionItemId]
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (entry != null) {
                        CardArt(entry, state.serverUrl, state.prefs, Modifier.width(48.dp).height((48 / CARD_ASPECT).dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(top.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "×${top.quantity} · ${top.condition} · ${top.variant}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(money.format(top.totalValue), style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        if (dashboard == null && !state.dashboardLoading) {
            item {
                Text(
                    "Nothing loaded yet. Tap the refresh button at the top.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
