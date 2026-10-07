package app.cardpulse.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cardpulse.android.core.AddFriendMode
import app.cardpulse.android.core.FriendDto
import app.cardpulse.android.core.FriendRequestDto
import app.cardpulse.android.core.FriendsAvailability
import app.cardpulse.android.core.FriendsSession
import app.cardpulse.android.core.FriendsState
import app.cardpulse.android.core.MoneyFormatter
import app.cardpulse.android.core.ShareLevel
import app.cardpulse.android.core.ShareSection
import app.cardpulse.android.core.TradeSlot
import app.cardpulse.android.core.copiesForTrade
import app.cardpulse.android.core.inviteCode
import app.cardpulse.android.core.markedFor
import app.cardpulse.android.core.requestFor
import app.cardpulse.android.core.sharing
import app.cardpulse.android.core.summary
import app.cardpulse.android.core.supported
import app.cardpulse.android.core.CollectionItemDto
import app.cardpulse.android.core.isCustomCard
import app.cardpulse.android.core.rowLabel
import app.cardpulse.android.core.setName
import app.cardpulse.android.ui.AccentTextButton
import app.cardpulse.android.ui.AppState
import app.cardpulse.android.ui.Banner
import app.cardpulse.android.ui.CARD_ASPECT
import app.cardpulse.android.ui.RemoteImage
import app.cardpulse.android.ui.TradeSection
import app.cardpulse.android.ui.theme.extras
import app.cardpulse.android.core.ServerUrls

private enum class FriendsTabKind(val label: String) {
    FRIENDS("Friends"),
    REQUESTS("Requests"),
    SHARING("Sharing"),
}

/**
 * Friends and trading: the people the user has added, the requests waiting, and what the user shares and with whom. It talks to
 * the server only through [session]; the server decides who may see what. A friend opens on a page of their own, and so does the
 * list of cards the user has put up for trade. [onCopyCode] puts the user's invite code on the clipboard.
 *
 * On a server that has not had the Friends update, or that has no sign-in, the screen says so instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(
    app: AppState,
    session: FriendsSession,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onCopyCode: (String) -> Unit = {},
) {
    val friends by session.state.collectAsState()
    LaunchedEffect(Unit) { session.start() }

    var openFriend by rememberSaveable { mutableStateOf<Int?>(null) }
    var showTrade by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(FriendsTabKind.FRIENDS) }

    // A friend takes the screen, as a page. Back returns to the list, and nothing of the friend is kept.
    openFriend?.let { id ->
        val friend = friends.friends.firstOrNull { it.id == id }
        BackHandler {
            session.forgetFriend(id)
            openFriend = null
        }
        when {
            friend != null -> FriendScreen(
                app = app,
                friend = friend,
                session = session,
                onBack = {
                    session.forgetFriend(id)
                    openFriend = null
                },
                modifier = modifier,
            )
            // Not found once the friends are known: they ended the friendship, or it was a leftover from before. Back to the list.
            friends.loaded -> LaunchedEffect(Unit) { openFriend = null }
            else -> Column(modifier.fillMaxSize()) { LoadingNote("Loading your friends…") }
        }
        return
    }

    if (showTrade) {
        BackHandler { showTrade = false }
        MyTradePage(
            app = app,
            friends = friends,
            onBack = { showTrade = false },
            onChangeSharing = {
                showTrade = false
                tab = FriendsTabKind.SHARING
            },
            modifier = modifier,
        )
        return
    }

    Column(modifier.fillMaxSize()) {
        FriendsHeader(friends, onBack = onBack, onRefresh = { session.refresh() })
        if (friends.checking || friends.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        when (friends.availability) {
            FriendsAvailability.UNKNOWN -> LoadingNote("Checking your server…")
            FriendsAvailability.MISSING -> NoteScreen { ServerNeedsUpdate(onCheckAgain = { session.start(force = true) }) }
            FriendsAvailability.NEEDS_MULTI_USER -> NoteScreen {
                InfoNote(
                    Icons.Default.Lock,
                    "Friends needs multi-user mode",
                    friends.availabilityNote ?: "With multi-user mode off nobody has to sign in, so nothing could be kept private.",
                )
            }
            FriendsAvailability.FAILED -> NoteScreen {
                ProblemNote("Couldn't reach your server.", friends.availabilityNote.orEmpty(), onRetry = { session.start(force = true) })
            }
            FriendsAvailability.SUPPORTED -> {
                PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                    FriendsTabKind.entries.forEach { kind ->
                        Tab(
                            selected = tab == kind,
                            onClick = { tab = kind },
                            text = {
                                Text(
                                    if (kind == FriendsTabKind.REQUESTS && friends.incoming.isNotEmpty()) "Requests (${friends.incoming.size})" else kind.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
                when (tab) {
                    FriendsTabKind.FRIENDS -> FriendsListTab(friends, session, onOpen = { openFriend = it.id })
                    FriendsTabKind.REQUESTS -> RequestsTab(friends, session)
                    FriendsTabKind.SHARING -> SharingTab(app, friends, session, onCopyCode = onCopyCode, onOpenTrade = { showTrade = true })
                }
            }
        }
    }
}

@Composable
private fun FriendsHeader(friends: FriendsState, onBack: () -> Unit, onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Column(Modifier.weight(1f)) {
            Text("Friends", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (friends.supported && friends.loaded) {
                Text(
                    buildString {
                        append(friends.friends.size).append(if (friends.friends.size == 1) " friend" else " friends")
                        if (friends.incoming.isNotEmpty()) {
                            append(" · ").append(friends.incoming.size).append(if (friends.incoming.size == 1) " request" else " requests")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onRefresh, enabled = !friends.checking && !friends.loading) {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh your friends")
        }
    }
}

/** What to say on a server that has not had the update: what it is, what is missing, and that nothing else is affected. */
@Composable
private fun ServerNeedsUpdate(onCheckAgain: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Default.People, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.width(44.dp).aspectRatio(1f))
        Text("Your server needs the Friends update", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            "Friends and trading need a small update to your PokéCollector server, so that the server itself can keep each person's lists " +
                "private. The update, and the steps to install it, are in the CardPulse project on GitHub, in the folder called server.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            "Everything else in CardPulse works without it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(onClick = onCheckAgain) { Text("Check again") }
    }
}

