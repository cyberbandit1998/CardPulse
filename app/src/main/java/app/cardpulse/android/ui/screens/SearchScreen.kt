package app.cardpulse.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.cardpulse.android.core.CardDto
import app.cardpulse.android.core.CatalogSearchState
import app.cardpulse.android.core.CollectionIndex
import app.cardpulse.android.core.MoneyFormatter
import app.cardpulse.android.core.SearchScope
import app.cardpulse.android.core.ServerUrls
import app.cardpulse.android.core.SetDto
import app.cardpulse.android.core.artistName
import app.cardpulse.android.core.copies
import app.cardpulse.android.core.ownershipOf
import app.cardpulse.android.core.plainCardId
import app.cardpulse.android.core.priceFor
import app.cardpulse.android.core.searchSubtitle
import app.cardpulse.android.ui.AccentTextButton
import app.cardpulse.android.ui.AppState
import app.cardpulse.android.ui.Banner
import app.cardpulse.android.ui.CARD_ASPECT
import app.cardpulse.android.ui.RemoteImage
import app.cardpulse.android.ui.SearchViewModel
import app.cardpulse.android.ui.WishlistHeart
import app.cardpulse.android.ui.theme.extras

/** Everything the search screen can ask for, so the screen itself holds no logic. */
class SearchActions(
    val text: (String) -> Unit = {},
    val scope: (SearchScope) -> Unit = {},
    /** Search now rather than when typing pauses (the keyboard's search key). */
    val submit: () -> Unit = {},
    /** The end of the list is near: read the next page. */
    val loadMore: () -> Unit = {},
    val retry: () -> Unit = {},
    val close: () -> Unit = {},
)

/** Wires the view model into [SearchContent]. */
@Composable
fun SearchScreen(app: AppState, vm: SearchViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val state by vm.session.state.collectAsState()
    SearchContent(
        state = state,
        app = app,
        actions = SearchActions(
            text = vm.session::setText,
            scope = vm.session::setScope,
            submit = vm.session::submit,
            loadMore = vm.session::loadMore,
            retry = vm.session::retry,
            close = onClose,
        ),
        modifier = modifier,
    )
}

/**
 * One search box over the whole catalogue, with what to look at chosen by chips (All, Pokémon, Artist, Set, Number), and the cards
 * found as rows like the wishlist's: picture, name, set, number, artist, rarity, price, how many are owned, and a heart. A card
 * opens as a page; the artist on it is a way back here, for every card they drew.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchContent(
    state: CatalogSearchState,
    app: AppState,
    actions: SearchActions,
    modifier: Modifier = Modifier,
    /** Raise the keyboard when the screen opens with nothing typed. */
    focusOnOpen: Boolean = true,
) {
    val money = remember(app.prefs.currency, app.prefs.rateFromEur) { MoneyFormatter(app.prefs.currency, app.prefs.rateFromEur) }
    // Until the collection has loaded nothing can honestly be said about what is owned.
    val index = remember(app.collection, app.collectionLoaded) { if (app.collectionLoaded) CollectionIndex(app.collection) else null }
    // Held here, above the page a card opens as, so the list is where it was when the page is closed.
    val listState = rememberLazyListState()
    var openCard by remember { mutableStateOf<CardDto?>(null) }

    val page = openCard
    if (page != null) {
        BackHandler { openCard = null }
        CardDetailsPage(
            card = page,
            sets = state.sets,
            app = app,
            money = money,
            ownership = index.ownershipOf(page),
            onBack = { openCard = null },
            modifier = modifier,
        )
        return
    }
    BackHandler(onBack = actions.close)

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (focusOnOpen && state.text.isEmpty()) focus.requestFocus() }

    // The next page is read when the user comes near the end of the list.
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - NEAR_THE_END
        }
    }
    LaunchedEffect(nearEnd, state.results.size, state.hasMore, state.loadingMore, state.moreError) {
        if (nearEnd && state.hasMore && !state.loadingMore && state.moreError == null && state.results.isNotEmpty()) actions.loadMore()
    }

    Column(modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            SearchField(
                text = state.text,
                hint = state.scope.hint,
                onText = actions.text,
                onSearch = actions.submit,
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchScope.entries.forEach { option ->
                SnugChip(label = option.label, selected = state.scope == option, onClick = { actions.scope(option) })
            }
        }
        if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))

        when {
            state.results.isNotEmpty() -> ResultList(
                state = state,
                app = app,
                money = money,
                index = index,
                listState = listState,
                actions = actions,
                onOpen = { openCard = it },
            )
            state.error != null && !state.searching -> SearchProblem("Couldn't search the catalogue.", state.error, actions.retry)
            state.searching -> Unit // the bar above says so; an empty list under it is not "no match"
            state.noMatch -> NoMatch(state)
            state.query.searchable -> Unit // typing is being waited out: the search starts in a moment
            else -> SearchHint(state)
        }
    }
}

/** How many rows from the end of the list the next page starts to be read. */
private const val NEAR_THE_END = 6

/** The search box: a pill with the lens in front of the text, and a cross to clear it when there is some. */
@Composable
private fun SearchField(text: String, hint: String, onText: (String) -> Unit, onSearch: () -> Unit, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    TextField(
        value = text,
        onValueChange = onText,
        modifier = modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        placeholder = { Text(hint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (text.isNotEmpty()) {
                IconButton(onClick = { onText("") }) { Icon(Icons.Default.Close, contentDescription = "Clear the search") }
            }
        },
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.surface,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            focus.clearFocus()
            onSearch()
        }),
    )
}

