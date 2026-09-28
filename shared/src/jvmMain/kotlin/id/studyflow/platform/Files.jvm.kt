package id.studyflow.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
actual fun rememberPdfPicker(onPicked: (PickedFile) -> Unit): () -> Unit = remember(onPicked) {
    {
        val dialog = FileDialog(null as Frame?, "Pilih PDF", FileDialog.LOAD).apply {
            setFilenameFilter { _, name -> name.endsWith(".pdf", ignoreCase = true) }
            file = "*.pdf" // filter untuk Windows, yang mengabaikan FilenameFilter
            isVisible = true
        }
        val dir = dialog.directory
        val name = dialog.file
        if (dir != null && name != null) {
            val file = File(dir, name)
            onPicked(PickedFile(file.name, file.readBytes()))
        }
    }
}

@Composable
actual fun rememberPdfOpener(): (bytes: ByteArray, fileName: String) -> Boolean = remember {
    { bytes, fileName ->
        runCatching {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                return@runCatching false
            }
            val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "dokumen" }
            val file = File.createTempFile("studyflow-", "-$safeName.pdf").apply { deleteOnExit() }
            file.writeBytes(bytes)
            Desktop.getDesktop().open(file)
            true
        }.getOrDefault(false)
    }
}
