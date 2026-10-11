package id.studyflow.ui.materials

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.studyflow.platform.rememberPdfOpener
import id.studyflow.ui.study.OfflineNote
import id.studyflow.ui.study.StudyScaffold
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun SourceScreen(chunkId: String, onBack: () -> Unit) {
    val viewModel = koinViewModel<SourceViewModel>(key = "source-$chunkId") { parametersOf(chunkId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openPdf = rememberPdfOpener()
    StudyScaffold("Sumber", onBack) { modifier ->
        Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.offline) OfflineNote()
            val chunk = state.chunk
            if (chunk != null) {
                Text(chunk.documentTitle, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${pageLabel(chunk.pageStart, chunk.pageEnd)} (urutan halaman di berkas PDF, bisa berbeda dengan nomor cetak)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Text(chunk.text, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                }
                Button(onClick = { viewModel.openPdf(openPdf) }, enabled = !state.busy && !state.offline) {
                    Text(if (state.busy) "Mengunduh…" else "Buka PDF")
                }
            }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
