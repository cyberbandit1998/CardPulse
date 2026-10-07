package app.cardpulse.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.cardpulse.android.core.MoneyFormatter
import app.cardpulse.android.core.MoneyInput
import app.cardpulse.android.core.ServerUrls
import app.cardpulse.android.core.WishlistEntry
import app.cardpulse.android.core.WishlistFilter
import app.cardpulse.android.core.WishlistItemDto
import app.cardpulse.android.core.WishlistOrder
import app.cardpulse.android.core.WishlistPriority
import app.cardpulse.android.core.counts
import app.cardpulse.android.core.entries
import app.cardpulse.android.core.filtered
import app.cardpulse.android.core.ordered
import app.cardpulse.android.ui.AccentTextButton
import app.cardpulse.android.ui.AppState
import app.cardpulse.android.ui.Banner
import app.cardpulse.android.ui.CARD_ASPECT
import app.cardpulse.android.ui.LocalWishlist
import app.cardpulse.android.ui.RemoteImage
import app.cardpulse.android.ui.theme.extras

/**
 * The wishlist: the cards you want, newest first until you say otherwise. Each row has the card's picture, name, set, number,
 * rarity and what it costs now (by the price you chose in PokéCollector), says whether you own a copy, and shows the target
 * price and priority if you set them. It can be put in order of when it was added, name, set or price, and narrowed to the cards
 * you are missing or the ones you own (you may want more copies, so a card stays on the list until you take it off).
 * Pressing a row opens the card, where the target price, the priority and the way to remove it are.
 *
 * The list is the server's own, loaded with everything else when the app opens ([onLoad] asks again; true to ask whatever was
 * loaded). Which cards are owned comes from your collection.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WishlistScreen(
    state: AppState,
    onBack: () -> Unit,
    onLoad: (force: Boolean) -> Unit,
    onSetTarget: (item: WishlistItemDto, targetEur: Double?, done: (String?) -> Unit) -> Unit,
    onSetPriority: (cardId: String, priority: WishlistPriority?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { onLoad(false) }

    val prefs = state.prefs
    val money = remember(prefs.currency, prefs.rateFromEur) { MoneyFormatter(prefs.currency, prefs.rateFromEur) }
    val entries = remember(state.wishlist, state.collection, state.collectionLoaded, prefs.priceField, state.wishlistPriorities) {
        state.wishlist.entries(state.collection, state.collectionLoaded, prefs.priceField, state.wishlistPriorities)
    }
    val counts = remember(entries) { entries.counts() }
    var order by rememberSaveable { mutableStateOf(WishlistOrder.RECENT) }
    var filter by rememberSaveable { mutableStateOf(WishlistFilter.ALL) }
    val shown = remember(entries, filter, order) { entries.filtered(filter).ordered(order) }
    // The open card is kept by id, so the dialog follows the row (a saved target shows at once) and goes when the card is taken off.
    var openCardId by remember { mutableStateOf<String?>(null) }
    val openEntry = openCardId?.let { id -> entries.firstOrNull { it.cardId == id } }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Column(Modifier.weight(1f)) {
                Text("Wishlist", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.wishlistLoaded && state.wishlist.isNotEmpty()) {
                    Text(
                        buildString {
                            append(counts.all).append(if (counts.all == 1) " card" else " cards")
                            if (state.collectionLoaded) append(" · ").append(counts.missing).append(" missing")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = { onLoad(true) }, enabled = !state.wishlistLoading) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh the wishlist")
            }
        }
        if (state.wishlistLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))

        val problem = state.wishlistError
        when {
            !state.wishlistLoaded && problem != null && !state.wishlistLoading -> WishlistProblem(problem, onRetry = { onLoad(true) })
            !state.wishlistLoaded -> WishlistLoading()
            state.wishlist.isEmpty() -> WishlistEmpty()
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "sort") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Sort", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        WishlistOrder.entries.forEach { option ->
                            SnugChip(label = option.label, selected = order == option, onClick = { order = option })
                        }
                    }
                }
                item(key = "filters") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WishlistFilter.entries.forEach { option ->
                            // Which cards are owned is not known until the collection has loaded, so only All can be chosen before.
                            val known = option == WishlistFilter.ALL || state.collectionLoaded
                            SnugChip(
                                label = if (known) "${option.label} (${counts.of(option)})" else option.label,
                                selected = filter == option,
                                onClick = { filter = option },
                                enabled = known,
                            )
                        }
                    }
                }
                if (problem != null) {
                    item(key = "problem") { Banner("Couldn't refresh the wishlist. $problem", isError = true) }
                }
                if (state.wishlistUnreadable > 0) {
                    item(key = "unreadable") {
                        Banner("${state.wishlistUnreadable} entries from the server couldn't be read by this app and are hidden.", isError = true)
                    }
                }
                if (shown.isEmpty()) {
                    item(key = "none") {
                        Text(
                            if (filter == WishlistFilter.OWNED) {
                                "You don't own any card from your wishlist yet."
                            } else {
                                "Nothing is missing: you own every card on your wishlist."
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
                        )
                    }
                }
                items(shown, key = { it.item.id }) { entry ->
                    WishlistRow(entry, state.serverUrl, money, onClick = { openCardId = entry.cardId })
                }
            }
        }
    }

    openEntry?.let { entry ->
        WishlistItemDialog(
            entry = entry,
            state = state,
            money = money,
            onSetTarget = onSetTarget,
            onSetPriority = onSetPriority,
            onClose = { openCardId = null },
        )
    }
}

/**
 * One card of the wishlist: its picture, name, set · number · rarity, tags (owned or missing, how many are wanted, the priority,
 * the target price) and what it costs now. Pressing it opens the card ([onClick]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WishlistRow(entry: WishlistEntry, serverUrl: String, money: MoneyFormatter, onClick: () -> Unit) {
    val extras = MaterialTheme.extras
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    ListCard {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = "Show details", onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = entry.describe(money) }
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RemoteImage(
                url = ServerUrls.cardImage(serverUrl, entry.cardId),
                description = null,
                modifier = Modifier.width(56.dp).aspectRatio(CARD_ASPECT).clip(RoundedCornerShape(6.dp)),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(entry.subtitle, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (entry.statusText.isNotEmpty()) {
                        if (entry.owned) {
                            Pill(entry.statusText, extras.positive.copy(alpha = 0.16f), extras.positive)
                        } else {
                            Pill(entry.statusText, MaterialTheme.colorScheme.surfaceVariant, muted)
                        }
                    }
                    if (entry.item.quantity > 1) Pill("Wants ×${entry.item.quantity}", MaterialTheme.colorScheme.surfaceVariant, muted)
                    entry.priority?.let { level ->
                        when (level) {
                            WishlistPriority.HIGH -> Pill("High priority", MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f), MaterialTheme.colorScheme.secondary)
                            WishlistPriority.MEDIUM -> Pill("Medium priority", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurface)
                            WishlistPriority.LOW -> Pill("Low priority", MaterialTheme.colorScheme.surfaceVariant, muted)
                        }
                    }
                    entry.targetEur?.let { target ->
                        if (entry.atTarget) {
                            Pill("Reached ${money.format(target)}", extras.positive.copy(alpha = 0.16f), extras.positive)
                        } else {
                            Pill("Target ${money.format(target)}", MaterialTheme.colorScheme.surfaceVariant, muted)
                        }
                    }
                }
            }
            Text(
                if (entry.hasPrice) money.format(entry.priceEur) else "No price",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (entry.hasPrice) FontWeight.Bold else FontWeight.Normal,
                color = if (entry.hasPrice) MaterialTheme.colorScheme.onSurface else muted,
                maxLines = 1,
            )
        }
    }
}

/** A small rounded label. */
@Composable
private fun Pill(text: String, background: Color, content: Color) {
    Text(
        text,
        color = content,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(background).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/**
 * A wishlist card opened: its picture and facts, the target price and the priority to change, and the way to take the card off the
 * list. Save applies the target price (to the server, so the website has it too) and the priority (kept on this phone).
 */
@Composable
private fun WishlistItemDialog(
    entry: WishlistEntry,
    state: AppState,
    money: MoneyFormatter,
    onSetTarget: (item: WishlistItemDto, targetEur: Double?, done: (String?) -> Unit) -> Unit,
    onSetPriority: (cardId: String, priority: WishlistPriority?) -> Unit,
    onClose: () -> Unit,
) {
    val wishlist = LocalWishlist.current
    val rate = state.prefs.rateFromEur
    val initialTarget = remember(entry.item.id) { MoneyInput.toInput(entry.targetEur, rate) }
    var targetText by remember(entry.item.id) { mutableStateOf(initialTarget) }
    var priority by remember(entry.item.id) { mutableStateOf(entry.priority) }
    var saving by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    // A blank box means no target; text that is not an amount is a mistake to fix before saving.
    val newTarget = MoneyInput.toEuros(targetText, rate)
    val invalid = targetText.isNotBlank() && newTarget == null
    val targetChanged = targetText.trim() != initialTarget
    val priorityChanged = priority != entry.priority

    fun save() {
        if (priorityChanged) onSetPriority(entry.cardId, priority)
        if (!targetChanged) {
            onClose()
            return
        }
        saving = true
        problem = null
        onSetTarget(entry.item, newTarget) { error ->
            saving = false
            if (error == null) onClose() else problem = error
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            AccentTextButton(onClick = { save() }, enabled = !saving && !invalid && (targetChanged || priorityChanged)) { Text("Save") }
        },
        dismissButton = { AccentTextButton(onClick = onClose) { Text("Close") } },
        title = { Text(entry.name) },
        text = {
            WishlistItemDetails(
                entry = entry,
                serverUrl = state.serverUrl,
                currency = state.prefs.currency,
                money = money,
                targetText = targetText,
                targetInvalid = invalid,
                problem = problem,
                priority = priority,
                onTargetText = { text ->
                    targetText = text
                    problem = null
                },
                onPriority = { priority = it },
                // Taking a card off is the user's own decision: owning a copy never does it.
                onRemove = { wishlist.toggle(entry.cardId) },
            )
        },
    )
}

/**
 * What a wishlist card's dialog holds apart from its buttons. Kept apart so that a picture can show it: a dialog is a window of
 * its own, which the screen pictures cannot see.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WishlistItemDetails(
    entry: WishlistEntry,
    serverUrl: String,
    currency: String,
    money: MoneyFormatter,
    targetText: String,
    targetInvalid: Boolean,
    problem: String?,
    priority: WishlistPriority?,
    onTargetText: (String) -> Unit,
    onPriority: (WishlistPriority?) -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RemoteImage(
            url = ServerUrls.cardImage(serverUrl, entry.cardId, large = true),
            description = entry.name,
            modifier = Modifier.fillMaxWidth().aspectRatio(CARD_ASPECT).clip(RoundedCornerShape(8.dp)),
        )
        DetailRow("Set", entry.setName.ifBlank { "—" })
        DetailRow("Number", entry.numberText.ifBlank { "—" })
        DetailRow("Rarity", entry.rarity ?: "—")
        DetailRow("Price now", if (entry.hasPrice) money.format(entry.priceEur) else "No price")
        DetailRow("You own", entry.statusText.ifBlank { "—" })
        if (entry.item.quantity > 1) DetailRow("Wanted", "×${entry.item.quantity}")

        OutlinedTextField(
            value = targetText,
            onValueChange = onTargetText,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Target price ($currency)") },
            placeholder = { Text("optional") },
            supportingText = { Text("Shown as reached when the price is at or below it.") },
            isError = targetInvalid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
        )
        problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

        Text("Priority", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SnugChip(label = "None", selected = priority == null, onClick = { onPriority(null) })
            WishlistPriority.entries.forEach { level ->
                SnugChip(label = level.label, selected = priority == level, onClick = { onPriority(level) })
            }
        }

        TextButton(
            onClick = onRemove,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Remove from wishlist")
        }
    }
}

@Composable
private fun WishlistLoading() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Top),
    ) {
        CircularProgressIndicator()
        Text(
            "Loading your wishlist…",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun WishlistProblem(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Top),
    ) {
        Text("Couldn't load your wishlist.", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Button(onClick = onRetry) { Text("Try again") }
    }
}

/** Nothing on the wishlist yet: says what it is for and where the hearts are. */
@Composable
private fun WishlistEmpty() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
    ) {
        Icon(
            Icons.Default.FavoriteBorder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(48.dp),
        )
        Text("Your wishlist is empty", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            "Tap the heart on a card to keep it here: in a set's checklist, in a card's details, or in the results when you add a " +
                "card by typing its name.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
