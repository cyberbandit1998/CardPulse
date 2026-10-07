package app.cardpulse.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.cardpulse.android.core.FriendCard
import app.cardpulse.android.core.FriendTag
import app.cardpulse.android.core.MoneyFormatter
import app.cardpulse.android.core.ServerUrls
import app.cardpulse.android.core.TagTone
import app.cardpulse.android.core.detailLines
import app.cardpulse.android.ui.AccentTextButton
import app.cardpulse.android.ui.AppState
import app.cardpulse.android.ui.CARD_ASPECT
import app.cardpulse.android.ui.CardArt
import app.cardpulse.android.ui.RemoteImage
import app.cardpulse.android.ui.WishlistHeart
import app.cardpulse.android.ui.WishlistToggleButton
import app.cardpulse.android.ui.theme.extras

// The pieces the Friends screens share: a person's badge, a card of a friend's list as a row or a tile, the card's details, and the
// notes for a page that is loading, failed, empty or not shared.

/** A round badge with a person's first letter, in place of a picture the server does not give. */
@Composable
internal fun Monogram(name: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f))
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).uppercase().ifEmpty { "?" },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

/** A tag under a card's name, in the colour of good news when it is one. */
@Composable
internal fun FriendTagPill(tag: FriendTag) {
    val extras = MaterialTheme.extras
    when (tag.tone) {
        TagTone.GOOD -> Pill(tag.text, extras.positive.copy(alpha = 0.16f), extras.positive)
        TagTone.NEUTRAL -> Pill(tag.text, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * One card of a friend's list as a row: its picture, name, set · number · rarity, the copy it is, tags (how many, how many for trade,
 * whether the user owns it) and what it costs now, with a heart to put it on the user's own wishlist. Pressing the card opens its
 * details ([onClick]); the heart is a button of its own.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FriendCardRow(card: FriendCard, serverUrl: String, money: MoneyFormatter, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    ListCard(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .clickable(role = Role.Button, onClickLabel = "Show details", onClick = onClick)
                    .semantics(mergeDescendants = true) { contentDescription = card.describe(money) }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RemoteImage(
                    url = ServerUrls.cardImage(serverUrl, card.cardId),
                    description = null,
                    modifier = Modifier.width(56.dp).aspectRatio(CARD_ASPECT).clip(RoundedCornerShape(6.dp)),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(card.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(card.subtitle, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (card.copyText.isNotEmpty()) Text(card.copyText, style = MaterialTheme.typography.labelMedium, color = muted, maxLines = 1)
                    if (card.tags.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            card.tags.forEach { FriendTagPill(it) }
                        }
                    }
                }
                Text(
                    if (card.hasPrice) money.format(card.priceEur) else "No price",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (card.hasPrice) FontWeight.Bold else FontWeight.Normal,
                    color = if (card.hasPrice) MaterialTheme.colorScheme.onSurface else muted,
                    maxLines = 1,
                )
            }
            WishlistHeart(card.cardId, card.name)
            Spacer(Modifier.width(4.dp))
        }
    }
}

/**
 * One card of a friend's collection as a tile in the grid: the picture with how many they have, how many are for trade and a heart
 * for the user's wishlist, and the name and set under it. Pressing the picture or the name opens the card's details ([onClick]).
 */
@Composable
internal fun FriendTile(card: FriendCard, serverUrl: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier) {
        Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
            Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box {
                    RemoteImage(
                        url = ServerUrls.cardImage(serverUrl, card.cardId),
                        description = card.name,
                        modifier = Modifier.fillMaxWidth().aspectRatio(CARD_ASPECT).clip(RoundedCornerShape(8.dp)),
                    )
                    if ((card.owned ?: 0) > 1) {
                        Text(
                            "×${card.owned}",
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
                    if ((card.offered ?: 0) > 0) {
                        Row(
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(4.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                .semantics(mergeDescendants = true) { contentDescription = "${card.offered} for trade" },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(14.dp))
                            Text("${card.offered}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Text(card.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(card.setName.ifBlank { null }, card.numberText.ifBlank { null }).filterNotNull().joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // A button of its own, so a screen reader finds it apart from the tile.
        WishlistHeart(card.cardId, card.name, Modifier.align(Alignment.TopStart).padding(top = 2.dp, start = 2.dp), onPicture = true)
    }
}

/**
 * A card of a friend's list opened: the same details as the user's own cards, with what the friend has or wants and what the user
 * owns, and the heart to put it on the user's wishlist. There is nothing to change here: it is the friend's card.
 */
@Composable
internal fun FriendCardDialog(card: FriendCard, owner: String, state: AppState, money: MoneyFormatter, onClose: () -> Unit) {
    val item = remember(card) { card.asCollectionItem() }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { AccentTextButton(onClick = onClose) { Text("Close") } },
        title = { Text(card.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CardArt(item, state.serverUrl, state.prefs, Modifier.fillMaxWidth().aspectRatio(CARD_ASPECT), large = true)
                WishlistToggleButton(card.cardId)
                card.detailLines(owner, money).forEach { (label, value) -> DetailRow(label, value) }
            }
        },
    )
}

// ---------------------------------------------------------------------------------------------
// Notes for a page
// ---------------------------------------------------------------------------------------------

@Composable
internal fun LoadingNote(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CircularProgressIndicator()
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** Why a page could not be had, with a way to ask again. */
@Composable
internal fun ProblemNote(title: String, message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (message.isNotBlank()) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        if (onRetry != null) Button(onClick = onRetry) { Text("Try again") }
    }
}

/** A page with nothing to show, or that is not shared: says why in a line or two. */
@Composable
internal fun InfoNote(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(40.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (text.isNotBlank()) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

/** A line of explanation inside a list, where a whole page of note would be too much. */
@Composable
internal fun SmallNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A full-screen holder for the pages that have nothing but a note. */
@Composable
internal fun NoteScreen(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) { content() }
}
