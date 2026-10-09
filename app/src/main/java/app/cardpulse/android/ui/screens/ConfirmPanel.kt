package app.cardpulse.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.cardpulse.android.core.AddEdits
import app.cardpulse.android.core.CardLanguages
import app.cardpulse.android.core.Conditions
import app.cardpulse.android.core.DAILY_LIMIT_MESSAGE
import app.cardpulse.android.core.MoneyInput
import app.cardpulse.android.core.Ownership
import app.cardpulse.android.core.ScanEntry
import app.cardpulse.android.core.ServerUrls
import app.cardpulse.android.core.TileState
import app.cardpulse.android.core.Variants
import app.cardpulse.android.core.details
import app.cardpulse.android.core.headline
import app.cardpulse.android.core.isDailyLimitMessage
import app.cardpulse.android.core.isInFlight
import app.cardpulse.android.core.progressNote
import app.cardpulse.android.core.recognizedSummary
import app.cardpulse.android.core.scannedLanguage
import app.cardpulse.android.core.subtitle
import app.cardpulse.android.core.tileState
import app.cardpulse.android.ui.AccentTextButton
import app.cardpulse.android.ui.RemoteImage
import coil3.compose.AsyncImage

/**
 * Everything to check before a scanned card goes into the collection: which card it is, whether the collection
 * already has it, and the condition, variant, language, quantity and price it will be added with.
 * Cards that need help instead (nothing matched, the read failed, the photo never arrived) get that instead.
 */
@Composable
fun ConfirmPanel(
    entry: ScanEntry,
    serverUrl: String,
    ownership: Ownership,
    currency: String,
    rateFromEur: Double,
    /** How many other results are waiting for confirmation. */
    othersWaiting: Int,
    /** How many other photos are still being sent or read. */
    othersInFlight: Int,
    onSelectCandidate: (Int) -> Unit,
    onEdits: (AddEdits) -> Unit,
    onAdd: () -> Unit,
    onSkip: () -> Unit,
    /** Reads the photo again, or sends it again, whichever the card needs. */
    onRetry: () -> Unit,
    /** Gives up on a photo that is still being sent or read. */
    onCancel: () -> Unit,
    onCancelAll: () -> Unit,
    /** Opens the screen where the card is typed in, for a photo the scanner could not match or read. */
    onAddManually: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 16.dp,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Check before adding", style = MaterialTheme.typography.titleMedium)
                    if (othersWaiting > 0) {
                        Text(
                            "$othersWaiting more waiting",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
            }
            when (entry.tileState()) {
                TileState.READY -> ReadyContent(entry, serverUrl, ownership, currency, rateFromEur, onSelectCandidate, onEdits, onAdd, onSkip)
                else -> AttentionContent(entry, serverUrl, othersInFlight, onRetry, onSkip, onCancel, onCancelAll, onAddManually)
            }
        }
    }
}

