package app.cardpulse.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.cardpulse.android.core.CardLanguages
import app.cardpulse.android.core.ChecklistEntry
import app.cardpulse.android.core.ChecklistFilter
import app.cardpulse.android.core.ChecklistTally
import app.cardpulse.android.core.CollectionItemDto
import app.cardpulse.android.core.ServerUrls
import app.cardpulse.android.core.SetDto
import app.cardpulse.android.core.collectionRow
import app.cardpulse.android.core.entries
import app.cardpulse.android.core.filtered
import app.cardpulse.android.core.findSet
import app.cardpulse.android.core.hasPicture
import app.cardpulse.android.core.tally
import app.cardpulse.android.ui.AppState
import app.cardpulse.android.ui.CARD_ASPECT
import app.cardpulse.android.ui.GreyscaleFilter
import app.cardpulse.android.ui.RemoteImage
import app.cardpulse.android.ui.WishlistHeart
import app.cardpulse.android.ui.theme.extras

/**
 * One set's checklist: every card the server has for it, the ones you own in full colour with a tick (and how many copies),
 * the ones you are missing greyed out and marked "Missing". Filter chips show all, only the owned, or only the missing
 * cards. Tapping an owned card shows its details.
 *
 * Which cards you own comes from your collection, so it changes as soon as you add or remove a card.
 */
@Composable
fun SetChecklistScreen(
    setId: String,
    state: AppState,
    onBack: () -> Unit,
    onLoad: (Boolean) -> Unit,
    onRemove: (item: CollectionItemDto, wholeRow: Boolean, done: (String?) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    /** Shown on a set you own cards from: leaves the checklist for your Collection, searched for this set's name. */
    onShowInCollection: ((String) -> Unit)? = null,
) {
    LaunchedEffect(setId) { onLoad(false) }

    val checklist = state.checklists[setId]
    val loading = setId in state.checklistsLoading
    val problem = state.checklistErrors[setId]
    val set = remember(setId, state.sets, state.collection, checklist) { findSet(setId, state.sets, state.collection) ?: checklist?.set }
    val entries = remember(checklist, state.collection, state.collectionLoaded) {
        checklist?.entries(state.collection, state.collectionLoaded).orEmpty()
    }
    val tally = remember(entries) { entries.tally() }
    var filter by rememberSaveable { mutableStateOf(ChecklistFilter.ALL) }
    val shown = remember(entries, filter) { entries.filtered(filter) }
    var openItem by remember { mutableStateOf<CollectionItemDto?>(null) }
    val title = set?.name?.takeIf { it.isNotBlank() } ?: setId

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = { onLoad(true) }, enabled = !loading) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh this set")
            }
        }

        when {
            checklist == null && problem != null && !loading -> ChecklistProblem(problem, onRetry = { onLoad(true) })
            checklist == null -> ChecklistLoading()
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 96.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ChecklistSummary(
                        set = set,
                        serverUrl = state.serverUrl,
                        tally = tally,
                        onShowInCollection = if (onShowInCollection != null && tally.owned > 0) {
                            { onShowInCollection(title) }
                        } else {
                            null
                        },
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ChecklistFilters(filter, tally, onFilter = { filter = it })
                }
                if (shown.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            when {
                                entries.isEmpty() -> "The server has no cards listed for this set yet. Try again later."
                                filter == ChecklistFilter.OWNED -> "You don't own any card of this set yet."
                                else -> "Nothing is missing: you own every card of this set."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
                items(shown, key = { it.card.id }) { entry ->
                    val row = if (entry.owned) entry.collectionRow(state.collection) else null
                    ChecklistTile(entry, state.serverUrl, onClick = row?.let { found -> { openItem = found } })
                }
            }
        }
    }

    openItem?.let { row -> ItemDialog(row, state, onRemove = onRemove, onClose = { openItem = null }) }
}

