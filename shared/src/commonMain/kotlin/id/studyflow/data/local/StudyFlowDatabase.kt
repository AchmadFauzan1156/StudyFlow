package id.studyflow.data.local

import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

@Database(
    entities = [
        CourseEntity::class,
        NoteEntity::class,
        TaskEntity::class,
        OutboxEntity::class,
        SettingEntity::class,
        ConflictEntity::class,
        RemoteCacheEntity::class,
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
@ConstructedBy(StudyFlowDatabaseConstructor::class)
abstract class StudyFlowDatabase : RoomDatabase() {
    abstract fun courseDao(): CourseDao
    abstract fun noteDao(): NoteDao
    abstract fun taskDao(): TaskDao
    abstract fun outboxDao(): OutboxDao
    abstract fun settingsDao(): SettingsDao
    abstract fun syncDao(): SyncDao
    abstract fun cacheDao(): CacheDao

    companion object {
        /** Mode lokal (tanpa akun). Tiap akun memakai file terpisah, lihat [accountDbName]. */
        const val LOCAL_DB_NAME = "studyflow-local.db"

        fun accountDbName(userId: String): String = "studyflow-user-$userId.db"
    }
}

@Suppress("KotlinNoActualForExpect")
expect object StudyFlowDatabaseConstructor : RoomDatabaseConstructor<StudyFlowDatabase> {
    override fun initialize(): StudyFlowDatabase
}

fun buildDatabase(builder: RoomDatabase.Builder<StudyFlowDatabase>): StudyFlowDatabase =
    builder
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
