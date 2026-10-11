package id.studyflow.data.sync

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import id.studyflow.data.AppClock
import id.studyflow.data.local.ConflictEntity
import id.studyflow.data.local.CourseEntity
import id.studyflow.data.local.DatabaseManager
import id.studyflow.data.local.NoteEntity
import id.studyflow.data.local.OutboxEntity
import id.studyflow.data.local.SettingEntity
import id.studyflow.data.local.StudyFlowDatabase
import id.studyflow.data.local.TaskEntity
import id.studyflow.data.remote.ApiException
import id.studyflow.data.remote.ApiJson
import id.studyflow.data.remote.ChangeDto
import id.studyflow.data.remote.MutationDto
import id.studyflow.data.remote.ServerEntity
import id.studyflow.data.remote.SyncApi
import id.studyflow.data.repository.EntityType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

data class SyncReport(val pushed: Int = 0, val conflicts: Int = 0, val rejected: Int = 0, val pulled: Int = 0)

/**
 * Satu siklus sinkronisasi: kirim outbox (push) lalu ambil perubahan server (pull).
 *
 * - Setiap entri outbox dikirim dengan mutation_id-nya sendiri; retry setelah gagal jaringan memakai
 *   mutation_id dan payload yang sama sehingga server tidak menggandakan data.
 * - Per batch hanya mutasi tertua tiap entity yang dikirim. Setelah diterapkan, mutasi berikutnya
 *   untuk entity itu di-rebase ke versi baru server.
 * - Konflik tidak menimpa apa pun: salinan server disimpan di `sync_conflicts` dan mutasi entity itu
 *   ditahan sampai pengguna memilih.
 * - Pull tidak menimpa entity yang masih punya perubahan lokal tertunda atau konflik.
 */
class SyncEngine(private val databases: DatabaseManager, private val api: SyncApi, private val clock: AppClock) {
    private val mutex = Mutex()

    suspend fun syncOnce(): SyncReport = mutex.withLock {
        if (databases.accountId.value == null) return@withLock SyncReport()
        val db = databases.db
        val pushed = push(db)
        pushed.copy(pulled = pull(db))
    }

    // ---------- Push ----------

    private suspend fun push(db: StudyFlowDatabase): SyncReport {
        val dao = db.syncDao()
        var report = SyncReport()
        while (true) {
            val held = dao.conflicts().map { it.entityType to it.entityId }.toSet()
            val batch = dao.outbox()
                .filter { (it.entityType to it.entityId) !in held }
                .distinctBy { it.entityType to it.entityId }
                .take(PUSH_BATCH_SIZE)
            if (batch.isEmpty()) return report

            // Tandai sebelum dikirim: entri yang pernah dikirim tidak boleh berubah lagi.
            dao.markAttempted(batch.map { it.seq })
            val results = api.push(batch.map { it.toDto() }).results.associateBy { it.mutationId }

            db.useWriterConnection { tx ->
                tx.immediateTransaction {
                    for (entry in batch) {
                        val result = results[entry.mutationId] ?: continue
                        when (result.status) {
                            "applied" -> {
                                val version = result.version ?: entry.baseVersion
                                dao.deleteOutbox(entry.seq)
                                dao.rebaseOutbox(entry.entityType, entry.entityId, version)
                                val remaining = dao.outboxCountFor(entry.entityType, entry.entityId)
                                setSynced(db, entry.entityType, entry.entityId, version, pending = remaining > 0)
                                report = report.copy(pushed = report.pushed + 1)
                            }
                            "conflict" -> {
                                val server = result.serverEntity
                                if (server != null) {
                                    dao.upsertConflict(conflictOf(entry.entityType, server))
                                }
                                dao.deleteOutbox(entry.seq)
                                report = report.copy(conflicts = report.conflicts + 1)
                            }
                            else -> {
                                // Ditolak permanen (mis. induk sudah dihapus di server): jangan diulang terus.
                                dao.deleteOutbox(entry.seq)
                                if (dao.outboxCountFor(entry.entityType, entry.entityId) == 0) {
                                    val version = currentVersion(db, entry.entityType, entry.entityId)
                                    setSynced(db, entry.entityType, entry.entityId, version, pending = false)
                                }
                                report = report.copy(rejected = report.rejected + 1)
                            }
                        }
                    }
                }
            }
            // Respons tidak lengkap: hentikan agar tidak berputar; sisa entri dicoba pada siklus berikutnya.
            if (batch.any { it.mutationId !in results }) return report
        }
    }

    // ---------- Pull ----------

    private suspend fun pull(db: StudyFlowDatabase): Int {
        val settings = db.settingsDao()
        var cursor: String? = settings.get(KEY_CURSOR)
        val changes = mutableListOf<ChangeDto>()
        var restarted = false
        while (true) {
            val page = try {
                api.pull(cursor, PULL_PAGE_SIZE)
            } catch (e: ApiException) {
                if (e.code == "cursor_invalid" && !restarted) {
                    // Full resync: mulai dari awal tanpa membuang outbox.
                    restarted = true
                    cursor = null
                    changes.clear()
                    continue
                }
                throw e
            }
            changes += page.changes
            cursor = page.nextCursor
            if (!page.hasMore) break
        }

        val nextCursor = checkNotNull(cursor)
        // Semua halaman diterapkan dalam satu transaksi; course lebih dulu agar FK anak terpenuhi.
        val ordered = changes.sortedBy { ORDER.indexOf(it.entityType) }
        var applied = 0
        db.useWriterConnection { tx ->
            tx.immediateTransaction {
                for (change in ordered) {
                    if (applyServerChange(db, change.entityType, change.entity)) applied++
                }
                settings.put(SettingEntity(KEY_CURSOR, nextCursor))
            }
        }
        return applied
    }

