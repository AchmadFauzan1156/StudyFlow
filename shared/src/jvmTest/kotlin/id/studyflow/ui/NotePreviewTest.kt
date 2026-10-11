package id.studyflow.ui

import id.studyflow.ui.courses.notePreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotePreviewTest {
    @Test
    fun usesFirstNonBlankLine() {
        assertEquals("RSA memakai dua bilangan prima.", notePreview("\n\n  RSA memakai dua bilangan prima.  \n\nLangkah 1"))
    }

    @Test
    fun blankContentHasNoPreview() {
        assertNull(notePreview(" \n\t\n"))
    }
}
