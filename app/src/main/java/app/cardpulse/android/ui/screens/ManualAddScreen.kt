package app.cardpulse.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.cardpulse.android.core.AddEdits
import app.cardpulse.android.core.AddedNote
import app.cardpulse.android.core.CardDto
import app.cardpulse.android.core.CardLanguages
import app.cardpulse.android.core.CardTypes
import app.cardpulse.android.core.CollectionIndex
import app.cardpulse.android.core.Conditions
import app.cardpulse.android.core.CustomCardForm
import app.cardpulse.android.core.ManualAddState
import app.cardpulse.android.core.ManualMode
import app.cardpulse.android.core.MoneyInput
import app.cardpulse.android.core.Ownership
import app.cardpulse.android.core.ServerUrls
import app.cardpulse.android.core.SetDto
import app.cardpulse.android.core.Variants
import app.cardpulse.android.core.details
import app.cardpulse.android.core.facts
import app.cardpulse.android.core.matching
import app.cardpulse.android.core.ownLanguage
import app.cardpulse.android.core.ownershipOf
import app.cardpulse.android.core.subtitle
import app.cardpulse.android.core.variantNames
import app.cardpulse.android.ui.AccentTextButton
import app.cardpulse.android.ui.AppState
import app.cardpulse.android.ui.Banner
import app.cardpulse.android.ui.RemoteImage
import app.cardpulse.android.ui.ScanViewModel

/** Everything the manual-add screen can ask for, so the screen itself holds no logic. */
class ManualAddActions(
    val name: (String) -> Unit = {},
    val number: (String) -> Unit = {},
    /** Look the card up now rather than when typing pauses. */
    val search: () -> Unit = {},
    val select: (String) -> Unit = {},
    val edits: (AddEdits) -> Unit = {},
    val add: () -> Unit = {},
    val byHand: () -> Unit = {},
    val backToLookup: () -> Unit = {},
    val form: (CustomCardForm) -> Unit = {},
    val create: () -> Unit = {},
    val loadSets: () -> Unit = {},
    val close: () -> Unit = {},
)

/** Wires the view model into [ManualAddContent]. */
@Composable
fun ManualAddScreen(
    app: AppState,
    vm: ScanViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** The card ids on the wishlist, and how to put a found card on it or take it off (null leaves the hearts out). */
    wishlistedIds: Set<String> = emptySet(),
    onToggleWishlist: ((CardDto) -> Unit)? = null,
) {
    val state by vm.manualAdd.state.collectAsState()
    // Until the collection has loaded nothing can honestly be called new or a duplicate.
    val index = remember(app.collection, app.collectionLoaded) {
        if (app.collectionLoaded) CollectionIndex(app.collection) else null
    }
    val close = {
        vm.closeManualAdd()
        onClose()
    }
    BackHandler(onBack = close)

    ManualAddContent(
        state = state,
        serverUrl = app.serverUrl,
        currency = app.prefs.currency,
        rateFromEur = app.prefs.rateFromEur,
        ownership = { card -> index.ownershipOf(card) },
        actions = ManualAddActions(
            name = vm.manualAdd::setName,
            number = vm.manualAdd::setNumber,
            search = vm.manualAdd::search,
            select = vm.manualAdd::select,
            edits = vm.manualAdd::setEdits,
            add = vm::addManualCard,
            byHand = vm.manualAdd::startByHand,
            backToLookup = vm.manualAdd::backToLookup,
            form = vm.manualAdd::setForm,
            create = vm.manualAdd::create,
            loadSets = vm.manualAdd::loadSets,
            close = close,
        ),
        modifier = modifier,
        isWishlisted = { card -> card.id in wishlistedIds },
        onToggleWishlist = onToggleWishlist,
    )
}

/**
 * Type a card's name and number and its details fill in: the matching card is found on the server and shown with its
 * picture, set and rarity, ready to be added with a condition, variant and quantity. A card the server doesn't have can
 * be made by hand instead.
 */
