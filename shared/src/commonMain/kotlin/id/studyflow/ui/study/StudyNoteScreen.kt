package id.studyflow.ui.study

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.studyflow.data.ai.NoteReadiness
import id.studyflow.data.remote.JobDto
import id.studyflow.data.remote.NoteResultDto
import id.studyflow.domain.formatDateTime
import id.studyflow.ui.components.EmptyState
import id.studyflow.ui.components.InfoCard
import kotlinx.datetime.TimeZone
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun StudyNoteScreen(noteId: String, zone: TimeZone, onBack: () -> Unit, onOpenSummary: (String) -> Unit, onOpenQuiz: (String) -> Unit) {
    val viewModel = koinViewModel<StudyNoteViewModel>(key = "study-$noteId") { parametersOf(noteId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var questionCount by remember { mutableIntStateOf(5) }

    StudyScaffold(state.noteTitle.ifBlank { "Belajar" }, onBack) { modifier ->
        LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val info = state.info
            if (state.loading) {
                item { CircularProgressIndicator() }
                return@LazyColumn
            }
            state.message?.let { message ->
                item { Text(message, color = MaterialTheme.colorScheme.error) }
            }
            if (state.offline) item { OfflineNote() }
            when (state.readiness) {
                NoteReadiness.NotFound -> item { EmptyState("Catatan tidak ditemukan", "Mungkin sudah dihapus.") }
                NoteReadiness.NeedsSync -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        InfoCard("Sinkronkan dulu", "AI hanya memproses versi catatan yang sudah tersimpan di server.")
                        Button(onClick = viewModel::syncNow) { Text("Sinkronkan sekarang") }
                    }
                }
                else -> Unit
            }
            if (info != null && state.readiness is NoteReadiness.Ready) {
                item {
                    if (!info.available) {
                        InfoCard("Fitur AI belum tersedia", "Server ini belum mengaktifkan penyedia AI.")
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "Kuota hari ini: ${info.usedToday}/${info.dailyLimit} · " +
                                    "maks. ${info.maxInputChars} karakter per catatan",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (info.mode == "demo") DemoLabel()
                            val canRequest = !state.busy && !state.offline && info.usedToday < info.dailyLimit
                            Button(onClick = { viewModel.request(PendingRequest.Summary) }, enabled = canRequest) {
                                Text("Buat rangkuman")
                            }
                            Text("Jumlah soal kuis", style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(3, 5, 10).filter { it in info.quizMinQuestions..info.quizMaxQuestions }.forEach { n ->
                                    FilterChip(selected = questionCount == n, onClick = { questionCount = n }, label = { Text("$n") })
                                }
                            }
                            OutlinedButton(onClick = { viewModel.request(PendingRequest.Quiz(questionCount)) }, enabled = canRequest) {
                                Text("Buat kuis $questionCount soal")
                            }
                        }
                    }
                }
            }
            items(state.jobs, key = { "job-${it.id}" }) { job -> JobCard(job, onDismiss = { viewModel.dismissJob(job.id) }) }
            if (state.results.isNotEmpty()) {
                item { Text("Hasil", style = MaterialTheme.typography.titleMedium) }
            }
            items(state.results, key = { "${it.type}-${it.id}" }) { result ->
                ResultCard(result, zone, currentVersion = (state.readiness as? NoteReadiness.Ready)?.version) {
                    if (result.type == "summary") onOpenSummary(result.id) else onOpenQuiz(result.id)
                }
            }
        }
    }

    state.consentFor?.let {
        AlertDialog(
            onDismissRequest = viewModel::dismissConsent,
            title = { Text("Kirim catatan ke ${state.info?.providerLabel ?: "penyedia AI"}?") },
            text = {
                Text(
                    (state.info?.dataNotice ?: "") +
                        "\n\nHasil AI bisa keliru. Periksa kembali dengan materi aslimu dan tandai soal yang salah.",
                )
            },
            confirmButton = { TextButton(onClick = viewModel::confirmConsent) { Text("Setuju dan kirim") } },
            dismissButton = { TextButton(onClick = viewModel::dismissConsent) { Text("Batal") } },
        )
    }
}

@Composable
private fun JobCard(job: JobDto, onDismiss: () -> Unit) {
    val label = if (job.type == "summary") "Rangkuman" else "Kuis"
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (job.status == "failed") {
                Column(Modifier.weight(1f)) {
                    Text("$label gagal", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                    Text(jobErrorMessage(job.errorCode), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onDismiss) { Text("Tutup") }
            } else {
                CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
                Text(
                    if (job.status == "running") "$label sedang diproses…" else "$label menunggu antrean…",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ResultCard(result: NoteResultDto, zone: TimeZone, currentVersion: Int?, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (result.type == "summary") "Rangkuman" else (result.title ?: "Kuis"),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (result.isDemo) DemoLabel()
            }
            val meta = buildList {
                result.createdAt?.let { add(formatDateTime(it, zone)) }
                if (result.type == "quiz") add(result.bestScore?.let { "Skor terbaik $it/${result.total}" } ?: "Belum dikerjakan")
                if (currentVersion != null && currentVersion != result.sourceVersion) add("dari versi catatan lama")
            }.joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------- Rangkuman ----------
