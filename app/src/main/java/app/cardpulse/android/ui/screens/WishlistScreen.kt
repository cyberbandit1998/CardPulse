package app.cardpulse.android.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.cardpulse.android.core.MoneyFormatter
import app.cardpulse.android.core.MoneyInput
import app.cardpulse.android.core.ServerUrls
import app.cardpulse.android.core.WishlistFilter
import app.cardpulse.android.core.WishlistPriority
import app.cardpulse.android.core.WishlistSort
import app.cardpulse.android.core.isAtOrBelowTarget
import app.cardpulse.android.data.wishlist.WishlistItem
import app.cardpulse.android.ui.AppState
import app.cardpulse.android.ui.CARD_ASPECT
import app.cardpulse.android.ui.GreyscaleFilter
import app.cardpulse.android.ui.RemoteImage
import app.cardpulse.android.ui.theme.extras
import kotlinx.coroutines.launch

/**
 * The cards you want: newest first by default, or by name, set or price, and narrowed to the ones still missing or the
 * ones you have by now. Swipe an entry away (or press its bin) to take it off, with a moment to undo; tap it to set the
 * price you'd pay and how much you want it.
 *
 * [items] is null until the database first answers, so an empty list is only called empty when it really is.
 */
@Composable
fun WishlistScreen(
    state: AppState,
    items: List<WishlistItem>?,
    sort: WishlistSort,
    filter: WishlistFilter,
    onSort: (WishlistSort) -> Unit,
    onFilter: (WishlistFilter) -> Unit,
    onRemove: (WishlistItem) -> Unit,
    onUndoRemove: (WishlistItem) -> Unit,
    onSave: (cardId: String, targetPrice: Double?, priority: WishlistPriority) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val money = remember(state.prefs.currency, state.prefs.rateFromEur) {
        MoneyFormatter(state.prefs.currency, state.prefs.rateFromEur)
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<WishlistItem?>(null) }

    val remove: (WishlistItem) -> Unit = { item ->
        onRemove(item)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar("Removed ${item.cardName}", actionLabel = "Undo", withDismissAction = true)
            if (result == SnackbarResult.ActionPerformed) onUndoRemove(item)
        }
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Text("Wishlist", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                if (!items.isNullOrEmpty()) {
                    Text(
                        if (items.size == 1) "1 card" else "${items.size} cards",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
            }
            ChipRow {
                WishlistSort.entries.forEach { option ->
                    WishlistChip(option.label, selected = sort == option, onClick = { onSort(option) })
                }
            }
            ChipRow {
                WishlistFilter.entries.forEach { option ->
                    WishlistChip(option.label, selected = filter == option, onClick = { onFilter(option) })
                }
            }

            when {
                items == null -> Unit
                items.isEmpty() -> WishlistEmpty(filter, onShowAll = { onFilter(WishlistFilter.ALL) })
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(items, key = { it.cardId }) { item ->
                        SwipeToRemove(onRemove = { remove(item) }) {
                            WishlistItemCard(
                                item = item,
                                serverUrl = state.serverUrl,
                                money = money,
                                onClick = { editing = item },
                                onRemove = { remove(item) },
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }

    editing?.let { item ->
        WishlistEditSheet(
            item = item,
            currency = state.prefs.currency,
            rateFromEur = state.prefs.rateFromEur,
            money = money,
            onSave = { target, priority ->
                onSave(item.cardId, target, priority)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun WishlistChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
    )
}

/** Swiping the entry to the left takes it off the list; a red strip with a bin shows underneath while it is dragged. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToRemove(onRemove: () -> Unit, content: @Composable () -> Unit) {
    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) onRemove()
    }
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val dragging = dismissState.targetValue == SwipeToDismissBoxValue.EndToStart
            val color by animateColorAsState(
                if (dragging) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surfaceVariant,
                label = "swipe background",
            )
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(color).padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = if (dragging) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) { content() }
}

/**
 * One wished-for card: its picture, name, set, number and rarity, today's price and the target price, how much it is
 * wanted, and a bin. A card owned by now is drawn in colour with an "Owned" tag; one still missing is drained of colour,
 * as on a set's checklist. The whole card shrinks a little while pressed.
 */
@Composable
internal fun WishlistItemCard(
    item: WishlistItem,
    serverUrl: String,
    money: MoneyFormatter,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "wishlist press")
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val imageUrl = if (serverUrl.isNotBlank()) ServerUrls.cardImage(serverUrl, item.cardId) else item.imageUrl
    val belowTarget = isAtOrBelowTarget(item.cachedPrice, item.targetPrice)

    Row(
        modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(interactionSource = interaction, indication = ripple(), role = Role.Button, onClickLabel = "Edit", onClick = onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteImage(
            url = imageUrl,
            description = item.cardName,
            modifier = Modifier.width(60.dp).aspectRatio(CARD_ASPECT).clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Fit,
            alpha = if (item.isOwned) 1f else 0.85f,
            colorFilter = if (item.isOwned) null else GreyscaleFilter,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    item.cardName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                PriorityBadge(item.priority)
            }
            val where = listOfNotNull(
                item.setName?.takeIf { it.isNotBlank() },
                item.collectorNumber?.takeIf { it.isNotBlank() }?.let { "#$it" },
            ).joinToString(" · ")
            if (where.isNotEmpty()) Text(where, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item.rarity?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
                if (item.isOwned) Tag("Owned", MaterialTheme.extras.positive)
            }
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PriceColumn("Market", item.cachedPrice?.let { money.format(it) } ?: "—", if (belowTarget) MaterialTheme.extras.positive else null)
                PriceColumn("Target", item.targetPrice?.let { money.format(it) } ?: "Not set", null)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "Remove ${item.cardName} from wishlist", tint = muted)
            }
            Icon(Icons.Default.Edit, contentDescription = null, tint = muted.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun PriceColumn(label: String, value: String, highlight: Color?) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = highlight ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/** High in the brand red, medium in the lighter accent, low in a quiet grey. */
@Composable
internal fun PriorityBadge(priority: WishlistPriority, modifier: Modifier = Modifier) {
    val color = when (priority) {
        WishlistPriority.HIGH -> MaterialTheme.colorScheme.primary
        WishlistPriority.MEDIUM -> MaterialTheme.colorScheme.secondary
        WishlistPriority.LOW -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Tag(priority.label, color, modifier)
}

@Composable
private fun Tag(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = color,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** What an empty list says: how to fill it, or, when only the filter hides everything, the way back to all of it. */
@Composable
private fun WishlistEmpty(filter: WishlistFilter, onShowAll: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
    ) {
        Spacer(Modifier.height(32.dp))
        Box(
            Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.FavoriteBorder, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(44.dp))
        }
        Text(
            when (filter) {
                WishlistFilter.ALL -> "Your wishlist is empty"
                WishlistFilter.MISSING -> "Nothing missing"
                WishlistFilter.OWNED -> "None owned yet"
            },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            when (filter) {
                WishlistFilter.ALL ->
                    "Tap the heart on a card (in a set's checklist, a search result or a card's details) to keep track of the cards you want."
                WishlistFilter.MISSING -> "You own every card on your wishlist."
                WishlistFilter.OWNED -> "No card on your wishlist is in your collection yet."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (filter != WishlistFilter.ALL) TextButton(onClick = onShowAll) { Text("Show all") }
    }
}

/**
 * Sets the price the user would pay (typed in their own currency, kept in euros like every other amount) and how much they
 * want the card. Blank means no target.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WishlistEditSheet(
    item: WishlistItem,
    currency: String,
    rateFromEur: Double,
    money: MoneyFormatter,
    onSave: (targetPrice: Double?, priority: WishlistPriority) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var priceText by rememberSaveable(item.cardId) { mutableStateOf(MoneyInput.toInput(item.targetPrice, rateFromEur)) }
    var priority by rememberSaveable(item.cardId) { mutableStateOf(item.priority) }
    val invalid = priceText.isNotBlank() && MoneyInput.toEuros(priceText, rateFromEur) == null

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                Text(item.cardName, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "Market price: " + (item.cachedPrice?.let { money.format(it) } ?: "unknown"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = priceText,
                onValueChange = { priceText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Target price ($currency)") },
                placeholder = { Text("No target") },
                singleLine = true,
                isError = invalid,
                supportingText = { Text(if (invalid) "Enter an amount, like 12.50" else "What you'd pay for it. Leave empty for no target.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Priority", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    WishlistPriority.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = priority == option,
                            onClick = { priority = option },
                            shape = SegmentedButtonDefaults.itemShape(index, WishlistPriority.entries.size),
                        ) { Text(option.label) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onSave(MoneyInput.toEuros(priceText, rateFromEur), priority) },
                    enabled = !invalid,
                ) { Text("Save") }
            }
        }
    }
}
