package id.studyflow.ui.materials

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.studyflow.platform.rememberPdfOpener
import id.studyflow.ui.components.ConfirmDeleteDialog
import id.studyflow.ui.study.OfflineNote
import id.studyflow.ui.study.StudyScaffold
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun DocumentScreen(documentId: String, onBack: () -> Unit) {
    val viewModel = koinViewModel<DocumentViewModel>(key = "document-$documentId") { parametersOf(documentId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openPdf = rememberPdfOpener()
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }

    StudyScaffold(state.document?.title ?: "Materi", onBack) { modifier ->
        LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.offline) OfflineNote()
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    state.document?.let { doc ->
                        doc.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.open(openPdf) }, enabled = !state.busy && !state.offline) {
                                Text(if (state.busy) "Mengunduh…" else "Buka PDF")
                            }
                            OutlinedButton(onClick = { confirmDelete = true }, enabled = !state.offline) { Text("Hapus") }
                        }
                        Text("Pratinjau teks hasil ekstraksi", style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            items(state.pages, key = { it.pageNumber }) { page ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Halaman ${page.pageNumber}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(page.text.ifBlank { "(tidak ada teks terbaca)" }, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (state.hasMorePages) {
                item { OutlinedButton(onClick = viewModel::loadMorePages) { Text("Muat halaman berikutnya") } }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDeleteDialog(
            title = "Hapus materi ini?",
            message = "Berkas, teks, dan indeks pencarian dokumen ini dihapus dari server.",
            onConfirm = {
                confirmDelete = false
                viewModel.delete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

// ---------- Tanya materi ----------
