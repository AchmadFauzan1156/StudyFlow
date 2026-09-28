package id.studyflow.platform

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

@Composable
actual fun rememberPdfPicker(onPicked: (PickedFile) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "dokumen.pdf"
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@rememberLauncherForActivityResult
        onPicked(PickedFile(name, bytes))
    }
    return remember(launcher) { { launcher.launch(arrayOf("application/pdf")) } }
}

@Composable
actual fun rememberPdfOpener(): (bytes: ByteArray, fileName: String) -> Boolean {
    val context = LocalContext.current
    return remember(context) {
        { bytes, _ ->
            // Berkas sementara di cache privat, dibagikan ke penampil PDF lewat FileProvider (izin baca saja).
            val dir = File(context.cacheDir, "pdf").apply { mkdirs() }
            val file = File(dir, "dokumen.pdf").apply { writeBytes(bytes) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
                true
            } catch (e: ActivityNotFoundException) {
                false
            }
        }
    }
}
