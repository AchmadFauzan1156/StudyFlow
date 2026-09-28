package id.studyflow.data.local

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
    ],
    version = 1,
    exportSchema = true,
)
@ConstructedBy(StudyFlowDatabaseConstructor::class)
abstract class StudyFlowDatabase : RoomDatabase() {
    abstract fun courseDao(): CourseDao
    abstract fun noteDao(): NoteDao
    abstract fun taskDao(): TaskDao
    abstract fun outboxDao(): OutboxDao
    abstract fun settingsDao(): SettingsDao

    companion object {
        /** Mode lokal memakai database terpisah dari database akun (fase 2). */
        const val LOCAL_DB_NAME = "studyflow-local.db"
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
