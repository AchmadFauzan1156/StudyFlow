package id.studyflow.data.sync

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import id.studyflow.data.local.ConflictEntity
import id.studyflow.data.local.DatabaseManager
import id.studyflow.data.local.StudyFlowDatabase
import id.studyflow.data.remote.ApiJson
import id.studyflow.data.remote.ServerEntity
import id.studyflow.data.repository.CoursePayload
import id.studyflow.data.repository.DeletePayload
import id.studyflow.data.repository.EntityType
import id.studyflow.data.repository.LocalMutations
import id.studyflow.data.repository.NotePayload
import id.studyflow.data.repository.Operation
import id.studyflow.data.repository.TaskPayload
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/** Satu sisi konflik untuk ditampilkan. [dueAt] hanya untuk tugas. */
data class ConflictSide(val title: String, val body: String, val deleted: Boolean, val dueAt: Long? = null, val done: Boolean = false)

data class ConflictItem(val entityType: EntityType, val entityId: String, val local: ConflictSide?, val server: ConflictSide)

@OptIn(ExperimentalCoroutinesApi::class)
class ConflictRepository(private val databases: DatabaseManager, private val mutations: LocalMutations) {
    fun observeConflicts(): Flow<List<ConflictItem>> = databases.current.flatMapLatest { db ->
        db.syncDao().observeConflicts().map { list -> list.map { toItem(db, it) } }
    }

    fun observeCount(): Flow<Int> = observeConflicts().map { it.size }

    /**
     * Pertahankan isi lokal: kirim ulang sebagai mutasi baru di atas versi server saat ini.
     * Jika lokal dihapus, kirim DELETE; jika server menghapus, UPDATE memulihkannya.
     */
    suspend fun keepLocal(type: EntityType, id: String) {
        mutations.write {
            val dao = db.syncDao()
            val conflict = dao.conflict(type.name, id) ?: return@write
            val server = ApiJson.decodeFromString(ServerEntity.serializer(), conflict.serverEntity)
            val base = conflict.serverVersion
            dao.deleteOutboxFor(type.name, id)
            dao.deleteConflict(type.name, id)
            val json = mutations.json
            val now = mutations.now()
            when (type) {
                EntityType.COURSE -> {
                    val local = db.courseDao().getById(id) ?: return@write
                    val op = localOperation(local.deletedAt != null, server.deletedAt != null) ?: run {
                        dao.setCourseSynced(id, base, pending = false)
                        return@write
                    }
                    val payload = if (op == Operation.DELETE) {
                        json.encodeToString(DeletePayload(now))
                    } else {
                        json.encodeToString(CoursePayload(local.name, local.semester))
                    }
                    db.courseDao().upsert(local.copy(version = base, pendingSync = true))
                    enqueue(type, id, op, base, payload)
                }
                EntityType.NOTE -> {
                    val local = db.noteDao().getById(id) ?: return@write
                    val op = localOperation(local.deletedAt != null, server.deletedAt != null) ?: run {
                        dao.setNoteSynced(id, base, pending = false)
                        return@write
                    }
                    val payload = if (op == Operation.DELETE) {
                        json.encodeToString(DeletePayload(now))
                    } else {
                        json.encodeToString(NotePayload(local.courseId, local.title, local.content))
                    }
                    db.noteDao().upsert(local.copy(version = base, pendingSync = true))
                    enqueue(type, id, op, base, payload)
                }
                EntityType.TASK -> {
                    val local = db.taskDao().getById(id) ?: return@write
                    val op = localOperation(local.deletedAt != null, server.deletedAt != null) ?: run {
                        dao.setTaskSynced(id, base, pending = false)
                        return@write
                    }
                    val payload = if (op == Operation.DELETE) {
                        json.encodeToString(DeletePayload(now))
                    } else {
                        json.encodeToString(
                            TaskPayload(local.courseId, local.title, local.description, local.dueAt, local.timeZoneId, local.status),
                        )
                    }
                    db.taskDao().upsert(local.copy(version = base, pendingSync = true))
                    enqueue(type, id, op, base, payload)
                }
            }
        }
    }

    /** Pakai versi server: buang perubahan lokal yang tertahan untuk entity ini. */
    suspend fun keepServer(type: EntityType, id: String) {
        val db = databases.db
        db.useWriterConnection { tx ->
            tx.immediateTransaction {
                val dao = db.syncDao()
                val conflict = dao.conflict(type.name, id) ?: return@immediateTransaction
                val server = ApiJson.decodeFromString(ServerEntity.serializer(), conflict.serverEntity)
                dao.deleteOutboxFor(type.name, id)
                writeServerEntity(db, type.name, server)
                dao.deleteConflict(type.name, id)
            }
        }
    }

    /** null = kedua sisi sudah terhapus, tidak ada yang perlu dikirim. */
    private fun localOperation(localDeleted: Boolean, serverDeleted: Boolean): Operation? = when {
        localDeleted && serverDeleted -> null
        localDeleted -> Operation.DELETE
        else -> Operation.UPDATE
    }

    private suspend fun toItem(db: StudyFlowDatabase, conflict: ConflictEntity): ConflictItem {
        val type = EntityType.valueOf(conflict.entityType)
        val server = ApiJson.decodeFromString(ServerEntity.serializer(), conflict.serverEntity)
        val local = when (type) {
            EntityType.COURSE -> db.courseDao().getById(conflict.entityId)?.let {
                ConflictSide(it.name, it.semester.orEmpty(), it.deletedAt != null)
            }
            EntityType.NOTE -> db.noteDao().getById(conflict.entityId)?.let {
                ConflictSide(it.title, it.content, it.deletedAt != null)
            }
            EntityType.TASK -> db.taskDao().getById(conflict.entityId)?.let {
                ConflictSide(it.title, it.description, it.deletedAt != null, it.dueAt, it.status == "DONE")
            }
        }
        val serverSide = ConflictSide(
            title = server.name ?: server.title.orEmpty(),
            body = server.semester ?: server.content ?: server.description.orEmpty(),
            deleted = server.deletedAt != null,
            dueAt = server.dueAt,
            done = server.status == "DONE",
        )
        return ConflictItem(type, conflict.entityId, local, serverSide)
    }
}
