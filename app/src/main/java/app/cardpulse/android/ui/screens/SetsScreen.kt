package app.cardpulse.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import app.cardpulse.android.core.SetFilter
import app.cardpulse.android.core.SetOrder
import app.cardpulse.android.core.browseSets
import app.cardpulse.android.core.filtered
import app.cardpulse.android.core.matching
import app.cardpulse.android.core.ordered
import app.cardpulse.android.core.setProgress
import app.cardpulse.android.ui.AccentTextButton
import app.cardpulse.android.ui.AppState
import app.cardpulse.android.ui.BottomBarOverhang

/**
 * The Sets tab: every set the server lists, each with how many of its cards you own ("18 / 132", or "0 / 132" for a set
 * you have none of yet). It can be searched, narrowed to the sets you own, the ones still incomplete or the complete ones,
 * and put in another order; by default the sets you have cards from come first, closest to finished first. Tapping a set
 * opens its checklist ([onOpenSet] gets the set's id).
 *
 * The list of sets is asked for when the tab opens ([onLoadSets]; true to ask again whatever was loaded). Until it has
 * arrived, or if it cannot be had, the sets you have cards from are shown, so the tab is never empty for no reason.
 *
 * The title, search and chips are the first rows of the list, so they scroll away and leave the room to the sets.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetsScreen(
    state: AppState,
    onOpenSet: (String) -> Unit,
    modifier: Modifier = Modifier,
    onLoadSets: (Boolean) -> Unit = {},
) {
    LaunchedEffect(Unit) { onLoadSets(false) }

    val all = remember(state.sets, state.setsLoaded, state.collection, state.collectionLoaded) {
        if (state.setsLoaded) browseSets(state.sets, state.collection, state.collectionLoaded) else state.collection.setProgress()
    }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(SetFilter.ALL) }
    var order by rememberSaveable { mutableStateOf(SetOrder.PROGRESS) }
    val shown = remember(all, query, filter, order) { all.matching(query).filtered(filter).ordered(order) }
    val started = remember(all) { all.count { it.owned > 0 } }
    val cards = remember(all) { all.sumOf { it.owned } }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + BottomBarOverhang),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "title") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sets", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        when {
                            state.setsLoaded -> "$started of ${all.size} sets started · $cards ${if (cards == 1) "card" else "cards"}"
                            state.setsLoading -> "Loading every set…"
                            state.setsError != null -> "Showing only the sets you have cards from"
                            else -> "The sets you have cards from"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RoundIconButton(Icons.Default.Refresh, "Refresh the sets", onClick = { onLoadSets(true) })
            }
        }
        item(key = "search") {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search sets") },
                singleLine = true,
            )
        }
        item(key = "filters") {
            // The four chips fit across even a 360dp phone; with larger text, or a narrower one, the last wraps to a second
            // line rather than being cut off.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SetFilter.entries.forEach { option ->
                    SnugChip(label = option.label, selected = filter == option, onClick = { filter = option })
                }
            }
        }
        item(key = "sort") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Sort", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SetOrder.entries.forEach { option ->
                    SnugChip(label = option.label, selected = order == option, onClick = { order = option })
                }
            }
        }
        state.setsError?.let { problem ->
            item(key = "problem") {
                SetsProblem(
                    if (state.setsLoaded) "Couldn't refresh the sets. $problem" else "Couldn't load every set. $problem",
                    onRetry = { onLoadSets(true) },
                )
            }
        }
        if (state.setsLoading || (state.collectionLoading && !state.collectionLoaded)) {
            item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }

        when {
            all.isEmpty() -> item(key = "empty") {
                EmptyNote(
                    when {
                        state.setsLoaded -> "The server lists no sets yet."
                        state.setsError != null -> "No sets to show yet."
                        else -> "Loading the sets…"
                    },
                )
            }
            shown.isEmpty() -> item(key = "none") { EmptyNote(noSetsText(filter, query)) }
            else -> items(shown, key = { it.id }) { set ->
                ListCard { SetProgressRow(set, state.serverUrl, nameLines = 2, onClick = { onOpenSet(set.id) }) }
            }
        }
    }
}

/** How much nearer the edges of a [SnugChip] its text sits than in a Material chip. */
private val SnugChipTrim = 4.dp

/**
 * A label narrower than this (just "All") keeps Material's padding: a chip under 48dp wide is centred in a 48dp slot, which
 * would leave it a few dp in from the edge that the search box and the rows below line up on.
 */
private val SnugChipShortLabel = 24.dp

/**
 * A filter chip 8dp narrower than Material's: the text sits [SnugChipTrim] nearer each edge. With Material's own padding the
 * four set filters (All, Owned, Incomplete, Complete) need about 343dp and a 360dp phone leaves 328dp, so the last one would
 * wrap onto a second line; like this they take about 318dp and sit on one row. The Wishlist uses it for its rows of chips too.
 */
@Composable
internal fun SnugChip(label: String, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = {
            Text(
                label,
                Modifier.layout { measurable, constraints ->
                    val text = measurable.measure(constraints)
                    val trim = if (text.width < SnugChipShortLabel.roundToPx()) 0 else SnugChipTrim.roundToPx()
                    layout(text.width - 2 * trim, text.height) { text.place(-trim, 0) }
                },
            )
        },
    )
}

/** What to say when the search and the filter between them leave no set. */
private fun noSetsText(filter: SetFilter, query: String): String {
    val searched = query.trim()
    return when {
        searched.isNotEmpty() && filter == SetFilter.ALL -> "No set matches “$searched”."
        searched.isNotEmpty() -> "No ${filter.label.lowercase()} set matches “$searched”."
        filter == SetFilter.OWNED -> "You don't have cards from any set yet."
        filter == SetFilter.INCOMPLETE -> "No set is partly collected yet."
        filter == SetFilter.COMPLETE -> "No set is complete yet."
        else -> "No sets to show."
    }
}

/** Why the list of sets could not be had, with a way to ask again. */
@Composable
private fun SetsProblem(message: String, onRetry: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(start = 14.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        // In the banner's own text colour: the accent colour is unreadable on the dark red of an error.
        AccentTextButton(onClick = onRetry, color = LocalContentColor.current) { Text("Try again") }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 8.dp),
    )
}
