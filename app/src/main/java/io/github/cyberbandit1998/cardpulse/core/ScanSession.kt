package io.github.cyberbandit1998.cardpulse.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** What the scan session needs from the server. The real one is `Repository`; tests use a fake. */
interface ScanBackend {
    suspend fun enqueue(photo: File): ScanJobDto
    suspend fun scanJobs(): List<ScanJobDto>
    suspend fun scanJob(jobId: Int): ScanJobDto
    suspend fun resolveAndAdd(jobId: Int, itemId: Int, request: ResolveAndAddRequest): ResolveAndAddResponse
    suspend fun skip(jobId: Int, itemId: Int): ScanItemDto
    suspend fun retry(jobId: Int, itemId: Int): ScanItemDto
    suspend fun scanPhotoBytes(jobId: Int, itemId: Int): ByteArray
    suspend fun uploadOwnerPhoto(collectionItemId: Int, jpeg: ByteArray)
}

enum class Upload { WAITING, SENDING, SENT, FAILED }

/** What the user did with a scan. The server only knows "resolved", so this is remembered here. */
sealed interface ScanOutcome {
    data class Added(val name: String) : ScanOutcome
    data object Skipped : ScanOutcome
}

/** One photo on its way from the camera to the collection. */
data class ScanEntry(
    val id: Long,
    /** The local photo, shown until the card is handled. Null for scans picked up from the server. */
    val photo: File?,
    val upload: Upload,
    val uploadError: String? = null,
    val jobId: Int? = null,
    /** The server's latest word on this photo. Null until the first look after upload. */
    val item: ScanItemDto? = null,
    /** Which of the scanner's candidates the user is looking at. */
    val candidate: Int = 0,
    /** The user's choices; null until they are first needed. */
    val edits: AddEdits? = null,
    val outcome: ScanOutcome? = null,
    val busy: Boolean = false,
    /** The last problem adding or skipping this card. */
    val error: String? = null,
) {
    val match: ScanMatchDto? get() = item?.matches?.getOrNull(candidate)
}

enum class TileState { SENDING, SEND_FAILED, READING, WAITING, READY, NO_MATCH, FAILED, ADDED, SKIPPED }

fun ScanEntry.tileState(): TileState {
    when (outcome) {
        is ScanOutcome.Added -> return TileState.ADDED
        ScanOutcome.Skipped -> return TileState.SKIPPED
        null -> Unit
    }
    return when (upload) {
        Upload.FAILED -> TileState.SEND_FAILED
        Upload.WAITING, Upload.SENDING -> TileState.SENDING
        Upload.SENT -> {
            val current = item ?: return TileState.READING
            when (current.phase()) {
                ItemPhase.QUEUED, ItemPhase.PROCESSING -> TileState.READING
                ItemPhase.WAITING_TO_RETRY -> TileState.WAITING
                ItemPhase.NEEDS_REVIEW -> if (current.matches.isEmpty()) TileState.NO_MATCH else TileState.READY
                ItemPhase.FAILED -> TileState.FAILED
                ItemPhase.HANDLED -> TileState.SKIPPED
            }
        }
    }
}

/** The server may still change its mind about this photo, so it keeps being checked. */
val ScanEntry.isWorking: Boolean
    get() = upload == Upload.SENT && outcome == null && when (item?.phase()) {
        null, ItemPhase.QUEUED, ItemPhase.PROCESSING, ItemPhase.WAITING_TO_RETRY -> true
        else -> false
    }

val ScanEntry.isHandled: Boolean get() = outcome != null

data class SessionState(
    /** Newest first. */
    val entries: List<ScanEntry> = emptyList(),
    val loading: Boolean = false,
    val message: String? = null,
)

/** Results waiting for the user to confirm, newest first. */
val SessionState.toReview: List<ScanEntry> get() = entries.filter { it.tileState() == TileState.READY }

/** Photos still on their way (uploading or being read), not counting the ones that need the user. */
val SessionState.inFlight: Int
    get() = entries.count {
        when (it.tileState()) {
            TileState.SENDING, TileState.READING, TileState.WAITING -> true
            else -> false
        }
    }

