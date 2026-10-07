package app.cardpulse.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.cardpulse.android.core.CollectionItemDto
import app.cardpulse.android.core.FriendCard
import app.cardpulse.android.core.FriendCardKind
import app.cardpulse.android.core.FriendCardOrder
import app.cardpulse.android.core.FriendDto
import app.cardpulse.android.core.FriendTab
import app.cardpulse.android.core.FriendView
import app.cardpulse.android.core.FriendsSession
import app.cardpulse.android.core.Loadable
import app.cardpulse.android.core.MoneyFormatter
import app.cardpulse.android.core.TradeMatchDto
import app.cardpulse.android.core.TradeItemDto
import app.cardpulse.android.core.WishlistItemDto
import app.cardpulse.android.core.collectionCards
import app.cardpulse.android.core.copiesByCardId
import app.cardpulse.android.core.entriesLine
import app.cardpulse.android.core.matchCards
import app.cardpulse.android.core.matches
import app.cardpulse.android.core.ordered
import app.cardpulse.android.core.summary
import app.cardpulse.android.core.total
import app.cardpulse.android.core.tradeCards
import app.cardpulse.android.core.wishlistCards
import app.cardpulse.android.ui.AccentTextButton
import app.cardpulse.android.ui.AppState
import app.cardpulse.android.ui.BottomBarOverhang
import app.cardpulse.android.ui.Banner

