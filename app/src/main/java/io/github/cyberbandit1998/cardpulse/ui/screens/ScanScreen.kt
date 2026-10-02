package io.github.cyberbandit1998.cardpulse.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import io.github.cyberbandit1998.cardpulse.camera.CameraPreview
import io.github.cyberbandit1998.cardpulse.camera.CaptureController
import io.github.cyberbandit1998.cardpulse.core.AddEdits
import io.github.cyberbandit1998.cardpulse.core.Conditions
import io.github.cyberbandit1998.cardpulse.core.ItemPhase
import io.github.cyberbandit1998.cardpulse.core.MoneyFormatter
import io.github.cyberbandit1998.cardpulse.core.MoneyInput
import io.github.cyberbandit1998.cardpulse.core.ScanItemDto
import io.github.cyberbandit1998.cardpulse.core.ScanJobDto
import io.github.cyberbandit1998.cardpulse.core.ServerUrls
import io.github.cyberbandit1998.cardpulse.core.Variants
import io.github.cyberbandit1998.cardpulse.core.phase
import io.github.cyberbandit1998.cardpulse.core.recognizedSummary
import io.github.cyberbandit1998.cardpulse.core.retryNote
import io.github.cyberbandit1998.cardpulse.core.subtitle
import io.github.cyberbandit1998.cardpulse.ui.AppState
import io.github.cyberbandit1998.cardpulse.ui.Banner
import io.github.cyberbandit1998.cardpulse.ui.ItemOutcome
import io.github.cyberbandit1998.cardpulse.ui.RemoteImage
import io.github.cyberbandit1998.cardpulse.ui.ScanStage
import io.github.cyberbandit1998.cardpulse.ui.ScanState
import io.github.cyberbandit1998.cardpulse.ui.ScanViewModel
import kotlinx.coroutines.delay
import java.time.Instant

private const val MAX_PHOTOS = 50

@Composable
fun ScanScreen(app: AppState, vm: ScanViewModel, modifier: Modifier = Modifier) {
    val scan by vm.state.collectAsState()
    when (scan.stage) {
        ScanStage.HOME -> ScanHome(scan, vm, modifier)
        ScanStage.CAPTURE -> CaptureView(scan, vm, modifier)
        ScanStage.REVIEW -> ReviewView(app, scan, vm, modifier)
    }
}

// ---------------------------------------------------------------------------------------------
// Home: start scanning, send waiting photos, pick up scans still on the server
// ---------------------------------------------------------------------------------------------

