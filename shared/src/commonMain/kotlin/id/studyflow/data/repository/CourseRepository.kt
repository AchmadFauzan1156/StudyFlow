package id.studyflow.data.repository

import id.studyflow.data.local.CourseEntity
import id.studyflow.data.local.DatabaseManager
import id.studyflow.domain.Course
import id.studyflow.domain.Limits
import id.studyflow.domain.ValidationException
import id.studyflow.domain.optionalText
import id.studyflow.domain.requireText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString

@OptIn(ExperimentalCoroutinesApi::class)
class CourseRepository(
    private val databases: DatabaseManager,
    private val mutations: LocalMutations,
) {
    private val courseDao get() = databases.db.courseDao()

    fun observeCourses(): Flow<List<Course>> =
        databases.current.flatMapLatest { it.courseDao().observeAllWithCounts() }.map { rows ->
            rows.map { it.course.toDomain(it.noteCount, it.openTaskCount) }
        }

    fun observeCourse(id: String): Flow<Course?> =
        databases.current.flatMapLatest { it.courseDao().observeById(id) }.map { it?.toDomain() }

    fun observeCount(): Flow<Int> = databases.current.flatMapLatest { it.courseDao().observeCount() }

    suspend fun create(name: String, semester: String?): String {
        val cleanName = requireText(name, "Nama mata kuliah", Limits.COURSE_NAME_MAX)
        val cleanSemester = optionalText(semester, "Semester", Limits.SEMESTER_MAX)
        val now = mutations.now()
        val entity = CourseEntity(
            id = mutations.newId(),
            name = cleanName,
            semester = cleanSemester,
            createdAt = now,
            updatedAt = now,
        )
        mutations.write {
            db.courseDao().upsert(entity)
            enqueue(EntityType.COURSE, entity.id, Operation.CREATE, entity.version, entity.payload())
        }
        return entity.id
    }

    suspend fun update(id: String, name: String, semester: String?) {
        val cleanName = requireText(name, "Nama mata kuliah", Limits.COURSE_NAME_MAX)
        val cleanSemester = optionalText(semester, "Semester", Limits.SEMESTER_MAX)
        mutations.write {
            val current = db.courseDao().getById(id)?.takeIf { it.deletedAt == null }
                ?: throw ValidationException("Mata kuliah tidak ditemukan")
            val updated = current.copy(
                name = cleanName,
                semester = cleanSemester,
                updatedAt = mutations.now(),
                pendingSync = true,
            )
            db.courseDao().upsert(updated)
            enqueue(EntityType.COURSE, id, Operation.UPDATE, current.version, updated.payload())
        }
    }

    /**
     * Tombstone mata kuliah beserta catatan dan tugasnya. Hanya mutasi course yang masuk outbox;
     * server menerapkan cascade yang sama pada anak-anaknya (lihat docs/DECISIONS.md).
     */
    suspend fun delete(id: String) {
        mutations.write {
            val current = db.courseDao().getById(id)?.takeIf { it.deletedAt == null } ?: return@write
            val now = mutations.now()
            db.courseDao().upsert(current.copy(deletedAt = now, updatedAt = now, pendingSync = true))
            db.noteDao().getActiveByCourse(id).forEach {
                db.noteDao().upsert(it.copy(deletedAt = now, updatedAt = now))
            }
            db.taskDao().getActiveByCourse(id).forEach {
                db.taskDao().upsert(it.copy(deletedAt = now, updatedAt = now))
            }
            enqueue(
                EntityType.COURSE, id, Operation.DELETE, current.version,
                mutations.json.encodeToString(DeletePayload(now)),
            )
        }
    }

    private fun CourseEntity.payload(): String =
        mutations.json.encodeToString(CoursePayload(name, semester))
}

private fun CourseEntity.toDomain(noteCount: Int = 0, openTaskCount: Int = 0) = Course(
    id = id,
    name = name,
    semester = semester,
    noteCount = noteCount,
    openTaskCount = openTaskCount,
    pendingSync = pendingSync,
)
