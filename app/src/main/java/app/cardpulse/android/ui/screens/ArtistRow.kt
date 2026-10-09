package app.cardpulse.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.cardpulse.android.ui.LocalCardSearch

/**
 * The artist line of a card's details. The name is a way into the search for every card they drew ([onSearch] is told, so a
 * dialog can close as the search opens). A card the catalogue has no artist for has no such line at all.
 */
@Composable
internal fun ArtistRow(artist: String?, modifier: Modifier = Modifier, onSearch: () -> Unit = {}) {
    val name = artist?.trim()?.takeIf { it.isNotEmpty() } ?: return
    val search = LocalCardSearch.current
    if (!search.enabled) {
        DetailRow("Artist", name)
        return
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = 40.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Artist", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // The accent colour and an underline say it can be pressed; the label says what it does.
        Text(
            name,
            modifier = Modifier
                .padding(start = 16.dp)
                .weight(1f, fill = false)
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClickLabel = "Show every card by $name") {
                    search.byArtist(name)
                    onSearch()
                }
                .heightIn(min = 40.dp)
                .padding(horizontal = 8.dp, vertical = 9.dp),
            style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.Underline),
            color = MaterialTheme.colorScheme.secondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