@Composable
private fun ScanHome(scan: ScanState, vm: ScanViewModel, modifier: Modifier) {
    LaunchedEffect(Unit) { vm.refreshInbox() }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text("Scan cards", style = MaterialTheme.typography.headlineMedium) }
        scan.message?.let { item { Banner(it, isError = true, onDismiss = vm::dismissMessage) } }

        item {
            Button(onClick = vm::startCapture, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.PhotoCamera, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (scan.photos.isEmpty()) "Open camera" else "Take more photos")
            }
        }

        if (scan.photos.isNotEmpty()) item { PendingPhotos(scan, vm) }

        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Options", style = MaterialTheme.typography.titleMedium)
                    OptionRow(
                        title = "Read each card on its own",
                        detail = "More accurate. Turn off to let your server combine several photos into one request and save scanner quota.",
                        checked = scan.prefs.individual,
                        onChange = vm::setIndividual,
                    )
                    OptionRow(
                        title = "Keep my photo with every card",
                        detail = "Otherwise your photo is kept only for cards that have no official artwork, as in the PokéCollector web app.",
                        checked = scan.prefs.savePhotos,
                        onChange = vm::setSavePhotos,
                    )
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Scans on your server", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = vm::refreshInbox, enabled = !scan.inboxLoading) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh scans")
                }
            }
            if (scan.inboxLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        if (scan.inbox.isEmpty() && !scan.inboxLoading) {
            item {
                Text(
                    "Nothing waiting. Scans stay on the server until you add or skip every card, for up to 14 days.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(scan.inbox, key = { it.id }) { job -> JobRow(job, onOpen = { vm.openJob(job.id) }) }
    }
}

@Composable
private fun PendingPhotos(scan: ScanState, vm: ScanViewModel) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "${scan.photos.size} photo${if (scan.photos.size == 1) "" else "s"} ready to send",
                style = MaterialTheme.typography.titleMedium,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(scan.photos, key = { it.name }) { file ->
                    AsyncImage(
                        model = file,
                        contentDescription = "Photo waiting to be sent",
                        modifier = Modifier.height(88.dp).width(64.dp).clip(RoundedCornerShape(6.dp)),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            if (scan.uploading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Sending…", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = vm::sendPhotos, enabled = !scan.uploading, modifier = Modifier.weight(1f)) { Text("Send for scanning") }
                OutlinedButton(onClick = vm::discardPhotos, enabled = !scan.uploading) { Text("Discard") }
            }
        }
    }
}

@Composable
private fun OptionRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun JobRow(job: ScanJobDto, onOpen: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Scan #${job.id} · ${job.total} photo${if (job.total == 1) "" else "s"}", style = MaterialTheme.typography.titleSmall)
                val parts = buildList {
                    if (job.active > 0) add("${job.active} still being read")
                    if (job.attention > 0) add("${job.attention} waiting for you")
                }
                Text(
                    parts.joinToString(" · ").ifEmpty { "All handled" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (job.active > 0) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            TextButton(onClick = onOpen) { Text("Open") }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Camera
// ---------------------------------------------------------------------------------------------

@Composable
private fun CaptureView(scan: ScanState, vm: ScanViewModel, modifier: Modifier) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
    BackHandler { vm.leaveCapture() }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (!granted) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("The camera is needed to scan cards.", color = Color.White, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                TextButton(onClick = vm::leaveCapture) { Text("Back") }
            }
            return@Box
        }

        val controller = remember { CaptureController() }
        val haptics = LocalHapticFeedback.current
        var capturing by remember { mutableStateOf(false) }
        val full = scan.photos.size >= MAX_PHOTOS

        CameraPreview(controller, Modifier.fillMaxSize())

        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().safeDrawingPadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::leaveCapture) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Text(
                    "Fill the outline with one card. Avoid glare.",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            scan.message?.let { Banner(it, isError = true, onDismiss = vm::dismissMessage) }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0x99000000))
                .safeDrawingPadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (scan.photos.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(scan.photos, key = { it.name }) { file ->
                        Box {
                            AsyncImage(
                                model = file,
                                contentDescription = "Photo taken",
                                modifier = Modifier.height(72.dp).width(52.dp).clip(RoundedCornerShape(6.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            IconButton(
                                onClick = { vm.removePhoto(file) },
                                modifier = Modifier.align(Alignment.TopEnd).size(24.dp).background(Color(0xAA000000), CircleShape),
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Remove photo", tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = vm::leaveCapture, modifier = Modifier.width(96.dp)) { Text("Done", color = Color.White) }
                ShutterButton(
                    enabled = !capturing && !full && !scan.uploading,
                    onClick = {
                        capturing = true
                        controller.take(vm.newPhotoFile()) { result ->
                            capturing = false
                            result.onSuccess {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                vm.photoTaken(it)
                            }.onFailure { vm.cameraFailed("Couldn't take the photo: ${it.message ?: "unknown error"}") }
                        }
                    },
                )
                Button(
                    onClick = vm::sendPhotos,
                    enabled = scan.photos.isNotEmpty() && !scan.uploading,
                    modifier = Modifier.width(96.dp),
                ) { Text(if (scan.uploading) "…" else "Send ${scan.photos.size}") }
            }
            if (full) Text("That's the limit of $MAX_PHOTOS photos per batch.", color = Color.White, style = MaterialTheme.typography.bodySmall)
        }

        if (scan.uploading) {
            Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text("Sending ${scan.photos.size} photo${if (scan.photos.size == 1) "" else "s"}…", color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(76.dp)
            .border(4.dp, Color.White, CircleShape)
            .padding(7.dp)
            .clip(CircleShape)
            .background(if (enabled) Color.White else Color.Gray)
            .clickable(enabled = enabled, onClick = onClick),
    )
}

// ---------------------------------------------------------------------------------------------
// Review: confirm what the scanner found
// ---------------------------------------------------------------------------------------------

@Composable
private fun ReviewView(app: AppState, scan: ScanState, vm: ScanViewModel, modifier: Modifier) {
    BackHandler { vm.closeReview() }
    var confirmDelete by remember { mutableStateOf(false) }
    val job = scan.job

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::closeReview) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Column(Modifier.weight(1f)) {
                Text(job?.let { "Scan #${it.id}" } ?: "Opening scan…", style = MaterialTheme.typography.titleLarge)
                if (job != null) {
                    val handled = job.items.count { it.resolved }
                    Text(
                        "$handled of ${job.total} handled" + if (job.active > 0) " · ${job.active} still being read" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (job != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete this scan") }
        }
        if (job != null && job.total > 0) {
            LinearProgressIndicator(
                progress = { job.items.count { it.resolved }.toFloat() / job.total },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        scan.message?.let { Banner(it, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), isError = true, onDismiss = vm::dismissMessage) }

        if (job != null) {
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(job.items, key = { it.id }) { item -> ReviewItem(app, scan, vm, job, item) }
                if (job.active == 0 && job.attention == 0) {
                    item {
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("All done", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Every photo in this scan has been added or skipped.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Button(onClick = vm::closeReview) { Text("Back to scanner") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this scan?") },
            text = { Text("The photos and results are removed from your server. Cards you already added stay in your collection.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteJob() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun ReviewItem(app: AppState, scan: ScanState, vm: ScanViewModel, job: ScanJobDto, item: ScanItemDto) {
    val busy = item.id in scan.busyItems
    ElevatedCard(Modifier.fillMaxWidth()) {
        when (item.phase()) {
            ItemPhase.HANDLED -> {
                val outcome = scan.outcomes[item.id]
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        when (outcome) {
                            is ItemOutcome.Added -> "✓ Added ${outcome.name}"
                            ItemOutcome.Skipped -> "Skipped"
                            null -> "✓ Handled"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            ItemPhase.QUEUED, ItemPhase.PROCESSING -> WaitingRow(
                app, job, item,
                text = if (item.status == "processing") "Reading this card…" else "Waiting to be read…",
                showSpinner = true,
            )

            ItemPhase.WAITING_TO_RETRY -> {
                val now by produceState(Instant.now()) {
                    while (true) {
                        delay(1000)
                        value = Instant.now()
                    }
                }
                WaitingRow(app, job, item, text = retryNote(item.retryReason, item.nextAttemptAt, now), showSpinner = true)
            }

            ItemPhase.FAILED -> Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RemoteImage(
                        ServerUrls.scanItemImage(app.serverUrl, job.id, item.id), "Your photo",
                        Modifier.height(96.dp).width(72.dp).clip(RoundedCornerShape(6.dp)), ContentScale.Crop,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Couldn't read this card", style = MaterialTheme.typography.titleSmall)
                        Text(
                            item.error ?: "The scanner gave up on this photo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { vm.retryItem(item.id) }, enabled = !busy) { Text("Try again") }
                    OutlinedButton(onClick = { vm.skip(item.id) }, enabled = !busy) { Text("Skip") }
                }
            }

            ItemPhase.NEEDS_REVIEW -> CandidateReview(app, scan, vm, job, item, busy)
        }
    }
}

@Composable
private fun WaitingRow(app: AppState, job: ScanJobDto, item: ScanItemDto, text: String, showSpinner: Boolean) {
    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RemoteImage(
            ServerUrls.scanItemImage(app.serverUrl, job.id, item.id), "Your photo",
            Modifier.height(64.dp).width(48.dp).clip(RoundedCornerShape(6.dp)), ContentScale.Crop,
        )
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (showSpinner) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun CandidateReview(app: AppState, scan: ScanState, vm: ScanViewModel, job: ScanJobDto, item: ScanItemDto, busy: Boolean) {
    val matches = item.matches
    val selectedIndex = (scan.selected[item.id] ?: 0).coerceIn(0, (matches.size - 1).coerceAtLeast(0))
    val match = matches.getOrNull(selectedIndex)
    val edits = scan.edits[item.id] ?: AddEdits(condition = scan.prefs.condition, variant = scan.prefs.variant)
    var editing by remember { mutableStateOf(false) }
    val money = remember(app.prefs.currency, app.prefs.rateFromEur) { MoneyFormatter(app.prefs.currency, app.prefs.rateFromEur) }

    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LabeledPicture(
                "Your photo",
                ServerUrls.scanItemImage(app.serverUrl, job.id, item.id),
                Modifier.weight(1f),
            )
            LabeledPicture(
                if (match != null) "Match ${selectedIndex + 1} of ${matches.size}" else "No match",
                match?.let { ServerUrls.candidateImage(app.serverUrl, job.id, item.id, selectedIndex) },
                Modifier.weight(1f),
            )
        }

        item.recognizedSummary()?.let {
            Text("Scanner read: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (match == null) {
            Text(
                "The scanner couldn't match this card. You can skip it and add it by hand in PokéCollector.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { vm.skip(item.id) }, enabled = !busy) { Text("Skip") }
            return@Column
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(match.name, style = MaterialTheme.typography.titleMedium)
            Text(match.subtitle(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (match.printedTotalMismatch) {
                Text(
                    "The set size printed on your photo doesn't match this card's set. Check it carefully.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (matches.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(matches.size) { index ->
                    val candidate = matches[index]
                    val selected = index == selectedIndex
                    Column(
                        Modifier
                            .width(64.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(8.dp))
                            .clickable { vm.selectCandidate(item.id, index) }
                            .padding(2.dp),
                    ) {
                        RemoteImage(candidate.image, candidate.name, Modifier.fillMaxWidth().height(88.dp).clip(RoundedCornerShape(6.dp)))
                        Text(
                            candidate.set ?: candidate.setAbbreviation ?: candidate.name,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.add(item.id) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                if (busy) {
                    CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                }
                Text("Add ×${edits.quantity} · ${edits.condition} · ${edits.variant}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(onClick = { editing = true }, enabled = !busy) { Text("Edit") }
            TextButton(onClick = { vm.skip(item.id) }, enabled = !busy) { Text("Skip") }
        }
        edits.purchasePrice?.let {
            Text("Paid ${money.format(it)} each", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (editing) {
        EditDialog(
            initial = edits,
            currency = app.prefs.currency,
            rateFromEur = app.prefs.rateFromEur,
            onSave = { vm.setEdits(item.id, it); editing = false },
            onDismiss = { editing = false },
        )
    }
}

@Composable
private fun LabeledPicture(label: String, url: String?, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RemoteImage(
            url, label,
            Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            ContentScale.Fit,
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EditDialog(
    initial: AddEdits,
    currency: String,
    rateFromEur: Double,
    onSave: (AddEdits) -> Unit,
    onDismiss: () -> Unit,
) {
    var quantity by remember { mutableIntStateOf(initial.quantity) }
    var condition by remember { mutableStateOf(initial.condition) }
    var variant by remember { mutableStateOf(initial.variant) }
    var price by remember { mutableStateOf(MoneyInput.toInput(initial.purchasePrice, rateFromEur)) }
    val priceInvalid = price.isNotBlank() && MoneyInput.toEuros(price, rateFromEur) == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Card details") },
        confirmButton = {
            TextButton(
                enabled = !priceInvalid,
                onClick = { onSave(AddEdits(quantity, condition, variant, MoneyInput.toEuros(price, rateFromEur))) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Quantity", Modifier.weight(1f))
                    IconButton(onClick = { quantity = (quantity - 1).coerceAtLeast(1) }) { Icon(Icons.Default.Remove, contentDescription = "Fewer") }
                    Text("$quantity", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { quantity = (quantity + 1).coerceAtMost(999) }) { Icon(Icons.Default.Add, contentDescription = "More") }
                }
                Text("Condition", style = MaterialTheme.typography.labelLarge)
                ChipRow(Conditions.ALL, condition) { condition = it }
                Text("Variant", style = MaterialTheme.typography.labelLarge)
                ChipRow(Variants.ALL, variant) { variant = it }
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Price paid per card ($currency, optional)") },
                    isError = priceInvalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
            }
        },
    )
}

@Composable
private fun ChipRow(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(option) })
        }
    }
}