/** The form (which scrolls when it is taller than the panel) with Skip and Add pinned underneath it. */
@Composable
private fun ColumnScope.ReadyContent(
    entry: ScanEntry,
    serverUrl: String,
    ownership: Ownership,
    currency: String,
    rateFromEur: Double,
    onSelectCandidate: (Int) -> Unit,
    onEdits: (AddEdits) -> Unit,
    onAdd: () -> Unit,
    onSkip: () -> Unit,
) {
    val item = entry.item ?: return
    val jobId = entry.jobId ?: return
    val match = entry.match ?: return
    val edits = entry.edits ?: AddEdits()
    val scanned = match.scannedLanguage()
    val language = CardLanguages.normalize(edits.lang) ?: scanned

    var priceText by remember(entry.id) { mutableStateOf(MoneyInput.toInput(edits.purchasePrice, rateFromEur)) }
    val priceInvalid = priceText.isNotBlank() && MoneyInput.toEuros(priceText, rateFromEur) == null

    Column(
        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Which card is it?
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PhotoColumn("Your photo") { YourPhoto(entry, serverUrl, Modifier.size(width = 84.dp, height = 117.dp)) }
            PhotoColumn("Match") {
                RemoteImage(
                    ServerUrls.candidateImage(serverUrl, jobId, item.id, entry.candidate),
                    match.name,
                    Modifier.size(width = 84.dp, height = 117.dp).clip(RoundedCornerShape(6.dp)),
                    ContentScale.Fit,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(match.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    match.subtitle(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                item.recognizedSummary()?.let {
                    Text(
                        "Scanner read: $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (match.printedTotalMismatch) {
                    Text(
                        "The set size on your photo doesn't match this card's set. Check it carefully.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // Do I already have it?
        OwnershipBanner(ownership)

        val matches = item.matches
        if (matches.size > 1) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Not the right card? Pick another match", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    matches.forEachIndexed { index, candidate ->
                        val selected = index == entry.candidate
                        val shape = RoundedCornerShape(8.dp)
                        Column(
                            Modifier
                                .width(58.dp)
                                .clip(shape)
                                .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
                                .clickable { onSelectCandidate(index) }
                                .padding(2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            RemoteImage(
                                ServerUrls.candidateImage(serverUrl, jobId, item.id, index),
                                candidate.name,
                                Modifier.size(width = 54.dp, height = 75.dp).clip(RoundedCornerShape(5.dp)),
                                ContentScale.Fit,
                            )
                            Text(
                                CardLanguages.label(candidate.scannedLanguage()),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        // What exactly am I adding?
        ChoiceRow("Condition", Conditions.ALL, edits.condition, { it }) { onEdits(edits.copy(condition = it)) }
        ChoiceRow("Variant", Variants.ALL, edits.variant, { it }) { onEdits(edits.copy(variant = it)) }
        ChoiceRow(
            title = "Language · ${CardLanguages.name(language)}",
            options = CardLanguages.ALL.map { it.code },
            selected = language,
            label = { CardLanguages.label(it) },
        ) { code -> onEdits(edits.copy(lang = code.takeIf { it != scanned })) }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            QuantityStepper(edits.quantity) { onEdits(edits.copy(quantity = it)) }
            OutlinedTextField(
                value = priceText,
                onValueChange = { text ->
                    priceText = text
                    if (text.isBlank()) {
                        onEdits(edits.copy(purchasePrice = null))
                    } else {
                        MoneyInput.toEuros(text, rateFromEur)?.let { euros -> onEdits(edits.copy(purchasePrice = euros)) }
                    }
                },
                modifier = Modifier.weight(1f),
                label = { Text("Paid each ($currency)") },
                placeholder = { Text("optional") },
                isError = priceInvalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
            )
        }
    }

    ConfirmFooter(error = entry.error) {
        OutlinedButton(onClick = onSkip, enabled = !entry.busy, modifier = Modifier.heightIn(min = 56.dp)) { Text("Skip") }
        Button(onClick = onAdd, enabled = !entry.busy && !priceInvalid, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
            if (entry.busy) {
                CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Add ×${edits.quantity}", style = MaterialTheme.typography.labelLarge)
                Text(edits.details(scanned), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Stays in view under the scrolling form, so the main action never has to be hunted for. */
@Composable
internal fun ConfirmFooter(error: String?, modifier: Modifier = Modifier, buttons: @Composable RowScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(Modifier.padding(top = 4.dp))
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Row(
            Modifier.padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = buttons,
        )
    }
}

/** A picture with a caption underneath. */
@Composable
private fun PhotoColumn(caption: String, picture: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        picture()
        Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The photo that was taken: the copy on the phone while there is one, otherwise the server's. */
@Composable
private fun YourPhoto(entry: ScanEntry, serverUrl: String, modifier: Modifier) {
    val shape = RoundedCornerShape(6.dp)
    val photo = entry.photo
    val jobId = entry.jobId
    val item = entry.item
    when {
        photo != null -> AsyncImage(
            model = photo,
            contentDescription = "Your photo",
            modifier = modifier.clip(shape),
            contentScale = ContentScale.Fit,
        )

        jobId != null && item != null -> RemoteImage(
            ServerUrls.scanItemImage(serverUrl, jobId, item.id),
            "Your photo",
            modifier.clip(shape),
            ContentScale.Fit,
        )

        else -> Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

/** "New to your collection", or how many copies there already are and in what form. */
@Composable
fun OwnershipBanner(ownership: Ownership, modifier: Modifier = Modifier) {
    val container: Color
    val content: Color
    val badgeText: String?
    val badgeColor: Color
    when (ownership) {
        Ownership.Unknown -> {
            container = MaterialTheme.colorScheme.surfaceVariant
            content = MaterialTheme.colorScheme.onSurfaceVariant
            badgeText = null
            badgeColor = Color.Transparent
        }
        Ownership.New -> {
            container = ScanColors.newContainer
            content = ScanColors.newContent
            badgeText = "NEW"
            badgeColor = ScanColors.newBadge
        }
        is Ownership.Owned -> {
            container = ScanColors.ownedContainer
            content = ScanColors.ownedContent
            badgeText = "DUPLICATE"
            badgeColor = ScanColors.ownedBadge
        }
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(container)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (badgeText != null) {
            Text(
                badgeText,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(badgeColor)
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(ownership.headline(), color = content, style = MaterialTheme.typography.titleSmall)
            if (ownership is Ownership.Owned) {
                ownership.lines.take(MAX_OWNED_LINES).forEach {
                    Text(it.describe(), color = content, style = MaterialTheme.typography.bodySmall)
                }
                val more = ownership.lines.size - MAX_OWNED_LINES
                if (more > 0) Text("+$more more", color = content, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private const val MAX_OWNED_LINES = 5

/** A title and a scrolling row of chips, one of which is selected. */
@Composable
internal fun <T> ChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
            }
        }
    }
}

@Composable
internal fun QuantityStepper(quantity: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange((quantity - 1).coerceAtLeast(1)) }, enabled = quantity > 1) {
            Icon(Icons.Default.Remove, contentDescription = "Fewer")
        }
        Text("$quantity", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(32.dp), maxLines = 1)
        IconButton(onClick = { onChange((quantity + 1).coerceAtMost(999)) }, enabled = quantity < 999) {
            Icon(Icons.Default.Add, contentDescription = "More")
        }
    }
}

/**
 * For a card that can't be added yet: say why, and offer the way forward. A photo that is still being sent or read
 * says what it is waiting for and can always be cancelled; the server won't let it be skipped before it is read.
 */
@Composable
private fun ColumnScope.AttentionContent(
    entry: ScanEntry,
    serverUrl: String,
    othersInFlight: Int,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
    onCancel: () -> Unit,
    onCancelAll: () -> Unit,
    onAddManually: () -> Unit,
) {
    val state = entry.tileState()
    val inFlight = entry.isInFlight
    val progress = if (inFlight) entry.progressNote() else null
    Row(
        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        YourPhoto(entry, serverUrl, Modifier.size(width = 84.dp, height = 117.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                progress?.title ?: when (state) {
                    TileState.NO_MATCH -> "The scanner couldn't match this card"
                    TileState.FAILED ->
                        if (isDailyLimitMessage(entry.item?.error)) DAILY_LIMIT_MESSAGE else "The scanner couldn't read this card"
                    TileState.SEND_FAILED ->
                        if (isDailyLimitMessage(entry.uploadError)) DAILY_LIMIT_MESSAGE else "This photo hasn't reached your server"
                    else -> "Still working on this one…"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                progress?.detail ?: when (state) {
                    TileState.NO_MATCH -> "Try again with the card flat and well lit, or type its name and number in yourself."
                    TileState.FAILED -> entry.item?.error ?: "The scanner gave up on this photo."
                    TileState.SEND_FAILED -> entry.uploadError ?: "The upload failed."
                    else -> "It will appear here as soon as it is ready."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (entry.slow) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (inFlight) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
    ConfirmFooter(error = entry.error) {
        if (inFlight) {
            // A scan that looks stuck makes cancelling the obvious thing to do.
            if (entry.slow) {
                Button(onClick = onCancel) { Text("Cancel scan") }
            } else {
                OutlinedButton(onClick = onCancel) { Text("Cancel scan") }
            }
            if (othersInFlight > 0) {
                Spacer(Modifier.weight(1f))
                AccentTextButton(onClick = onCancelAll) { Text("Cancel all ${othersInFlight + 1}") }
            }
        } else {
            if (state == TileState.FAILED || state == TileState.SEND_FAILED) {
                Button(onClick = onRetry, enabled = !entry.busy) { Text(if (state == TileState.FAILED) "Try again" else "Send again") }
            }
            OutlinedButton(onClick = onSkip, enabled = !entry.busy) {
                Text(if (state == TileState.SEND_FAILED) "Discard" else "Skip")
            }
            // A card the scanner can't place can still be added by typing it in.
            if (state == TileState.NO_MATCH || state == TileState.FAILED) {
                AccentTextButton(onClick = onAddManually) { Text("Type it in") }
            }
        }
    }
}
