package app.cardpulse.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.cardpulse.android.core.Ownership
import app.cardpulse.android.core.ScanEntry
import app.cardpulse.android.core.ScanOutcome
import app.cardpulse.android.core.ServerUrls
import app.cardpulse.android.core.TileState
import app.cardpulse.android.core.isHandled
import app.cardpulse.android.core.tileState
import app.cardpulse.android.ui.RemoteImage
import coil3.compose.AsyncImage

/** Colours that mean "new card" and "you already have this" wherever they appear. */
internal object ScanColors {
    val newBadge = Color(0xFF2E7D32)
    val ownedBadge = Color(0xFFB26A00)
    val newContainer = Color(0xFF12301D)
    val newContent = Color(0xFF9BE3AE)
    val ownedContainer = Color(0xFF3A2900)
    val ownedContent = Color(0xFFFFD27A)
    val stuck = Color(0xFFFFC107)
}

/**
 * The scans of this session as a row of small tiles, newest first. A tile shows what is happening to its photo
 * and, once it has been read, the matched card with a badge saying whether it is new to the collection.
 */
@Composable
fun ResultTray(
    entries: List<ScanEntry>,
    serverUrl: String,
    ownership: (ScanEntry) -> Ownership,
    openId: Long?,
    onOpen: (ScanEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(entries, key = { it.id }) { entry ->
            if (entry.isHandled) {
                HandledPill(entry)
            } else {
                ScanTile(entry, serverUrl, ownership(entry), selected = entry.id == openId, onClick = { onOpen(entry) })
            }
        }
    }
}

@Composable
fun ScanTile(
    entry: ScanEntry,
    serverUrl: String,
    ownership: Ownership,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = entry.tileState()
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .size(width = 58.dp, height = 82.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color(0x33FFFFFF),
                shape = shape,
            )
            .clickable(onClick = onClick),
    ) {
        TileImage(entry, serverUrl, state)
        when (state) {
            TileState.SENDING, TileState.READING, TileState.WAITING -> {
                Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                }
                // Nothing has changed for a long time: tap the tile to cancel it.
                if (entry.slow) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = "Taking a long time",
                        tint = ScanColors.stuck,
                        modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(16.dp),
                    )
                }
            }

            TileState.READY -> OwnershipBadge(ownership, Modifier.align(Alignment.TopStart).padding(3.dp))

            TileState.NO_MATCH, TileState.FAILED, TileState.SEND_FAILED ->
                Box(Modifier.fillMaxSize().background(Color(0xB35A0000)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Warning, contentDescription = "Needs attention", tint = Color.White)
                }

            TileState.ADDED, TileState.SKIPPED -> Unit // handled cards are shown as pills, not tiles
        }
    }
}

/** The matched card once there is one; otherwise the photo that was taken. */
@Composable
private fun TileImage(entry: ScanEntry, serverUrl: String, state: TileState) {
    val jobId = entry.jobId
    val item = entry.item
    when {
        state == TileState.READY && jobId != null && item != null ->
            RemoteImage(
                ServerUrls.candidateImage(serverUrl, jobId, item.id, entry.candidate),
                entry.match?.name,
                Modifier.fillMaxSize(),
                ContentScale.Crop,
            )

        entry.photo != null ->
            AsyncImage(
                model = entry.photo,
                contentDescription = "Photo",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )

        jobId != null && item != null ->
            RemoteImage(ServerUrls.scanItemImage(serverUrl, jobId, item.id), "Photo", Modifier.fillMaxSize(), ContentScale.Crop)

        else -> Box(Modifier.fillMaxSize())
    }
}

/** "NEW" or "×3": whether the card is already in the collection, at a glance. Nothing while that isn't known. */
@Composable
fun OwnershipBadge(ownership: Ownership, modifier: Modifier = Modifier) {
    val (text, color) = when (ownership) {
        Ownership.Unknown -> return
        Ownership.New -> "NEW" to ScanColors.newBadge
        is Ownership.Owned -> "×${ownership.total}" to ScanColors.ownedBadge
    }
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** What happened to a card that has been dealt with, small enough not to crowd the tray. */
@Composable
fun HandledPill(entry: ScanEntry, modifier: Modifier = Modifier) {
    val text = when (val outcome = entry.outcome) {
        is ScanOutcome.Added -> "✓ ${outcome.name}"
        ScanOutcome.Skipped, null -> "Skipped"
    }
    Text(
        text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier
            .widthIn(max = 120.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x40FFFFFF))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
