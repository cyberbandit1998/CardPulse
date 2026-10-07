package app.cardpulse.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.cardpulse.android.core.CollectionItemDto
import app.cardpulse.android.core.ShareLevel
import app.cardpulse.android.core.TradeSlot
import app.cardpulse.android.core.isCustomCard

/**
 * What the screens can do with the user's For Trade marks, handed to all of them at once ([LocalTrade]) so that a card's details
 * can offer copies for trade without every screen in between passing a callback along. Until the marks have loaded ([ready]),
 * and on a server that has no Friends, nothing is offered: nothing honest can be said about a card before then.
 */
@Immutable
class TradeControls(
    val ready: Boolean = false,
    /** The copies marked For Trade by collection row id, counting a change that is on its way as done. */
    val marks: Map<Int, Int> = emptyMap(),
    /** Who can see the For Trade list, so that the screens can say so plainly. */
    val visibleTo: ShareLevel = ShareLevel.PRIVATE,
    /** Marks [quantity] copies of a collection row For Trade (0 takes the row off). */
    val set: (itemId: Int, quantity: Int) -> Unit = { _, _ -> },
) {
    fun offered(itemId: Int): Int = marks[itemId] ?: 0

    companion object {
        /** No marks: no control anywhere. What a screen gets when nothing provides any. */
        val None = TradeControls()
    }
}

val LocalTrade = staticCompositionLocalOf { TradeControls.None }

/** What the For Trade control says about who can see the copies on offer. */
internal fun ShareLevel.tradeVisibility(): String = when (this) {
    ShareLevel.PRIVATE -> "Only you can see your For Trade list for now. Choose who can in Friends, under Sharing."
    ShareLevel.FRIENDS -> "Your friends can see these copies."
    ShareLevel.PUBLIC -> "Everyone on this server can see these copies."
}

/** A small label on a card's picture when some of its copies are for trade: a swap arrow and how many. Nothing for any other card. */
@Composable
fun TradeBadge(itemId: Int, held: Int, modifier: Modifier = Modifier) {
    val trade = LocalTrade.current
    if (!trade.ready) return
    val offered = TradeSlot(held, trade.offered(itemId)).shown
    if (offered <= 0) return
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$offered for trade" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(14.dp))
        Text("$offered", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * The "For trade" line of a card's details: how many of the copies in this row are on offer, with a button to offer one fewer and
 * one more. Nothing is offered until the user says so, and never more than the row holds. Not shown for a card made by hand,
 * which a friend could not look up. [showVisibility] adds a line saying who can see the copies on offer.
 */
@Composable
fun TradeSection(entry: CollectionItemDto, modifier: Modifier = Modifier, showVisibility: Boolean = true) {
    val trade = LocalTrade.current
    if (!trade.ready || entry.isCustomCard() || entry.quantity <= 0) return
    val slot = TradeSlot(held = entry.quantity, offered = trade.offered(entry.id))
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("For trade", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(slot.summary, style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedIconButton(onClick = { trade.set(entry.id, slot.fewer()) }, enabled = slot.canOfferFewer, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Remove, contentDescription = "Offer one fewer copy for trade")
            }
            OutlinedIconButton(onClick = { trade.set(entry.id, slot.more()) }, enabled = slot.canOfferMore, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Add, contentDescription = "Offer one more copy for trade")
            }
        }
        if (showVisibility) {
            Text(trade.visibleTo.tradeVisibility(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