// ---------------------------------------------------------------------------------------------
// The cards found
// ---------------------------------------------------------------------------------------------

@Composable
private fun ResultList(
    state: CatalogSearchState,
    app: AppState,
    money: MoneyFormatter,
    index: CollectionIndex?,
    listState: LazyListState,
    actions: SearchActions,
    onOpen: (CardDto) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "count") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (state.matches == 1) "1 card" else "${count(state.matches)} cards",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.note?.let { note ->
                    Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(state.results, key = { it.id }) { card ->
            SearchResultRow(
                card = card,
                sets = state.sets,
                serverUrl = app.serverUrl,
                money = money,
                priceField = app.prefs.priceField,
                copies = index?.ownershipOf(card.plainCardId())?.copies,
                onClick = { onOpen(card) },
            )
        }
        item(key = "more") {
            when {
                state.moreError != null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Banner("Couldn't read more cards. ${state.moreError}", isError = true)
                    AccentTextButton(onClick = actions.retry) { Text("Try again") }
                }
                state.loadingMore -> Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                state.hasMore -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AccentTextButton(onClick = actions.loadMore) { Text("Show more") }
                }
            }
        }
    }
}

private fun count(number: Int): String = java.text.NumberFormat.getIntegerInstance().format(number)

/** The room at the end of a name's line for the heart that sits over the corner of the row. */
private val HeartRoom = 36.dp

/**
 * One card found: its picture, name, set · number · rarity, who drew it (when the catalogue says), how many are owned, what it
 * costs now, and a heart for the wishlist. Pressing the row opens the card ([onClick]); the heart is a button of its own.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchResultRow(
    card: CardDto,
    sets: List<SetDto>,
    serverUrl: String,
    money: MoneyFormatter,
    priceField: String,
    /** Copies owned, in any language; null until the collection has loaded. */
    copies: Int?,
    onClick: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val extras = MaterialTheme.extras
    val subtitle = card.searchSubtitle(sets)
    val artist = card.artistName()
    val price = card.priceFor("Normal", priceField)
    val priceText = if (price > 0.0) money.format(price) else "No price"
    ListCard {
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = "Show details", onClick = onClick)
                    .semantics(mergeDescendants = true) { contentDescription = describeResult(card, subtitle, artist, priceText, copies) }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RemoteImage(
                    url = ServerUrls.cardImage(serverUrl, card.id),
                    description = null,
                    modifier = Modifier.width(56.dp).aspectRatio(CARD_ASPECT).clip(RoundedCornerShape(6.dp)),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        card.name,
                        modifier = Modifier.padding(end = HeartRoom),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle.isNotEmpty()) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    // Left out for a card the catalogue has no artist for, rather than shown blank.
                    if (artist != null) {
                        Text("Illus. $artist", style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (copies != null && copies > 0) Pill("Owned ×$copies", extras.positive.copy(alpha = 0.16f), extras.positive)
                        }
                        Text(
                            priceText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (price > 0.0) FontWeight.Bold else FontWeight.Normal,
                            color = if (price > 0.0) MaterialTheme.colorScheme.onSurface else muted,
                            maxLines = 1,
                        )
                    }
                }
            }
            // A button of its own, so a screen reader finds it apart from the row.
            WishlistHeart(card.id, card.name, Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp))
        }
    }
}

/** What a screen reader says for the whole row. */
private fun describeResult(card: CardDto, subtitle: String, artist: String?, priceText: String, copies: Int?): String = buildString {
    append(card.name)
    if (subtitle.isNotEmpty()) append(", ").append(subtitle)
    if (artist != null) append(", illustrated by ").append(artist)
    append(", ").append(if (priceText == "No price") "no price" else "price $priceText")
    if (copies != null && copies > 0) append(", you own ").append(copies)
}

// ---------------------------------------------------------------------------------------------
// Nothing to list
// ---------------------------------------------------------------------------------------------

/** What to say before anything is typed, or when too little is: what this search can find. */
@Composable
private fun SearchHint(state: CatalogSearchState) {
    val typed = state.text.isNotBlank()
    val (title, text) = when {
        typed -> "Keep typing" to "A few more letters, and the catalogue is searched."
        state.scope == SearchScope.ARTIST -> "Search by artist" to "Type the name of an illustrator to see every card they drew."
        state.scope == SearchScope.SET -> "Search by set" to "Type a set's name or its code to see every card in it."
        state.scope == SearchScope.NUMBER -> "Search by number" to "Type a card number, like 125, TG05 or 125/197. With the size of the set it is that set's card."
        state.scope == SearchScope.POKEMON -> "Search by name" to "Type a Pokémon's name, or any part of a card's name, to see every card with it."
        else -> "Search every card" to "The whole catalogue, not only what you own: by a card's or Pokémon's name, an artist, a set's name or code, a card number like 125/197, or a rarity."
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(40.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun NoMatch(state: CatalogSearchState) {
    val typed = state.query.text
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("No card matches “$typed”", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            state.note ?: if (state.scope == SearchScope.ALL) {
                "Check the spelling, or try fewer words."
            } else {
                "Check the spelling, or choose All to look at names, artists, sets and numbers together."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SearchProblem(title: String, message: String?, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (!message.isNullOrBlank()) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        Button(onClick = onRetry) { Text("Try again") }
    }
}