/** The set's logo and name's details, how many of its cards are owned, and a bar showing how far along it is. */
@Composable
private fun ChecklistSummary(set: SetDto?, serverUrl: String, tally: ChecklistTally, onShowInCollection: (() -> Unit)?) {
    val positive = MaterialTheme.extras.positive
    val details = listOfNotNull(
        set?.abbreviation?.takeIf { it.isNotBlank() },
        set?.series?.takeIf { it.isNotBlank() },
        set?.lang?.let { CardLanguages.name(it) },
    ).joinToString(" · ")
    ListCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (set != null) SetLogo(ServerUrls.setLogo(serverUrl, set.id), set.name, Modifier.width(64.dp).height(42.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("${tally.owned}") }
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(" / ${tally.total}") }
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                    )
                    if (details.isNotEmpty()) {
                        Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                }
                Text(
                    if (tally.isComplete) "Complete" else "${tally.percent}%",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (tally.isComplete) positive else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                )
            }
            SlimProgress(tally.fraction, color = if (tally.isComplete) positive else MaterialTheme.colorScheme.secondary)
            if (onShowInCollection != null) {
                OutlinedButton(onClick = onShowInCollection, modifier = Modifier.fillMaxWidth()) { Text("Open my cards in Collection") }
            }
        }
    }
}

@Composable
private fun ChecklistFilters(selected: ChecklistFilter, tally: ChecklistTally, onFilter: (ChecklistFilter) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ChecklistFilter.entries.forEach { option ->
            val count = when (option) {
                ChecklistFilter.ALL -> tally.total
                ChecklistFilter.OWNED -> tally.owned
                ChecklistFilter.MISSING -> tally.missing
            }
            FilterChip(selected = selected == option, onClick = { onFilter(option) }, label = { Text("${option.label} ($count)") })
        }
    }
}

/**
 * A card of the checklist with a heart on the corner of its picture, to put the card on the wishlist or take it off. Owned
 * cards can be wished for too: you may want more copies. The heart sits beside the tile's column rather than in it, because
 * the column is read out as one phrase for the whole card and the heart is a button of its own.
 */
@Composable
private fun ChecklistTile(entry: ChecklistEntry, serverUrl: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    Box(modifier) {
        ChecklistTileBody(entry, serverUrl, onClick, Modifier.fillMaxWidth())
        WishlistHeart(entry.card.id, entry.card.name, Modifier.align(Alignment.TopStart).padding(2.dp), onPicture = true)
    }
}

/**
 * One card. Owned: its picture in full colour with a tick (and "×2" for more copies). Missing: the picture drained of colour
 * and faded. Both say so in words under the name as well, so colour is never the only difference. An owned card can be
 * pressed for its details ([onClick]).
 */
@Composable
private fun ChecklistTileBody(entry: ChecklistEntry, serverUrl: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val owned = entry.owned
    val pictureShape = RoundedCornerShape(8.dp)
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = "Show details", onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = entry.description },
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(CARD_ASPECT)) {
            if (entry.card.hasPicture) {
                RemoteImage(
                    url = ServerUrls.cardImage(serverUrl, entry.card.id),
                    description = null,
                    modifier = Modifier.fillMaxSize().clip(pictureShape),
                    alpha = if (owned) 1f else MISSING_ALPHA,
                    colorFilter = if (owned) null else GreyscaleFilter,
                )
            } else {
                // The server has no picture of this card: its number stands in.
                Box(
                    Modifier.fillMaxSize().clip(pictureShape).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(entry.numberText, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (owned) OwnedBadge(entry.copies, Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
        Text(
            entry.card.name.ifBlank { "—" },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (owned) FontWeight.SemiBold else FontWeight.Normal,
            color = if (owned) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOf(entry.numberText, entry.statusText).filter { it.isNotEmpty() }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = if (owned) MaterialTheme.extras.positive else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A tick on the corner of an owned card's picture, with "×2" when more than one copy is owned. */
@Composable
private fun OwnedBadge(copies: Int, modifier: Modifier = Modifier) {
    val positive = MaterialTheme.extras.positive
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .padding(horizontal = 5.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Check, contentDescription = null, tint = positive, modifier = Modifier.size(14.dp))
        if (copies > 1) {
            Spacer(Modifier.width(2.dp))
            Text("×$copies", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = positive, maxLines = 1)
        }
    }
}

@Composable
private fun ChecklistLoading() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Top),
    ) {
        CircularProgressIndicator()
        Text(
            "Loading the cards of this set…",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            "The first time can take a moment: your server fetches them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ChecklistProblem(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Top),
    ) {
        Text("Couldn't load this set.", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Button(onClick = onRetry) { Text("Try again") }
    }
}

/** How faded a card you are missing is drawn. */
private const val MISSING_ALPHA = 0.45f
