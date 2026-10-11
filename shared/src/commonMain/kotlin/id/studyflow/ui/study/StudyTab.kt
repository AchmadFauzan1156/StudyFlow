package id.studyflow.ui.study

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.studyflow.data.auth.AuthState
import id.studyflow.data.auth.SessionManager
import id.studyflow.domain.Note
import id.studyflow.ui.components.EmptyState
import id.studyflow.ui.components.InfoCard
import org.koin.compose.koinInject

@Composable
fun StudyTab(notes: List<Note>, onOpenNote: (String) -> Unit, onOpenAsk: () -> Unit, modifier: Modifier) {
    val session = koinInject<SessionManager>()
    val auth by session.state.collectAsStateWithLifecycle()
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            auth !is AuthState.SignedIn -> InfoCard(
                "Belajar dengan AI",
                "Rangkuman dan kuis dari catatan membutuhkan akun dan koneksi internet. Masuk dari menu Pengaturan.",
            )
            else -> {
                Card(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenAsk),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Tanya materi", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Ajukan pertanyaan; jawaban diambil dari PDF di tab Materi beserta halaman sumbernya.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (notes.isEmpty()) {
                    EmptyState("Belum ada catatan", "Tulis catatan untuk membuat rangkuman atau kuis darinya.")
                    return@Column
                }
                Text("Pilih catatan untuk dirangkum atau dijadikan kuis.", style = MaterialTheme.typography.bodyMedium)
                notes.forEach { note ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onOpenNote(note.id) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(note.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            if (note.pendingSync) {
                                Label(
                                    "Belum tersinkron",
                                    MaterialTheme.colorScheme.surfaceContainerHighest,
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- Belajar dari satu catatan ----------
