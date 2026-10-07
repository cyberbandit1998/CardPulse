package app.cardpulse.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.cardpulse.android.core.ArtSource
import app.cardpulse.android.core.CollectionFilter
import app.cardpulse.android.core.CollectionItemDto
import app.cardpulse.android.core.MoneyFormatter
import app.cardpulse.android.core.defaultArtSource
import app.cardpulse.android.core.filterOptions
import app.cardpulse.android.core.hasCatalogueImage
import app.cardpulse.android.core.parseServerInstant
import app.cardpulse.android.core.passing
import app.cardpulse.android.core.takesItsPhotoWhenRemoved
import app.cardpulse.android.ui.AccentTextButton
import app.cardpulse.android.ui.AppState
import app.cardpulse.android.ui.Banner
import app.cardpulse.android.ui.BottomBarOverhang
import app.cardpulse.android.ui.CARD_ASPECT
import app.cardpulse.android.ui.CardArt
import app.cardpulse.android.ui.TradeBadge
import app.cardpulse.android.ui.TradeSection
import app.cardpulse.android.ui.WishlistBadge
import app.cardpulse.android.ui.WishlistToggleButton
import app.cardpulse.android.ui.formatDate

private enum class SortOrder(val label: String) {
    RECENT("Recent"),
    NAME("Name"),
    SET("Set"),
}

private fun CollectionItemDto.setName(): String = card?.setRef?.name ?: card?.setRef?.abbreviation ?: card?.setId.orEmpty()

private fun CollectionItemDto.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val haystack = listOfNotNull(card?.name, setName(), card?.number, card?.rarity, variant, condition)
    return query.trim().split(' ').filter { it.isNotEmpty() }.all { word -> haystack.any { it.contains(word, ignoreCase = true) } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CollectionScreen(
    state: AppState,
    onRefresh: () -> Unit,
    onRemove: (item: CollectionItemDto, wholeRow: Boolean, done: (String?) -> Unit) -> Unit,
    /** Opens the screen where a card is typed in. */
    onAddCard: () -> Unit,
    modifier: Modifier = Modifier,
    /** What the search box starts with, such as a set's name when the user came from the set's progress on Home. */
    initialQuery: String = "",
) {
    var query by rememberSaveable(initialQuery) { mutableStateOf(initialQuery) }
    var sort by rememberSaveable { mutableStateOf(SortOrder.RECENT) }
    var filter by rememberSaveable(stateSaver = CollectionFilterSaver) { mutableStateOf(CollectionFilter()) }
    var showFilter by remember { mutableStateOf(false) }
    var openItem by remember { mutableStateOf<CollectionItemDto?>(null) }

    val priceField = state.prefs.priceField
    val rate = state.prefs.rateFromEur
    val money = remember(state.prefs.currency, rate) { MoneyFormatter(state.prefs.currency, rate) }
    val options = remember(state.collection, priceField, rate) { state.collection.filterOptions(priceField, rate) }
    val shown = remember(state.collection, query, sort, filter, priceField, rate) {
        val narrowed = state.collection.passing(filter, priceField, rate).filter { it.matches(query) }
        when (sort) {
            SortOrder.RECENT -> narrowed // the server already returns newest first
            SortOrder.NAME -> narrowed.sortedBy { it.card?.name?.lowercase().orEmpty() }
            SortOrder.SET -> narrowed.sortedWith(compareBy({ it.setName().lowercase() }, { it.card?.number?.padStart(4, '0').orEmpty() }))
        }
    }
    val totalCards = remember(state.collection) { state.collection.sumOf { it.quantity } }

    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search name, set, number, rarity…") },
                singleLine = true,
            )
            // Wraps on a narrow phone, so the Filter chip is never cut off.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                SortOrder.entries.forEach { option ->
                    FilterChip(selected = sort == option, onClick = { sort = option }, label = { Text(option.label) })
                }
                FilterChip(
                    selected = filter.isActive,
                    onClick = { showFilter = true },
                    // With the count there is no room for the icon on a small phone; the selected look says it is on.
                    label = { Text(if (filter.isActive) "Filter ${filter.activeCount}" else "Filter") },
                    leadingIcon = if (filter.isActive) {
                        null
                    } else {
                        { Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (filter.isActive) {
                        "${shown.size} of ${state.collection.size} entries · ${shown.sumOf { it.quantity }} cards"
                    } else {
                        "${shown.size} entries · $totalCards cards"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                AccentTextButton(onClick = onRefresh) { Text("Refresh") }
                AccentTextButton(onClick = onAddCard) { Text("Add card") }
            }
            if (state.collectionLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.collectionUnreadable > 0) {
                Banner("${state.collectionUnreadable} entries from the server couldn't be read by this app and are hidden.", isError = true)
            }
        }

        when {
            !state.collectionLoaded && state.collectionLoading -> Unit
            state.collection.isEmpty() -> EmptyNote("Your collection is empty. Scan a card, or type one in with Add card, to add the first one.")
            shown.isEmpty() -> EmptyNote(
                text = if (query.isBlank()) "Nothing matches this filter." else "Nothing matches “${query.trim()}”.",
                action = if (filter.isActive) "Clear the filter" else null,
                onAction = { filter = CollectionFilter() },
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp + BottomBarOverhang),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(shown, key = { it.id }) { entry ->
                    CollectionTile(entry, state, onClick = { openItem = entry })
                }
            }
        }
    }

    if (showFilter) {
        CollectionFilterDialog(
            options = options,
            filter = filter,
            shownCount = shown.size,
            currency = money,
            onChange = { filter = it },
            onClose = { showFilter = false },
        )
    }

    openItem?.let { entry -> ItemDialog(entry, state, onRemove = onRemove, onClose = { openItem = null }) }
}

