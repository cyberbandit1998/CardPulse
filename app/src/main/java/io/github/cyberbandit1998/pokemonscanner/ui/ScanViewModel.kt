package io.github.cyberbandit1998.pokemonscanner.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.cyberbandit1998.pokemonscanner.PokeApp
import io.github.cyberbandit1998.pokemonscanner.core.AddEdits
import io.github.cyberbandit1998.pokemonscanner.core.ScanJobDto
import io.github.cyberbandit1998.pokemonscanner.core.attempt
import io.github.cyberbandit1998.pokemonscanner.core.isSettled
import io.github.cyberbandit1998.pokemonscanner.core.toAddRequest
import io.github.cyberbandit1998.pokemonscanner.core.userMessage
import io.github.cyberbandit1998.pokemonscanner.data.ScanPrefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.File

enum class ScanStage { HOME, CAPTURE, REVIEW }

sealed interface ItemOutcome {
    data class Added(val name: String) : ItemOutcome
    data object Skipped : ItemOutcome
}

data class ScanState(
    val stage: ScanStage = ScanStage.HOME,
    /** Photos taken and not yet sent, oldest first. Kept on disk so they survive the app being killed. */
    val photos: List<File> = emptyList(),
    val uploading: Boolean = false,
    /** Jobs the server still has for this user (running, or waiting for review). */
    val inbox: List<ScanJobDto> = emptyList(),
    val inboxLoading: Boolean = false,
    /** The job being reviewed, with its items. */
    val job: ScanJobDto? = null,
    /** What the user did with each item this session; the server only says "resolved". */
    val outcomes: Map<Int, ItemOutcome> = emptyMap(),
    val busyItems: Set<Int> = emptySet(),
    /** Which candidate the user picked for an item (default: the first). */
    val selected: Map<Int, Int> = emptyMap(),
    val edits: Map<Int, AddEdits> = emptyMap(),
    val message: String? = null,
    val prefs: ScanPrefs = ScanPrefs(),
)