// ---------------------------------------------------------------------------------------------
// Friends
// ---------------------------------------------------------------------------------------------

@Composable
private fun FriendsListTab(friends: FriendsState, session: FriendsSession, onOpen: (FriendDto) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "add") { AddFriendCard(friends, session) }
        friends.error?.let { problem ->
            item(key = "problem") { Banner("Couldn't load your friends. $problem", isError = true) }
        }
        if (friends.loaded && friends.friends.isEmpty()) {
            item(key = "none") {
                InfoNote(
                    Icons.Default.People,
                    "No friends yet",
                    "Add someone by their username or invite code. When they accept, you can compare wishlists and see what each of you has to trade.",
                )
            }
        }
        items(friends.friends, key = { it.id }) { friend -> FriendRow(friend, onClick = { onOpen(friend) }) }
    }
}

@Composable
private fun AddFriendCard(friends: FriendsState, session: FriendsSession) {
    var mode by rememberSaveable { mutableStateOf(AddFriendMode.USERNAME) }
    var text by rememberSaveable { mutableStateOf("") }
    val canSend = mode.requestFor(text) != null && !friends.sending
    fun send() {
        if (canSend) session.sendRequest(mode, text) { sent -> if (sent) text = "" }
    }
    ListCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Add a friend", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AddFriendMode.entries.forEach { option ->
                    SnugChip(
                        label = option.label,
                        selected = mode == option,
                        onClick = {
                            mode = option
                            text = ""
                            session.dismissNotice()
                        },
                    )
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    if (friends.notice != null) session.dismissNotice()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(mode.field) },
                supportingText = { Text(mode.hint) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = if (mode == AddFriendMode.CODE) KeyboardCapitalization.Characters else KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
            Button(onClick = { send() }, enabled = canSend, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (friends.sending) "Sending…" else "Send request")
            }
            friends.notice?.let { notice ->
                Text(
                    notice.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (notice.isError) MaterialTheme.colorScheme.error else MaterialTheme.extras.positive,
                )
            }
        }
    }
}

