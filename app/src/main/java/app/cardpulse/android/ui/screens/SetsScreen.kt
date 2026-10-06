package app.cardpulse.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cardpulse.android.core.SetOrder
import app.cardpulse.android.core.matching
import app.cardpulse.android.core.ordered
import app.cardpulse.android.core.setProgress
import app.cardpulse.android.ui.AppState
import app.cardpulse.android.ui.BottomBarOverhang

/**
 * The Sets tab: every set the collection holds a card from, with how far along each is. The same rows as Home's "Set
 * progress", all of them, which can be searched and put in another order. Tapping a set shows its cards ([onOpenSet] gets
 * the set's name).
 */
@Composable
fun SetsScreen(
    state: AppState,
    onOpenSet: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val all = remember(state.collection) { state.collection.setProgress() }
    var query by rememberSaveable { mutableStateOf("") }
    var order by rememberSaveable { mutableStateOf(SetOrder.PROGRESS) }
    val shown = remember(all, query, order) { all.matching(query).ordered(order) }
    val cards = remember(all) { all.sumOf { it.owned } }

    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column {
                Text("Sets", style = MaterialTheme.typography.headlineMedium)
                Text(
                    when {
                        all.isEmpty() -> "The sets you have cards from"
                        all.size == 1 -> "1 set · $cards different ${if (cards == 1) "card" else "cards"}"
                        else -> "${all.size} sets · $cards different cards"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search sets") },
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Sort", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SetOrder.entries.forEach { option ->
                    FilterChip(selected = order == option, onClick = { order = option }, label = { Text(option.label) })
                }
            }
            if (state.collectionLoading && !state.collectionLoaded) LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        when {
            all.isEmpty() -> EmptyNote(
                if (state.collectionLoaded) "No sets yet. Cards you add show up here with how much of their set you own." else "Loading your sets…",
            )
            shown.isEmpty() -> EmptyNote("No set matches “${query.trim()}”.")
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp + BottomBarOverhang),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(shown, key = { it.id }) { set ->
                    ListCard { SetProgressRow(set, state.serverUrl, onClick = { onOpenSet(set.name) }) }
                }
            }
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.TopCenter) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
