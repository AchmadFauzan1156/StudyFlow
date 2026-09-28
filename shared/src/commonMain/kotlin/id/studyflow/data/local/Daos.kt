package id.studyflow.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CourseDao {
    @Query(
        """
        SELECT c.*,
            (SELECT COUNT(*) FROM notes n WHERE n.course_id = c.id AND n.deleted_at IS NULL) AS note_count,
            (SELECT COUNT(*) FROM tasks t WHERE t.course_id = c.id AND t.deleted_at IS NULL AND t.status != 'DONE') AS open_task_count
        FROM courses c
        WHERE c.deleted_at IS NULL
        ORDER BY c.name COLLATE NOCASE
        """,
    )
    fun observeAllWithCounts(): Flow<List<CourseWithCounts>>

    @Query("SELECT * FROM courses WHERE id = :id AND deleted_at IS NULL")
    fun observeById(id: String): Flow<CourseEntity?>

    @Query("SELECT * FROM courses WHERE id = :id")
    suspend fun getById(id: String): CourseEntity?

    @Upsert
    suspend fun upsert(course: CourseEntity)

    @Query("SELECT COUNT(*) FROM courses WHERE deleted_at IS NULL")
    fun observeCount(): Flow<Int>
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE course_id = :courseId AND deleted_at IS NULL ORDER BY updated_at DESC")
    fun observeByCourse(courseId: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: String): NoteEntity?

    @Upsert
    suspend fun upsert(note: NoteEntity)

    @Query("SELECT * FROM notes WHERE course_id = :courseId AND deleted_at IS NULL")
    suspend fun getActiveByCourse(courseId: String): List<NoteEntity>

    @Query("SELECT COUNT(*) FROM notes n JOIN courses c ON c.id = n.course_id WHERE n.deleted_at IS NULL AND c.deleted_at IS NULL")
    fun observeCount(): Flow<Int>
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE course_id = :courseId AND deleted_at IS NULL ORDER BY status = 'DONE', due_at")
    fun observeByCourse(courseId: String): Flow<List<TaskEntity>>

    @Query(
        """
        SELECT t.*, c.name AS course_name
        FROM tasks t JOIN courses c ON c.id = t.course_id
        WHERE t.deleted_at IS NULL AND c.deleted_at IS NULL
        ORDER BY t.status = 'DONE', t.due_at
        """,
    )
    fun observeAllWithCourse(): Flow<List<TaskWithCourse>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getById(id: String): TaskEntity?

    @Upsert
    suspend fun upsert(task: TaskEntity)

    @Query("SELECT * FROM tasks WHERE course_id = :courseId AND deleted_at IS NULL")
    suspend fun getActiveByCourse(courseId: String): List<TaskEntity>
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: OutboxEntity)

    @Query("SELECT * FROM local_outbox ORDER BY seq")
    suspend fun getAll(): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM local_outbox")
    fun observePendingCount(): Flow<Int>
}

@Dao
interface SettingsDao {
    @Query("SELECT value FROM app_settings WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Query("SELECT value FROM app_settings WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("DELETE FROM app_settings WHERE `key` = :key")
    suspend fun remove(key: String)

    @Upsert
    suspend fun put(setting: SettingEntity)
}
