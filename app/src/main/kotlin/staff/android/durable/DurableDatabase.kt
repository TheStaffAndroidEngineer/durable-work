package staff.android.durable

import android.content.Context
import androidx.room3.*
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "authority")
data class Authority(@PrimaryKey val id: Int = 1, val accountId: String?,
    val generation: Long, val active: Boolean, val retryNotBeforeMillis: Long = 0)
data class SessionTicket(val accountId: String, val generation: Long)
fun Authority.ticket(): SessionTicket? = if (active && accountId != null)
    SessionTicket(accountId, generation) else null

@Entity(tableName = "cached_notes", primaryKeys = ["accountId", "productId"])
data class CachedNote(val accountId: String, val productId: String,
    val serverNote: String, val serverRevision: Long,
    val checkedAtMillis: Long, val localCounter: Long = 0)
@Entity(tableName = "pending_edits", primaryKeys = ["accountId", "productId"],
    foreignKeys = [ForeignKey(entity = CachedNote::class,
        parentColumns = ["accountId", "productId"], childColumns = ["accountId", "productId"],
        onDelete = ForeignKey.CASCADE)])
data class PendingEdit(val accountId: String, val productId: String,
    val localRevision: Long, val note: String, val baseRevision: Long,
    val conflict: Boolean = false, val attention: String? = null,
    val attempts: Int = 0)
data class NoteView(val serverNote: String, val serverRevision: Long,
    val visibleNote: String, val pendingRevision: Long?, val conflict: Boolean,
    val attention: String?, val checkedAtMillis: Long)
const val NOTE_VIEW = """SELECT c.serverNote, c.serverRevision,
    COALESCE(p.note, c.serverNote) AS visibleNote,
    p.localRevision AS pendingRevision,
    COALESCE(p.`conflict`, 0) AS `conflict`, p.attention, c.checkedAtMillis
    FROM cached_notes c LEFT JOIN pending_edits p
    ON c.accountId = p.accountId AND c.productId = p.productId
    WHERE c.accountId = :accountId AND c.productId = 'camera'"""

@Entity(tableName = "downloads")
data class Download(@PrimaryKey val id: String, val accountId: String,
    val generation: Long, val state: String = "Queued", val path: String? = null,
    val bytes: Long = 0, val attemptId: String? = null)

@Dao
interface DurableDao {
    @Query("SELECT * FROM authority WHERE id = 1") suspend fun authority(): Authority?
    @Query("SELECT * FROM authority WHERE id = 1") fun observeAuthority(): Flow<Authority?>
    @Upsert suspend fun authority(value: Authority)
    @Query(NOTE_VIEW) fun observe(accountId: String): Flow<NoteView?>
    @Query(NOTE_VIEW) suspend fun view(accountId: String): NoteView?
    @Query("""SELECT * FROM cached_notes
        WHERE accountId = :accountId AND productId = 'camera'""")
    suspend fun cached(accountId: String): CachedNote?
    @Query("""SELECT * FROM pending_edits
        WHERE accountId = :accountId AND productId = 'camera'""")
    suspend fun pending(accountId: String): PendingEdit?
    @Upsert suspend fun cache(value: CachedNote)
    @Upsert suspend fun enqueue(value: PendingEdit)
    @Query("DELETE FROM pending_edits WHERE accountId = :accountId") suspend fun clearPending(accountId: String)
    @Query("DELETE FROM cached_notes WHERE accountId = :accountId") suspend fun purge(accountId: String)
    @Upsert suspend fun download(value: Download)
    @Query("SELECT * FROM downloads WHERE id = :id") suspend fun download(id: String): Download?
    @Query("SELECT * FROM downloads ORDER BY rowid DESC LIMIT 1")
    suspend fun latestDownload(): Download?
    @Query("""SELECT * FROM downloads WHERE accountId = :accountId
        ORDER BY rowid DESC LIMIT 1""")
    fun observeDownload(accountId: String): Flow<Download?>
    @Query("""UPDATE downloads SET state = 'Cancelled', path = NULL
        WHERE accountId = :accountId""")
    suspend fun cancelDownloads(accountId: String)
}
@Database(entities = [Authority::class, CachedNote::class, PendingEdit::class, Download::class],
    version = 1, exportSchema = true)
abstract class DurableDatabase : RoomDatabase() { abstract fun data(): DurableDao }
fun openDurable(context: Context, name: String = "durable.db"): DurableDatabase =
    Room.databaseBuilder<DurableDatabase>(context, name).setDriver(BundledSQLiteDriver()).build()
