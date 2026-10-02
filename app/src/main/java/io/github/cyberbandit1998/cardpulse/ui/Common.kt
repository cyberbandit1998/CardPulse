package io.github.cyberbandit1998.cardpulse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.cyberbandit1998.cardpulse.core.ArtSource
import io.github.cyberbandit1998.cardpulse.core.CollectionItemDto
import io.github.cyberbandit1998.cardpulse.core.DisplayPrefs
import io.github.cyberbandit1998.cardpulse.core.ServerUrls
import io.github.cyberbandit1998.cardpulse.core.defaultArtSource
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.Instant

/** A playing card is 63 x 88 mm. */
const val CARD_ASPECT = 63f / 88f

private val shortDate: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

fun formatDate(instant: Instant?): String =
    instant?.let { shortDate.format(it.atZone(ZoneId.systemDefault())) }.orEmpty()

/** An image from a URL, or a flat placeholder while it loads, fails, or when there is no URL. */
@Composable
fun RemoteImage(
    url: String?,
    description: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
    if (url.isNullOrBlank()) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    } else {
        AsyncImage(
            model = url,
            contentDescription = description,
            modifier = modifier,
            contentScale = contentScale,
            placeholder = placeholder,
            error = placeholder,
        )
    }
}

/** Where to load a collection item's picture from: official artwork, or the owner's own photo. */
fun artUrl(
    item: CollectionItemDto,
    server: String,
    source: ArtSource,
    large: Boolean = false,
): String = when (source) {
    ArtSource.OWN_PHOTO -> ServerUrls.ownPhoto(server, item.id)
    ArtSource.OFFICIAL -> ServerUrls.cardImage(server, item.cardId ?: item.card?.id.orEmpty(), large)
}

@Composable
fun CardArt(
    item: CollectionItemDto,
    server: String,
    prefs: DisplayPrefs,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    source: ArtSource = defaultArtSource(item, prefs.preferOwnPhotos),
) {
    RemoteImage(
        url = artUrl(item, server, source, large),
        description = item.card?.name,
        modifier = modifier.clip(RoundedCornerShape(8.dp)),
    )
}

@Composable
fun Banner(text: String, modifier: Modifier = Modifier, isError: Boolean = false, onDismiss: (() -> Unit)? = null) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Row(
            Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (onDismiss != null) TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    ElevatedCard(modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** Green for gains and the error colour for losses, so a glance tells you which way it went. */
@Composable
fun gainColor(amount: Double): Color = when {
    amount > 0.004 -> Color(0xFF4CAF50)
    amount < -0.004 -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
