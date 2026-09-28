package id.studyflow.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Query khusus mesin sinkronisasi (outbox, konflik, dan penanda versi server). */
@Dao
interface SyncDao {
    @Query("SELECT * FROM local_outbox ORDER BY seq")
    suspend fun outbox(): List<OutboxEntity>

    @Query("UPDATE local_outbox SET retry_count = retry_count + 1 WHERE seq IN (:seqs)")
    suspend fun markAttempted(seqs: List<Long>)

    @Query("DELETE FROM local_outbox WHERE seq = :seq")
    suspend fun deleteOutbox(seq: Long)

    @Query("DELETE FROM local_outbox WHERE entity_type = :type AND entity_id = :id")
    suspend fun deleteOutboxFor(type: String, id: String)

    @Query("SELECT COUNT(*) FROM local_outbox WHERE entity_type = :type AND entity_id = :id")
    suspend fun outboxCountFor(type: String, id: String): Int

    /** Mutasi berikutnya untuk entity ini dibuat di atas versi yang baru saja diterapkan server. */
    @Query("UPDATE local_outbox SET base_version = :version WHERE entity_type = :type AND entity_id = :id")
    suspend fun rebaseOutbox(type: String, id: String, version: Int)

    @Upsert
    suspend fun upsertConflict(conflict: ConflictEntity)

    @Query("SELECT * FROM sync_conflicts WHERE entity_type = :type AND entity_id = :id")
    suspend fun conflict(type: String, id: String): ConflictEntity?

    @Query("DELETE FROM sync_conflicts WHERE entity_type = :type AND entity_id = :id")
    suspend fun deleteConflict(type: String, id: String)

    @Query("SELECT * FROM sync_conflicts ORDER BY detected_at")
    fun observeConflicts(): Flow<List<ConflictEntity>>

    @Query("SELECT * FROM sync_conflicts")
    suspend fun conflicts(): List<ConflictEntity>

    @Query("UPDATE courses SET version = :version, pending_sync = :pending WHERE id = :id")
    suspend fun setCourseSynced(id: String, version: Int, pending: Boolean)

    @Query("UPDATE notes SET version = :version, pending_sync = :pending WHERE id = :id")
    suspend fun setNoteSynced(id: String, version: Int, pending: Boolean)

    @Query("UPDATE tasks SET version = :version, pending_sync = :pending WHERE id = :id")
    suspend fun setTaskSynced(id: String, version: Int, pending: Boolean)

    @Query("SELECT COUNT(*) FROM courses WHERE deleted_at IS NULL")
    suspend fun activeCourseCount(): Int

    @Query("SELECT * FROM courses WHERE deleted_at IS NULL")
    suspend fun activeCourses(): List<CourseEntity>

    @Query("SELECT * FROM notes WHERE deleted_at IS NULL")
    suspend fun activeNotes(): List<NoteEntity>

    @Query("SELECT * FROM tasks WHERE deleted_at IS NULL")
    suspend fun activeTasks(): List<TaskEntity>
}