class ScanViewModel(app: Application) : AndroidViewModel(app) {
    private val container = getApplication<PokeApp>().container
    private val repo = container.repository
    private val store = container.store

    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            val prefs = store.scanPrefs()
            _state.update { it.copy(prefs = prefs, photos = pendingPhotos()) }
        }
    }

    private fun pendingPhotos(): List<File> =
        container.pendingScansDir.listFiles { file -> file.extension == "jpg" && file.length() > 0 }
            ?.sortedBy { it.name }
            .orEmpty()

    fun dismissMessage() = _state.update { it.copy(message = null) }

    // --- capture ------------------------------------------------------------------------------

    fun startCapture() = _state.update { it.copy(stage = ScanStage.CAPTURE, message = null) }

    fun leaveCapture() = _state.update { it.copy(stage = ScanStage.HOME) }

    /** Where the camera should write the next photo. */
    fun newPhotoFile(): File = File(container.pendingScansDir, "scan-${System.currentTimeMillis()}.jpg")

    fun photoTaken(file: File) {
        if (file.length() == 0L) {
            file.delete()
            _state.update { it.copy(message = "That photo came out empty. Try again.") }
            return
        }
        _state.update { it.copy(photos = it.photos + file) }
    }

    fun cameraFailed(reason: String) = _state.update { it.copy(message = reason) }

    fun removePhoto(file: File) {
        file.delete()
        _state.update { it.copy(photos = it.photos.filterNot { photo -> photo == file }) }
    }

    fun discardPhotos() {
        _state.value.photos.forEach { it.delete() }
        _state.update { it.copy(photos = emptyList(), stage = ScanStage.HOME) }
    }

    /** Uploads everything taken so far as one job, then opens it for review. */
    fun sendPhotos() {
        val photos = _state.value.photos
        if (photos.isEmpty() || _state.value.uploading) return
        viewModelScope.launch {
            _state.update { it.copy(uploading = true, message = null) }
            attempt { repo.enqueueScan(photos, individual = _state.value.prefs.individual) }
                .onSuccess { job ->
                    photos.forEach { it.delete() }
                    _state.update { it.copy(uploading = false, photos = emptyList()) }
                    openJob(job.id)
                }
                .onFailure { error ->
                    // The photos stay on the phone so nothing is lost; the user can try again.
                    _state.update {
                        it.copy(uploading = false, message = "Couldn't send the photos: ${error.userMessage()}")
                    }
                }
        }
    }

    // --- inbox and review ---------------------------------------------------------------------

    fun refreshInbox() {
        viewModelScope.launch {
            _state.update { it.copy(inboxLoading = true) }
            attempt { repo.scanJobs() }
                .onSuccess { jobs -> _state.update { it.copy(inbox = jobs, inboxLoading = false) } }
                .onFailure { error -> _state.update { it.copy(inboxLoading = false, message = error.userMessage()) } }
        }
    }

    fun openJob(jobId: Int) {
        _state.update {
            it.copy(
                stage = ScanStage.REVIEW,
                job = null,
                outcomes = emptyMap(),
                selected = emptyMap(),
                edits = emptyMap(),
                message = null,
            )
        }
        startPolling(jobId)
    }

    fun closeReview() {
        pollJob?.cancel()
        _state.update { it.copy(stage = ScanStage.HOME, job = null) }
        refreshInbox()
    }

    /** Polls the job until the server has nothing left to process. */
    private fun startPolling(jobId: Int) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                val result = attempt { repo.scanJob(jobId) }
                result.onSuccess { job -> _state.update { it.copy(job = job) } }
                result.onFailure { error ->
                    if (error is HttpException && error.code() == 404) {
                        _state.update {
                            it.copy(
                                stage = ScanStage.HOME,
                                job = null,
                                message = "That scan is no longer on the server (scans expire after 14 days).",
                            )
                        }
                        refreshInbox()
                        return@launch
                    }
                    _state.update { it.copy(message = error.userMessage()) }
                }
                if (result.getOrNull()?.isSettled == true) return@launch
                delay(if (result.isFailure) POLL_ERROR_MS else POLL_MS)
            }
        }
    }

    private suspend fun refreshJobOnce(jobId: Int) {
        attempt { repo.scanJob(jobId) }.onSuccess { job -> _state.update { it.copy(job = job) } }
    }

    // --- review actions -----------------------------------------------------------------------

    fun selectCandidate(itemId: Int, index: Int) = _state.update { it.copy(selected = it.selected + (itemId to index)) }

    fun editsFor(itemId: Int): AddEdits {
        val state = _state.value
        return state.edits[itemId] ?: AddEdits(condition = state.prefs.condition, variant = state.prefs.variant)
    }

    fun setEdits(itemId: Int, edits: AddEdits) = _state.update { it.copy(edits = it.edits + (itemId to edits)) }

    /** Adds the chosen candidate to the collection and marks the photo handled, in one server transaction. */
    fun add(itemId: Int) {
        val state = _state.value
        val job = state.job ?: return
        val item = job.items.firstOrNull { it.id == itemId } ?: return
        val match = item.matches.getOrNull(state.selected[itemId] ?: 0) ?: return
        val edits = editsFor(itemId)
        if (itemId in state.busyItems) return

        viewModelScope.launch {
            setBusy(itemId, true)
            // The server deletes its copy of the photo when the item is resolved, so fetch it first if it is
            // going to be kept: always when the user asked for that, and for cards with no official artwork
            // (the same rule the PokéCollector web app follows).
            val keepPhoto = state.prefs.savePhotos || match.image.isNullOrBlank()
            val photo = if (keepPhoto) attempt { repo.scanPhotoBytes(job.id, itemId) }.getOrNull() else null

            attempt { repo.resolveAndAdd(job.id, itemId, match.toAddRequest(edits)) }
                .onSuccess { response ->
                    _state.update { it.copy(outcomes = it.outcomes + (itemId to ItemOutcome.Added(match.name))) }
                    container.collectionUpdates.tryEmit(response.collectionItem)
                    rememberChoices(edits)
                    // Best effort, like the web app: a failed photo upload never undoes the add.
                    if (photo != null) attempt { repo.uploadOwnerPhoto(response.collectionItem.id, photo) }
                    refreshJobOnce(job.id)
                }
                .onFailure { error ->
                    // A 409 usually means it was already handled (a retry after a lost reply): show the real state.
                    if (error is HttpException && error.code() == 409) refreshJobOnce(job.id)
                    _state.update { it.copy(message = error.userMessage()) }
                }
            setBusy(itemId, false)
        }
    }

    fun skip(itemId: Int) {
        val job = _state.value.job ?: return
        if (itemId in _state.value.busyItems) return
        viewModelScope.launch {
            setBusy(itemId, true)
            attempt { repo.skip(job.id, itemId) }
                .onSuccess {
                    _state.update { it.copy(outcomes = it.outcomes + (itemId to ItemOutcome.Skipped)) }
                    refreshJobOnce(job.id)
                }
                .onFailure { error -> _state.update { it.copy(message = error.userMessage()) } }
            setBusy(itemId, false)
        }
    }

    fun retryItem(itemId: Int) {
        val job = _state.value.job ?: return
        viewModelScope.launch {
            setBusy(itemId, true)
            attempt { repo.retry(job.id, itemId) }
                .onSuccess {
                    refreshJobOnce(job.id)
                    startPolling(job.id)
                }
                .onFailure { error -> _state.update { it.copy(message = error.userMessage()) } }
            setBusy(itemId, false)
        }
    }

    /** Throws the whole job away on the server (used when a batch was a mistake). */
    fun deleteJob() {
        val job = _state.value.job ?: return
        viewModelScope.launch {
            attempt { repo.deleteScanJob(job.id) }
                .onSuccess { closeReview() }
                .onFailure { error -> _state.update { it.copy(message = error.userMessage()) } }
        }
    }

    private fun setBusy(itemId: Int, busy: Boolean) = _state.update {
        it.copy(busyItems = if (busy) it.busyItems + itemId else it.busyItems - itemId)
    }

    /** The next card starts with the condition and variant used last time. */
    private suspend fun rememberChoices(edits: AddEdits) {
        val updated = _state.value.prefs.copy(condition = edits.condition, variant = edits.variant)
        _state.update { it.copy(prefs = updated) }
        store.saveScanPrefs(updated)
    }

    // --- settings -----------------------------------------------------------------------------

    fun setIndividual(value: Boolean) = updatePrefs { it.copy(individual = value) }

    fun setSavePhotos(value: Boolean) = updatePrefs { it.copy(savePhotos = value) }

    private fun updatePrefs(change: (ScanPrefs) -> ScanPrefs) {
        val updated = change(_state.value.prefs)
        _state.update { it.copy(prefs = updated) }
        viewModelScope.launch { store.saveScanPrefs(updated) }
    }

    private companion object {
        const val POLL_MS = 2_000L
        const val POLL_ERROR_MS = 5_000L
    }
}
