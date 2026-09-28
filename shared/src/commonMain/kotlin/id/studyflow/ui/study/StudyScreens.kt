package id.studyflow.ui.study

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.studyflow.data.ai.NoteReadiness
import id.studyflow.data.auth.AuthState
import id.studyflow.data.auth.SessionManager
import id.studyflow.data.remote.AnswerResultDto
import id.studyflow.data.remote.JobDto
import id.studyflow.data.remote.NoteResultDto
import id.studyflow.data.remote.QuestionDto
import id.studyflow.domain.Note
import id.studyflow.domain.formatDateTime
import id.studyflow.ui.components.EmptyState
import id.studyflow.ui.components.InfoCard
import kotlinx.datetime.TimeZone
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun Label(text: String, container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color) {
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
internal fun DemoLabel() = Label("Demo, bukan AI", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)

@Composable
internal fun OfflineNote() = Text(
    "Offline: menampilkan salinan tersimpan.",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StudyScaffold(title: String, onBack: () -> Unit, content: @Composable (Modifier) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            content(Modifier.widthIn(max = 840.dp).fillMaxWidth())
        }
    }
}

// ---------- Tab Belajar pada detail mata kuliah ----------

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
                        Text("Ajukan pertanyaan; jawaban diambil dari PDF di tab Materi beserta halaman sumbernya.",
                            style = MaterialTheme.typography.bodySmall)
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
                                Label("Belum tersinkron", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- Belajar dari satu catatan ----------

@Composable
fun StudyNoteScreen(
    noteId: String,
    zone: TimeZone,
    onBack: () -> Unit,
    onOpenSummary: (String) -> Unit,
    onOpenQuiz: (String) -> Unit,
) {
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
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) {
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

@Composable
fun SummaryScreen(summaryId: String, zone: TimeZone, onBack: () -> Unit) {
    val viewModel = koinViewModel<SummaryViewModel>(key = "summary-$summaryId") { parametersOf(summaryId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    StudyScaffold("Rangkuman", onBack) { modifier ->
        Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val summary = state.summary ?: return@Column
            if (state.offline) OfflineNote()
            if (summary.isDemo) DemoLabel()
            if (summary.source.outdated) {
                InfoCard("Dari versi catatan lama", "Catatan sudah diubah setelah rangkuman ini dibuat.")
            }
            Text(summary.content, style = MaterialTheme.typography.bodyLarge)
            Text(
                "Sumber: ${summary.source.title ?: "catatan"} (versi ${summary.source.version})" +
                    (summary.createdAt?.let { " · ${formatDateTime(it, zone)}" } ?: "") +
                    " · model ${summary.modelId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!summary.isDemo) {
                Text(
                    "Rangkuman dibuat AI dan bisa keliru. Periksa kembali dengan catatan aslimu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------- Kuis ----------

@Composable
fun QuizScreen(quizId: String, attemptId: String?, onBack: () -> Unit) {
    val viewModel = koinViewModel<QuizViewModel>(key = "quiz-$quizId-$attemptId") { parametersOf(quizId, attemptId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val quiz = state.quiz
    StudyScaffold(quiz?.title ?: "Kuis", onBack) { modifier ->
        LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (quiz == null) return@LazyColumn
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (state.offline) OfflineNote()
                    if (quiz.isDemo) DemoLabel()
                    if (quiz.source.outdated) Text("Kuis ini dibuat dari versi catatan lama.", style = MaterialTheme.typography.bodySmall)
                    state.attempt?.let { a ->
                        Text("Skor: ${a.score}/${a.total}", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            val results = state.attempt?.results?.associateBy { it.questionId }
            items(quiz.questions, key = { it.id }) { q ->
                QuestionCard(
                    question = q,
                    selected = state.answers[q.id],
                    result = results?.get(q.id),
                    reported = q.id in state.reported,
                    onSelect = { viewModel.select(q.id, it) },
                    onReport = { viewModel.report(q.id) },
                )
            }
            item {
                if (state.attempt == null) {
                    val answered = state.answers.size
                    Button(
                        onClick = viewModel::submit,
                        enabled = !state.submitting && !state.offline && answered > 0,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (state.submitting) "Mengirim…" else "Kirim jawaban ($answered/${quiz.questions.size})") }
                } else if (!state.offline) {
                    OutlinedButton(onClick = viewModel::retake, modifier = Modifier.fillMaxWidth()) { Text("Kerjakan ulang") }
                }
            }
        }
    }
}

@Composable
private fun QuestionCard(
    question: QuestionDto,
    selected: String?,
    result: AnswerResultDto?,
    reported: Boolean,
    onSelect: (String) -> Unit,
    onReport: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${question.position + 1}. ${question.question}", style = MaterialTheme.typography.titleSmall)
            question.options.forEach { option ->
                val isCorrect = result != null && option.id == result.correctOptionId
                val isWrongPick = result != null && option.id == selected && !result.correct
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = when {
                        isCorrect -> colors.primaryContainer
                        isWrongPick -> colors.errorContainer
                        else -> colors.surfaceContainerLow
                    },
                    border = BorderStroke(1.dp, colors.outlineVariant),
                    modifier = Modifier.fillMaxWidth().selectable(
                        selected = selected == option.id,
                        enabled = result == null,
                        role = Role.RadioButton,
                        onClick = { onSelect(option.id) },
                    ),
                ) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected == option.id, onClick = null, enabled = result == null)
                        Text(option.text, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            if (result != null) {
                Text(
                    if (result.correct) "Benar" else if (result.selectedOptionId == null) "Tidak dijawab" else "Salah",
                    color = if (result.correct) colors.primary else colors.error,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(result.explanation, style = MaterialTheme.typography.bodyMedium)
                result.sources.forEach { source ->
                    Text("Sumber: ${source.title ?: "catatan"}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                TextButton(onClick = onReport, enabled = !reported) {
                    Text(if (reported) "Soal sudah dilaporkan" else "Tandai soal bermasalah")
                }
            }
        }
    }
}

// ---------- Riwayat kuis (dipakai layar Progres) ----------

@Composable
fun QuizHistorySection(zone: TimeZone, onOpenAttempt: (quizId: String, attemptId: String) -> Unit) {
    val session = koinInject<SessionManager>()
    val auth by session.state.collectAsStateWithLifecycle()
    if (auth !is AuthState.SignedIn) {
        InfoCard("Riwayat kuis", "Masuk untuk membuat kuis dari catatan dan melihat riwayat skormu.")
        return
    }
    val viewModel = koinViewModel<QuizHistoryViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.load() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Riwayat kuis", style = MaterialTheme.typography.titleSmall)
        if (state.offline) OfflineNote()
        state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (state.loaded && state.attempts.isEmpty() && state.error == null) {
            Text("Belum ada kuis yang dikerjakan.", style = MaterialTheme.typography.bodyMedium)
        }
        if (state.attempts.isNotEmpty()) {
            val totalScore = state.attempts.sumOf { it.score }
            val totalQuestions = state.attempts.sumOf { it.total }.coerceAtLeast(1)
            Text("Rata-rata benar ${totalScore * 100 / totalQuestions}% dari ${state.attempts.size} percobaan",
                style = MaterialTheme.typography.bodyMedium)
        }
        state.attempts.forEach { a ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onOpenAttempt(a.quizId, a.id) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.quizTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        a.submittedAt?.let { Text(formatDateTime(it, zone), style = MaterialTheme.typography.bodySmall) }
                    }
                    Text("${a.score}/${a.total}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
