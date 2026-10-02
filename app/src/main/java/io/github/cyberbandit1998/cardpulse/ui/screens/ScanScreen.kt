package io.github.cyberbandit1998.cardpulse.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.cyberbandit1998.cardpulse.camera.CameraPreview
import io.github.cyberbandit1998.cardpulse.camera.CaptureController
import io.github.cyberbandit1998.cardpulse.core.AddEdits
import io.github.cyberbandit1998.cardpulse.core.CollectionIndex
import io.github.cyberbandit1998.cardpulse.core.Ownership
import io.github.cyberbandit1998.cardpulse.core.ScanEntry
import io.github.cyberbandit1998.cardpulse.core.SessionState
import io.github.cyberbandit1998.cardpulse.core.TileState
import io.github.cyberbandit1998.cardpulse.core.inFlight
import io.github.cyberbandit1998.cardpulse.core.ownershipOf
import io.github.cyberbandit1998.cardpulse.core.tileState
import io.github.cyberbandit1998.cardpulse.core.toReview
import io.github.cyberbandit1998.cardpulse.ui.AppState
import io.github.cyberbandit1998.cardpulse.ui.Banner
import io.github.cyberbandit1998.cardpulse.ui.ScanStage
import io.github.cyberbandit1998.cardpulse.ui.ScanState
import io.github.cyberbandit1998.cardpulse.ui.ScanViewModel

@Composable
fun ScanScreen(app: AppState, vm: ScanViewModel, modifier: Modifier = Modifier) {
    val scan by vm.state.collectAsState()
    val session by vm.session.state.collectAsState()
    when (scan.stage) {
        ScanStage.HOME -> ScanHome(scan, session, vm, modifier)
        ScanStage.RAPID -> RapidScreen(app, scan, session, vm, modifier)
    }
}

// ---------------------------------------------------------------------------------------------
// Home: start scanning, pick up what is waiting
// ---------------------------------------------------------------------------------------------

@Composable
private fun ScanHome(scan: ScanState, session: SessionState, vm: ScanViewModel, modifier: Modifier) {
    LaunchedEffect(Unit) { vm.refresh() }
    val ready = session.toReview.size
    val reading = session.inFlight
    val attention = session.entries.count {
        when (it.tileState()) {
            TileState.NO_MATCH, TileState.FAILED, TileState.SEND_FAILED -> true
            else -> false
        }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text("Scan cards", style = MaterialTheme.typography.headlineMedium) }
        (session.message ?: scan.message)?.let { item { Banner(it, isError = true, onDismiss = vm::dismissMessage) } }

        item {
            Button(onClick = { vm.startRapid() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.PhotoCamera, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Start rapid scan")
            }
        }

        if (ready + reading + attention > 0) {
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Waiting for you", style = MaterialTheme.typography.titleMedium)
                        if (ready > 0) Text(if (ready == 1) "1 card is ready to add" else "$ready cards are ready to add")
                        if (reading > 0) Text("$reading still being read", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (attention > 0) Text("$attention need a look", color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = { vm.startRapid(reviewFirst = ready > 0) }) {
                            Text(if (ready > 0) "Review $ready" else "Open results")
                        }
                    }
                }
            }
        }

        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Options", style = MaterialTheme.typography.titleMedium)
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
            Text(
                "Keep the camera open and photograph card after card. Your server reads each photo while you take the next. " +
                    "Results appear along the bottom: tap one to check the details and add it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

// ---------------------------------------------------------------------------------------------
// Rapid scan: the camera stays open while results come in
// ---------------------------------------------------------------------------------------------

/** Everything the rapid-scan screen can ask for, so the screen itself holds no logic. */
class RapidActions(
    val shutter: () -> Unit = {},
    val done: () -> Unit = {},
    val review: () -> Unit = {},
    val open: (ScanEntry) -> Unit = {},
    val closePanel: () -> Unit = {},
    val select: (Long, Int) -> Unit = { _, _ -> },
    val edits: (Long, AddEdits) -> Unit = { _, _ -> },
    val add: (Long) -> Unit = {},
    val skip: (Long) -> Unit = {},
    val retry: (Long) -> Unit = {},
    val dismissMessage: () -> Unit = {},
)

/** Wires the camera, the camera permission and the view model into [RapidScreenContent]. */
@Composable
private fun RapidScreen(app: AppState, scan: ScanState, session: SessionState, vm: ScanViewModel, modifier: Modifier) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

    val controller = remember { CaptureController() }
    val haptics = LocalHapticFeedback.current
    var capturing by remember { mutableStateOf(false) }
    // Until the collection has loaded nothing can honestly be called new or a duplicate.
    val index = remember(app.collection, app.collectionLoaded) {
        if (app.collectionLoaded) CollectionIndex(app.collection) else null
    }

    BackHandler { if (scan.openId != null) vm.closePanel() else vm.leaveRapid() }

    RapidScreenContent(
        entries = session.entries,
        openId = scan.openId,
        message = session.message ?: scan.message,
        serverUrl = app.serverUrl,
        currency = app.prefs.currency,
        rateFromEur = app.prefs.rateFromEur,
        ownership = { entry -> entry.match?.let { index.ownershipOf(it) } ?: Ownership.Unknown },
        capturing = capturing,
        canShoot = granted,
        preview = {
            if (granted) {
                CameraPreview(controller, Modifier.fillMaxSize())
            } else {
                PermissionNote(onAllow = { permission.launch(Manifest.permission.CAMERA) })
            }
        },
        actions = RapidActions(
            shutter = {
                capturing = true
                controller.take(vm.newPhotoFile()) { result ->
                    capturing = false
                    result
                        .onSuccess {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            vm.photoTaken(it)
                        }
                        .onFailure { vm.cameraFailed("Couldn't take the photo: ${it.message ?: "unknown error"}") }
                }
            },
            done = vm::leaveRapid,
            review = vm::openOldestReady,
            open = { vm.open(it.id) },
            closePanel = vm::closePanel,
            select = vm::select,
            edits = vm::setEdits,
            add = vm::add,
            skip = vm::skip,
            retry = { id ->
                val state = session.entries.firstOrNull { it.id == id }?.tileState()
                if (state == TileState.SEND_FAILED) vm.resend(id) else vm.retryItem(id)
            },
            dismissMessage = vm::dismissMessage,
        ),
        modifier = modifier,
    )
}

