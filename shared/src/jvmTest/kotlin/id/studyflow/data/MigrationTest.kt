package id.studyflow.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import id.studyflow.data.local.StudyFlowDatabase
import id.studyflow.data.local.buildDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Database fase 1 (skema v1) harus terbuka di versi terbaru tanpa kehilangan data. */
class MigrationTest {
    @Test
    fun version1DatabaseMigratesToLatest() = runBlocking<Unit> {
        val schema = Json.parseToJsonElement(
            File("schemas/id.studyflow.data.local.StudyFlowDatabase/1.json").readText(),
        ).jsonObject["database"]!!.jsonObject
        val dir = Files.createTempDirectory("studyflow-migration").toFile()
        val file = File(dir, "v1.db")
        try {
            BundledSQLiteDriver().open(file.absolutePath).use { conn ->
                for (entity in schema["entities"]!!.jsonArray) {
                    val obj = entity.jsonObject
                    val table = obj["tableName"]!!.jsonPrimitive.content
                    conn.execSQL(obj["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                    obj["indices"]?.jsonArray?.forEach { index ->
                        conn.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                    }
                }
                schema["setupQueries"]!!.jsonArray.forEach { conn.execSQL(it.jsonPrimitive.content) }
                conn.execSQL(
                    "INSERT INTO courses (id, name, semester, created_at, updated_at, version, deleted_at, pending_sync) " +
                        "VALUES ('c1', 'Kriptografi', NULL, 1, 1, 0, NULL, 1)",
                )
                conn.execSQL(
                    "INSERT INTO local_outbox (mutation_id, entity_type, entity_id, operation, base_version, " +
                        "payload, created_at, retry_count) " +
                        "VALUES ('m1', 'COURSE', 'c1', 'CREATE', 0, '{\"name\":\"Kriptografi\"}', 1, 0)",
                )
                conn.execSQL("PRAGMA user_version = 1")
            }

            val db = buildDatabase(Room.databaseBuilder<StudyFlowDatabase>(name = file.absolutePath))
            try {
                assertEquals("Kriptografi", db.courseDao().getById("c1")!!.name)
                assertEquals(1, db.outboxDao().getAll().size)
                assertTrue(db.syncDao().conflicts().isEmpty(), "tabel sync_conflicts dibuat oleh migrasi 1→2")
                assertEquals(null, db.cacheDao().get("apa-saja"), "tabel remote_cache dibuat oleh migrasi 2→3")
            } finally {
                db.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
