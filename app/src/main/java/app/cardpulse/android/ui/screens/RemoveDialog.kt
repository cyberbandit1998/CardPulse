package app.cardpulse.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cardpulse.android.core.CollectionItemDto
import app.cardpulse.android.core.rowLabel
import app.cardpulse.android.ui.AccentTextButton

/**
 * Asks before a card is taken out of the collection, because the server can't undo it. With several copies in the
 * row there are two choices, one copy or all of them; otherwise it is a plain yes or no. The server's reason is shown
 * when it refuses (a card in a deck or a product can't be removed).
 *
 * [onRemove] starts the removal and reports back: null when it worked, otherwise why it didn't.
 * [onRemoved] closes whatever was showing the card.
 */
@Composable
fun RemoveDialog(
    entry: CollectionItemDto,
    photoGoesToo: Boolean,
    onRemove: (wholeRow: Boolean, done: (String?) -> Unit) -> Unit,
    onRemoved: () -> Unit,
    onDismiss: () -> Unit,
) {
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    fun remove(wholeRow: Boolean) {
        busy = true
        problem = null
        onRemove(wholeRow) { reason ->
            busy = false
            if (reason == null) onRemoved() else problem = reason
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Remove ${entry.card?.name ?: "this card"}?") },
        text = { RemoveChoices(entry, photoGoesToo, busy, problem, onRemove = ::remove) },
        confirmButton = {
            // With several copies the choices are in the body; with one there is only yes or no.
            if (entry.quantity <= 1) {
                TextButton(
                    onClick = { remove(true) },
                    enabled = !busy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Remove") }
            }
        },
        dismissButton = { AccentTextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

/** What the removal dialog says and offers. Kept apart from the dialog window so it can be drawn on its own. */
@Composable
internal fun RemoveChoices(
    entry: CollectionItemDto,
    photoGoesToo: Boolean,
    busy: Boolean,
    problem: String?,
    onRemove: (wholeRow: Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (entry.quantity > 1) {
                "You have ${entry.quantity} copies of this card (${entry.rowLabel()})."
            } else {
                "${entry.rowLabel()}. It will be taken out of your collection."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "This can't be undone." + if (photoGoesToo) " The photo you saved of it goes too." else "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (entry.quantity > 1) {
            OutlinedButton(onClick = { onRemove(false) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Remove 1 copy")
            }
            Button(
                onClick = { onRemove(true) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text("Remove all ${entry.quantity}") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}