@Composable
private fun FriendRow(friend: FriendDto, onClick: () -> Unit) {
    ListCard {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = "Open ${friend.username}", onClick = onClick)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Monogram(friend.username)
            Column(Modifier.weight(1f)) {
                Text(friend.username, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    friend.shares.summary(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Chevron()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Requests
// ---------------------------------------------------------------------------------------------

@Composable
private fun RequestsTab(friends: FriendsState, session: FriendsSession) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        friends.requestError?.let { problem -> item(key = "problem") { Banner(problem, isError = true) } }
        friends.error?.let { problem -> item(key = "loadproblem") { Banner("Couldn't load your requests. $problem", isError = true) } }
        if (friends.incoming.isEmpty() && friends.outgoing.isEmpty()) {
            item(key = "none") {
                InfoNote(
                    Icons.Default.PersonAdd,
                    "No requests waiting",
                    "When someone asks to be your friend it appears here, and you choose whether to accept. A request shares nothing by itself.",
                )
            }
        }
        if (friends.incoming.isNotEmpty()) {
            item(key = "in-title") { Text("Asking to be your friend", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp)) }
            items(friends.incoming, key = { "in-${it.id}" }) { request ->
                IncomingRow(request, busy = request.id in friends.answering, onAccept = { session.accept(request.id) }, onDecline = { session.decline(request.id) })
            }
        }
        if (friends.outgoing.isNotEmpty()) {
            item(key = "out-title") { Text("Waiting for an answer", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            items(friends.outgoing, key = { "out-${it.id}" }) { request ->
                OutgoingRow(request, busy = request.id in friends.answering, onCancel = { session.cancel(request.id) })
            }
        }
    }
}

@Composable
private fun IncomingRow(request: FriendRequestDto, busy: Boolean, onAccept: () -> Unit, onDecline: () -> Unit) {
    val name = request.user.username
    ListCard {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Monogram(name)
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Wants to be your friend", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onAccept,
                    enabled = !busy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "Accept $name" },
                ) { Text("Accept") }
                OutlinedButton(
                    onClick = onDecline,
                    enabled = !busy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "Decline $name" },
                ) { Text("Decline") }
            }
        }
    }
}

