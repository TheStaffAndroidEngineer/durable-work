package staff.android.durable

import android.Manifest
import android.app.*
import android.app.job.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.room3.withWriteTransaction
import androidx.work.*
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request

const val CATALOG_JOB = 1401
const val CATALOG_NOTIFICATION = 1402
const val CATALOG_CHANNEL = "catalog-download"

class CatalogDownloads(private val context: Context, private val store: DurableStore,
    private val fetch: suspend (String) -> HttpReply = { account ->
        HttpTransport(client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).build())
            .exchange(Request.Builder().url("http://127.0.0.1:8789/catalog")
            .header("Authorization", "Bearer demo-$account").build())
    }) {
    private val jobs = context.getSystemService(JobScheduler::class.java)
    private val work = WorkManager.getInstance(context)
    private val root = File(context.filesDir, "catalogs").apply { mkdirs() }
    private val control = Mutex()
    internal var beforePlatformCancel: suspend () -> Unit = {}
    private val initialization = Mutex()
    private var initialized = false
    private suspend fun initializeFiles() = initialization.withLock {
        if (initialized) return@withLock
        // Single-process app: first access precedes any new download in this process.
        store.database.withWriteTransaction {
            val latest = store.dao.latestDownload()
            val authority = store.dao.authority()?.ticket()
            val retained = latest?.takeIf { it.state == "Complete" &&
                authority == SessionTicket(it.accountId, it.generation) }?.path
            root.walkTopDown().filter { it.isFile && it.path != retained }.forEach { it.delete() }
            if (latest?.state == "Complete" && (retained == null || !File(retained).isFile))
                store.dao.download(latest.copy(state = "Needs attention", path = null))
        }
        initialized = true
    }
    suspend fun prepare(ticket: SessionTicket): Download = control.withLock {
        initializeFiles()
        store.authorized(ticket) {
        store.dao.latestDownload()?.let { previous ->
            if (previous.state in setOf("Queued", "Downloading", "Interrupted"))
                store.dao.download(previous.copy(state = "Cancelled"))
        }
        val item = Download(UUID.randomUUID().toString(), ticket.accountId, ticket.generation)
        store.dao.download(item)
        item
        }
    }
    // Called directly from the visible Activity, after the durable request has been recorded.
    suspend fun schedule(item: Download): Boolean = control.withLock {
        store.authorized(SessionTicket(item.accountId, item.generation)) {
            check(store.dao.latestDownload()?.id == item.id) { "Request replaced" }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val extras = PersistableBundle().apply { putString("download", item.id) }
            val request = JobInfo.Builder(CATALOG_JOB, ComponentName(context, CatalogJobService::class.java))
                .setUserInitiated(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setEstimatedNetworkBytes(48 * 1024, 0).setExtras(extras).build()
            return@withLock jobs.schedule(request) == JobScheduler.RESULT_SUCCESS
        }
        val request = OneTimeWorkRequestBuilder<CatalogWorker>()
            .setInputData(workDataOf("download" to item.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("catalog").build()
        work.enqueueUniqueWork("catalog", ExistingWorkPolicy.REPLACE, request).await()
        true
    }
    suspend fun markAdmissionFailure(id: String, attemptId: String? = null) {
        val item = store.dao.download(id) ?: return
        try { store.authorized(SessionTicket(item.accountId, item.generation)) {
            val now = checkNotNull(store.dao.download(id))
            if (now.state in setOf("Queued", "Downloading", "Interrupted") &&
                (attemptId == null || now.attemptId == attemptId))
                store.dao.download(now.copy(state = "Needs attention"))
        } } catch (_: StaleSession) { }
    }
    suspend fun cancel(id: String) = control.withLock {
        val item = store.dao.download(id) ?: return@withLock
        val current = store.authorized(SessionTicket(item.accountId, item.generation)) {
            if (store.dao.latestDownload()?.id != id) false
            else {
                val now = checkNotNull(store.dao.download(id))
                store.dao.download(now.copy(state = "Cancelled", path = null)); true
            }
        }
        if (!current) return@withLock
        item.path?.let { File(it).delete() }
        beforePlatformCancel()
        cancelPlatform()
    }
    suspend fun signOut(authority: Authority) = control.withLock {
        store.signOut(authority)
        cancelPlatform()
        authority.accountId?.let { deleteAccountFiles(it) }
    }
    private suspend fun cancelPlatform() {
        jobs.cancel(CATALOG_JOB)
        work.cancelUniqueWork("catalog").await()
    }
    fun deleteAccountFiles(account: String) { File(root, account).deleteRecursively() }
    suspend fun run(id: String): Boolean {
        initializeFiles()
        val attemptId = UUID.randomUUID().toString()
        val item = store.dao.download(id) ?: return true
        val ticket = SessionTicket(item.accountId, item.generation)
        val directory = File(root, item.accountId).apply { mkdirs() }
        val temporary = File(directory, "$id-${UUID.randomUUID()}.part")
        try {
            val admitted = store.authorized(ticket) {
                val now = checkNotNull(store.dao.download(id))
                if (now.state !in setOf("Queued", "Downloading", "Interrupted")) false
                else { store.dao.download(now.copy(state = "Downloading", attemptId = attemptId)); true }
            }
            if (!admitted) return true
            val response = fetch(item.accountId)
            if (response.code == 401) { store.expire(ticket); return true }
            if (response.code != 200 || response.body.size != 48 * 1024)
                throw ContractFailure("Incomplete catalog fixture")
            withContext(Dispatchers.IO) { temporary.writeBytes(response.body) }
            currentCoroutineContext().ensureActive()
            store.authorized(ticket) {
                val now = checkNotNull(store.dao.download(id))
                currentCoroutineContext().ensureActive()
                if (now.state != "Downloading" || now.attemptId != attemptId) return@authorized
                val completed = File(directory, "$id.catalog")
                check(temporary.renameTo(completed)) { "Cannot publish catalog" }
                store.dao.download(now.copy(state = "Complete", path = completed.path,
                    bytes = response.body.size.toLong()))
                directory.listFiles()?.filter { it.extension == "catalog" && it != completed }
                    ?.forEach { check(it.delete()) { "Cannot remove superseded catalog" } }
            }
            return true
        } catch (cancelled: CancellationException) {
            // A callback is useful bookkeeping, never the source of resumption correctness.
            withContext(NonCancellable) {
                try { store.authorized(ticket) {
                    val now = checkNotNull(store.dao.download(id))
                    if (now.state == "Downloading" && now.attemptId == attemptId) store.dao.download(now.copy(state = "Interrupted"))
                } } catch (_: StaleSession) { }
            }
            throw cancelled
        }
        catch (_: StaleSession) { return true }
        catch (_: Exception) { markAdmissionFailure(id, attemptId); return false }
        finally { temporary.delete() }
    }
}
fun catalogNotification(context: Context, id: String): Notification {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(CATALOG_CHANNEL,
        "Catalog download", NotificationManager.IMPORTANCE_LOW))
    val intent = Intent(context, CancelCatalogReceiver::class.java)
        .setData(android.net.Uri.parse("staff-durable://catalog/$id"))
        .putExtra("download", id)
    val cancel = PendingIntent.getBroadcast(context, CATALOG_NOTIFICATION, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    return NotificationCompat.Builder(context, CATALOG_CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("Downloading catalog")
        .setContentText("Small lab fixture; restarts from the beginning if interrupted")
        .setOngoing(true).setProgress(0, 0, true)
        .addAction(0, "Cancel", cancel).build()
}
class CancelCatalogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("download") ?: return
        val pending = goAsync()
        val graph = (context.applicationContext as DurableApplication).graph
        graph.scope.launch {
            try { graph.downloads.cancel(id) } catch (_: StaleSession) { }
            finally { pending.finish() }
        }
    }
}
@androidx.annotation.RequiresApi(34)
class CatalogJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val attempts = mutableMapOf<Int, Job>()
    override fun onStartJob(params: JobParameters): Boolean {
        val id = params.extras.getString("download") ?: return false
        setNotification(params, CATALOG_NOTIFICATION, catalogNotification(this, id),
            JOB_END_NOTIFICATION_POLICY_REMOVE)
        val graph = (application as DurableApplication).graph
        val job = scope.launch(start = CoroutineStart.LAZY) {
            graph.downloads.run(id)
            if (attempts[params.jobId] === currentCoroutineContext()[Job]) {
                attempts.remove(params.jobId)
                jobFinished(params, false)
            }
        }
        attempts[params.jobId]?.cancel()
        attempts[params.jobId] = job
        job.start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        attempts.remove(params.jobId)?.cancel()
        return params.stopReason != JobParameters.STOP_REASON_USER
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
class CatalogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("download") ?: return Result.failure()
        val notification = catalogNotification(applicationContext, id)
        val info = if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(CATALOG_NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(CATALOG_NOTIFICATION, notification)
        setForeground(info)
        val downloads = (applicationContext as DurableApplication).graph.downloads
        downloads.run(id)
        return Result.success()
    }
}
