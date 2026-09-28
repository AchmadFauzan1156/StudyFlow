package id.studyflow.data.repository

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import id.studyflow.data.AppClock
import id.studyflow.data.IdGenerator
import id.studyflow.data.local.DatabaseManager
import id.studyflow.data.local.OutboxEntity
import id.studyflow.data.local.StudyFlowDatabase
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class EntityType { COURSE, NOTE, TASK }
enum class Operation { CREATE, UPDATE, DELETE }

@Serializable
data class CoursePayload(val name: String, val semester: String?)

// Nama field payload mengikuti kontrak API (snake_case), lihat docs/API.md.
@Serializable
data class NotePayload(
    @SerialName("course_id") val courseId: String,
    val title: String,
    val content: String,
)

@Serializable
data class TaskPayload(
    @SerialName("course_id") val courseId: String,
    val title: String,
    val description: String,
    @SerialName("due_at") val dueAt: Long,
    @SerialName("time_zone_id") val timeZoneId: String,
    val status: String,
)

@Serializable
data class DeletePayload(@SerialName("deleted_at") val deletedAt: Long)

/**
 * Menjalankan perubahan lokal dan menulis entri outbox dalam satu transaksi Room,
 * sehingga tidak ada edit yang tersimpan tanpa antrean sinkronisasinya.
 */
class LocalMutations(
    private val databases: DatabaseManager,
    private val clock: AppClock,
    private val ids: IdGenerator,
) {
    internal val json = Json { encodeDefaults = true }

    fun now(): Long = clock.nowMillis()
    fun newId(): String = ids.newId()

    internal suspend fun <T> write(block: suspend OutboxWriter.() -> T): T {
        val db = databases.db
        return db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                OutboxWriter(db).block()
            }
        }
    }

    inner class OutboxWriter(val db: StudyFlowDatabase) {
        suspend fun enqueue(
            type: EntityType,
            entityId: String,
            operation: Operation,
            baseVersion: Int,
            payload: String,
        ) {
            db.outboxDao().insert(
                OutboxEntity(
                    mutationId = ids.newId(),
                    entityType = type.name,
                    entityId = entityId,
                    operation = operation.name,
                    baseVersion = baseVersion,
                    payload = payload,
                    createdAt = clock.nowMillis(),
                ),
            )
        }
    }
}
