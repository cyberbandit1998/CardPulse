package app.cardpulse.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cardpulse.android.core.CollectionItemDto
import app.cardpulse.android.core.MoneyFormatter
import app.cardpulse.android.core.SearchScope
import app.cardpulse.android.core.cardsMissingCost
import app.cardpulse.android.core.costCoverage
import app.cardpulse.android.core.setProgress
import app.cardpulse.android.core.topCard
import app.cardpulse.android.core.toChartPoints
import app.cardpulse.android.ui.AccentTextButton
import app.cardpulse.android.ui.AppState
import app.cardpulse.android.ui.Banner
import app.cardpulse.android.ui.BottomBarOverhang
import app.cardpulse.android.ui.gainColor
import java.text.NumberFormat

/** How many cards and sets the Home screen lists; "See all" opens the rest. */
private const val HOME_VALUABLE_SHOWN = 4
private const val HOME_SETS_SHOWN = 3

/** 1,234 in the phone's own style. */
private fun count(number: Int): String = NumberFormat.getIntegerInstance().format(number)

/**
 * The collection at a glance: what it is worth and how that has moved, a few counts, and short lists of the cards you
 * added lately, the ones worth the most, and how far along your sets are. Each list has a "See all".
 */
@Composable
fun HomeScreen(
    state: AppState,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenPortfolio: () -> Unit = {},
    /** "See all" under Recently added, and the Cards tile: the whole collection, newest first. */
    onOpenCollection: () -> Unit = {},
    onSeeAllValuable: () -> Unit = {},
    /** "See all" under Set progress, and the Sets tile: the Sets tab, which lists every set. */
    onOpenSets: () -> Unit = {},
    /** A set was tapped: open its checklist. Gets the set's id (such as "sv3_en"). */
    onOpenSet: (String) -> Unit = {},
    onRemove: (item: CollectionItemDto, wholeRow: Boolean, done: (String?) -> Unit) -> Unit = { _, _, done -> done(null) },
    /** The heart in the header: the wishlist. Without it the header has no such button. */
    onOpenWishlist: (() -> Unit)? = null,
    /** The row that opens Friends and trading, when given: what it says under its title, and how many requests are waiting. */
    friendsNote: String = "",
    friendsRequests: Int = 0,
    onOpenFriends: (() -> Unit)? = null,
    /** The search bar under the header, when given: opens the search of the whole catalogue, for what the user chose to look at. */
    onOpenSearch: ((SearchScope) -> Unit)? = null,
) {
    val money = remember(state.prefs.currency, state.prefs.rateFromEur) {
        MoneyFormatter(state.prefs.currency, state.prefs.rateFromEur)
    }
    val dashboard = state.dashboard
    val byId = remember(state.collection) { state.collection.associateBy { it.id } }
    val coverage = remember(state.collection) { state.collection.costCoverage() }
    val cardsMissingCost = remember(state.collection) { state.collection.cardsMissingCost() }
    val sets = remember(state.collection) { state.collection.setProgress() }
    val history = remember(dashboard) { dashboard?.valueHistory.orEmpty().toChartPoints() }
    // The card worth the most, found in the collection from what the server says. Until the collection has loaded there is
    // nothing to open, and the tile is not pressable.
    val best = remember(dashboard) { dashboard?.topCard() }
    val topEntry = best?.let { byId[it.collectionItemId] }
    var openItem by remember { mutableStateOf<CollectionItemDto?>(null) }
    var explainCost by remember { mutableStateOf(false) }
    // Everything sits 16 dp in from the edges except the carousel, which runs to them so cards slide out under them.
    val inset = Modifier.padding(horizontal = 16.dp)
    // The way into Friends sits under the numbers, or under the note when nothing has loaded.
    val friendsRow: LazyListScope.() -> Unit = {
        if (onOpenFriends != null) {
            item(key = "friends") { HomeFriendsRow(friendsNote, friendsRequests, onOpenFriends, inset) }
        }
    }

    LazyColumn(
        modifier = modifier,
        // The room at the end is for the round camera button, which rises above the bottom bar.
        contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp + BottomBarOverhang),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Column {
                HomeHeader(
                    connected = !state.offline,
                    onRefresh = onRefresh,
                    onOpenSettings = onOpenSettings,
                    modifier = inset,
                    onOpenWishlist = onOpenWishlist,
                )
                if (onOpenSearch != null) {
                    HomeSearchBar(onOpen = { onOpenSearch(SearchScope.ALL) }, onPickScope = onOpenSearch, modifier = inset.padding(top = 12.dp))
                }
                if (state.dashboardLoading || state.collectionLoading) {
                    LinearProgressIndicator(inset.fillMaxWidth().padding(top = 8.dp))
                }
            }
        }

        if (state.noLogin) {
            item {
                Banner(
                    "This server has no sign-in (single-user mode): anyone who can reach it is the administrator. " +
                        "Turn on Multi-User Mode in PokéCollector before exposing it to the internet.",
                    modifier = inset,
                    isError = true,
                )
            }
        }

        if (dashboard != null) {
            item {
                val pnl = dashboard.unrealizedPnl
                SummaryCard(
                    value = money.format(dashboard.totalValue),
                    gain = money.signed(pnl),
                    gainLabel = if (pnl <= -0.005) "loss" else "gain",
                    gainColor = gainColor(pnl),
                    costBasis = money.format(dashboard.totalCost),
                    history = history,
                    // Only said once the whole collection is known, so it is never a count of what has loaded so far.
                    cardsMissingCost = if (state.collectionLoaded) cardsMissingCost else 0,
                    onOpenPortfolio = onOpenPortfolio,
                    onExplainCost = { explainCost = true },
                    modifier = inset,
                )
            }

            item {
                Row(inset.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HomeStat(
                        Icons.Default.Style, count(dashboard.totalCards), "Cards", Modifier.weight(1f),
                        onClick = onOpenCollection, actionLabel = "Show all cards",
                    )
                    HomeStat(
                        Icons.Default.Layers, count(dashboard.ownedSets), "Sets", Modifier.weight(1f),
                        onClick = onOpenSets, actionLabel = "Show your sets",
                    )
                    HomeStat(
                        Icons.Default.EmojiEvents, best?.let { money.format(it.totalValue) } ?: "—", "Top Card", Modifier.weight(1f),
                        onClick = topEntry?.let { entry -> { openItem = entry } }, actionLabel = "Show the most valuable card",
                    )
                }
            }

            friendsRow()

            val recents = dashboard.recentAdditions.mapNotNull { recent -> byId[recent.collectionItemId] }
            if (recents.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeader("Recently added", inset, onSeeAll = onOpenCollection)
                        RecentCarousel(recents, state, onOpen = { openItem = it })
                    }
                }
            }

            val tops = dashboard.topCards.take(HOME_VALUABLE_SHOWN)
            if (tops.isNotEmpty()) {
                item {
                    Column(inset, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeader("Most valuable", onSeeAll = onSeeAllValuable)
                        ListCard {
                            tops.forEachIndexed { index, top ->
                                if (index > 0) RowDivider()
                                val entry = byId[top.collectionItemId]
                                ValuableRow(top, entry, state, money, onClick = entry?.let { found -> { openItem = found } })
                            }
                        }
                    }
                }
            }

            val shownSets = sets.take(HOME_SETS_SHOWN)
            if (shownSets.isNotEmpty()) {
                item {
                    Column(inset, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeader("Set progress", onSeeAll = onOpenSets)
                        ListCard {
                            shownSets.forEachIndexed { index, set ->
                                if (index > 0) RowDivider(startIndent = 76.dp)
                                SetProgressRow(set, state.serverUrl, onClick = { onOpenSet(set.id) })
                            }
                        }
                    }
                }
            }
        } else if (!state.dashboardLoading) {
            item {
                Text(
                    "Nothing loaded yet. Tap the refresh button at the top.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = inset,
                )
            }
            friendsRow()
        }
    }

    if (explainCost) {
        AlertDialog(
            onDismissRequest = { explainCost = false },
            title = { Text("Cost basis is missing") },
            text = {
                Text(
                    "Only ${coverage.withCost} of ${coverage.total} collection entries have a purchase price, " +
                        "and PokéCollector counts a missing price as zero cost, so this gain looks larger " +
                        "than it really is. Add purchase prices in PokéCollector for a true figure.",
                )
            },
            confirmButton = { AccentTextButton(onClick = { explainCost = false }) { Text("OK") } },
        )
    }

    openItem?.let { entry -> ItemDialog(entry, state, onRemove = onRemove, onClose = { openItem = null }) }
}
