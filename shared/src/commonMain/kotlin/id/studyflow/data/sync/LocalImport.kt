package id.studyflow.data.sync

import id.studyflow.data.local.DatabaseManager
import id.studyflow.data.local.SettingEntity
import id.studyflow.data.repository.CoursePayload
import id.studyflow.data.repository.EntityType
import id.studyflow.data.repository.LocalMutations
import id.studyflow.data.repository.NotePayload
import id.studyflow.data.repository.Operation
import id.studyflow.data.repository.TaskPayload
import id.studyflow.platform.sha256
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Menyalin data mode lokal ke database akun yang sedang masuk.
 *
 * ID hasil impor diturunkan secara deterministik dari (user_id, id lokal): impor ulang ke akun yang
 * sama tidak menggandakan data, sedangkan akun berbeda mendapat ID berbeda (ID entity unik global
 * di server). Data mode lokal tidak dihapus.
 */
class LocalImport(private val databases: DatabaseManager, private val mutations: LocalMutations) {
    suspend fun localCourseCount(): Int = databases.local.syncDao().activeCourseCount()

    /** true jika tawaran impor untuk akun ini belum pernah ditampilkan. */
    suspend fun shouldOffer(userId: String): Boolean =
        localCourseCount() > 0 && databases.local.settingsDao().get(promptKey(userId)) == null

    suspend fun markOffered(userId: String) = databases.local.settingsDao().put(SettingEntity(promptKey(userId), "1"))

    /** Mengembalikan jumlah mata kuliah yang baru ditambahkan. */
    suspend fun importInto(userId: String): Int {
        check(databases.accountId.value == userId) { "Database akun tidak aktif" }
        val source = databases.local.syncDao()
        val courses = source.activeCourses()
        val notes = source.activeNotes()
        val tasks = source.activeTasks()
        var imported = 0
        mutations.write {
            val now = mutations.now()
            val json = mutations.json
            for (c in courses) {
                val id = importedId(userId, c.id)
                if (db.courseDao().getById(id) != null) continue
                db.courseDao().upsert(c.copy(id = id, version = 0, pendingSync = true, updatedAt = now))
                enqueue(EntityType.COURSE, id, Operation.CREATE, 0, json.encodeToString(CoursePayload(c.name, c.semester)))
                imported++
            }
            for (n in notes) {
                val id = importedId(userId, n.id)
                val courseId = importedId(userId, n.courseId)
                if (db.noteDao().getById(id) != null || db.courseDao().getById(courseId) == null) continue
                db.noteDao().upsert(n.copy(id = id, courseId = courseId, version = 0, pendingSync = true, updatedAt = now))
                enqueue(EntityType.NOTE, id, Operation.CREATE, 0, json.encodeToString(NotePayload(courseId, n.title, n.content)))
            }
            for (t in tasks) {
                val id = importedId(userId, t.id)
                val courseId = importedId(userId, t.courseId)
                if (db.taskDao().getById(id) != null || db.courseDao().getById(courseId) == null) continue
                db.taskDao().upsert(t.copy(id = id, courseId = courseId, version = 0, pendingSync = true, updatedAt = now))
                enqueue(
                    EntityType.TASK,
                    id,
                    Operation.CREATE,
                    0,
                    json.encodeToString(TaskPayload(courseId, t.title, t.description, t.dueAt, t.timeZoneId, t.status)),
                )
            }
        }
        markOffered(userId)
        return imported
    }

    private fun promptKey(userId: String) = "import_offered:$userId"
}

/** UUID berbasis nama (SHA-256, versi 8 menurut RFC 9562) dari user_id dan ID lokal. */
@OptIn(ExperimentalUuidApi::class)
internal fun importedId(userId: String, localId: String): String {
    val bytes = sha256("studyflow-import:$userId:$localId".encodeToByteArray()).copyOf(16)
    bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x80).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
    return Uuid.fromByteArray(bytes).toString()
}
