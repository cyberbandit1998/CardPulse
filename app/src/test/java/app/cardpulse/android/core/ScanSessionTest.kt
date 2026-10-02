package app.cardpulse.android.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private fun httpError(code: Int, body: String = """{"detail":"nope"}""") =
    HttpException(Response.error<Any>(code, body.toResponseBody("application/json".toMediaType())))

/** A server whose scans only change when the test says so. One item per job, with the same id as the job. */
private class FakeBackend : ScanBackend {
    class Job(
        @Volatile var status: String = "pending",
        @Volatile var matches: List<ScanMatchDto> = emptyList(),
        @Volatile var resolved: Boolean = false,
        @Volatile var gone: Boolean = false,
    )

    val jobs = ConcurrentHashMap<Int, Job>()
    val enqueued = CopyOnWriteArrayList<Pair<Int, File>>()
    val enqueueCalls = AtomicInteger()
    @Volatile var enqueueFailures = 0
    @Volatile var enqueueError: Throwable = IOException("offline")
    val scanJobCalls = AtomicInteger()
    @Volatile var addError: Throwable? = null
    val adds = CopyOnWriteArrayList<Triple<Int, Int, ResolveAndAddRequest>>()
    val skips = CopyOnWriteArrayList<Pair<Int, Int>>()
    val retries = CopyOnWriteArrayList<Pair<Int, Int>>()
    val ownerPhotos = CopyOnWriteArrayList<Pair<Int, ByteArray>>()
    val serverPhotoReads = AtomicInteger()
    private val nextJob = AtomicInteger(100)

    private fun itemOf(jobId: Int): ScanItemDto {
        val job = jobs.getValue(jobId)
        return ScanItemDto(id = jobId, status = job.status, resolved = job.resolved, matches = job.matches)
    }

    private fun jobOf(jobId: Int): ScanJobDto {
        val job = jobs.getValue(jobId)
        val active = if (!job.resolved && job.status in setOf("pending", "processing", "retrying")) 1 else 0
        return ScanJobDto(id = jobId, total = 1, active = active, items = listOf(itemOf(jobId)))
    }

    /** A job the server already holds, as if it had been started earlier. */
    fun existing(status: String = "pending", matches: List<ScanMatchDto> = emptyList(), resolved: Boolean = false): Int {
        val id = nextJob.incrementAndGet()
        jobs[id] = Job(status, matches, resolved)
        return id
    }

    override suspend fun enqueue(photo: File): ScanJobDto {
        enqueueCalls.incrementAndGet()
        if (enqueueFailures > 0) {
            enqueueFailures--
            throw enqueueError
        }
        val id = existing()
        enqueued += id to photo
        return ScanJobDto(id = id, total = 1, active = 1) // like the real server: progress only, no items yet
    }

    override suspend fun scanJobs(): List<ScanJobDto> =
        jobs.keys.sortedDescending().filter { !jobs.getValue(it).resolved }.map { jobOf(it).copy(items = emptyList()) }

    override suspend fun scanJob(jobId: Int): ScanJobDto {
        scanJobCalls.incrementAndGet()
        val job = jobs[jobId]
        if (job == null || job.gone) throw httpError(404)
        return jobOf(jobId)
    }

    override suspend fun resolveAndAdd(jobId: Int, itemId: Int, request: ResolveAndAddRequest): ResolveAndAddResponse {
        addError?.let { throw it }
        adds += Triple(jobId, itemId, request)
        jobs.getValue(jobId).resolved = true
        return ResolveAndAddResponse(
            item = itemOf(jobId),
            collectionItem = CollectionItemDto(
                id = 900 + jobId, cardId = request.cardId, quantity = request.quantity,
                condition = request.condition, variant = request.variant, lang = request.lang,
            ),
        )
    }

    override suspend fun skip(jobId: Int, itemId: Int): ScanItemDto {
        skips += jobId to itemId
        jobs.getValue(jobId).resolved = true
        return itemOf(jobId)
    }

    override suspend fun retry(jobId: Int, itemId: Int): ScanItemDto {
        retries += jobId to itemId
        jobs.getValue(jobId).status = "pending"
        return itemOf(jobId)
    }

    override suspend fun scanPhotoBytes(jobId: Int, itemId: Int): ByteArray {
        serverPhotoReads.incrementAndGet()
        return "server-copy".toByteArray()
    }

