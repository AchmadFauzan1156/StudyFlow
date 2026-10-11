package id.studyflow.ui.materials

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.studyflow.data.auth.AuthState
import id.studyflow.data.auth.SessionManager
import id.studyflow.data.remote.DocumentDto
import id.studyflow.platform.rememberPdfPicker
import id.studyflow.ui.components.ConfirmDeleteDialog
import id.studyflow.ui.components.EmptyState
import id.studyflow.ui.study.Label
import id.studyflow.ui.study.OfflineNote
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun MaterialsTab(courseId: String, onOpenDocument: (String) -> Unit, modifier: Modifier) {
    val session = koinInject<SessionManager>()
    val auth by session.state.collectAsStateWithLifecycle()
    if (auth !is AuthState.SignedIn) {
        Column(modifier.padding(16.dp)) { NeedsAccount("Materi PDF") }
        return
    }
    val viewModel = koinViewModel<MaterialsViewModel>(key = "materials-$courseId") { parametersOf(courseId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pick = rememberPdfPicker(viewModel::onPicked)
    var deleting by remember { mutableStateOf<DocumentDto?>(null) }

    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!state.courseSynced) {
            item { NeedsSync(viewModel::syncNow) }
            return@LazyColumn
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Unggah PDF berbasis teks (maks. 10 MB, 100 halaman) untuk ditanyakan. PDF hasil scan belum didukung.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = pick, enabled = !state.uploading && !state.offline) {
                        Text(if (state.uploading) "Mengunggah…" else "Unggah PDF")
                    }
                    if (state.retryAvailable && !state.uploading) {
                        OutlinedButton(onClick = viewModel::retryUpload) { Text("Coba lagi") }
                    }
                }
                if (state.offline) OfflineNote()
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        if (state.loading) item { CircularProgressIndicator() }
        if (!state.loading && state.documents.isEmpty()) {
            item { EmptyState("Belum ada materi", "PDF yang kamu unggah akan muncul di sini.") }
        }
        items(state.documents, key = { it.id }) { doc ->
            DocumentCard(doc, onClick = { onOpenDocument(doc.id) }, onDelete = { deleting = doc })
        }
    }

    state.consentFor?.let { file ->
        AlertDialog(
            onDismissRequest = viewModel::dismissConsent,
            title = { Text("Unggah \"${file.name}\"?") },
            text = {
                Text(
                    "PDF disimpan privat di server StudyFlow. Teksnya diproses menjadi embedding oleh " +
                        "${state.info?.providerLabel ?: "penyedia AI"} agar bisa ditanyakan.\n\n" +
                        (state.info?.dataNotice ?: ""),
                )
            },
            confirmButton = { TextButton(onClick = viewModel::confirmConsent) { Text("Setuju dan unggah") } },
            dismissButton = { TextButton(onClick = viewModel::dismissConsent) { Text("Batal") } },
        )
    }
    deleting?.let { doc ->
        ConfirmDeleteDialog(
            title = "Hapus ${doc.title}?",
            message = "Berkas, teks, dan indeks pencarian dokumen ini dihapus dari server.",
            onConfirm = {
                viewModel.delete(doc.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun DocumentCard(doc: DocumentDto, onClick: () -> Unit, onDelete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = doc.status == "ready", onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    doc.title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                when (doc.status) {
                    "ready" -> Label("Siap", colors.primaryContainer, colors.onPrimaryContainer)
                    "queued", "processing" -> Label("Diproses…", colors.secondaryContainer, colors.onSecondaryContainer)
                    "unsupported" -> Label("Tidak didukung", colors.errorContainer, colors.onErrorContainer)
                    else -> Label("Gagal", colors.errorContainer, colors.onErrorContainer)
                }
            }
            Text(
                "${doc.pageCount} halaman · ${doc.sizeBytes / 1024} KB",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            if (doc.status == "unsupported" || doc.status == "failed") {
                Text(documentErrorMessage(doc.errorCode), style = MaterialTheme.typography.bodySmall, color = colors.error)
            }
            doc.warnings.filter { it.startsWith("Halaman tanpa teks") }.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            TextButton(onClick = onDelete, modifier = Modifier.align(Alignment.End)) { Text("Hapus") }
        }
    }
}

// ---------- Detail dokumen ----------
