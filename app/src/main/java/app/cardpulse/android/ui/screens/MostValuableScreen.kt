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
import app.cardpulse.android.ui.AppState

/** Every card the server lists as most valuable, one to a row, under a back arrow: what "See all" on Home opens. */
@Composable
fun MostValuableScreen(
    state: AppState,
    onBack: () -> Unit,
    onRemove: (item: CollectionItemDto, wholeRow: Boolean, done: (String?) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val money = remember(state.prefs.currency, state.prefs.rateFromEur) {
        MoneyFormatter(state.prefs.currency, state.prefs.rateFromEur)
    }
    val byId = remember(state.collection) { state.collection.associateBy { it.id } }
    val tops = state.dashboard?.topCards.orEmpty()
    var openItem by remember { mutableStateOf<CollectionItemDto?>(null) }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Most valuable", style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (tops.isEmpty()) {
                item {
                    Text(
                        "Nothing to show yet. Add a few cards and refresh.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(tops, key = { it.collectionItemId }) { top ->
                val entry = byId[top.collectionItemId]
                ListCard { ValuableRow(top, entry, state, money, onClick = entry?.let { found -> { openItem = found } }) }
            }
        }
    }

    openItem?.let { entry -> ItemDialog(entry, state, onRemove = onRemove, onClose = { openItem = null }) }
}
