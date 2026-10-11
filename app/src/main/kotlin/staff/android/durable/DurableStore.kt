package staff.android.durable

import androidx.room3.withWriteTransaction
import java.io.IOException
import kotlinx.coroutines.CancellationException

class StaleSession : IllegalStateException("Session changed")
enum class AttemptResult { Finished, Retry }

class DurableStore(val database: DurableDatabase, private val api: NoteApi,
    private val clock: () -> Long = System::currentTimeMillis) {
    val dao = database.data()
    suspend fun current(): SessionTicket? = dao.authority()?.ticket()
    suspend fun <T> authorized(ticket: SessionTicket, block: suspend () -> T): T =
        database.withWriteTransaction {
            if (dao.authority()?.ticket() != ticket) throw StaleSession()
            block()
        }
    suspend fun signIn(account: String): SessionTicket = database.withWriteTransaction {
        require(account in setOf("A", "B"))
        val old = dao.authority()
        check(old?.active != true) { "Sign out before changing account" }
        check(old?.accountId == null || old.accountId == account) { "Discard old account before changing" }
        val value = Authority(accountId = account,
            generation = Math.addExact(old?.generation ?: 0, 1), active = true)
        dao.authority(value)
        checkNotNull(value.ticket())
    }
    suspend fun signOut(expected: Authority) = database.withWriteTransaction {
        check(dao.authority() == expected) { "Session changed" }
        expected.accountId?.let { dao.purge(it); dao.cancelDownloads(it) }
        dao.authority(Authority(accountId = null,
            generation = Math.addExact(expected.generation, 1), active = false))
    }
    suspend fun expire(ticket: SessionTicket) = authorized(ticket) {
        dao.authority(Authority(accountId = ticket.accountId,
            generation = Math.addExact(ticket.generation, 1), active = false))
        dao.cancelDownloads(ticket.accountId)
    }
    private suspend fun acceptRemote(account: String, remote: RemoteNote) {
        val old = dao.cached(account)
        if (old != null && remote.revision < old.serverRevision) return
        if (old != null && remote.revision == old.serverRevision && remote.note != old.serverNote)
            throw ContractFailure("One revision described different notes")
        dao.cache(CachedNote(account, "camera", remote.note, remote.revision,
            clock(), old?.localCounter ?: 0))
    }
    suspend fun refresh(ticket: SessionTicket) {
        authorized(ticket) { Unit }
        try {
            val remote = api.fetch(ticket.accountId)
            authorized(ticket) { acceptRemote(ticket.accountId, remote) }
        } catch (failure: HttpStatusFailure) {
            if (failure.code == 401) expire(ticket)
            throw failure
        }
    }
    suspend fun save(ticket: SessionTicket, text: String) = authorized(ticket) {
        require(text.length <= 2000)
        val cached = checkNotNull(dao.cached(ticket.accountId)) { "Refresh a base first" }
        val old = dao.pending(ticket.accountId)
        val next = Math.addExact(cached.localCounter, 1)
        dao.cache(cached.copy(localCounter = next))
        dao.enqueue(PendingEdit(ticket.accountId, "camera", next, text,
            old?.baseRevision ?: cached.serverRevision, old?.conflict ?: false))
    }
    suspend fun retry(ticket: SessionTicket) = authorized(ticket) {
        dao.pending(ticket.accountId)?.let { dao.enqueue(it.copy(attention = null, attempts = 0)) }
    }
    suspend fun eligible(ticket: SessionTicket): Boolean = authorized(ticket) {
        dao.pending(ticket.accountId)?.let { !it.conflict && it.attention == null } == true
    }
    private suspend fun attention(ticket: SessionTicket, sent: PendingEdit, reason: String) = authorized(ticket) {
        val now = dao.pending(ticket.accountId)
        if (now?.localRevision == sent.localRevision) dao.enqueue(now.copy(attention = reason))
    }
    suspend fun attempt(ticket: SessionTicket): AttemptResult {
        var deferred = false
        val sent = try { authorized(ticket) {
            val pending = dao.pending(ticket.accountId) ?: return@authorized null
            if (pending.conflict || pending.attention != null) return@authorized null
            if (clock() < checkNotNull(dao.authority()).retryNotBeforeMillis) {
                deferred = true; return@authorized null
            }
            if (pending.attempts >= 5) {
                dao.enqueue(pending.copy(attention = "Retry budget exhausted"))
                return@authorized null
            }
            pending.copy(attempts = pending.attempts + 1).also { dao.enqueue(it) }
        } } catch (_: StaleSession) { return AttemptResult.Finished }
        if (deferred) return AttemptResult.Retry
        if (sent == null) return AttemptResult.Finished
        try {
            // No database transaction spans the network call.
            val reply = api.put(ticket.accountId, sent.baseRevision, sent.note)
            authorized(ticket) {
                when (reply) {
                    is WriteReply.Accepted -> {
                        if (reply.remote.revision <= sent.baseRevision || reply.remote.note != sent.note)
                            throw ContractFailure("Invalid acknowledgement")
                        acceptRemote(ticket.accountId, reply.remote)
                        val now = dao.pending(ticket.accountId)
                        if (now?.localRevision == sent.localRevision) dao.clearPending(ticket.accountId)
                        else if (now != null) dao.enqueue(now.copy(baseRevision = reply.remote.revision, conflict = false))
                    }
                    is WriteReply.Conflict -> {
                        if (reply.remote.revision <= sent.baseRevision) throw ContractFailure("Invalid conflict")
                        acceptRemote(ticket.accountId, reply.remote)
                        dao.pending(ticket.accountId)?.let { dao.enqueue(it.copy(conflict = true)) }
                    }
                }
            }
            return AttemptResult.Finished
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: StaleSession) { return AttemptResult.Finished }
        catch (failure: IOException) {
            try {
                if (failure is HttpStatusFailure && failure.code == 401) {
                    expire(ticket)
                    return AttemptResult.Finished
                }
                val transient = failure !is ContractFailure &&
                    (failure !is HttpStatusFailure || failure.code in setOf(408, 429, 500, 502, 503, 504))
                if (transient && failure is HttpStatusFailure) authorized(ticket) {
                    val authority = checkNotNull(dao.authority())
                    dao.authority(authority.copy(retryNotBeforeMillis =
                        maxOf(authority.retryNotBeforeMillis, failure.notBeforeMillis)))
                }
                if (transient && sent.attempts < 5) {
                    authorized(ticket) { Unit }
                    return AttemptResult.Retry
                }
                attention(ticket, sent, if (transient) "Retry budget exhausted" else "Service contract needs review")
            } catch (_: StaleSession) { /* A newer authority owns the state now. */ }
            return AttemptResult.Finished
        }
    }
    suspend fun resolve(ticket: SessionTicket, server: Long, local: Long, keepMine: Boolean) = authorized(ticket) {
        val cached = checkNotNull(dao.cached(ticket.accountId))
        val pending = checkNotNull(dao.pending(ticket.accountId))
        check(pending.conflict && pending.localRevision == local && cached.serverRevision == server) {
            "Conflict changed; review again"
        }
        if (!keepMine) dao.clearPending(ticket.accountId)
        else {
            val next = Math.addExact(cached.localCounter, 1)
            dao.cache(cached.copy(localCounter = next))
            dao.enqueue(pending.copy(localRevision = next, baseRevision = server, conflict = false, attention = null, attempts = 0))
        }
    }
}
