package id.studyflow.ui

import id.studyflow.data.Device
import id.studyflow.data.repository.TaskInput
import id.studyflow.domain.TaskStatus
import id.studyflow.ui.courses.CoursesViewModel
import id.studyflow.ui.dashboard.HomeViewModel
import id.studyflow.ui.notes.NoteEditorViewModel
import id.studyflow.ui.progress.ProgressViewModel
import id.studyflow.ui.tasks.TaskEditorViewModel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ViewModelTest {
    private val device = Device(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) })

    @AfterTest
    fun close() = device.close()

    private suspend fun <T> Flow<T>.firstMatching(predicate: (T) -> Boolean): T = withTimeout(5_000) { first(predicate) }

    @Test
    fun coursesViewModelSavesCourse() = runBlocking<Unit> {
        val vm = CoursesViewModel(device.courses)
        val saved = CompletableDeferred<Unit>()
        vm.save(null, "Kriptografi", "Ganjil 2026") { saved.complete(Unit) }
        withTimeout(5_000) { saved.await() }
        val list = vm.courses.firstMatching { it?.isNotEmpty() == true }!!
        assertEquals("Kriptografi", list.single().name)
        assertNull(vm.error.value)
    }

    @Test
    fun coursesViewModelReportsValidationErrorWithoutSaving() = runBlocking<Unit> {
        val vm = CoursesViewModel(device.courses)
        vm.save(null, "   ", "") { error("tidak boleh tersimpan") }
        vm.error.firstMatching { it != null }
        assertEquals(emptyList(), vm.courses.firstMatching { it != null })
        vm.clearError()
        assertNull(vm.error.value)
    }

    @Test
    fun homeCountsOpenAndOverdueTasks() = runBlocking<Unit> {
        val courseId = device.courses.create("Basis Data", null)
        device.tasks.create(courseId, TaskInput("Lewat", "", device.clock.now - 60_000, "Asia/Jakarta"))
        device.tasks.create(courseId, TaskInput("Besok", "", device.clock.now + 86_400_000, "Asia/Jakarta"))
        val vm = HomeViewModel(device.courses, device.tasks, device.clock, device.session(), device.conflicts)

        val state = vm.state.firstMatching { !it.loading && it.openCount == 2 }
        assertEquals(1, state.overdueCount)
        assertEquals(1, state.courseCount)
    }

    @Test
    fun homeToggleDoneRemovesTaskFromOpenList() = runBlocking<Unit> {
        val courseId = device.courses.create("Basis Data", null)
        device.tasks.create(courseId, TaskInput("Lewat", "", device.clock.now - 60_000, "Asia/Jakarta"))
        val vm = HomeViewModel(device.courses, device.tasks, device.clock, device.session(), device.conflicts)
        val state = vm.state.firstMatching { !it.loading && it.openCount == 1 }

        vm.toggleDone(state.upcoming.single())
        val after = vm.state.firstMatching { it.openCount == 0 }
        assertEquals(0, after.overdueCount)
        assertEquals(TaskStatus.DONE, device.tasks.observeAll().first().single().status)
    }

    @Test
    fun taskEditorDefaultsToTomorrowAt2359InUserZone() {
        val courseId = runBlocking { device.courses.create("Jaringan", null) }
        val state = TaskEditorViewModel(courseId, null, device.tasks, device.clock).state.value
        assertEquals(23 to 59, state.hour to state.minute)
        assertEquals("Asia/Jakarta", state.timeZoneId)
        assertEquals(true, state.isNew)
    }

    @Test
    fun taskEditorSavesNewTask() = runBlocking<Unit> {
        val courseId = device.courses.create("Jaringan", null)
        val vm = TaskEditorViewModel(courseId, null, device.tasks, device.clock)
        vm.onTitleChange("Laporan praktikum")
        val done = CompletableDeferred<Unit>()
        vm.save { done.complete(Unit) }
        withTimeout(5_000) { done.await() }
        val task = device.tasks.observeAll().first().single()
        assertEquals("Laporan praktikum", task.title)
        assertEquals(TaskStatus.TODO, task.status)
    }

    @Test
    fun taskEditorRejectsBlankTitle() = runBlocking<Unit> {
        val courseId = device.courses.create("Jaringan", null)
        val vm = TaskEditorViewModel(courseId, null, device.tasks, device.clock)
        vm.save { error("tidak boleh tersimpan") }
        val state = vm.state.firstMatching { it.error != null }
        assertFalse(state.saving)
        assertEquals(emptyList(), device.tasks.observeAll().first())
    }

    @Test
    fun noteEditorCreatesNote() = runBlocking<Unit> {
        val courseId = device.courses.create("Kriptografi", null)
        val vm = NoteEditorViewModel(courseId, null, device.notes)
        vm.onTitleChange("RSA")
        vm.onContentChange("Dua bilangan prima besar.")
        val saved = CompletableDeferred<Unit>()
        vm.save { saved.complete(Unit) }
        withTimeout(5_000) { saved.await() }
        val note = device.notes.observeByCourse(courseId).firstMatching { it.isNotEmpty() }.single()
        assertEquals("RSA", note.title)
        assertEquals("Dua bilangan prima besar.", note.content)
    }

    @Test
    fun noteEditorLoadsAndUpdatesExistingNote() = runBlocking<Unit> {
        val courseId = device.courses.create("Kriptografi", null)
        val noteId = device.notes.create(courseId, "RSA", "Isi lama")
        val vm = NoteEditorViewModel(courseId, noteId, device.notes)
        val loaded = vm.state.firstMatching { !it.loading }
        assertEquals("RSA", loaded.title)
        assertEquals("Isi lama", loaded.content)
        assertFalse(loaded.isNew)

        vm.onContentChange("Isi baru")
        val saved = CompletableDeferred<Unit>()
        vm.save { saved.complete(Unit) }
        withTimeout(5_000) { saved.await() }
        assertEquals("Isi baru", device.notes.get(noteId)!!.content)
    }

    @Test
    fun noteEditorBlankTitleShowsErrorWithoutSaving() = runBlocking<Unit> {
        val courseId = device.courses.create("Kriptografi", null)
        val vm = NoteEditorViewModel(courseId, null, device.notes)
        vm.onContentChange("Tanpa judul")
        vm.save { error("tidak boleh tersimpan") }
        val state = vm.state.firstMatching { it.error != null }
        assertNotNull(state.error)
        assertFalse(state.saving)
        assertEquals(emptyList(), device.notes.observeByCourse(courseId).first())
    }

    @Test
    fun progressCountsCoursesNotesAndTasks() = runBlocking<Unit> {
        val courseId = device.courses.create("Basis Data", null)
        device.notes.create(courseId, "Normalisasi", "")
        val done = device.tasks.create(courseId, TaskInput("Selesai", "", device.clock.now + 86_400_000, "Asia/Jakarta"))
        device.tasks.setStatus(done, TaskStatus.DONE)
        device.tasks.create(courseId, TaskInput("Lewat", "", device.clock.now - 60_000, "Asia/Jakarta"))
        val vm = ProgressViewModel(device.courses, device.notes, device.tasks, device.clock)

        val state = vm.state.firstMatching { it.taskTotal == 2 && it.taskDone == 1 }
        assertEquals(1, state.courseCount)
        assertEquals(1, state.noteCount)
        assertEquals(1, state.overdue)
    }
}
