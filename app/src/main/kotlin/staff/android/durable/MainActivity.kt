package staff.android.durable

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.room3.withWriteTransaction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ScreenState(val authority: Authority? = null, val note: NoteView? = null,
    val download: Download? = null, val ready: Boolean = false,
    val message: String = "Opening local state", val error: Boolean = false,
    val recoveryFailed: Boolean = false)

class DurableOwner(application: Application) : AndroidViewModel(application) {
    val graph = (application as DurableApplication).graph
    private val store = graph.store
    private val mutable = MutableStateFlow(ScreenState())
    val screen = mutable.asStateFlow()
    init { observe() }
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe() {
        viewModelScope.launch {
            try {
                store.dao.observeAuthority().collectLatest { authority ->
                    val account = authority?.accountId
                    if (account == null) mutable.value = ScreenState(authority, message = "Choose a fixture account")
                    else combine(store.dao.observe(account), store.dao.observeDownload(account),
                        graph.recoveryReady, graph.recoveryError) { note, download, ready, failed ->
                        ScreenState(authority, note, download, ready,
                            if (failed) "Scheduling unavailable; Retry recovery"
                            else if (authority.active) "Local state is observed; work may run without this screen"
                            else "Session expired; saved text retained. Sign in again to send it.",
                            recoveryFailed = failed)
                    }.collect { if (store.dao.authority() == authority) mutable.value = it }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = mutable.value.copy(error = true, message = "Local read unavailable") }
        }
    }
    private fun action(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.value = mutable.value.copy(message = "Action unavailable; inspect saved state") }
    }
    fun signIn(account: String) = action {
        val ticket = store.signIn(account)
        if (store.eligible(ticket)) graph.scheduler.enqueue(ticket)
    }
    fun refresh(ticket: SessionTicket) = action { store.refresh(ticket) }
    fun save(ticket: SessionTicket, text: String) = action {
        check(graph.recoveryReady.value)
        store.save(ticket, text)
        graph.scheduler.enqueue(ticket)
    }
    fun retry(ticket: SessionTicket) = action { store.retry(ticket); graph.scheduler.enqueue(ticket) }
    fun resolve(ticket: SessionTicket, note: NoteView, keep: Boolean) = action {
        store.resolve(ticket, note.serverRevision, checkNotNull(note.pendingRevision), keep)
        if (keep) graph.scheduler.enqueue(ticket)
    }
    fun signOut(authority: Authority) = action {
        graph.downloads.signOut(authority)
        if (authority.accountId != null) graph.scheduler.cancel(SessionTicket(authority.accountId, authority.generation))
    }
    fun cancelDownload(id: String) = action { graph.downloads.cancel(id) }
}

class MainActivity : ComponentActivity() {
    private lateinit var owner: DurableOwner
    private var notificationsAllowed by mutableStateOf(true)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAllowed = it
    }
    override fun onResume() {
        super.onResume()
        notificationsAllowed = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    private var admittingDownload = false
    private fun download(ticket: SessionTicket) {
        if (admittingDownload) return
        admittingDownload = true
        lifecycleScope.launch {
            var item: Download? = null
            try {
                item = owner.graph.downloads.prepare(ticket)
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                    !owner.graph.downloads.schedule(item)) owner.graph.downloads.markAdmissionFailure(item.id)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { item?.let { owner.graph.downloads.markAdmissionFailure(it.id) } }
                throw cancelled
            }
            catch (_: Exception) { item?.let { owner.graph.downloads.markAdmissionFailure(it.id) } }
            finally { admittingDownload = false }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        owner = ViewModelProvider(this)[DurableOwner::class.java]
        setContent {
            val state by owner.screen.collectAsStateWithLifecycle()
            CommonGoodsTheme { Surface(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding().imePadding().padding(20.dp)
                    .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CommonGoodsHeader()
                        Text("Saved", style = MaterialTheme.typography.headlineLarge)
                    val authority = state.authority
                    val ticket = authority?.ticket()
                    if (authority?.accountId == null) {
                        Text("Each fixture account owns a separate Camera note.")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { owner.signIn("A") }) { Text("Account A") }
                            Button(onClick = { owner.signIn("B") }) { Text("Account B") }
                        }
                    } else {
                        Text("Account ${authority.accountId}")
                        ProductIdentity("camera", "Camera", "Your private product note")
                        val note = state.note
                        if (note == null) Text("No local note. Refresh obtains the service base.")
                        else key(authority.accountId) {
                            var draft by rememberSaveable { mutableStateOf(note.visibleNote) }
                            ShopMessage("Saved note: ${note.visibleNote}")
                            Text(when { note.conflict -> "Conflict · review both versions"
                                note.attention != null -> "Needs attention · ${note.attention}"
                                note.pendingRevision != null -> "Pending · local revision ${note.pendingRevision}"
                                else -> "Synced" })

                            OutlinedTextField(draft, { draft = it }, label = { Text("Note draft") },
                                enabled = ticket != null, modifier = Modifier.fillMaxWidth())
                            Button(onClick = { owner.save(checkNotNull(ticket), draft) },
                                enabled = ticket != null && state.ready && !state.error && draft.length <= 2000) {
                                Text("Save and schedule")
                            }
                            if (ticket != null && !state.error && note.conflict) {
                                Text("Service note: ${note.serverNote}")
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { owner.resolve(ticket, note, true) }) { Text("Keep mine") }
                                    OutlinedButton(onClick = { owner.resolve(ticket, note, false) }) { Text("Use server") }
                                }
                            }
                            if (ticket != null && !state.error && note.attention != null)
                                Button(onClick = { owner.retry(ticket) }) { Text("Try again") }
                        }
                        if (ticket != null && !state.error) {
                            OutlinedButton(onClick = { owner.refresh(ticket) }) { Text("Refresh") }
                            HorizontalDivider()
                            Text("Offline catalog", style = MaterialTheme.typography.headlineSmall)
                            Text("If a download is interrupted, tap Download catalog again.")
                            Button(onClick = { download(ticket) }) { Text("Download catalog") }
                            state.download?.let { item ->
                                Text("Catalog: ${item.state}${if (item.state == "Complete") " · ${item.bytes} bytes" else ""}")
                                OutlinedButton(onClick = { owner.cancelDownload(item.id) },
                                    enabled = item.state in setOf("Queued", "Downloading", "Interrupted")) {
                                    Text("Cancel download")
                                }
                            }
                            if (Build.VERSION.SDK_INT >= 33 && !notificationsAllowed) {
                                Text("Drawer notifications are off; transfer status remains here.")
                                OutlinedButton(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                                    Text("Enable notifications")
                                }
                            }
                        } else if (ticket == null) Button(onClick = { owner.signIn(authority.accountId) }) { Text("Sign in again") }
                        OutlinedButton(onClick = { owner.signOut(authority) }) { Text("Sign out · discard local edits") }
                    }
                    if ((!state.ready || state.recoveryFailed) && state.authority?.active == true)
                        OutlinedButton(onClick = { owner.graph.startRecovery() }) { Text("Retry recovery") }
                    if (state.error) OutlinedButton(onClick = { owner.observe() }) { Text("Retry local read") }
                    if (state.message.isNotBlank()) ShopMessage(state.message)
                    LabControls {
                        Text("Work admission is not a delivery deadline")
                        Text("Small transfer fixture · restarts from byte 0")
                        Text("Stored status alone does not confirm that a transfer is still running.")
                        state.authority?.let { Text("Account ${it.accountId} · generation ${it.generation}") }
                        state.note?.let { Text("Server revision ${it.serverRevision}") }
                    }
                }
            } }
        }
    }
}