    override suspend fun uploadOwnerPhoto(collectionItemId: Int, jpeg: ByteArray) {
        ownerPhotos += collectionItemId to jpeg
    }
}

class ScanSessionTest {
    private lateinit var dir: File
    private lateinit var scope: CoroutineScope
    private lateinit var backend: FakeBackend
    private lateinit var session: ScanSession
    private val collectionUpdates = CopyOnWriteArrayList<CollectionItemDto>()

    private val charizard = ScanMatchDto(
        id = "sv3-125_en", tcgCardId = "sv3-125", name = "Charizard ex", set = "Obsidian Flames", number = "125",
        image = "https://img.example/sv3-125.png", lang = "en",
    )
    private val glurak = ScanMatchDto(
        id = "sv3-125_de", tcgCardId = "sv3-125", name = "Glurak ex", image = "https://img.example/sv3-125-de.png", lang = "de",
    )

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("rapid-scan").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        backend = FakeBackend()
        session = ScanSession(
            backend = backend,
            scope = scope,
            onCollectionItem = { collectionUpdates += it },
            describe = { it.userMessage() },
            pollMs = 10, waitingPollMs = 20, errorPollMs = 20, uploadRetryMs = listOf(10, 10),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun photo(name: String, text: String = "jpeg-$name"): File = File(dir, "scan-$name.jpg").apply { writeText(text) }

    private suspend fun await(timeoutMs: Long = 5_000, until: (SessionState) -> Boolean): SessionState =
        withTimeout(timeoutMs) { session.state.first(until) }

    /** For things that happen just after the state changes, like deleting a file. */
    private suspend fun awaitTrue(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        withTimeout(timeoutMs) { while (!condition()) delay(5) }
    }

    private fun SessionState.entry(id: Long) = entries.first { it.id == id }
    private fun SessionState.tile(id: Long) = entries.firstOrNull { it.id == id }?.tileState()
    private fun jobFor(file: File) = backend.enqueued.first { it.second == file }.first
    private fun finishReading(jobId: Int, vararg matches: ScanMatchDto) {
        backend.jobs.getValue(jobId).apply {
            this.matches = matches.toList()
            status = "done"
        }
    }

    /** Takes a photo and waits until it is read and ready for review. Returns the entry id and its job. */
    private suspend fun scanReady(name: String, vararg matches: ScanMatchDto, text: String = "jpeg-$name"): Pair<Long, Int> {
        val file = photo(name, text)
        val id = session.capture(file)
        await { it.tile(id) == TileState.READING }
        val job = jobFor(file)
        finishReading(job, *matches)
        await { it.tile(id) == TileState.READY }
        return id to job
    }

    // --- sending and reading ----------------------------------------------------------------------

    @Test
    fun `a photo is sent at once and becomes ready when the server has read it`() = runBlocking {
        val file = photo("1")
        val id = session.capture(file)
        // The tile exists before anything has been sent.
        assertEquals(id, session.state.value.entries.first().id)

        await { it.tile(id) == TileState.READING }
        finishReading(jobFor(file), charizard)
        val state = await { it.tile(id) == TileState.READY }

        assertEquals("Charizard ex", state.entry(id).match?.name)
        assertEquals(1, state.toReview.size)
        assertEquals(0, state.inFlight)
        // Renamed once sent, so a leftover file can be told apart from one that still needs sending.
        assertFalse(file.exists())
        assertTrue(File(dir, "sent-1.jpg").exists())
    }

    @Test
    fun `several photos are sent without waiting for each other, newest first`() = runBlocking {
        val ids = (1..3).map { session.capture(photo("$it")) }
        assertEquals(ids.reversed(), session.state.value.entries.map { it.id })

        await { state -> ids.all { state.tile(it) == TileState.READING } }
        assertEquals(3, backend.enqueued.size)
        backend.enqueued.forEach { (job, _) -> finishReading(job, charizard) }

        assertEquals(3, await { it.toReview.size == 3 }.toReview.size)
    }

    @Test
    fun `a failed upload is retried, then offered again, and nothing is lost`() = runBlocking {
        backend.enqueueFailures = 10
        val file = photo("1")
        val id = session.capture(file)

        val failed = await { it.tile(id) == TileState.SEND_FAILED }
        assertEquals(3, backend.enqueueCalls.get()) // the first try and two retries
        assertEquals("offline", failed.entry(id).uploadError)
        assertTrue(file.exists())

        backend.enqueueFailures = 0
        session.resend(id)
        await { it.tile(id) == TileState.READING }
        assertEquals(1, backend.enqueued.size)
    }

    @Test
    fun `a photo the server rejects is not retried`() = runBlocking {
        backend.enqueueFailures = 10
        backend.enqueueError = httpError(400)
        val id = session.capture(photo("1"))

        val failed = await { it.tile(id) == TileState.SEND_FAILED }
        assertEquals(1, backend.enqueueCalls.get())
        assertEquals("nope", failed.entry(id).uploadError)
    }

    @Test
    fun `a photo that was waiting to retry shows as waiting, and a no-match read is flagged`() = runBlocking {
        val file = photo("1")
        val id = session.capture(file)
        await { it.tile(id) == TileState.READING }
        val job = jobFor(file)

        backend.jobs.getValue(job).status = "retrying"
        await { it.tile(id) == TileState.WAITING }

        finishReading(job) // done, but nothing matched
        val state = await { it.tile(id) == TileState.NO_MATCH }
        assertEquals(0, state.toReview.size)
    }

    @Test
    fun `reading stops being checked once nothing is left to read`() = runBlocking {
        scanReady("1", charizard)
        delay(100) // let a poll that was in flight finish
        val before = backend.scanJobCalls.get()
        delay(150)
        assertEquals(before, backend.scanJobCalls.get())
    }

    @Test
    fun `a failed read can be tried again`() = runBlocking {
        val file = photo("1")
        val id = session.capture(file)
        await { it.tile(id) == TileState.READING }
        val job = jobFor(file)
        backend.jobs.getValue(job).status = "failed"
        await { it.tile(id) == TileState.FAILED }

        session.retryItem(id)
        awaitTrue { backend.retries.isNotEmpty() }
        finishReading(job, charizard)
        await { it.tile(id) == TileState.READY }
        assertEquals(listOf(job to job), backend.retries.toList())
    }

    // --- adding and skipping -------------------------------------------------------------------------

    @Test
    fun `adding sends the chosen candidate with the user's choices`() = runBlocking {
        val (id, job) = scanReady("1", charizard, glurak)

        session.select(id, 1)
        session.initEdits(id, AddEdits(condition = "LP"))
        session.setEdits(id, session.state.value.entry(id).edits!!.copy(quantity = 2, variant = "Holo", purchasePrice = 3.5))
        session.add(id, savePhotos = false)

        val state = await { it.tile(id) == TileState.ADDED }
        val (sentJob, sentItem, request) = backend.adds.single()
        assertEquals(job, sentJob)
        assertEquals(job, sentItem)
        assertEquals("sv3-125_de", request.cardId)
        assertEquals("sv3-125", request.confirmedCardId)
        assertEquals("de", request.lang)
        assertEquals(2, request.quantity)
        assertEquals("LP", request.condition)
        assertEquals("Holo", request.variant)
        assertEquals(3.5, request.purchasePrice!!, 0.0)
        assertEquals(ScanOutcome.Added("Glurak ex"), state.entry(id).outcome)
        assertEquals(1, collectionUpdates.size) // the collection screen hears about it
        assertEquals(0, backend.ownerPhotos.size) // official art exists, so the photo isn't kept
        awaitTrue { !File(dir, "sent-1.jpg").exists() } // and the local copy is cleaned up
    }

    @Test
    fun `adding in another language asks for that language's version`() = runBlocking {
        val (id, _) = scanReady("1", charizard)
        session.initEdits(id, AddEdits())
        session.setEdits(id, session.state.value.entry(id).edits!!.copy(lang = "fr"))
        session.add(id, savePhotos = false)

        await { it.tile(id) == TileState.ADDED }
        val request = backend.adds.single().third
        assertEquals("sv3-125_fr", request.cardId)
        assertEquals("sv3-125", request.confirmedCardId)
        assertEquals("fr", request.lang)
    }

    @Test
    fun `the user's photo is kept when asked, and when the card has no official art`() = runBlocking {
        val (asked, _) = scanReady("1", charizard, text = "mine")
        session.add(asked, savePhotos = true)
        await { it.tile(asked) == TileState.ADDED }
        awaitTrue { backend.ownerPhotos.size == 1 }
        assertEquals("mine", String(backend.ownerPhotos.single().second))

        val (noArt, _) = scanReady("2", charizard.copy(image = null), text = "plain")
        session.add(noArt, savePhotos = false)
        await { it.tile(noArt) == TileState.ADDED }
        awaitTrue { backend.ownerPhotos.size == 2 }
        assertEquals("plain", String(backend.ownerPhotos.last().second))
    }

    @Test
    fun `the server's copy of the photo is used when the local one is gone`() = runBlocking {
        val (id, _) = scanReady("1", charizard)
        session.state.value.entry(id).photo!!.delete()
        session.add(id, savePhotos = true)
        await { it.tile(id) == TileState.ADDED }
        awaitTrue { backend.ownerPhotos.size == 1 }
        assertEquals("server-copy", String(backend.ownerPhotos.single().second))
    }

    @Test
    fun `a failed add keeps the card ready and says why`() = runBlocking {
        val (id, _) = scanReady("1", charizard)
        backend.addError = httpError(404, """{"detail":"Card is no longer available locally."}""")
        session.add(id, savePhotos = false)

        val state = await { it.entry(id).error != null }
        assertEquals("Card is no longer available locally.", state.entry(id).error)
        assertEquals(TileState.READY, state.tile(id))
        assertFalse(state.entry(id).busy)

        backend.addError = null // fixing the choice and trying again works
        session.add(id, savePhotos = false)
        assertEquals(TileState.ADDED, await { it.tile(id) == TileState.ADDED }.tile(id))
    }

    @Test
    fun `a conflict on add shows the card's real state instead of an error`() = runBlocking {
        val (id, job) = scanReady("1", charizard)
        backend.addError = httpError(409, """{"detail":"This scan has already been handled."}""")
        backend.jobs.getValue(job).resolved = true // it was handled somewhere else in the meantime

        session.add(id, savePhotos = false)
        val state = await { s -> s.entries.none { it.id == id } }
        assertEquals("This scan has already been handled.", state.message)
    }

    @Test
    fun `skipping marks the card handled and cleans up`() = runBlocking {
        val (id, job) = scanReady("1", charizard)
        session.skip(id)
        await { it.tile(id) == TileState.SKIPPED }
        assertEquals(listOf(job to job), backend.skips.toList())
        awaitTrue { !File(dir, "sent-1.jpg").exists() }
    }

    @Test
    fun `skipping a photo that never reached the server just discards it`() = runBlocking {
        backend.enqueueFailures = 10
        val file = photo("1")
        val id = session.capture(file)
        await { it.tile(id) == TileState.SEND_FAILED }

        session.skip(id)
        await { state -> state.entries.isEmpty() }
        assertTrue(backend.skips.isEmpty())
        awaitTrue { !file.exists() }
    }

    @Test
    fun `choosing another candidate goes back to following that candidate's language`() = runBlocking {
        val (id, _) = scanReady("1", charizard, glurak)
        session.initEdits(id, AddEdits())
        session.setEdits(id, AddEdits(quantity = 3, lang = "fr"))

        session.select(id, 1)
        val entry = session.state.value.entry(id)
        assertEquals(1, entry.candidate)
        assertEquals("Glurak ex", entry.match?.name)
        assertNull(entry.edits!!.lang)
        assertEquals(3, entry.edits!!.quantity)

        session.select(id, 99) // out of range stays on a real candidate
        assertEquals(1, session.state.value.entry(id).candidate)
    }

    @Test
    fun `initial choices never overwrite what the user already chose`() = runBlocking {
        val (id, _) = scanReady("1", charizard)
        session.initEdits(id, AddEdits(condition = "LP"))
        session.setEdits(id, AddEdits(condition = "HP"))
        session.initEdits(id, AddEdits(condition = "Mint"))
        assertEquals("HP", session.state.value.entry(id).edits!!.condition)
    }

    @Test
    fun `clearing handled cards keeps the ones that still need attention`() = runBlocking {
        val (added, _) = scanReady("1", charizard)
        val (waiting, _) = scanReady("2", charizard)
        session.add(added, savePhotos = false)
        await { it.tile(added) == TileState.ADDED }

        session.clearHandled()
        assertEquals(listOf(waiting), session.state.value.entries.map { it.id })
    }

    // --- picking up where things were left ------------------------------------------------------------

    @Test
    fun `scans the server already holds are picked up and keep being followed`() = runBlocking {
        val ready = backend.existing("done", listOf(charizard))
        val busy = backend.existing("processing")
        backend.existing("done", listOf(charizard), resolved = true) // already handled: not shown

        session.resumeServer()
        val state = await { it.entries.size == 2 && !it.loading }
        assertEquals(setOf(TileState.READY, TileState.READING), state.entries.map { it.tileState() }.toSet())
        assertEquals(setOf(ready, busy), state.entries.map { it.jobId }.toSet())

        finishReading(busy, charizard)
        assertEquals(2, await { it.toReview.size == 2 }.toReview.size)
    }

    @Test
    fun `a scan the server no longer has is dropped with an explanation`() = runBlocking {
        val file = photo("1")
        val id = session.capture(file)
        await { it.tile(id) == TileState.READING }
        backend.jobs.getValue(jobFor(file)).gone = true

        val state = await { it.entries.isEmpty() }
        assertTrue(state.message!!, state.message!!.contains("no longer on the server"))
    }

    @Test
    fun `a scan handled somewhere else disappears when next checked`() = runBlocking {
        val file = photo("1")
        val id = session.capture(file)
        await { it.tile(id) == TileState.READING }
        backend.jobs.getValue(jobFor(file)).resolved = true
        assertTrue(await { state -> state.entries.isEmpty() }.entries.isEmpty())
    }

    @Test
    fun `photos left over from an earlier run are sent, once each`() = runBlocking {
        val first = photo("a")
        val second = photo("b")
        session.resumeLocal(listOf(second, first))
        session.resumeLocal(listOf(first, second)) // asking again adds nothing
        assertEquals(2, session.state.value.entries.size)

        val state = await { s -> s.entries.all { it.upload == Upload.SENT } }
        assertEquals(listOf("sent-b.jpg", "sent-a.jpg"), state.entries.map { it.photo!!.name })
        assertEquals(2, backend.enqueued.size)
    }

    // --- what each tile says ------------------------------------------------------------------------

    private fun entry(
        upload: Upload = Upload.SENT,
        status: String? = null,
        matches: List<ScanMatchDto> = emptyList(),
        resolved: Boolean = false,
        outcome: ScanOutcome? = null,
    ) = ScanEntry(
        id = 1, photo = null, upload = upload, jobId = 1,
        item = status?.let { ScanItemDto(id = 1, status = it, resolved = resolved, matches = matches) },
        outcome = outcome,
    )

    @Test
    fun `tiles say what is happening to each photo`() {
        assertEquals(TileState.SENDING, entry(upload = Upload.WAITING).tileState())
        assertEquals(TileState.SENDING, entry(upload = Upload.SENDING).tileState())
        assertEquals(TileState.SEND_FAILED, entry(upload = Upload.FAILED).tileState())
        assertEquals(TileState.READING, entry().tileState()) // sent, not looked at yet
        assertEquals(TileState.READING, entry(status = "pending").tileState())
        assertEquals(TileState.READING, entry(status = "processing").tileState())
        assertEquals(TileState.WAITING, entry(status = "retrying").tileState())
        assertEquals(TileState.READY, entry(status = "done", matches = listOf(charizard)).tileState())
        assertEquals(TileState.NO_MATCH, entry(status = "done").tileState())
        assertEquals(TileState.FAILED, entry(status = "failed").tileState())
        assertEquals(TileState.SKIPPED, entry(status = "done", matches = listOf(charizard), resolved = true).tileState())
        assertEquals(TileState.ADDED, entry(status = "done", outcome = ScanOutcome.Added("x")).tileState())
        assertEquals(TileState.SKIPPED, entry(status = "done", outcome = ScanOutcome.Skipped).tileState())
    }

    @Test
    fun `only photos the server may still change keep being checked`() {
        assertTrue(entry().isWorking)
        assertTrue(entry(status = "processing").isWorking)
        assertTrue(entry(status = "retrying").isWorking)
        assertFalse(entry(status = "done", matches = listOf(charizard)).isWorking)
        assertFalse(entry(status = "failed").isWorking)
        assertFalse(entry(upload = Upload.WAITING).isWorking)
        assertFalse(entry(status = "processing", outcome = ScanOutcome.Skipped).isWorking)
    }
}
