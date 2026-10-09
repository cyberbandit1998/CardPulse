package app.cardpulse.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.cardpulse.android.core.CardDto
import app.cardpulse.android.core.MoneyFormatter
import app.cardpulse.android.core.Ownership
import app.cardpulse.android.core.ServerUrls
import app.cardpulse.android.core.SetDto
import app.cardpulse.android.core.printedNumber
import app.cardpulse.android.core.priceFor
import app.cardpulse.android.core.setName
import app.cardpulse.android.ui.AppState
import app.cardpulse.android.ui.CARD_ASPECT
import app.cardpulse.android.ui.RemoteImage
import app.cardpulse.android.ui.WishlistToggleButton

/**
 * A card of the catalogue opened, as a page under a back arrow, whether or not it is owned: its picture, the heart that puts it on
 * the wishlist, and its set, number, rarity, who drew it, what it costs now and how many copies the collection holds. The artist's
 * name is a way into the search for every card they drew ([ArtistRow]); the page closes as that search opens.
 */
@Composable
internal fun CardDetailsPage(
    card: CardDto,
    sets: List<SetDto>,
    app: AppState,
    money: MoneyFormatter,
    ownership: Ownership,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val price = card.priceFor("Normal", app.prefs.priceField)
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the results") }
            Text(card.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RemoteImage(
                url = ServerUrls.cardImage(app.serverUrl, card.id, large = true),
                description = card.name,
                modifier = Modifier
                    .fillMaxWidth(0.58f)
                    .aspectRatio(CARD_ASPECT)
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(8.dp)),
            )
            WishlistToggleButton(card.id)
            DetailRow("Set", card.setName() ?: "—")
            DetailRow("Number", card.printedNumber(sets) ?: "—")
            DetailRow("Rarity", card.rarity?.takeIf { it.isNotBlank() } ?: "—")
            ArtistRow(card.artist, onSearch = onBack)
            DetailRow("Price now", if (price > 0.0) money.format(price) else "No price")
            when (ownership) {
                Ownership.Unknown -> DetailRow("You own", "—")
                Ownership.New -> DetailRow("You own", "none")
                is Ownership.Owned -> {
                    DetailRow("You own", "×${ownership.total}")
                    // The copies by condition, variant and language, under the total.
                    ownership.lines.forEach { line ->
                        Text(
                            line.describe(),
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                        )
                    }
                }
            }
        }
    }
}
