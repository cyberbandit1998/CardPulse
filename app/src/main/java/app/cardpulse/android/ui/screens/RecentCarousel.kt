package app.cardpulse.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.cardpulse.android.core.CollectionItemDto
import app.cardpulse.android.core.setName
import app.cardpulse.android.ui.AppState
import app.cardpulse.android.ui.CARD_ASPECT
import app.cardpulse.android.ui.CardArt

/** How many cards are in view at once: two whole ones and most of a third, so it is plain there is more to swipe to. */
private const val CARDS_IN_VIEW = 2.7f

private val MIN_CARD_WIDTH = 96.dp
private val MAX_CARD_WIDTH = 150.dp
private val SIDE = 16.dp
private val GAP = 12.dp

/**
 * Cards you added lately, small, in a row that scrolls sideways: the card, its name, and its set and number. Tapping one
 * calls [onOpen]. The row runs to the screen's edges, so cards slide out under them rather than stopping short.
 */
@Composable
fun RecentCarousel(
    cards: List<CollectionItemDto>,
    state: AppState,
    modifier: Modifier = Modifier,
    onOpen: (CollectionItemDto) -> Unit = {},
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cardWidth = ((maxWidth - SIDE) / CARDS_IN_VIEW - GAP).coerceIn(MIN_CARD_WIDTH, MAX_CARD_WIDTH)
        LazyRow(
            contentPadding = PaddingValues(horizontal = SIDE),
            horizontalArrangement = Arrangement.spacedBy(GAP),
        ) {
            items(cards, key = { it.id }) { entry -> RecentCard(entry, state, Modifier.width(cardWidth), onClick = { onOpen(entry) }) }
        }
    }
}

@Composable
private fun RecentCard(entry: CollectionItemDto, state: AppState, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick)) {
        Box {
            CardArt(entry, state.serverUrl, state.prefs, Modifier.fillMaxWidth().aspectRatio(CARD_ASPECT), corner = 12.dp)
            if (entry.quantity > 1) {
                Text(
                    "×${entry.quantity}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            entry.card?.name.orEmpty(),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        entry.card?.let { card ->
            val set = card.setName()?.takeIf { it.isNotBlank() }
            val number = card.number?.takeIf { it.isNotBlank() }?.let { "#$it" }
            if (set != null || number != null) {
                // The set's name gives way first when the line is too long: the number is what tells two cards apart.
                Row {
                    if (set != null) {
                        Text(
                            set,
                            Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (number != null) {
                        Text(
                            if (set != null) " · $number" else number,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}