@Composable
fun ManualAddContent(
    state: ManualAddState,
    serverUrl: String,
    currency: String,
    rateFromEur: Double,
    ownership: (CardDto) -> Ownership,
    actions: ManualAddActions,
    modifier: Modifier = Modifier,
    isWishlisted: (CardDto) -> Boolean = { false },
    onToggleWishlist: ((CardDto) -> Unit)? = null,
) {
    val selected = state.selected
    // The cursor is ready in the name box when the screen opens and after each add, so cards can be typed one after another.
    val nameFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { nameFocus.requestFocus() } }
    LaunchedEffect(state.lastAdded) { if (state.lastAdded != null) runCatching { nameFocus.requestFocus() } }
    // The text of the price box lives here so the Add button, pinned below, knows when it can't be read.
    var priceText by remember(state.selectedId) { mutableStateOf(MoneyInput.toInput(state.edits.purchasePrice, rateFromEur)) }
    val priceInvalid = selected != null && priceText.isNotBlank() && MoneyInput.toEuros(priceText, rateFromEur) == null

    Column(modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Add a card manually", style = MaterialTheme.typography.titleLarge)
        }

        Column(
            Modifier.weight(1f, fill = true).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.lastAdded?.let { note -> AddedBanner(note) }
            NameAndNumber(state, actions, nameFocus)
            when (state.mode) {
                ManualMode.LOOKUP -> LookupBody(state, serverUrl, ownership, actions, isWishlisted, onToggleWishlist)
                ManualMode.BY_HAND -> ByHandBody(state, actions)
            }
            if (state.mode == ManualMode.LOOKUP && selected != null) {
                AddControls(
                    card = selected,
                    edits = state.edits,
                    currency = currency,
                    rateFromEur = rateFromEur,
                    priceText = priceText,
                    priceInvalid = priceInvalid,
                    onPriceText = { text ->
                        priceText = text
                        if (text.isBlank()) {
                            actions.edits(state.edits.copy(purchasePrice = null))
                        } else {
                            MoneyInput.toEuros(text, rateFromEur)?.let { euros -> actions.edits(state.edits.copy(purchasePrice = euros)) }
                        }
                    },
                    onEdits = actions.edits,
                )
            }
            // Offered once there is something to say "no" to: nothing was found, or a list is showing.
            if (state.mode == ManualMode.LOOKUP && (state.noMatch || (state.results.isNotEmpty() && !state.searching))) {
                AccentTextButton(onClick = actions.byHand) {
                    Text(if (state.noMatch) "Create it by hand" else "Not the one? Create it by hand")
                }
            }
        }

        ConfirmFooter(error = state.error, modifier = Modifier.padding(horizontal = 16.dp)) {
            OutlinedButton(onClick = actions.close, modifier = Modifier.heightIn(min = 56.dp)) { Text("Close") }
            when (state.mode) {
                ManualMode.LOOKUP -> Button(
                    onClick = actions.add,
                    enabled = selected != null && !state.busy && !priceInvalid,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                ) {
                    if (state.busy) BusyDot()
                    if (selected != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Add ×${state.edits.quantity}", style = MaterialTheme.typography.labelLarge)
                            Text(
                                state.edits.details(selected.ownLanguage()),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        Text("Add")
                    }
                }

                ManualMode.BY_HAND -> Button(
                    onClick = actions.create,
                    enabled = state.form.name.isNotBlank() && !state.busy,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                ) {
                    if (state.busy) BusyDot()
                    Text("Create card")
                }
            }
        }
    }
}

