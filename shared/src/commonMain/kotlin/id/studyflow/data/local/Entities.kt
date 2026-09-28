package id.studyflow.data.local

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Kolom sinkronisasi dipakai bersama semua entity yang disinkronkan:
 * - [version]: versi terakhir yang diketahui dari server (0 = belum pernah tersinkron).
 * - [deletedAt]: tombstone; baris tidak dihapus fisik selama masih dibutuhkan sync.
 * - [pendingSync]: ada mutasi lokal di outbox yang belum dikirim.
 */
@Entity(tableName = "courses")
data class CourseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val semester: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val version: Int = 0,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "pending_sync") val pendingSync: Boolean = true,
)

@Entity(
    tableName = "notes",
    foreignKeys = [
        ForeignKey(
            entity = CourseEntity::class,
            parentColumns = ["id"],
            childColumns = ["course_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("course_id")],
)
data class NoteEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "course_id") val courseId: String,
    val title: String,
    val content: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val version: Int = 0,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "pending_sync") val pendingSync: Boolean = true,
)

/**
 * Deadline disimpan sebagai instant UTC ([dueAt], epoch millis) ditambah zona waktu saat
 * deadline dibuat ([timeZoneId]) agar maknanya tidak berubah diam-diam saat perangkat pindah zona.
 */
@Entity(
    tableName = "tasks",
    foreignKeys = [
        ForeignKey(
            entity = CourseEntity::class,
            parentColumns = ["id"],
            childColumns = ["course_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("course_id"), Index("due_at")],
)
data class TaskEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "course_id") val courseId: String,
    val title: String,
    val description: String,
    @ColumnInfo(name = "due_at") val dueAt: Long,
    @ColumnInfo(name = "time_zone_id") val timeZoneId: String,
    val status: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val version: Int = 0,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "pending_sync") val pendingSync: Boolean = true,
)

/** Antrean mutasi lokal (hanya di perangkat). Ditulis dalam transaksi yang sama dengan mutasinya. */
@Entity(
    tableName = "local_outbox",
    indices = [Index(value = ["mutation_id"], unique = true)],
)
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    @ColumnInfo(name = "mutation_id") val mutationId: String,
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    val operation: String,
    @ColumnInfo(name = "base_version") val baseVersion: Int,
    val payload: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "retry_count") val retryCount: Int = 0,
)

/**
 * Konflik sinkronisasi yang menunggu keputusan pengguna. Isi lokal tetap di tabel entity;
 * [serverEntity] menyimpan salinan server (JSON) pada [serverVersion].
 */
@Entity(tableName = "sync_conflicts", primaryKeys = ["entity_type", "entity_id"])
data class ConflictEntity(
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "server_version") val serverVersion: Int,
    @ColumnInfo(name = "server_entity") val serverEntity: String,
    @ColumnInfo(name = "detected_at") val detectedAt: Long,
)

@Entity(tableName = "app_settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

data class CourseWithCounts(
    @Embedded val course: CourseEntity,
    @ColumnInfo(name = "note_count") val noteCount: Int,
    @ColumnInfo(name = "open_task_count") val openTaskCount: Int,
)

data class TaskWithCourse(
    @Embedded val task: TaskEntity,
    @ColumnInfo(name = "course_name") val courseName: String,
)
