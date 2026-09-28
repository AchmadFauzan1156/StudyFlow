package id.studyflow.data.repository

import id.studyflow.data.local.NoteEntity
import id.studyflow.data.local.DatabaseManager
import id.studyflow.domain.Limits
import id.studyflow.domain.Note
import id.studyflow.domain.ValidationException
import id.studyflow.domain.requireText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString

@OptIn(ExperimentalCoroutinesApi::class)
class NoteRepository(
    private val databases: DatabaseManager,
    private val mutations: LocalMutations,
) {
    private val noteDao get() = databases.db.noteDao()

    fun observeByCourse(courseId: String): Flow<List<Note>> =
        databases.current.flatMapLatest { it.noteDao().observeByCourse(courseId) }.map { list -> list.map { it.toDomain() } }

    fun observeCount(): Flow<Int> = databases.current.flatMapLatest { it.noteDao().observeCount() }

    suspend fun get(id: String): Note? = noteDao.getById(id)?.takeIf { it.deletedAt == null }?.toDomain()

    suspend fun create(courseId: String, title: String, content: String): String {
        val cleanTitle = requireText(title, "Judul catatan", Limits.NOTE_TITLE_MAX)
        val cleanContent = checkContent(content)
        val now = mutations.now()
        val entity = NoteEntity(
            id = mutations.newId(),
            courseId = courseId,
            title = cleanTitle,
            content = cleanContent,
            createdAt = now,
            updatedAt = now,
        )
        mutations.write {
            val course = db.courseDao().getById(courseId)
            if (course == null || course.deletedAt != null) throw ValidationException("Mata kuliah tidak ditemukan")
            db.noteDao().upsert(entity)
            enqueue(EntityType.NOTE, entity.id, Operation.CREATE, entity.version, entity.payload())
        }
        return entity.id
    }

    suspend fun update(id: String, title: String, content: String) {
        val cleanTitle = requireText(title, "Judul catatan", Limits.NOTE_TITLE_MAX)
        val cleanContent = checkContent(content)
        mutations.write {
            val current = db.noteDao().getById(id)?.takeIf { it.deletedAt == null }
                ?: throw ValidationException("Catatan tidak ditemukan")
            if (current.title == cleanTitle && current.content == cleanContent) return@write
            val updated = current.copy(
                title = cleanTitle,
                content = cleanContent,
                updatedAt = mutations.now(),
                pendingSync = true,
            )
            db.noteDao().upsert(updated)
            enqueue(EntityType.NOTE, id, Operation.UPDATE, current.version, updated.payload())
        }
    }

    suspend fun delete(id: String) {
        mutations.write {
            val current = db.noteDao().getById(id)?.takeIf { it.deletedAt == null } ?: return@write
            val now = mutations.now()
            db.noteDao().upsert(current.copy(deletedAt = now, updatedAt = now, pendingSync = true))
            enqueue(
                EntityType.NOTE, id, Operation.DELETE, current.version,
                mutations.json.encodeToString(DeletePayload(now)),
            )
        }
    }

    private fun checkContent(content: String): String {
        if (content.length > Limits.NOTE_CONTENT_MAX) {
            throw ValidationException("Isi catatan maksimal ${Limits.NOTE_CONTENT_MAX} karakter")
        }
        return content
    }

    private fun NoteEntity.payload(): String =
        mutations.json.encodeToString(NotePayload(courseId, title, content))
}

private fun NoteEntity.toDomain() = Note(
    id = id,
    courseId = courseId,
    title = title,
    content = content,
    updatedAt = updatedAt,
    pendingSync = pendingSync,
)