/**
 * Scans card after card without waiting: every photo is uploaded as its own job the moment it is taken,
 * the server reads them in the background, and results are checked until they are ready.
 *
 * It holds no Android types, so it is tested on the JVM against a fake server.
 */
class ScanSession(
    private val backend: ScanBackend,
    private val scope: CoroutineScope,
    private val onCollectionItem: (CollectionItemDto) -> Unit = {},
    private val describe: (Throwable) -> String = { it.message ?: "Something went wrong." },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pollMs: Long = 1_500,
    private val waitingPollMs: Long = 5_000,
    private val errorPollMs: Long = 5_000,
    uploadWorkers: Int = 2,
    private val uploadRetryMs: List<Long> = listOf(1_000, 3_000),
) {
    private val mutableState = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    private val uploads = Channel<Long>(Channel.UNLIMITED)
    private val nextId = AtomicLong(1)
    private val lock = Any()
    private var poller: Job? = null

    init {
        repeat(uploadWorkers) {
            scope.launch { for (id in uploads) upload(id) }
        }
    }

    // --- capturing ------------------------------------------------------------------------------

    /** Adds a freshly taken photo at the front and starts sending it. */
    fun capture(photo: File): Long {
        val id = nextId.getAndIncrement()
        mutableState.update { it.copy(entries = listOf(ScanEntry(id, photo, Upload.WAITING)) + it.entries) }
        uploads.trySend(id)
        return id
    }

    /** Photos left over from an earlier run that never reached the server. Oldest first, so the newest ends up in front. */
    fun resumeLocal(files: List<File>) {
        val known = mutableState.value.entries.mapNotNull { it.photo }.toSet()
        files.filter { it !in known }.sortedBy { it.name }.forEach { capture(it) }
    }

    /** Picks up scans the server still holds for this user (started earlier, or from the web app). */
    suspend fun resumeServer() {
        mutableState.update { it.copy(loading = true) }
        val result = attempt {
            val known = mutableState.value.entries.mapNotNull { it.jobId }.toSet()
            backend.scanJobs().filter { it.id !in known }.map { backend.scanJob(it.id) }
        }
        result
            .onSuccess { jobs ->
                val found = jobs.flatMap { job ->
                    job.items.filter { !it.resolved }.map { item ->
                        ScanEntry(nextId.getAndIncrement(), photo = null, upload = Upload.SENT, jobId = job.id, item = item)
                    }
                }.take(MAX_RESUMED)
                mutableState.update { it.copy(entries = it.entries + found, loading = false) }
                ensurePolling()
            }
            .onFailure { error -> mutableState.update { it.copy(loading = false, message = describe(error)) } }
    }

    fun resend(entryId: Long) {
        val entry = find(entryId) ?: return
        if (entry.upload != Upload.FAILED) return
        update(entryId) { it.copy(upload = Upload.WAITING, uploadError = null) }
        uploads.trySend(entryId)
    }

    /** Drops a photo that never reached the server. */
    fun discard(entryId: Long) {
        val entry = find(entryId) ?: return
        mutableState.update { s -> s.copy(entries = s.entries.filterNot { it.id == entryId }) }
        scope.launch { deleteLocalPhoto(entry.photo) }
    }

    /** Clears the cards that are already added or skipped, leaving the ones that still need attention. */
    fun clearHandled() {
        mutableState.update { s -> s.copy(entries = s.entries.filterNot { it.isHandled }) }
    }

    fun dismissMessage() = mutableState.update { it.copy(message = null) }

    // --- choosing ---------------------------------------------------------------------------------

    fun select(entryId: Long, index: Int) = update(entryId) { entry ->
        val last = ((entry.item?.matches?.size ?: 1) - 1).coerceAtLeast(0)
        // A different candidate may be another language, so go back to following the candidate's own.
        entry.copy(candidate = index.coerceIn(0, last), edits = entry.edits?.copy(lang = null), error = null)
    }

    fun setEdits(entryId: Long, edits: AddEdits) = update(entryId) { it.copy(edits = edits, error = null) }

    /** Sets the starting choices the first time a card is opened, and leaves anything the user already chose. */
    fun initEdits(entryId: Long, defaults: AddEdits) = update(entryId) { it.copy(edits = it.edits ?: defaults) }

    // --- deciding ---------------------------------------------------------------------------------

    /** Adds the chosen candidate with the user's choices and marks the photo handled, in one server step. */
    fun add(entryId: Long, savePhotos: Boolean) {
        val entry = find(entryId) ?: return
        val jobId = entry.jobId ?: return
        val item = entry.item ?: return
        val match = entry.match ?: return
        if (entry.busy || entry.outcome != null) return
        update(entryId) { it.copy(busy = true, error = null) }
        scope.launch {
            // The server drops its copy of the photo once the card is handled, so get it first if it will be kept:
            // always when asked, and for cards with no official artwork (what the PokéCollector web app does).
            val photo = if (savePhotos || match.image.isNullOrBlank()) photoBytes(entry, jobId, item.id) else null
            attempt { backend.resolveAndAdd(jobId, item.id, match.toAddRequest(entry.edits ?: AddEdits())) }
                .onSuccess { response ->
                    // Tell the collection first, so duplicate counts are already right when the tile says "added".
                    onCollectionItem(response.collectionItem)
                    update(entryId) {
                        it.copy(busy = false, outcome = ScanOutcome.Added(match.name), item = response.item, photo = null)
                    }
                    // Best effort, like the web app: a failed photo upload never undoes the add.
                    if (photo != null) attempt { backend.uploadOwnerPhoto(response.collectionItem.id, photo) }
                    deleteLocalPhoto(entry.photo)
                }
                .onFailure { error ->
                    update(entryId) { it.copy(busy = false, error = describe(error)) }
                    if (error is HttpException && error.code() == 409) {
                        // Usually already handled (a retry after a lost reply): show the real state.
                        mutableState.update { it.copy(message = describe(error)) }
                        refreshJob(jobId)
                    }
                }
        }
    }

    fun skip(entryId: Long) {
        val entry = find(entryId) ?: return
        val jobId = entry.jobId
        val item = entry.item
        if (jobId == null || item == null) {
            discard(entryId)
            return
        }
        if (entry.busy || entry.outcome != null) return
        update(entryId) { it.copy(busy = true, error = null) }
        scope.launch {
            attempt { backend.skip(jobId, item.id) }
                .onSuccess { handled ->
                    update(entryId) { it.copy(busy = false, outcome = ScanOutcome.Skipped, item = handled, photo = null) }
                    deleteLocalPhoto(entry.photo)
                }
                .onFailure { error -> update(entryId) { it.copy(busy = false, error = describe(error)) } }
        }
    }

    /** Asks the scanner to read a photo again. */
    fun retryItem(entryId: Long) {
        val entry = find(entryId) ?: return
        val jobId = entry.jobId ?: return
        val item = entry.item ?: return
        if (entry.busy) return
        update(entryId) { it.copy(busy = true, error = null) }
        scope.launch {
            attempt { backend.retry(jobId, item.id) }
                .onSuccess { fresh ->
                    update(entryId) { it.copy(busy = false, item = fresh) }
                    ensurePolling()
                }
                .onFailure { error -> update(entryId) { it.copy(busy = false, error = describe(error)) } }
        }
    }

    // --- uploading --------------------------------------------------------------------------------

    private suspend fun upload(entryId: Long) {
        val photo = find(entryId)?.takeIf { it.upload == Upload.WAITING }?.photo ?: return
        update(entryId) { it.copy(upload = Upload.SENDING, uploadError = null) }

        var failure: Throwable? = null
        for (tryNumber in 0..uploadRetryMs.size) {
            if (tryNumber > 0) delay(uploadRetryMs[tryNumber - 1])
            if (find(entryId) == null) return // discarded while waiting
            val result = attempt { backend.enqueue(photo) }
            val job = result.getOrNull()
            if (job != null) {
                markSent(entryId, photo, job)
                return
            }
            failure = result.exceptionOrNull()
            // A rejected photo or a missing scanner key won't improve by asking again; a busy or unreachable server might.
            if (failure is HttpException && failure.code() in 400..499 && failure.code() != 408 && failure.code() != 429) break
        }
        update(entryId) { it.copy(upload = Upload.FAILED, uploadError = failure?.let(describe)) }
    }

    private suspend fun markSent(entryId: Long, photo: File, job: ScanJobDto) {
        // Renamed so a leftover file can be told apart from one that still needs sending.
        val kept = withContext(io) {
            val sent = File(photo.parentFile, "sent-" + photo.name.removePrefix("scan-"))
            if (photo.renameTo(sent)) sent else photo
        }
        update(entryId) { it.copy(upload = Upload.SENT, jobId = job.id, photo = kept) }
        ensurePolling()
    }

    // --- following the server ------------------------------------------------------------------

    private fun ensurePolling() {
        synchronized(lock) {
            // isActive, not null: on an immediately-dispatching scope a loop that finds nothing to do can finish
            // before its handle is stored here, and a finished handle must not stop polling from ever restarting.
            if (poller?.isActive != true) poller = scope.launch { pollLoop() }
        }
    }

    private suspend fun pollLoop() {
        while (true) {
            val jobs = synchronized(lock) {
                val working = mutableState.value.entries.filter { it.isWorking }.mapNotNull { it.jobId }.distinct()
                if (working.isEmpty()) poller = null
                working
            }
            if (jobs.isEmpty()) return

            var failed = false
            for (jobId in jobs) {
                attempt { backend.scanJob(jobId) }
                    .onSuccess { merge(it) }
                    .onFailure { error ->
                        if (error is HttpException && error.code() == 404) dropJob(jobId) else failed = true
                    }
            }
            val working = mutableState.value.entries.filter { it.isWorking }
            val onlyWaiting = working.isNotEmpty() && working.all { it.tileState() == TileState.WAITING }
            delay(if (failed) errorPollMs else if (onlyWaiting) waitingPollMs else pollMs)
        }
    }

    private suspend fun refreshJob(jobId: Int) {
        attempt { backend.scanJob(jobId) }.onSuccess { merge(it) }
    }

    private fun merge(job: ScanJobDto) = mutableState.update { s ->
        s.copy(
            entries = s.entries.mapNotNull { entry ->
                if (entry.jobId != job.id) return@mapNotNull entry
                val known = entry.item
                val fresh = if (known != null) job.items.firstOrNull { it.id == known.id } else job.items.firstOrNull()
                when {
                    fresh == null -> entry
                    // Handled somewhere else (the web app): nothing left to do here.
                    fresh.resolved && entry.outcome == null -> null
                    else -> entry.copy(
                        item = fresh,
                        candidate = entry.candidate.coerceIn(0, (fresh.matches.size - 1).coerceAtLeast(0)),
                    )
                }
            },
        )
    }

    private fun dropJob(jobId: Int) = mutableState.update { s ->
        s.copy(
            entries = s.entries.filterNot { it.jobId == jobId && it.outcome == null },
            message = "A scan is no longer on the server (scans expire after 14 days).",
        )
    }

    // --- helpers ------------------------------------------------------------------------------------

    private fun find(entryId: Long): ScanEntry? = mutableState.value.entries.firstOrNull { it.id == entryId }

    private fun update(entryId: Long, change: (ScanEntry) -> ScanEntry) = mutableState.update { s ->
        s.copy(entries = s.entries.map { if (it.id == entryId) change(it) else it })
    }

    private suspend fun photoBytes(entry: ScanEntry, jobId: Int, itemId: Int): ByteArray? {
        val local = entry.photo?.let { file -> withContext(io) { runCatching { file.readBytes() }.getOrNull() } }
        return local ?: attempt { backend.scanPhotoBytes(jobId, itemId) }.getOrNull()
    }

    private suspend fun deleteLocalPhoto(photo: File?) {
        if (photo != null) withContext(io) { runCatching { photo.delete() } }
    }

    private companion object {
        const val MAX_RESUMED = 200
    }
}