/**
 * One friend, on a page of their own: what the two of you could trade (their For Trade cards that are on your wishlist, and yours
 * that are on theirs), then their For Trade list, wishlist and collection, as far as they share them with you. Pressing any card
 * opens its details, with the heart that puts it on your own wishlist.
 *
 * Everything shown is what the server sent for this friend: a list they do not share is not here, whatever this screen asks for.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FriendScreen(
    app: AppState,
    friend: FriendDto,
    session: FriendsSession,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val friends by session.state.collectAsState()
    val view = friends.views[friend.id] ?: FriendView()
    var tab by rememberSaveable(friend.id) { mutableStateOf(FriendTab.MATCH) }
    var openCard by remember { mutableStateOf<FriendCard?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    var removeProblem by remember { mutableStateOf<String?>(null) }

    val prefs = app.prefs
    val money = remember(prefs.currency, prefs.rateFromEur) { MoneyFormatter(prefs.currency, prefs.rateFromEur) }
    // How many copies the user owns of each card, once the collection is known: it is how "you own it" is said.
    val mine = remember(app.collection, app.collectionLoaded) { if (app.collectionLoaded) app.collection.copiesByCardId() else null }

    // A list the friend does not share is not asked for: the server would refuse, and the page says so.
    val shared = when (tab) {
        FriendTab.MATCH -> true
        FriendTab.TRADE -> friend.shares.trade
        FriendTab.WISHLIST -> friend.shares.wishlist
        FriendTab.COLLECTION -> friend.shares.collection
    }
    LaunchedEffect(friend.id, tab, shared) { if (shared) session.loadFriend(friend.id, tab) }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Friends") }
            Monogram(friend.username, size = 36.dp)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(friend.username, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    friend.shares.summary(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = {
                    // The friend's own choices may have changed too, so the list of friends is asked for again with the page.
                    session.refresh()
                    session.loadFriend(friend.id, tab, force = true)
                },
            ) { Icon(Icons.Default.Refresh, contentDescription = "Refresh ${friend.username}") }
            IconButton(onClick = { confirmRemove = true }) { Icon(Icons.Default.PersonRemove, contentDescription = "Remove ${friend.username} from your friends") }
        }
        val loading = when (tab) {
            FriendTab.MATCH -> view.match.loading
            FriendTab.TRADE -> view.trade.loading
            FriendTab.WISHLIST -> view.wishlist.loading
            FriendTab.COLLECTION -> view.collection.loading
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        removeProblem?.let { problem -> Banner("Couldn't remove ${friend.username}. $problem", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), isError = true) }
        FlowRow(
            Modifier.fillMaxWidth().selectableGroup().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FriendTab.entries.forEach { option -> SnugChip(label = option.label, selected = tab == option, onClick = { tab = option }) }
        }

        Box(Modifier.weight(1f)) {
            when (tab) {
                FriendTab.MATCH -> MatchPage(
                    page = view.match,
                    friend = friend,
                    app = app,
                    mine = mine,
                    money = money,
                    haveMarks = friends.marks.isNotEmpty(),
                    onOpen = { openCard = it },
                    onRetry = { session.loadFriend(friend.id, FriendTab.MATCH, force = true) },
                )
                FriendTab.TRADE -> PageShell(
                    page = view.trade,
                    shared = friend.shares.trade,
                    friendName = friend.username,
                    what = "For Trade list",
                    onRetry = { session.loadFriend(friend.id, FriendTab.TRADE, force = true) },
                ) { rows: List<TradeItemDto> ->
                    val cards = remember(rows, mine, prefs.priceField) { rows.tradeCards(mine, prefs.priceField) }
                    CardList(cards, app, money, onOpen = { openCard = it }, empty = "${friend.username} hasn't put any cards up for trade.")
                }
                FriendTab.WISHLIST -> PageShell(
                    page = view.wishlist,
                    shared = friend.shares.wishlist,
                    friendName = friend.username,
                    what = "wishlist",
                    onRetry = { session.loadFriend(friend.id, FriendTab.WISHLIST, force = true) },
                ) { rows: List<WishlistItemDto> ->
                    val cards = remember(rows, mine, prefs.priceField) { rows.wishlistCards(mine, prefs.priceField) }
                    CardList(cards, app, money, onOpen = { openCard = it }, empty = "${friend.username}'s wishlist is empty.")
                }
                FriendTab.COLLECTION -> PageShell(
                    page = view.collection,
                    shared = friend.shares.collection,
                    friendName = friend.username,
                    what = "collection",
                    onRetry = { session.loadFriend(friend.id, FriendTab.COLLECTION, force = true) },
                ) { rows: List<CollectionItemDto> ->
                    val cards = remember(rows, mine, prefs.priceField) { rows.collectionCards(mine, prefs.priceField) }
                    CollectionPage(cards, friend.username, app, onOpen = { openCard = it })
                }
            }
        }
    }

    openCard?.let { card -> FriendCardDialog(card, friend.username, app, money, onClose = { openCard = null }) }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${friend.username}?") },
            text = { Text("You will stop seeing each other's lists, at once. You can add each other again later.") },
            confirmButton = {
                AccentTextButton(
                    onClick = {
                        confirmRemove = false
                        removeProblem = null
                        session.removeFriend(friend.id) { problem -> if (problem == null) onBack() else removeProblem = problem }
                    },
                ) { Text("Remove") }
            },
            dismissButton = { AccentTextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

/**
 * What a page of a friend shows for [page], whichever state it is in: a note when the friend does not share the list, a spinner
 * while it comes, a way to try again when it failed, and otherwise [content] with a banner if a refresh failed or some rows could
 * not be read.
 */
@Composable
private fun <T : Any> PageShell(
    page: Loadable<T>,
    shared: Boolean,
    friendName: String,
    what: String,
    onRetry: () -> Unit,
    content: @Composable (T) -> Unit,
) {
    val value = page.value
    when {
        !shared || page.notShared -> NoteScreen {
            InfoNote(
                Icons.Default.Lock,
                "$friendName hasn't shared their $what with you",
                "They choose who can see it in Friends, under Sharing.",
            )
        }
        value != null -> Column(Modifier.fillMaxSize()) {
            page.error?.let { problem -> Banner("Couldn't refresh. $problem", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), isError = true) }
            if (page.unreadable > 0) {
                Banner("${page.unreadable} entries from the server couldn't be read by this app and are hidden.", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), isError = true)
            }
            Box(Modifier.weight(1f)) { content(value) }
        }
        page.error != null && !page.loading -> NoteScreen { ProblemNote("Couldn't load $friendName's $what.", page.error, onRetry = onRetry) }
        else -> NoteScreen { LoadingNote("Loading…") }
    }
}

