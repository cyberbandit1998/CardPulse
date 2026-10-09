package app.cardpulse.android.ui.screens

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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.cardpulse.android.camera.CameraPreview
import app.cardpulse.android.camera.CaptureController
import app.cardpulse.android.core.AddEdits
import app.cardpulse.android.core.CollectionIndex
import app.cardpulse.android.core.DAILY_LIMIT_MESSAGE
import app.cardpulse.android.core.Ownership
import app.cardpulse.android.core.ScanAllowanceDto
import app.cardpulse.android.core.ScanEntry
import app.cardpulse.android.core.SessionState
import app.cardpulse.android.core.TileState
import app.cardpulse.android.core.isInFlight
import app.cardpulse.android.core.isUsedUp
import app.cardpulse.android.core.ownershipOf
import app.cardpulse.android.core.parseServerInstant
import app.cardpulse.android.core.resetText
import app.cardpulse.android.core.tileState
import app.cardpulse.android.core.toReview
import app.cardpulse.android.core.usageText
import app.cardpulse.android.ui.AppState
import app.cardpulse.android.ui.Banner
import app.cardpulse.android.ui.ScanState
import app.cardpulse.android.ui.ScanViewModel
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

/**
 * The camera, over whichever tab is showing. It is opened by the round button in the bottom bar, and when it closes the
 * tab underneath is there again.
 */
@Composable
fun ScanScreen(app: AppState, vm: ScanViewModel, onAddManually: () -> Unit, modifier: Modifier = Modifier) {
    val scan by vm.state.collectAsState()
    val session by vm.session.state.collectAsState()
    RapidScreen(app, scan, session, vm, onAddManually, modifier)
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
    /** Gives up on a photo that is still being sent or read. */
    val cancel: (Long) -> Unit = {},
    val cancelAll: () -> Unit = {},
    /** Opens the screen where a card is typed in, for one the scanner couldn't read. */
    val addManually: () -> Unit = {},
    val dismissMessage: () -> Unit = {},
)

/** Wires the camera, the camera permission and the view model into [RapidScreenContent]. */
@Composable
private fun RapidScreen(
    app: AppState,
    scan: ScanState,
    session: SessionState,
    vm: ScanViewModel,
    onAddManually: () -> Unit,
    modifier: Modifier,
) {
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

    // The clock behind "Scans reset at midnight (in 5 h)", moved on every half minute while the camera is open.
    val now by produceState(Instant.now()) {
        while (true) {
            delay(CLOCK_TICK_MS)
            value = Instant.now()
        }
    }
    // The count starts again when the day does, so ask again then: what was last heard is a day old by that time.
    val resetAt = session.allowance?.let { parseServerInstant(it.resetsAt) }
    LaunchedEffect(resetAt) {
        if (resetAt != null) {
            delay(Duration.between(Instant.now(), resetAt).toMillis().coerceAtLeast(0) + AFTER_RESET_MS)
            vm.refreshAllowance()
        }
    }

    RapidScreenContent(
        allowance = session.allowance,
        now = now,
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
            cancel = vm::cancel,
            cancelAll = vm::cancelUnfinished,
            addManually = onAddManually,
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
    /** What the user may still scan today (the server's daily scan limit); null when the server has none to say. */
    allowance: ScanAllowanceDto? = null,
    now: Instant = Instant.now(),
    /** The bars the controls keep clear of: the phone's own. Pictures and tests give them a size, to draw a phone that has them. */
    statusBar: WindowInsets = WindowInsets.statusBars,
    navigationBar: WindowInsets = WindowInsets.navigationBars,
) {
    val toReview = entries.count { it.tileState() == TileState.READY }
    val open = openId?.let { id -> entries.firstOrNull { it.id == id } }
    // Scans are refused by the server at the limit, so the shutter only saves the user a photo that would be turned away.
    val limitReached = allowance?.isUsedUp(now) == true

    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        val panelMaxHeight = maxHeight * 0.82f

        Box(Modifier.fillMaxSize()) { preview() }

        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().windowInsetsPadding(statusBar).padding(horizontal = 8.dp, vertical = 8.dp),
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
            if (allowance != null) ScanAllowanceNotice(allowance, now)
            message?.let { Banner(it, isError = true, onDismiss = actions.dismissMessage) }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0x99000000))
                .windowInsetsPadding(navigationBar)
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
            } else if (!limitReached) {
                // Below the camera, on the dark strip, and not over it: up in the camera it ran down into the card outline as soon
                // as anything else (the count of scans, a status bar of a few more dp, a larger text size) took a line above it.
                // The results of the first photo come up in this very place, which is what "here" means.
                Text(
                    "Fill the outline with one card, then tap the button. Results appear here as they are read.",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The two sides share what the shutter leaves, so it stays in the middle whatever the labels need.
                // A fixed 96 dp button cut "Review" short once the phone's text size was larger. The number waiting is
                // in the badge at the top, so the button only says what it does: a count squeezed in here would be
                // cut off on a small phone, and a wrong number is worse than none.
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    TextButton(onClick = actions.done) { Text("Done", color = Color.White, maxLines = 1) }
                }
                ShutterButton(enabled = canShoot && !capturing && !limitReached, onClick = actions.shutter)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Button(
                        onClick = actions.review,
                        enabled = toReview > 0,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text("Review", maxLines = 1, softWrap = false)
                    }
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
                othersInFlight = entries.count { it.isInFlight && it.id != open.id },
                onSelectCandidate = { actions.select(open.id, it) },
                onEdits = { actions.edits(open.id, it) },
                onAdd = { actions.add(open.id) },
                onSkip = { actions.skip(open.id) },
                onRetry = { actions.retry(open.id) },
                onCancel = { actions.cancel(open.id) },
                onCancelAll = actions.cancelAll,
                onAddManually = actions.addManually,
                onClose = actions.closePanel,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .imePadding()
                    .windowInsetsPadding(navigationBar)
                    .heightIn(max = panelMaxHeight),
            )
        }
    }
}

/** How often the clock behind "(in 5 h 12 min)" moves on. */
private const val CLOCK_TICK_MS = 30_000L

/** A moment after the reset, so the server has started the new day when it is asked. */
private const val AFTER_RESET_MS = 1_000L

/**
 * How many scans are used today, such as "23 of 100 scans used today", or "Unlimited scans" for a user with no limit. At the
 * limit that is replaced by a notice that says so, and when scans start again: the server refuses a photo then, and nothing
 * here would explain why. One element at a time keeps the top of the screen short, so it stays clear of the card outline.
 */
@Composable
private fun ScanAllowanceNotice(allowance: ScanAllowanceDto, now: Instant, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        if (allowance.isUsedUp(now)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    // Said out loud when it appears: a screen reader user otherwise finds out from a shutter that does nothing.
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    DAILY_LIMIT_MESSAGE,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                allowance.resetText(now)?.let {
                    Text(it, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            Text(
                allowance.usageText(),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
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