    /** Menerapkan snapshot server ke lokal; false jika dilewati. Harus dipanggil di dalam transaksi. */
    private suspend fun applyServerChange(db: StudyFlowDatabase, apiType: String, entity: ServerEntity): Boolean {
        val type = localType(apiType) ?: return false
        val dao = db.syncDao()
        val conflict = dao.conflict(type, entity.id)
        if (conflict != null) {
            // Perbarui salinan server pada konflik yang masih menunggu keputusan pengguna.
            dao.upsertConflict(conflictOf(type, entity))
            return false
        }
        if (dao.outboxCountFor(type, entity.id) > 0) return false
        return writeServerEntity(db, type, entity)
    }

    private fun conflictOf(type: String, server: ServerEntity) = ConflictEntity(
        entityType = type,
        entityId = server.id,
        serverVersion = server.version,
        serverEntity = ApiJson.encodeToString(ServerEntity.serializer(), server),
        detectedAt = clock.nowMillis(),
    )

    private suspend fun setSynced(db: StudyFlowDatabase, type: String, id: String, version: Int, pending: Boolean) {
        val dao = db.syncDao()
        when (type) {
            EntityType.COURSE.name -> dao.setCourseSynced(id, version, pending)
            EntityType.NOTE.name -> dao.setNoteSynced(id, version, pending)
            EntityType.TASK.name -> dao.setTaskSynced(id, version, pending)
        }
    }

    private suspend fun currentVersion(db: StudyFlowDatabase, type: String, id: String): Int = when (type) {
        EntityType.COURSE.name -> db.courseDao().getById(id)?.version
        EntityType.NOTE.name -> db.noteDao().getById(id)?.version
        else -> db.taskDao().getById(id)?.version
    } ?: 0

    private fun OutboxEntity.toDto() = MutationDto(
        mutationId = mutationId,
        entityType = entityType.lowercase(),
        entityId = entityId,
        operation = operation.lowercase(),
        baseVersion = baseVersion,
        payload = ApiJson.decodeFromString(JsonObject.serializer(), payload),
    )

    companion object {
        const val PUSH_BATCH_SIZE = 100
        const val PULL_PAGE_SIZE = 500
        internal const val KEY_CURSOR = "sync_cursor"
        private val ORDER = listOf("course", "note", "task")

        fun localType(apiType: String): String? = EntityType.entries.firstOrNull { it.name.equals(apiType, ignoreCase = true) }?.name
    }
}

/**
 * Menulis snapshot server ke tabel lokal (dipakai pull dan saat pengguna memilih versi server).
 * Anak yang induknya belum ada di lokal dilewati. Harus dipanggil di dalam transaksi.
 */
internal suspend fun writeServerEntity(db: StudyFlowDatabase, type: String, e: ServerEntity): Boolean {
    val updatedAt = e.updatedAt ?: 0L
    when (type) {
        EntityType.COURSE.name -> {
            val existing = db.courseDao().getById(e.id)
            db.courseDao().upsert(
                CourseEntity(
                    id = e.id,
                    name = e.name ?: existing?.name ?: return false,
                    semester = e.semester,
                    createdAt = existing?.createdAt ?: updatedAt,
                    updatedAt = updatedAt,
                    version = e.version,
                    deletedAt = e.deletedAt,
                    pendingSync = false,
                ),
            )
        }
        EntityType.NOTE.name -> {
            val courseId = e.courseId ?: return false
            if (db.courseDao().getById(courseId) == null) return false
            val existing = db.noteDao().getById(e.id)
            db.noteDao().upsert(
                NoteEntity(
                    id = e.id,
                    courseId = courseId,
                    title = e.title ?: return false,
                    content = e.content.orEmpty(),
                    createdAt = existing?.createdAt ?: updatedAt,
                    updatedAt = updatedAt,
                    version = e.version,
                    deletedAt = e.deletedAt,
                    pendingSync = false,
                ),
            )
        }
        EntityType.TASK.name -> {
            val courseId = e.courseId ?: return false
            if (db.courseDao().getById(courseId) == null) return false
            val existing = db.taskDao().getById(e.id)
            db.taskDao().upsert(
                TaskEntity(
                    id = e.id,
                    courseId = courseId,
                    title = e.title ?: return false,
                    description = e.description.orEmpty(),
                    dueAt = e.dueAt ?: return false,
                    timeZoneId = e.timeZoneId ?: "Asia/Jakarta",
                    status = e.status ?: "TODO",
                    createdAt = existing?.createdAt ?: updatedAt,
                    updatedAt = updatedAt,
                    version = e.version,
                    deletedAt = e.deletedAt,
                    pendingSync = false,
                ),
            )
        }
        else -> return false
    }
    return true
}
