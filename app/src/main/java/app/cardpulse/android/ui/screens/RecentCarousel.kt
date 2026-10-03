package app.cardpulse.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.cardpulse.android.core.CollectionItemDto
import app.cardpulse.android.core.parseServerInstant
import app.cardpulse.android.core.rowLabel
import app.cardpulse.android.core.subtitle
import app.cardpulse.android.ui.AppState
import app.cardpulse.android.ui.CARD_ASPECT
import app.cardpulse.android.ui.CardArt
import app.cardpulse.android.ui.formatDate

/**
 * How wide a card is in the carousel: 1.5 inches at Android's reference density (160 dp to the inch). A card keeps its
 * real shape (63 x 88 mm, nothing is cropped or stretched), so at this width it is about 2.1 inches tall.
 */
internal val CAROUSEL_CARD_WIDTH = 240.dp

/** A card may take at most this share of the screen's width, so the next one always peeks in and shows there is more. */
private const val MAX_CARD_SHARE = 0.66f

/** Above this many cards the dots would run off the screen, so they are left out. */
private const val MAX_DOTS = 12

/**
 * Cards you added lately as large pictures that swipe sideways, one at a time, with the next one peeking in at the
 * edge. Under each: its name, set and number, how it is kept, and when it was added.
 */
@Composable
fun RecentCarousel(cards: List<CollectionItemDto>, state: AppState, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cardWidth = minOf(CAROUSEL_CARD_WIDTH, maxWidth * MAX_CARD_SHARE)
        val side = 16.dp
        val gap = 12.dp
        val listState = rememberLazyListState()
        val pageWidthPx = with(LocalDensity.current) { (cardWidth + gap).toPx() }
        // The card the list has settled on, or is about to when it is scrolled more than half way to the next.
        val page by remember(cards.size, pageWidthPx) {
            derivedStateOf {
                val first = listState.firstVisibleItemIndex
                val past = listState.firstVisibleItemScrollOffset > pageWidthPx / 2
                (if (past) first + 1 else first).coerceIn(0, (cards.size - 1).coerceAtLeast(0))
            }
        }
        Column {
            LazyRow(
                state = listState,
                // Room after the last card, so that card can come to the start edge like every other.
                contentPadding = PaddingValues(start = side, end = maxOf(side, maxWidth - cardWidth - side)),
                horizontalArrangement = Arrangement.spacedBy(gap),
                flingBehavior = rememberSnapFlingBehavior(listState),
            ) {
                items(cards, key = { it.id }) { entry -> RecentCard(entry, state, Modifier.width(cardWidth)) }
            }
            if (cards.size in 2..MAX_DOTS) PageDots(count = cards.size, selected = page)
        }
    }
}

@Composable
private fun RecentCard(entry: CollectionItemDto, state: AppState, modifier: Modifier = Modifier) {
    Column(modifier) {
        Box {
            CardArt(entry, state.serverUrl, state.prefs, Modifier.fillMaxWidth().aspectRatio(CARD_ASPECT), large = true)
            if (entry.quantity > 1) {
                Text(
                    "×${entry.quantity}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Text(
            entry.card?.name.orEmpty(),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        entry.card?.subtitle()?.takeIf { it.isNotBlank() }?.let { details ->
            Text(
                details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            listOfNotNull(entry.rowLabel(), formatDate(parseServerInstant(entry.addedAt)).ifBlank { null }).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One dot per card, the one in view a little bigger and in the accent colour. */
@Composable
private fun PageDots(count: Int, selected: Int, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val active = index == selected
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(if (active) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    ),
            )
        }
    }
}
