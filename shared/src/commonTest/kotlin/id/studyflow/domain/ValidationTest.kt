package id.studyflow.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ValidationTest {
    @Test
    fun requireTextTrimsInput() {
        assertEquals("Kriptografi", requireText("  Kriptografi ", "Nama", 20))
    }

    @Test
    fun requireTextRejectsBlank() {
        assertFailsWith<ValidationException> { requireText("   ", "Nama", 20) }
    }

    @Test
    fun requireTextRejectsTooLong() {
        assertFailsWith<ValidationException> { requireText("a".repeat(21), "Nama", 20) }
    }

    @Test
    fun optionalTextTurnsBlankIntoNull() {
        assertNull(optionalText("  ", "Semester", 10))
        assertNull(optionalText(null, "Semester", 10))
    }
}