/** What was just added, in the green the scanner uses for a new card. */
@Composable
private fun AddedBanner(note: AddedNote) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(ScanColors.newContainer).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = ScanColors.newContent)
        Text(
            "Added ${note.name}${if (note.quantity > 1) " ×${note.quantity}" else ""} to your collection",
            color = ScanColors.newContent,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

@Composable
private fun BusyDot() {
    CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
}

// ---------------------------------------------------------------------------------------------
// The two boxes
// ---------------------------------------------------------------------------------------------

@Composable
private fun NameAndNumber(state: ManualAddState, actions: ManualAddActions, nameFocus: FocusRequester) {
    val focus = LocalFocusManager.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        OutlinedTextField(
            value = state.form.name,
            onValueChange = actions.name,
            modifier = Modifier.weight(1f).focusRequester(nameFocus),
            label = { Text("Card name") },
            placeholder = { Text("e.g. Charizard ex") },
            singleLine = true,
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            value = state.form.number,
            onValueChange = actions.number,
            modifier = Modifier.width(118.dp),
            label = { Text("Number") },
            placeholder = { Text("e.g. 125") },
            singleLine = true,
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                actions.search()
                focus.clearFocus()
            }),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Looking the card up
// ---------------------------------------------------------------------------------------------

@Composable
private fun LookupBody(
    state: ManualAddState,
    serverUrl: String,
    ownership: (CardDto) -> Ownership,
    actions: ManualAddActions,
    isWishlisted: (CardDto) -> Boolean = { false },
    onToggleWishlist: ((CardDto) -> Unit)? = null,
) {
    val focus = LocalFocusManager.current
    val selected = state.selected
    // With a card picked only that card is shown, so its details and the Add button stay close; the rest are one tap away.
    var seeAll by remember(state.resultsFor) { mutableStateOf(false) }

    when {
        !state.lookup.searchable && state.results.isEmpty() -> Text(
            "Type the card's name and number, as printed on it. Its set, rarity and picture fill in by themselves. " +
                "A set code and number works too, like OBF 125.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.searchError != null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Banner(state.searchError, isError = true)
            AccentTextButton(onClick = actions.search) { Text("Try again") }
        }

        state.results.isNotEmpty() -> {
            val showAll = selected == null || seeAll
            val rows = if (selected != null && !seeAll) listOf(selected) else state.results
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selected == null) {
                    Text(
                        if (state.results.size == 1) "1 card found" else "${state.results.size} cards found. Tap the right one.",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                rows.forEach { card ->
                    ResultRow(
                        card = card,
                        selected = card.id == state.selectedId,
                        serverUrl = serverUrl,
                        ownership = ownership(card),
                        onClick = {
                            focus.clearFocus()
                            actions.select(card.id)
                            seeAll = false
                        },
                        wishlisted = isWishlisted(card),
                        onToggleWishlist = onToggleWishlist?.let { toggle -> { toggle(card) } },
                    )
                }
                if (showAll && state.matches > state.results.size) {
                    Text(
                        "Showing the first ${state.results.size} of ${state.matches} matches. Add the number, or more of the name, to narrow it down.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (selected != null) OwnershipBanner(ownership(selected))
                if (selected != null && !seeAll && state.results.size > 1) {
                    AccentTextButton(onClick = { seeAll = true }) { Text("Not this one? See all ${state.results.size} matches") }
                }
            }
        }

        state.noMatch -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("No card found", style = MaterialTheme.typography.titleSmall)
            Text(
                if (state.lookup.number != null) {
                    "Your server's catalogue has no “${state.lookup.name}” numbered ${state.lookup.number}. " +
                        "Check the spelling, or try again without the number. " +
                        "Typing a set code and number in the name box, like OBF 125, works as well."
                } else {
                    "Your server's catalogue has no card named “${state.lookup.name}”. Check the spelling. " +
                        "Typing a set code and number in the name box, like OBF 125, works as well."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("Looking for it…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

}

/** A found card. The one that is picked shows everything the server knows about it. */
@Composable
private fun ResultRow(
    card: CardDto,
    selected: Boolean,
    serverUrl: String,
    ownership: Ownership,
    onClick: () -> Unit,
    wishlisted: Boolean = false,
    onToggleWishlist: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(12.dp)
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RemoteImage(
            ServerUrls.cardImage(serverUrl, card.id),
            card.name,
            Modifier
                .size(width = if (selected) 84.dp else 44.dp, height = if (selected) 117.dp else 62.dp)
                .clip(RoundedCornerShape(5.dp)),
            ContentScale.Fit,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    card.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Tag(CardLanguages.label(card.ownLanguage()), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                if (!selected && ownership is Ownership.Owned) Tag("×${ownership.total} owned", ScanColors.ownedBadge, Color.White)
            }
            Text(
                card.subtitle(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (selected) 3 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (selected) {
                card.facts().takeIf { it.isNotEmpty() }?.let { facts ->
                    Text(facts, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                card.variantNames().takeIf { it.isNotEmpty() }?.let { variants ->
                    Text(
                        "Comes as ${variants.joinToString(" · ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (card.isCustom) {
                    Text("Custom card: it has no market price.", style = MaterialTheme.typography.labelSmall, color = ScanColors.ownedContent)
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (selected) Icon(Icons.Default.CheckCircle, contentDescription = "Picked", tint = MaterialTheme.colorScheme.primary)
            if (onToggleWishlist != null) WishlistToggleButton(wishlisted, onToggleWishlist)
        }
    }
}

@Composable
private fun Tag(text: String, background: Color, content: Color) {
    Text(
        text,
        color = content,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(background).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** What exactly is being added: condition, variant, language, how many, and what was paid. */
@Composable
private fun AddControls(
    card: CardDto,
    edits: AddEdits,
    currency: String,
    rateFromEur: Double,
    priceText: String,
    priceInvalid: Boolean,
    onPriceText: (String) -> Unit,
    onEdits: (AddEdits) -> Unit,
) {
    val own = card.ownLanguage()
    val language = CardLanguages.normalize(edits.lang) ?: own
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChoiceRow("Condition", Conditions.ALL, edits.condition, { it }) { onEdits(edits.copy(condition = it)) }
        ChoiceRow("Variant", Variants.ALL, edits.variant, { it }) { onEdits(edits.copy(variant = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            QuantityStepper(edits.quantity) { onEdits(edits.copy(quantity = it)) }
            OutlinedTextField(
                value = priceText,
                onValueChange = onPriceText,
                modifier = Modifier.weight(1f),
                label = { Text("Paid each ($currency)") },
                placeholder = { Text("optional") },
                isError = priceInvalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
            )
        }
        // A result is already in its own language, so this is the rarely changed choice and comes last.
        // A card made by hand has the one language it was made in.
        if (!card.isCustom) {
            ChoiceRow(
                title = "Language · ${CardLanguages.name(language)}",
                options = CardLanguages.ALL.map { it.code },
                selected = language,
                label = { CardLanguages.label(it) },
            ) { code -> onEdits(edits.copy(lang = code.takeIf { it != own })) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// A card the catalogue doesn't have
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ByHandBody(state: ManualAddState, actions: ManualAddActions) {
    val form = state.form
    val enabled = !state.busy
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Not in the catalogue? Fill in what you know and the card is created on your server, as “Create card manually” " +
                "does on the website. A card made by hand has no market price.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .clickable(enabled = enabled) { actions.form(form.copy(shareAsTemplate = !form.shareAsTemplate)) }
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = form.shareAsTemplate, onCheckedChange = { actions.form(form.copy(shareAsTemplate = it)) }, enabled = enabled)
            Column(Modifier.padding(end = 8.dp)) {
                Text("Share as template", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Other users can find this card and create their own independent copy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SetField(state, actions)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = form.rarity,
                onValueChange = { actions.form(form.copy(rarity = it)) },
                modifier = Modifier.weight(1f),
                label = { Text("Rarity") },
                placeholder = { Text("e.g. Rare Holo") },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = form.hp,
                onValueChange = { actions.form(form.copy(hp = it)) },
                modifier = Modifier.width(118.dp),
                label = { Text("HP") },
                placeholder = { Text("e.g. 200") },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Type", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CardTypes.ALL.forEach { type ->
                    FilterChip(
                        selected = type in form.types,
                        onClick = { actions.form(form.copy(types = if (type in form.types) form.types - type else form.types + type)) },
                        label = { Text(type) },
                        enabled = enabled,
                    )
                }
            }
        }

        OutlinedTextField(
            value = form.artist,
            onValueChange = { actions.form(form.copy(artist = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Artist") },
            placeholder = { Text("e.g. Mitsuhiro Arita") },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = form.imageUrl,
                onValueChange = { actions.form(form.copy(imageUrl = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Image URL") },
                placeholder = { Text("https://...") },
                singleLine = true,
                enabled = enabled,
                isError = form.imageUrl.isNotBlank() && !form.imageUrl.trim().startsWith("https://", ignoreCase = true),
                supportingText = { Text("Optional. It has to start with https://") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            )
            if (form.imageUrl.trim().startsWith("https://", ignoreCase = true)) {
                RemoteImage(form.imageUrl.trim(), "Preview", Modifier.size(width = 80.dp, height = 112.dp).clip(RoundedCornerShape(6.dp)), ContentScale.Crop)
            }
        }

        AccentTextButton(onClick = actions.backToLookup, enabled = enabled) { Text("Back to searching the catalogue") }
    }
}

/** The set: type its id, or pick from the server's list, which narrows as you type. */
@Composable
private fun SetField(state: ManualAddState, actions: ManualAddActions) {
    val form = state.form
    var focused by remember { mutableStateOf(false) }
    val suggestions = remember(state.sets, form.otherSetId, form.set) {
        if (form.set != null) emptyList() else state.sets.orEmpty().matching(form.otherSetId).take(MAX_SET_SUGGESTIONS)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = form.set?.let { "${it.name} (${it.id})" } ?: form.otherSetId,
            onValueChange = { text -> actions.form(form.copy(set = null, otherSetId = text)) },
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            label = { Text("Set") },
            placeholder = { Text("Select or enter a set") },
            singleLine = true,
            enabled = !state.busy,
            trailingIcon = {
                if (form.set != null || form.otherSetId.isNotEmpty()) {
                    IconButton(onClick = { actions.form(form.copy(set = null, otherSetId = "")) }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear the set")
                    }
                }
            },
            supportingText = {
                Text(
                    when {
                        form.set != null -> "Picked from your server's sets."
                        form.otherSetId.isNotBlank() -> "Will be saved with the set id “${form.otherSetId.trim()}”."
                        state.setsLoading -> "Loading your server's sets…"
                        state.setsError != null -> "Couldn't load the sets (${state.setsError}). You can still type a set id, like sv1."
                        else -> "Pick one as you type, or enter a set id like sv1 or custom-set."
                    },
                )
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )
        if (state.setsError != null && state.sets == null) {
            AccentTextButton(onClick = actions.loadSets) { Text("Try loading the sets again") }
        }
        if ((focused || form.otherSetId.isNotEmpty()) && suggestions.isNotEmpty()) {
            SetSuggestions(suggestions) { picked -> actions.form(form.copy(set = picked, otherSetId = "")) }
        }
    }
}

@Composable
private fun SetSuggestions(sets: List<SetDto>, onPick: (SetDto) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        sets.forEach { set ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(set) }.padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(set.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(set.id, set.series).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Tag(CardLanguages.label(set.lang), MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private const val MAX_SET_SUGGESTIONS = 6