/**
 * The open camera with this session's results along the bottom and, when one is opened, the panel to check it.
 * [preview] is the live camera, kept out of here so the rest can be drawn without one.
 */
@Composable
fun RapidScreenContent(
    entries: List<ScanEntry>,
    openId: Long?,
    message: String?,
    serverUrl: String,
    currency: String,
    rateFromEur: Double,
    ownership: (ScanEntry) -> Ownership,
    capturing: Boolean,
    canShoot: Boolean,
    preview: @Composable () -> Unit,
    actions: RapidActions,
    modifier: Modifier = Modifier,
) {
    val toReview = entries.count { it.tileState() == TileState.READY }
    val open = openId?.let { id -> entries.firstOrNull { it.id == id } }

    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        val panelMaxHeight = maxHeight * 0.82f

        Box(Modifier.fillMaxSize()) { preview() }

        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.done) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Text("Rapid scan", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (toReview > 0) {
                    Text(
                        "$toReview to review",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable(onClick = actions.review)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            message?.let { Banner(it, isError = true, onDismiss = actions.dismissMessage) }
            if (entries.isEmpty()) {
                Text(
                    "Fill the outline with one card, then tap the button. Results appear below as they are read, " +
                        "while you carry on with the next card.",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0x99000000))
                .navigationBarsPadding()
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (entries.isNotEmpty()) {
                ResultTray(
                    entries = entries,
                    serverUrl = serverUrl,
                    ownership = ownership,
                    openId = openId,
                    onOpen = actions.open,
                    modifier = Modifier.fillMaxWidth().height(92.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = actions.done, modifier = Modifier.width(96.dp)) { Text("Done", color = Color.White) }
                ShutterButton(enabled = canShoot && !capturing, onClick = actions.shutter)
                Button(onClick = actions.review, enabled = toReview > 0, modifier = Modifier.width(96.dp)) {
                    Text(if (toReview > 0) "Review $toReview" else "Review", maxLines = 1)
                }
            }
        }

        if (open != null) {
            ConfirmPanel(
                entry = open,
                serverUrl = serverUrl,
                ownership = ownership(open),
                currency = currency,
                rateFromEur = rateFromEur,
                othersWaiting = entries.count { it.tileState() == TileState.READY && it.id != open.id },
                onSelectCandidate = { actions.select(open.id, it) },
                onEdits = { actions.edits(open.id, it) },
                onAdd = { actions.add(open.id) },
                onSkip = { actions.skip(open.id) },
                onRetry = { actions.retry(open.id) },
                onClose = actions.closePanel,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .imePadding()
                    .navigationBarsPadding()
                    .heightIn(max = panelMaxHeight),
            )
        }
    }
}

@Composable
private fun PermissionNote(onAllow: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("The camera is needed to take photos of cards.", color = Color.White, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onAllow) { Text("Allow camera") }
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
            .clickable(enabled = enabled, onClickLabel = "Take photo", onClick = onClick),
    )
}
