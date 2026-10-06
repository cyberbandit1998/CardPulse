package app.cardpulse.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cardpulse.android.core.CollectionItemDto
import app.cardpulse.android.core.MoneyFormatter
import app.cardpulse.android.core.setProgress
import app.cardpulse.android.ui.AppState

/** The lists that the "See all" links on Home open. */
enum class HomeList(val title: String, val empty: String) {
    VALUABLE("Most valuable", "Nothing to show yet. Add a few cards and refresh."),
    SETS("Set progress", "No sets yet. Cards you add will show up here with how much of their set you own."),
}

/** A whole list from Home, one card to a row, under a back arrow. */
@Composable
fun HomeListScreen(
    kind: HomeList,
    state: AppState,
    onBack: () -> Unit,
    /** A set was tapped: show the cards of it. Gets the set's name. */
    onOpenSet: (String) -> Unit,
    onRemove: (item: CollectionItemDto, wholeRow: Boolean, done: (String?) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val money = remember(state.prefs.currency, state.prefs.rateFromEur) {
        MoneyFormatter(state.prefs.currency, state.prefs.rateFromEur)
    }
    val byId = remember(state.collection) { state.collection.associateBy { it.id } }
    val sets = remember(state.collection) { state.collection.setProgress() }
    val tops = state.dashboard?.topCards.orEmpty()
    var openItem by remember { mutableStateOf<CollectionItemDto?>(null) }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text(kind.title, style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val nothing = when (kind) {
                HomeList.VALUABLE -> tops.isEmpty()
                HomeList.SETS -> sets.isEmpty()
            }
            if (nothing) {
                item {
                    Text(kind.empty, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when (kind) {
                HomeList.VALUABLE -> items(tops, key = { it.collectionItemId }) { top ->
                    val entry = byId[top.collectionItemId]
                    ListCard { ValuableRow(top, entry, state, money, onClick = entry?.let { found -> { openItem = found } }) }
                }
                HomeList.SETS -> items(sets, key = { it.id }) { set ->
                    ListCard { SetProgressRow(set, state.serverUrl, onClick = { onOpenSet(set.name) }) }
                }
            }
        }
    }

    openItem?.let { entry -> ItemDialog(entry, state, onRemove = onRemove, onClose = { openItem = null }) }
}