@Composable
private fun EmptyNote(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.TopCenter) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null) AccentTextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun CollectionTile(entry: CollectionItemDto, state: AppState, onClick: () -> Unit) {
    Card(Modifier.clickable(onClick = onClick)) {
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box {
                CardArt(entry, state.serverUrl, state.prefs, Modifier.fillMaxWidth().cardAspect())
                if (entry.quantity > 1) {
                    Text(
                        "×${entry.quantity}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                // A small heart when the card is on the wishlist.
                WishlistBadge(entry.cardId ?: entry.card?.id, Modifier.align(Alignment.TopStart).padding(4.dp))
                // And a swap arrow with a count when some of its copies are for trade.
                TradeBadge(entry.id, entry.quantity, Modifier.align(Alignment.BottomStart).padding(4.dp))
            }
            Text(entry.card?.name.orEmpty(), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(entry.setName().ifBlank { null }, entry.card?.number?.let { "#$it" }).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun Modifier.cardAspect(): Modifier = this.aspectRatio(CARD_ASPECT)

/** The details of one collection entry, with the way to remove it. Shared by the Collection tab and Home. */
@Composable
internal fun ItemDialog(
    entry: CollectionItemDto,
    state: AppState,
    onRemove: (item: CollectionItemDto, wholeRow: Boolean, done: (String?) -> Unit) -> Unit,
    onClose: () -> Unit,
) {
    val money = remember(state.prefs.currency, state.prefs.rateFromEur) { MoneyFormatter(state.prefs.currency, state.prefs.rateFromEur) }
    val hasOfficial = entry.card.hasCatalogueImage()
    var source by remember { mutableStateOf(defaultArtSource(entry, state.prefs.preferOwnPhotos)) }
    var askToRemove by remember { mutableStateOf(false) }

    if (askToRemove) {
        RemoveDialog(
            entry = entry,
            photoGoesToo = entry.takesItsPhotoWhenRemoved(state.collection),
            onRemove = { wholeRow, done -> onRemove(entry, wholeRow, done) },
            onRemoved = onClose, // the card (or its count) has changed, so close the details too
            onDismiss = { askToRemove = false },
        )
    }

    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { AccentTextButton(onClick = onClose) { Text("Close") } },
        dismissButton = {
            TextButton(
                onClick = { askToRemove = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Remove…") }
        },
        title = { Text(entry.card?.name.orEmpty()) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CardArt(entry, state.serverUrl, state.prefs, Modifier.fillMaxWidth().cardAspect(), large = true, source = source)
                if (entry.hasScanPhoto && hasOfficial) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = source == ArtSource.OFFICIAL, onClick = { source = ArtSource.OFFICIAL }, label = { Text("Official art") })
                        FilterChip(selected = source == ArtSource.OWN_PHOTO, onClick = { source = ArtSource.OWN_PHOTO }, label = { Text("My photo") })
                    }
                }
                // You may want more copies of a card you own, so a card in the collection can go on the wishlist too.
                WishlistToggleButton(entry.cardId ?: entry.card?.id)
                // And you may have copies to spare: how many are for trade is up to you, and none are until you say.
                TradeSection(entry)
                DetailRow("Set", entry.setName().ifBlank { "—" })
                DetailRow("Number", entry.card?.number ?: "—")
                DetailRow("Rarity", entry.card?.rarity ?: "—")
                DetailRow("Quantity", entry.quantity.toString())
                DetailRow("Condition", entry.condition)
                DetailRow("Variant", entry.variant)
                DetailRow("Language", entry.lang.uppercase())
                entry.printingDetailNames.takeIf { it.isNotEmpty() }?.let { DetailRow("Details", it.joinToString(", ")) }
                DetailRow("Paid", entry.purchasePrice?.let { money.format(it) } ?: "not recorded")
                DetailRow("Added", formatDate(parseServerInstant(entry.addedAt)).ifBlank { "—" })
            }
        },
    )
}

/** One line of a card's details: what it is on the left, its value on the right. */
@Composable
internal fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