/** A list of a friend's cards as rows. */
@Composable
private fun CardList(cards: List<FriendCard>, app: AppState, money: MoneyFormatter, onOpen: (FriendCard) -> Unit, empty: String) {
    if (cards.isEmpty()) {
        NoteScreen { InfoNote(Icons.Default.Style, empty, "") }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp + BottomBarOverhang),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "count") {
            Text(
                if (cards.size == 1) "1 card" else "${cards.size} cards",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(cards, key = { it.key }) { card -> FriendCardRow(card, app.serverUrl, money, onClick = { onOpen(card) }) }
    }
}

/** A friend's collection as tiles, with a search box and the order to see them in. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CollectionPage(cards: List<FriendCard>, friendName: String, app: AppState, onOpen: (FriendCard) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var order by rememberSaveable { mutableStateOf(FriendCardOrder.RECENT) }
    val shown = remember(cards, query, order) { cards.filter { it.matches(query) }.ordered(order) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search name, set, number, rarity…") },
                singleLine = true,
            )
            FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FriendCardOrder.entries.forEach { option -> SnugChip(label = option.label, selected = order == option, onClick = { order = option }) }
            }
            Text(shown.entriesLine(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            cards.isEmpty() -> NoteScreen { InfoNote(Icons.Default.Style, "$friendName's collection is empty.", "") }
            shown.isEmpty() -> SmallNote("Nothing matches “${query.trim()}”.", Modifier.padding(horizontal = 12.dp))
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp + BottomBarOverhang),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(shown, key = { it.key }) { card -> FriendTile(card, app.serverUrl, onClick = { onOpen(card) }) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Trade match
// ---------------------------------------------------------------------------------------------

/**
 * Two halves: the cards they have for trade that are on your wishlist, and the cards you have for trade that are on theirs. A half
 * the friend does not share what it needs for is empty and says so, rather than leaving a gap that could mean either.
 */
@Composable
private fun MatchPage(
    page: Loadable<TradeMatchDto>,
    friend: FriendDto,
    app: AppState,
    mine: Map<String, Int>?,
    money: MoneyFormatter,
    haveMarks: Boolean,
    onOpen: (FriendCard) -> Unit,
    onRetry: () -> Unit,
) {
    PageShell(page = page, shared = true, friendName = friend.username, what = "trade match", onRetry = onRetry) { match ->
        val priceField = app.prefs.priceField
        val theirs = remember(match, mine, priceField) { match.theyHaveYouWant.matchCards(FriendCardKind.THEIR_OFFER, mine, priceField) }
        val yours = remember(match, mine, priceField) { match.youHaveTheyWant.matchCards(FriendCardKind.YOUR_OFFER, mine, priceField) }
        val name = friend.username
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp + BottomBarOverhang),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "summary") {
                Text(
                    when {
                        match.total == 0 -> "No matches yet"
                        else -> "${theirs.size} you could get · ${yours.size} you could offer"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item(key = "theirs-title") { MatchTitle("They have · you want", theirs.size, Icons.Default.Favorite) }
            if (theirs.isEmpty()) {
                item(key = "theirs-none") {
                    SmallNote(
                        if (match.canSeeTheirTradeList) {
                            "None of $name's For Trade cards are on your wishlist."
                        } else {
                            "$name hasn't shared their For Trade list with you, so there is nothing to compare."
                        },
                    )
                }
            } else {
                items(theirs, key = { it.key }) { card -> FriendCardRow(card, app.serverUrl, money, onClick = { onOpen(card) }) }
            }
            item(key = "yours-title") { MatchTitle("You have · they want", yours.size, Icons.Default.SwapHoriz) }
            if (yours.isEmpty()) {
                item(key = "yours-none") {
                    SmallNote(
                        when {
                            !match.canSeeTheirWishlist -> "$name hasn't shared their wishlist with you, so there is nothing to compare."
                            !haveMarks -> "You haven't marked any cards for trade yet. Open a card in your Collection and use “For trade”."
                            else -> "None of your For Trade cards are on $name's wishlist."
                        },
                    )
                }
            } else {
                items(yours, key = { it.key }) { card -> FriendCardRow(card, app.serverUrl, money, onClick = { onOpen(card) }) }
            }
        }
    }
}

@Composable
private fun MatchTitle(title: String, count: Int, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(end = 0.dp))
        Text("$title ($count)", style = MaterialTheme.typography.titleMedium)
    }
}
