package staff.android.durable

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.*
import androidx.work.testing.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import okhttp3.Headers
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DurableWorkTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: DurableDatabase
    private lateinit var store: DurableStore
    private lateinit var api: FakeApi
    private lateinit var name: String
    @Before fun before() {
        name = "test-${UUID.randomUUID()}.db"
        db = openDurable(context, name); api = FakeApi(); store = DurableStore(db, api)
    }
    @After fun after() { db.close(); context.deleteDatabase(name) }
    private suspend fun pending(): SessionTicket {
        val ticket = store.signIn("A"); store.refresh(ticket); store.save(ticket, "Check lens mount"); return ticket
    }
    @Test fun authorityAndPendingSurviveFreshDatabaseOwner() = runBlocking {
        val ticket = pending(); db.close(); db = openDurable(context, name); store = DurableStore(db, api)
        assertEquals(ticket, store.current()); assertTrue(store.eligible(ticket))
        assertEquals(AttemptResult.Finished, store.attempt(ticket))
        assertNull(store.dao.pending("A")); assertEquals(6L, store.dao.cached("A")!!.serverRevision)
    }
    @Test fun oldResponseCannotRepopulateSignedOutAccount() = runBlocking {
        val old = pending(); api.hold = true
        val attempt = async { store.attempt(old) }; api.started.await()
        store.signOut(store.dao.authority()!!)
        val next = store.signIn("B"); store.refresh(next)
        api.release.complete(Unit); attempt.await()
        assertEquals(next, store.current()); assertNull(store.dao.cached("A"))
        assertEquals("B base", store.dao.cached("B")!!.serverNote)
    }
    @Test fun reenteringSameAccountDoesNotReviveOldTicket() = runBlocking {
        val old = pending(); store.signOut(store.dao.authority()!!)
        val next = store.signIn("A"); store.refresh(next); store.save(next, "New session")
        assertNotEquals(old, next); assertEquals(AttemptResult.Finished, store.attempt(old))
        assertEquals(0, api.puts); assertEquals("New session", store.dao.pending("A")!!.note)
    }
    @Test fun lateAcknowledgementPreservesNewerLocalSave() = runBlocking {
        val ticket = pending(); api.hold = true
        val attempt = async { store.attempt(ticket) }; api.started.await()
        store.save(ticket, "Ask about warranty"); api.release.complete(Unit); attempt.await()
        val row = store.dao.pending("A")!!
        assertEquals(2L, row.localRevision); assertEquals(6L, row.baseRevision)
        assertEquals("Ask about warranty", row.note)
        api.hold = false; store.attempt(ticket)
        assertNull(store.dao.pending("A")); assertEquals(7L, store.dao.cached("A")!!.serverRevision)
    }
    @Test fun transientRetryThenBudgetStopsWithTextRetained() = runBlocking {
        val ticket = pending(); api.failure = IOException("Offline")
        repeat(4) { assertEquals(AttemptResult.Retry, store.attempt(ticket)) }
        assertEquals(AttemptResult.Finished, store.attempt(ticket))
        assertEquals("Retry budget exhausted", store.dao.pending("A")!!.attention)
        assertFalse(store.eligible(ticket)); store.retry(ticket); assertTrue(store.eligible(ticket))
    }
    @Test fun httpPolicyDistinguishesTransientPermanentAndProtocolFailure() = runBlocking {
        val ticket = pending()
        api.failure = HttpStatusFailure(503); assertEquals(AttemptResult.Retry, store.attempt(ticket))
        api.failure = HttpStatusFailure(403); assertEquals(AttemptResult.Finished, store.attempt(ticket))
        assertFalse(store.eligible(ticket)); store.retry(ticket)
        api.failure = ContractFailure("Wrong ETag"); assertEquals(AttemptResult.Finished, store.attempt(ticket))
        assertEquals("Service contract needs review", store.dao.pending("A")!!.attention)
    }
    @Test fun retryAfterIsDurableAndNewSaveCannotRetryEarly() = runBlocking {
        var time = 1000L
        store = DurableStore(db, api) { time }
        val ticket = pending(); api.failure = HttpStatusFailure(429, 10000)
        assertEquals(AttemptResult.Retry, store.attempt(ticket)); assertEquals(1, api.puts)
        store.save(ticket, "New edit during rate limit"); api.failure = null
        val recreated = DurableStore(db, api) { time }
        assertEquals(AttemptResult.Retry, recreated.attempt(ticket)); assertEquals(1, api.puts)
        time = 10000; recreated.attempt(ticket); assertEquals(2, api.puts)
        assertNull(store.dao.pending("A"))
    }
    @Test fun unauthorizedPersistsExpiryAndRetainsPending() = runBlocking {
        val old = pending(); api.failure = HttpStatusFailure(401)
        assertEquals(AttemptResult.Finished, store.attempt(old)); assertNull(store.current())
        db.close(); db = openDurable(context, name); store = DurableStore(db, api)
        assertNull(store.current()); assertEquals("Check lens mount", store.dao.pending("A")!!.note)
        val renewed = store.signIn("A"); assertTrue(renewed.generation > old.generation)
        api.failure = null; store.attempt(renewed); assertNull(store.dao.pending("A"))
    }
    @Test fun cancellationLeavesAnEligibleDurableRow() = runBlocking {
        val ticket = pending(); api.hold = true
        val attempt = async { store.attempt(ticket) }; api.started.await(); attempt.cancelAndJoin()
        assertTrue(store.eligible(ticket)); api.hold = false; store.attempt(ticket)
        assertNull(store.dao.pending("A"))
    }
    @Test fun conflictIsCompletedAttemptButUnresolvedProductState() = runBlocking {
        val ticket = pending(); api.remote = RemoteNote("Check shutter count", 6)
        assertEquals(AttemptResult.Finished, store.attempt(ticket))
        assertTrue(store.dao.pending("A")!!.conflict); assertFalse(store.eligible(ticket))
        store.resolve(ticket, 6, 1, true); assertTrue(store.eligible(ticket))
        store.attempt(ticket); assertNull(store.dao.pending("A"))
    }
    @Test fun exhaustedOlderAttemptCannotBlockReplacementSave() = runBlocking {
        val ticket = pending(); store.dao.enqueue(store.dao.pending("A")!!.copy(attempts = 4))
        api.hold = true; api.failure = IOException("Unavailable")
        val attempt = async { store.attempt(ticket) }; api.started.await()
        store.save(ticket, "New draft"); api.release.complete(Unit); attempt.await()
        assertTrue(store.eligible(ticket)); assertEquals("New draft", store.dao.pending("A")!!.note)
    }
    private fun factory() = object : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String, params: WorkerParameters): ListenableWorker? =
            if (workerClassName == NoteWorker::class.java.name) NoteWorker(appContext, params, store) else null
    }
    @Test fun actualCoroutineWorkerMapsRetryAndHandledTerminalResults() = runBlocking {
        val ticket = pending(); api.failure = HttpStatusFailure(503)
        val retry = TestListenableWorkerBuilder<NoteWorker>(context).setWorkerFactory(factory())
            .setInputData(ticketData(ticket)).build().doWork()
        assertEquals(ListenableWorker.Result.retry(), retry)
        api.failure = HttpStatusFailure(401)
        val expired = TestListenableWorkerBuilder<NoteWorker>(context).setWorkerFactory(factory())
            .setInputData(ticketData(ticket)).build().doWork()
        assertEquals(ListenableWorker.Result.success(), expired)
    }
    @Test fun realWorkManagerConstraintsAndAppendedSuccessorPreserveWakeup() = runBlocking {
        val ticket = pending()
        val executor = Executors.newSingleThreadExecutor()
        try {
            WorkManagerTestInitHelper.initializeTestWorkManager(context,
                Configuration.Builder().setExecutor(executor).setWorkerFactory(factory()).build())
            val manager = WorkManager.getInstance(context)
            val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))
            fun request() = OneTimeWorkRequestBuilder<NoteWorker>().setInputData(ticketData(ticket))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            val first = request(); val second = request()
            manager.enqueueUniqueWork("test-chain", ExistingWorkPolicy.APPEND_OR_REPLACE, first).await()
            assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoById(first.id).get()!!.state)
            assertEquals(0, api.puts)
            api.hold = true; driver.setAllConstraintsMet(first.id); withTimeout(5000) { api.started.await() }
            store.save(ticket, "Saved while first runs")
            manager.enqueueUniqueWork("test-chain", ExistingWorkPolicy.APPEND_OR_REPLACE, second).await()
            api.hold = false; api.release.complete(Unit)
            withTimeout(5000) { while (manager.getWorkInfoById(first.id).get()!!.state != WorkInfo.State.SUCCEEDED) delay(20) }
            driver.setAllConstraintsMet(second.id)
            withTimeout(5000) { while (manager.getWorkInfoById(second.id).get()!!.state != WorkInfo.State.SUCCEEDED) delay(20) }
            assertNull(store.dao.pending("A")); assertEquals("Saved while first runs", store.dao.cached("A")!!.serverNote)
        } finally { executor.shutdown() }
    }
    @Test fun catalogueCancelPreventsLatePublication() = runBlocking {
        val ticket = pending(); val start = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val downloads = CatalogDownloads(context, store) { start.complete(Unit); release.await(); catalog() }
        val item = downloads.prepare(ticket)
        val running = async { downloads.run(item.id) }; start.await()
        downloads.cancel(item.id); release.complete(Unit); running.await()
        val row = store.dao.download(item.id)!!
        assertEquals("Cancelled", row.state); assertNull(row.path)
        downloads.deleteAccountFiles("A")
    }
    @Test fun repeatedSuccessRetainsOnlyOneCompletedCatalogFile() = runBlocking {
        val ticket = pending(); val downloads = CatalogDownloads(context, store) { catalog() }
        repeat(3) { val item = downloads.prepare(ticket); downloads.run(item.id) }
        val files = java.io.File(context.filesDir, "catalogs/A").listFiles()!!
            .filter { it.extension == "catalog" }
        assertEquals(1, files.size)
        assertEquals(store.dao.latestDownload()!!.path, files.single().path)
        downloads.deleteAccountFiles("A")
    }
    @Test fun oldCancelCannotCancelReplacementRequest() = runBlocking {
        val ticket = pending(); val downloads = CatalogDownloads(context, store) { catalog() }
        val old = downloads.prepare(ticket); val fresh = downloads.prepare(ticket)
        downloads.cancel(old.id)
        assertEquals("Queued", store.dao.download(fresh.id)!!.state)
        downloads.run(fresh.id); assertEquals("Complete", store.dao.download(fresh.id)!!.state)
        downloads.deleteAccountFiles("A")
    }
    @Test fun replacementCannotEnterTheDatabaseToPlatformCancelGap() = runBlocking {
        val ticket = pending(); val downloads = CatalogDownloads(context, store) { catalog() }
        val old = downloads.prepare(ticket)
        val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        downloads.beforePlatformCancel = { inside.complete(Unit); release.await() }
        val cancelling = async { downloads.cancel(old.id) }; inside.await()
        val replacement = async { downloads.prepare(ticket) }
        yield(); assertFalse(replacement.isCompleted)
        release.complete(Unit); cancelling.await()
        val fresh = replacement.await(); assertEquals("Queued", store.dao.download(fresh.id)!!.state)
        downloads.deleteAccountFiles("A")
    }
    @Test fun catalogueRejectsPublicationAfterAuthorityExpiry() = runBlocking {
        val ticket = pending(); val start = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val downloads = CatalogDownloads(context, store) { start.complete(Unit); release.await(); catalog() }
        val item = downloads.prepare(ticket); val running = async { downloads.run(item.id) }; start.await()
        store.expire(ticket); release.complete(Unit); running.await()
        assertEquals("Cancelled", store.dao.download(item.id)!!.state); assertNull(store.dao.download(item.id)!!.path)
        downloads.deleteAccountFiles("A")
    }
    @Test fun fileReconciliationRemovesOrphansAndFlagsMissingPublication() = runBlocking {
        val ticket = pending(); val downloads = CatalogDownloads(context, store) { catalog() }
        val item = downloads.prepare(ticket); downloads.run(item.id)
        val row = store.dao.download(item.id)!!; java.io.File(row.path!!).delete()
        val orphan = java.io.File(context.filesDir, "catalogs/A/orphan.part").apply { writeText("incomplete") }
        val freshOwner = CatalogDownloads(context, store) { catalog() }
        freshOwner.run(item.id)
        assertFalse(orphan.exists()); assertEquals("Needs attention", store.dao.download(item.id)!!.state)
        downloads.deleteAccountFiles("A")
    }
    private fun catalog() = HttpReply(200, Headers.headersOf(), ByteArray(48 * 1024) { 65 })
    private class FakeApi : NoteApi {
        var remote = RemoteNote("Compare lenses", 5)
        var hold = false
        var failure: IOException? = null
        var puts = 0
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        override suspend fun fetch(accountId: String) = if (accountId == "A") remote else RemoteNote("B base", 5)
        override suspend fun put(accountId: String, baseRevision: Long, note: String): WriteReply {
            puts++; started.complete(Unit); if (hold) release.await(); failure?.let { throw it }
            if (remote.revision != baseRevision) return WriteReply.Conflict(remote)
            remote = RemoteNote(note, baseRevision + 1); return WriteReply.Accepted(remote)
        }
    }
}
