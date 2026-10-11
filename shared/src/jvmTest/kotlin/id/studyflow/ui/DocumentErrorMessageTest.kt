package id.studyflow.ui

import id.studyflow.ui.materials.documentErrorMessage
import kotlin.test.Test
import kotlin.test.assertTrue

class DocumentErrorMessageTest {
    @Test
    fun tooHeavyPdfHasSpecificAdvice() {
        assertTrue("terlalu berat" in documentErrorMessage("pdf_too_heavy"))
    }
}
