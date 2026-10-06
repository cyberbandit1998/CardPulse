package app.cardpulse.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.cardpulse.android.PokeApp
import app.cardpulse.android.core.AddEdits
import app.cardpulse.android.core.ManualAddSession
import app.cardpulse.android.core.ScanSession
import app.cardpulse.android.core.SessionState
import app.cardpulse.android.core.isHandled
import app.cardpulse.android.core.toReview
import app.cardpulse.android.core.userMessage
import app.cardpulse.android.data.ScanPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Whether the camera is open: it comes up over whichever tab the user is on, and closing it goes back there. */
enum class ScanStage { HOME, RAPID }

data class ScanState(
    val stage: ScanStage = ScanStage.HOME,
    val prefs: ScanPrefs = ScanPrefs(),
    /** The scan whose confirm panel is open, if any. */
    val openId: Long? = null,
    /** A camera problem; the server's messages live in the session. */
    val message: String? = null,
)

class ScanViewModel(app: Application) : AndroidViewModel(app) {
    private val container = getApplication<PokeApp>().container
    private val store = container.store

    /** Everything about photos on their way to the collection. See [ScanSession]. */
    val session = ScanSession(
        backend = container.repository,
        scope = viewModelScope,
        onCollectionItem = { container.collectionUpdates.tryEmit(it) },
        describe = { it.userMessage() },
    )

    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state.asStateFlow()

    /** Adds cards by typing their name and number. It starts each card with the condition and variant used last. */
    val manualAdd = ManualAddSession(
        backend = container.repository,
        scope = viewModelScope,
        onCollectionItem = { container.collectionUpdates.tryEmit(it) },
        describe = { it.userMessage() },
        defaultEdits = { defaultEdits() },
    )

    private val photoNumber = AtomicInteger()

    init {
        viewModelScope.launch {
            _state.update { it.copy(prefs = store.scanPrefs()) }
            manualAdd.reset() // so its first card starts with the saved condition and variant
        }
        viewModelScope.launch { restorePhotos() }
        viewModelScope.launch { session.state.collect { followSession(it) } }
    }

    /** Sends photos that never made it to the server, and tidies copies of ones that did. */
    private suspend fun restorePhotos() {
        val unsent = withContext(Dispatchers.IO) {
            val files = container.pendingScansDir.listFiles().orEmpty()
            files.filter { it.name.startsWith("sent-") }.forEach { it.delete() }
            files.filter { it.name.startsWith("scan-") && it.extension == "jpg" }
                .filter { file -> (file.length() > 0).also { keep -> if (!keep) file.delete() } }
        }
        session.resumeLocal(unsent)
    }

    // --- the camera -------------------------------------------------------------------------------

    /** Opens the camera. With [reviewFirst] the oldest result waiting for confirmation opens too. */
    fun startRapid(reviewFirst: Boolean = false) {
        session.clearHandled()
        _state.update { it.copy(stage = ScanStage.RAPID, openId = null, message = null) }
        refresh()
        if (reviewFirst) openOldestReady()
    }

    fun leaveRapid() = _state.update { it.copy(stage = ScanStage.HOME, openId = null) }

    /** Picks up scans the server still holds for this user. */
    fun refresh() {
        viewModelScope.launch { session.resumeServer() }
    }

    /**
     * Like [refresh], but a server that can't be reached is not mentioned. It is for when the app opens, so the round
     * camera button can say how many scanned cards are waiting: the user did not ask, and an error would be waiting for
     * them in the camera later.
     */
    fun refreshQuietly() {
        viewModelScope.launch { session.resumeServer(report = false) }
    }

    /** Where the camera should write the next photo. Names sort in the order the photos were taken. */
    fun newPhotoFile(): File = File(
        container.pendingScansDir,
        "scan-%013d-%03d.jpg".format(System.currentTimeMillis(), photoNumber.getAndIncrement() % 1000),
    )

    fun photoTaken(file: File) {
        if (file.length() == 0L) {
            file.delete()
            _state.update { it.copy(message = "That photo came out empty. Try again.") }
            return
        }
        session.capture(file)
    }

    fun cameraFailed(reason: String) = _state.update { it.copy(message = reason) }

    fun dismissMessage() {
        session.dismissMessage()
        _state.update { it.copy(message = null) }
    }

    // --- confirming ---------------------------------------------------------------------------------

    private fun defaultEdits(): AddEdits {
        val prefs = _state.value.prefs
        return AddEdits(condition = prefs.condition, variant = prefs.variant)
    }

    fun open(id: Long) {
        session.initEdits(id, defaultEdits())
        _state.update { it.copy(openId = id) }
    }

    fun closePanel() = _state.update { it.copy(openId = null) }

    /** Results are reviewed in the order the cards were scanned. */
    fun openOldestReady() {
        session.state.value.toReview.lastOrNull()?.let { open(it.id) }
    }

    fun select(id: Long, index: Int) = session.select(id, index)

    fun setEdits(id: Long, edits: AddEdits) = session.setEdits(id, edits)

    fun add(id: Long) {
        val edits = session.state.value.entries.firstOrNull { it.id == id }?.edits ?: defaultEdits()
        // The next card starts with the condition and variant used last time.
        updatePrefs { it.copy(condition = edits.condition, variant = edits.variant) }
        session.add(id, _state.value.prefs.savePhotos)
    }

    fun skip(id: Long) = session.skip(id)

    // --- typing a card in -----------------------------------------------------------------------------

    /** Adds the card picked on the manual-add screen. The next card starts with the condition and variant used here. */
    fun addManualCard() {
        val edits = manualAdd.state.value.edits
        updatePrefs { it.copy(condition = edits.condition, variant = edits.variant) }
        manualAdd.add()
    }

    /** Leaving the manual-add screen starts the next visit with blank boxes. */
    fun closeManualAdd() = manualAdd.reset()

    fun retryItem(id: Long) = session.retryItem(id)

    fun resend(id: Long) = session.resend(id)

    /** Gives up on a scan that is still being sent or read: it leaves the list and is deleted on the server. */
    fun cancel(id: Long) = session.cancel(id)

    fun cancelUnfinished() = session.cancelUnfinished()

    /** When the open card is added, skipped or goes away, show the next one waiting, or close the panel. */
    private fun followSession(current: SessionState) {
        val open = _state.value.openId ?: return
        val entry = current.entries.firstOrNull { it.id == open }
        if (entry != null && !entry.isHandled) return
        val next = current.toReview.lastOrNull { it.id != open }
        if (next != null) {
            session.initEdits(next.id, defaultEdits())
            _state.update { it.copy(openId = next.id) }
        } else {
            _state.update { it.copy(openId = null) }
        }
    }

    // --- settings -----------------------------------------------------------------------------------

    fun setSavePhotos(value: Boolean) = updatePrefs { it.copy(savePhotos = value) }

    fun setLookUpPrices(value: Boolean) = updatePrefs { it.copy(lookUpPrices = value) }

    private fun updatePrefs(change: (ScanPrefs) -> ScanPrefs) {
        val updated = change(_state.value.prefs)
        _state.update { it.copy(prefs = updated) }
        viewModelScope.launch { store.saveScanPrefs(updated) }
    }
}
