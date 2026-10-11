package id.studyflow.data.repository

import id.studyflow.data.local.DatabaseManager
import id.studyflow.data.local.TaskEntity
import id.studyflow.domain.Limits
import id.studyflow.domain.StudyTask
import id.studyflow.domain.TaskStatus
import id.studyflow.domain.ValidationException
import id.studyflow.domain.optionalText
import id.studyflow.domain.requireText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone
import kotlinx.serialization.encodeToString

data class TaskInput(
    val title: String,
    val description: String,
    val dueAt: Long,
    val timeZoneId: String,
    val status: TaskStatus = TaskStatus.TODO,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TaskRepository(private val databases: DatabaseManager, private val mutations: LocalMutations) {
    private val taskDao get() = databases.db.taskDao()

    fun observeByCourse(courseId: String): Flow<List<StudyTask>> =
        databases.current.flatMapLatest { it.taskDao().observeByCourse(courseId) }.map { list -> list.map { it.toDomain(null) } }

    fun observeAll(): Flow<List<StudyTask>> =
        databases.current.flatMapLatest { it.taskDao().observeAllWithCourse() }.map { list -> list.map { it.task.toDomain(it.courseName) } }

    suspend fun get(id: String): StudyTask? = taskDao.getById(id)?.takeIf { it.deletedAt == null }?.toDomain(null)

    suspend fun create(courseId: String, input: TaskInput): String {
        val clean = input.validated()
        val now = mutations.now()
        val entity = TaskEntity(
            id = mutations.newId(),
            courseId = courseId,
            title = clean.title,
            description = clean.description,
            dueAt = clean.dueAt,
            timeZoneId = clean.timeZoneId,
            status = clean.status.name,
            createdAt = now,
            updatedAt = now,
        )
        mutations.write {
            val course = db.courseDao().getById(courseId)
            if (course == null || course.deletedAt != null) throw ValidationException("Mata kuliah tidak ditemukan")
            db.taskDao().upsert(entity)
            enqueue(EntityType.TASK, entity.id, Operation.CREATE, entity.version, entity.payload())
        }
        return entity.id
    }

    suspend fun update(id: String, input: TaskInput) {
        val clean = input.validated()
        mutations.write {
            val current = db.taskDao().getById(id)?.takeIf { it.deletedAt == null }
                ?: throw ValidationException("Tugas tidak ditemukan")
            val updated = current.copy(
                title = clean.title,
                description = clean.description,
                dueAt = clean.dueAt,
                timeZoneId = clean.timeZoneId,
                status = clean.status.name,
                updatedAt = mutations.now(),
                pendingSync = true,
            )
            if (updated.copy(updatedAt = current.updatedAt, pendingSync = current.pendingSync) == current) return@write
            db.taskDao().upsert(updated)
            enqueue(EntityType.TASK, id, Operation.UPDATE, current.version, updated.payload())
        }
    }

    suspend fun setStatus(id: String, status: TaskStatus) {
        val current = get(id) ?: throw ValidationException("Tugas tidak ditemukan")
        update(id, TaskInput(current.title, current.description, current.dueAt, current.timeZoneId, status))
    }

    suspend fun delete(id: String) {
        mutations.write {
            val current = db.taskDao().getById(id)?.takeIf { it.deletedAt == null } ?: return@write
            val now = mutations.now()
            db.taskDao().upsert(current.copy(deletedAt = now, updatedAt = now, pendingSync = true))
            enqueue(
                EntityType.TASK,
                id,
                Operation.DELETE,
                current.version,
                mutations.json.encodeToString(DeletePayload(now)),
            )
        }
    }

    private fun TaskInput.validated(): TaskInput {
        runCatching { TimeZone.of(timeZoneId) }.getOrElse { throw ValidationException("Zona waktu tidak valid") }
        return copy(
            title = requireText(title, "Judul tugas", Limits.TASK_TITLE_MAX),
            description = optionalText(description, "Deskripsi", Limits.TASK_DESCRIPTION_MAX).orEmpty(),
        )
    }

    private fun TaskEntity.payload(): String =
        mutations.json.encodeToString(TaskPayload(courseId, title, description, dueAt, timeZoneId, status))
}

private fun TaskEntity.toDomain(courseName: String?) = StudyTask(
    id = id,
    courseId = courseId,
    courseName = courseName,
    title = title,
    description = description,
    dueAt = dueAt,
    timeZoneId = timeZoneId,
    status = runCatching { TaskStatus.valueOf(status) }.getOrDefault(TaskStatus.TODO),
    pendingSync = pendingSync,
)
