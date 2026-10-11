package id.studyflow.ui

import id.studyflow.domain.Course
import id.studyflow.ui.courses.courseMeta
import kotlin.test.Test
import kotlin.test.assertEquals

class CourseMetaTest {
    @Test
    fun hidesZeroCounts() {
        assertEquals("Ganjil 2026 · 2 catatan", courseMeta(Course("1", "Kriptografi", "Ganjil 2026", noteCount = 2)))
        assertEquals("3 tugas terbuka", courseMeta(Course("1", "Basis Data", null, openTaskCount = 3)))
    }

    @Test
    fun explainsEmptyCourse() {
        assertEquals("Belum ada catatan atau tugas", courseMeta(Course("1", "Jaringan", null)))
    }
}
