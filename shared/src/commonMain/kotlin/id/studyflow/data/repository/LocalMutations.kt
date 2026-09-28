package id.studyflow.data.repository

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import id.studyflow.data.AppClock
import id.studyflow.data.IdGenerator
import id.studyflow.data.local.OutboxEntity
import id.studyflow.data.local.StudyFlowDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class EntityType { COURSE, NOTE, TASK }
enum class Operation { CREATE, UPDATE, DELETE }

@Serializable
data class CoursePayload(val name: String, val semester: String?)

@Serializable
data class NotePayload(val courseId: String, val title: String, val content: String)

@Serializable
data class TaskPayload(
    val courseId: String,
    val title: String,
    val description: String,
    val dueAt: Long,
    val timeZoneId: String,
    val status: String,
)

@Serializable
data class DeletePayload(val deletedAt: Long)

/**
 * Menjalankan perubahan lokal dan menulis entri outbox dalam satu transaksi Room,
 * sehingga tidak ada edit yang tersimpan tanpa antrean sinkronisasinya.
 */
class LocalMutations(
    private val db: StudyFlowDatabase,
    private val clock: AppClock,
    private val ids: IdGenerator,
) {
    internal val json = Json { encodeDefaults = true }

    fun now(): Long = clock.nowMillis()
    fun newId(): String = ids.newId()

    internal suspend fun <T> write(block: suspend OutboxWriter.() -> T): T =
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                OutboxWriter().block()
            }
        }

    inner class OutboxWriter {
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
