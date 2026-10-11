package staff.android.durable

import android.app.Application
import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

fun sessionTag(ticket: SessionTicket) = "session:${ticket.accountId}:${ticket.generation}"
fun ticketData(ticket: SessionTicket) = workDataOf(
    "account" to ticket.accountId, "generation" to ticket.generation)
fun Data.ticket(): SessionTicket? {
    val account = getString("account") ?: return null
    val generation = getLong("generation", -1)
    return if (account in setOf("A", "B") && generation > 0) SessionTicket(account, generation) else null
}

class NoteScheduler(private val manager: WorkManager) {
    suspend fun installRecovery() {
        val request = PeriodicWorkRequestBuilder<RecoveryWorker>(15, TimeUnit.MINUTES).build()
        manager.enqueueUniquePeriodicWork("note-recovery", ExistingPeriodicWorkPolicy.KEEP, request).await()
    }
    suspend fun enqueue(ticket: SessionTicket, recovery: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<NoteWorker>()
            .setInputData(ticketData(ticket))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(sessionTag(ticket)).build()
        manager.enqueueUniqueWork("notes:${sessionTag(ticket)}",
            if (recovery) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.APPEND_OR_REPLACE, request).await()
    }
    suspend fun cancel(ticket: SessionTicket) { manager.cancelAllWorkByTag(sessionTag(ticket)).await() }
}

class NoteWorker(context: Context, params: WorkerParameters, private val store: DurableStore) :
    CoroutineWorker(context, params) {
    constructor(context: Context, params: WorkerParameters) : this(context, params,
        (context.applicationContext as DurableApplication).graph.store)
    override suspend fun doWork(): Result {
        val ticket = inputData.ticket() ?: return Result.failure()
        return when (store.attempt(ticket)) {
            AttemptResult.Finished -> Result.success()
            AttemptResult.Retry -> Result.retry()
        }
    }
}
class RecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as DurableApplication).graph
        val ticket = graph.store.current() ?: return Result.success()
        try {
            if (graph.store.eligible(ticket)) graph.scheduler.enqueue(ticket, recovery = true)
        } catch (_: StaleSession) { /* Sign-out won the race. */ }
        return Result.success()
    }
}
class AppGraph(context: Context) {
    val database = openDurable(context)
    val store = DurableStore(database, HttpNoteApi("http://127.0.0.1:8789"))
    val scheduler = NoteScheduler(WorkManager.getInstance(context))
    val downloads = CatalogDownloads(context, store)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val recoveryReady = MutableStateFlow(false)
    val recoveryError = MutableStateFlow(false)
    fun startRecovery() {
        scope.launch {
            try {
                scheduler.installRecovery()
                recoveryReady.value = true
                recoveryError.value = false
                store.current()?.let { if (store.eligible(it)) scheduler.enqueue(it, recovery = true) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { recoveryError.value = true }
        }
    }
}
class DurableApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setJobSchedulerJobIdRange(20000, 29999).build()
    val graph: AppGraph by lazy { AppGraph(this) }
    override fun onCreate() { super.onCreate(); graph.startRecovery() }
}