@Composable
private fun OutgoingRow(request: FriendRequestDto, busy: Boolean, onCancel: () -> Unit) {
    val name = request.user.username
    ListCard {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Monogram(name)
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Waiting for an answer", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onCancel, enabled = !busy, modifier = Modifier.semantics { contentDescription = "Cancel the request to $name" }) { Text("Cancel") }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Sharing
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SharingTab(
    app: AppState,
    friends: FriendsState,
    session: FriendsSession,
    onCopyCode: (String) -> Unit,
    onOpenTrade: () -> Unit,
) {
    val sharing = friends.sharing
    val marked = remember(friends.marks, friends.marking, app.collection) {
        val shown = friends.marks + friends.marking.filterValues { it > 0 }
        shown.copiesForTrade(app.collection)
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "code") { InviteCodeCard(friends, session, onCopyCode) }
        item(key = "who") {
            ListCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Who can see what", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Nothing is shared until you choose. Each list is set on its own, and you can change your mind at any time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ShareSection.entries.forEach { section ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(section.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(section.contents, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ShareLevel.entries.forEach { level ->
                                    SnugChip(
                                        label = level.label,
                                        selected = sharing[section] == level,
                                        onClick = { session.setSharing(section, level) },
                                        enabled = !friends.sharingBusy,
                                        modifier = Modifier.semantics { contentDescription = "${section.title}: ${level.label}" },
                                    )
                                }
                            }
                            Text(sharing[section].summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    friends.sharingError?.let { problem -> Text("Couldn't save that. $problem", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                    Text(
                        "Your server checks this for every request, so a friend's phone can only ever see what you have shared with them. " +
                            "Friends never see what you paid, your price alerts or your photos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item(key = "trade") {
            ListCard {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClickLabel = "Open your For Trade list", onClick = onOpenTrade)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Column(Modifier.weight(1f)) {
                        Text("Your For Trade list", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                friends.marksError != null && !friends.marksLoaded -> "Couldn't load it. Tap to try again."
                                !friends.marksLoaded -> "Loading…"
                                marked == 0 -> "Nothing is marked for trade"
                                marked == 1 -> "1 card is marked for trade"
                                else -> "$marked cards are marked for trade"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Chevron()
                }
            }
        }
    }
}

@Composable
private fun InviteCodeCard(friends: FriendsState, session: FriendsSession, onCopyCode: (String) -> Unit) {
    val code = friends.inviteCode
    var copied by remember(code) { mutableStateOf(false) }
    var confirmNew by remember { mutableStateOf(false) }
    ListCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your invite code", style = MaterialTheme.typography.titleMedium)
            Text(
                code.ifEmpty { "…" },
                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 2.sp),
                modifier = Modifier.semantics { contentDescription = "Your invite code is ${code.replace("-", " dash ").toList().joinToString(" ")}" },
            )
            Text(
                "A friend can use this code instead of your username. They still have to ask, and you still have to accept.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        onCopyCode(code)
                        copied = true
                    },
                    enabled = code.isNotEmpty(),
                ) { Text(if (copied) "Copied" else "Copy") }
                AccentTextButton(onClick = { confirmNew = !confirmNew }, enabled = !friends.codeBusy && code.isNotEmpty()) { Text("New code…") }
            }
            if (confirmNew) {
                Text(
                    "The old code stops working at once. Friends you already have stay your friends.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            confirmNew = false
                            session.newInviteCode()
                        },
                        enabled = !friends.codeBusy,
                    ) { Text("Make a new code") }
                    TextButton(onClick = { confirmNew = false }) { Text("Keep this one") }
                }
            }
            friends.codeError?.let { problem -> Text("Couldn't make a new code. $problem", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The user's own For Trade list
// ---------------------------------------------------------------------------------------------

/**
 * The cards the user has marked For Trade, with how many copies of each and the buttons to offer fewer or more. A card is added
 * from its details in the Collection; nothing is ever added here for the user.
 */
@Composable
private fun MyTradePage(
    app: AppState,
    friends: FriendsState,
    onBack: () -> Unit,
    onChangeSharing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val money = remember(app.prefs.currency, app.prefs.rateFromEur) { MoneyFormatter(app.prefs.currency, app.prefs.rateFromEur) }
    val rows = remember(app.collection, friends.marks, friends.marking) {
        app.collection
            .filter { !it.isCustomCard() && TradeSlot(it.quantity, friends.markedFor(it.id)).shown > 0 }
            .sortedBy { it.card?.name?.lowercase().orEmpty() }
    }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Friends") }
            Text("Your For Trade list", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "who") {
                ListCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            when (friends.sharing.trade) {
                                ShareLevel.PRIVATE -> "Only you can see this list for now."
                                ShareLevel.FRIENDS -> "Your friends can see this list."
                                ShareLevel.PUBLIC -> "Everyone on this server can see this list."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "A card is added here from its details in your Collection. Duplicates are never added for you.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        AccentTextButton(onClick = onChangeSharing) { Text("Change who can see it") }
                    }
                }
            }
            when {
                !friends.marksLoaded -> item(key = "loading") { LoadingNote("Loading your For Trade list…") }
                !app.collectionLoaded -> item(key = "loadingcollection") { LoadingNote("Loading your collection…") }
                rows.isEmpty() -> item(key = "none") {
                    InfoNote(
                        Icons.Default.SwapHoriz,
                        "Nothing is marked for trade",
                        "Open a card in your Collection and use “For trade” to say how many copies you would trade.",
                    )
                }
                else -> items(rows, key = { it.id }) { row -> OwnTradeRow(row, app.serverUrl, money) }
            }
        }
    }
}

@Composable
private fun OwnTradeRow(item: CollectionItemDto, serverUrl: String, money: MoneyFormatter) {
    ListCard {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RemoteImage(
                    url = ServerUrls.cardImage(serverUrl, item.cardId ?: item.card?.id.orEmpty()),
                    description = null,
                    modifier = Modifier.width(48.dp).aspectRatio(CARD_ASPECT).clip(RoundedCornerShape(6.dp)),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.card?.name.orEmpty(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(item.card?.setName(), item.card?.number?.let { "#$it" }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(item.rowLabel(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            TradeSection(item, showVisibility = false)
        }
    }
}
