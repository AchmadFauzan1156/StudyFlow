package id.studyflow.data

import androidx.room.Room
import id.studyflow.data.local.DatabaseManager
import id.studyflow.data.local.StudyFlowDatabase
import id.studyflow.data.local.buildDatabase
import id.studyflow.data.repository.CourseRepository
import id.studyflow.data.repository.LocalMutations
import id.studyflow.data.repository.NoteRepository
import id.studyflow.data.repository.TaskInput
import id.studyflow.data.repository.TaskRepository
import id.studyflow.domain.TaskStatus
import id.studyflow.domain.ValidationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepositoryTest {
    private val clock = FakeClock()
    private val openDbs = mutableListOf<StudyFlowDatabase>()

    private fun inMemoryDb() = buildDatabase(Room.inMemoryDatabaseBuilder<StudyFlowDatabase>()).also { openDbs += it }

    private fun fileDb(file: File) = buildDatabase(Room.databaseBuilder<StudyFlowDatabase>(name = file.absolutePath)).also { openDbs += it }

    private class Repos(val db: StudyFlowDatabase, clock: AppClock, ids: IdGenerator = UuidGenerator) {
        val databases = DatabaseManager { db }
        val mutations = LocalMutations(databases, clock, ids)
        val courses = CourseRepository(databases, mutations)
        val notes = NoteRepository(databases, mutations)
        val tasks = TaskRepository(databases, mutations)
        val outbox = db.outboxDao()
    }

    @AfterTest
    fun closeDbs() = openDbs.forEach { it.close() }

    @Test
    fun courseCrudWritesOutboxInOrder() = runBlocking<Unit> {
        val r = Repos(inMemoryDb(), clock, SequentialIds())
        val id = r.courses.create("  Kriptografi ", "Ganjil 2026")
        r.courses.update(id, "Kriptografi Terapan", "")
        val course = r.courses.observeCourse(id).first()!!
        assertEquals("Kriptografi Terapan", course.name)
        assertNull(course.semester)

        val entries = r.outbox.getAll()
        assertEquals(listOf("CREATE", "UPDATE"), entries.map { it.operation })
        assertTrue(entries.all { it.entityId == id && it.entityType == "COURSE" && it.baseVersion == 0 })
        assertEquals(entries.size, entries.map { it.mutationId }.toSet().size, "mutation_id harus unik")
    }

    @Test
    fun invalidInputIsRejectedWithoutSideEffects() = runBlocking<Unit> {
        val r = Repos(inMemoryDb(), clock)
        assertFailsWith<ValidationException> { r.courses.create("   ", null) }
        assertFailsWith<ValidationException> { r.notes.create("missing-course", "Judul", "isi") }
        assertTrue(r.outbox.getAll().isEmpty())
        assertTrue(r.courses.observeCourses().first().isEmpty())
    }

    @Test
    fun noteUpdateWithoutChangesIsNoOp() = runBlocking<Unit> {
        val r = Repos(inMemoryDb(), clock)
        val courseId = r.courses.create("Basis Data", null)
        val noteId = r.notes.create(courseId, "Normalisasi", "1NF, 2NF, 3NF")
        r.notes.update(noteId, "Normalisasi", "1NF, 2NF, 3NF")
        assertEquals(2, r.outbox.getAll().size)
    }

    @Test
    fun deletingCourseTombstonesChildren() = runBlocking<Unit> {
        val r = Repos(inMemoryDb(), clock)
        val courseId = r.courses.create("Jaringan", null)
        r.notes.create(courseId, "OSI", "7 layer")
        r.tasks.create(courseId, TaskInput("Laporan", "", clock.now + 86_400_000, "Asia/Jakarta"))

        r.courses.delete(courseId)

        assertTrue(r.courses.observeCourses().first().isEmpty())
        assertTrue(r.notes.observeByCourse(courseId).first().isEmpty())
        assertTrue(r.tasks.observeAll().first().isEmpty())
        assertEquals("DELETE", r.outbox.getAll().last().operation)
        // Tombstone, bukan hapus fisik: baris tetap ada untuk sinkronisasi.
        assertTrue(r.db.courseDao().getById(courseId)?.deletedAt != null)
        assertTrue(r.db.noteDao().getActiveByCourse(courseId).isEmpty())
    }

    @Test
    fun courseCountsExcludeDoneAndDeletedTasks() = runBlocking<Unit> {
        val r = Repos(inMemoryDb(), clock)
        val courseId = r.courses.create("Algoritma", null)
        val due = clock.now + 3_600_000
        val t1 = r.tasks.create(courseId, TaskInput("Tugas 1", "", due, "Asia/Jakarta"))
        val t2 = r.tasks.create(courseId, TaskInput("Tugas 2", "", due, "Asia/Jakarta"))
        r.tasks.create(courseId, TaskInput("Tugas 3", "", due, "Asia/Jakarta"))
        r.tasks.setStatus(t1, TaskStatus.DONE)
        r.tasks.delete(t2)
        r.notes.create(courseId, "Big-O", "")

        val course = r.courses.observeCourses().first().single()
        assertEquals(1, course.openTaskCount)
        assertEquals(1, course.noteCount)
    }

    @Test
    fun tasksAreOrderedByDeadlineWithDoneLast() = runBlocking<Unit> {
        val r = Repos(inMemoryDb(), clock)
        val courseId = r.courses.create("Statistika", null)
        r.tasks.create(courseId, TaskInput("Akhir", "", clock.now + 3 * 86_400_000L, "Asia/Jakarta"))
        r.tasks.create(courseId, TaskInput("Awal", "", clock.now + 86_400_000L, "Asia/Jakarta"))
        r.tasks.create(courseId, TaskInput("Sudah", "", clock.now, "Asia/Jakarta", TaskStatus.DONE))

        val titles = r.tasks.observeAll().first().map { it.title }
        assertEquals(listOf("Awal", "Akhir", "Sudah"), titles)
        assertEquals("Statistika", r.tasks.observeAll().first().first().courseName)
    }

    @Test
    fun invalidTimeZoneIsRejected() = runBlocking<Unit> {
        val r = Repos(inMemoryDb(), clock)
        val courseId = r.courses.create("Fisika", null)
        assertFailsWith<ValidationException> {
            r.tasks.create(courseId, TaskInput("X", "", clock.now, "Mars/Olympus"))
        }
    }

    @Test
    fun dataSurvivesReopeningDatabaseFile() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("studyflow-test").toFile()
        val file = File(dir, "studyflow.db")
        try {
            val first = fileDb(file)
            val r1 = Repos(first, clock)
            val courseId = r1.courses.create("Kriptografi", "Ganjil")
            r1.notes.create(courseId, "RSA", "p, q, n = p*q")
            val dueAt = clock.now + 86_400_000
            r1.tasks.create(courseId, TaskInput("Kuis RSA", "Bab 3", dueAt, "Asia/Jakarta"))
            first.close()
            openDbs.remove(first)

            val r2 = Repos(fileDb(file), clock)
            val course = r2.courses.observeCourses().first().single()
            assertEquals("Kriptografi", course.name)
            assertEquals("RSA", r2.notes.observeByCourse(courseId).first().single().title)
            val task = r2.tasks.observeAll().first().single()
            assertEquals(dueAt, task.dueAt)
            assertEquals("Asia/Jakarta", task.timeZoneId)
            assertEquals(3, r2.outbox.getAll().size)
        } finally {
            openDbs.forEach { it.close() }
            openDbs.clear()
            dir.deleteRecursively()
        }
    }
}
