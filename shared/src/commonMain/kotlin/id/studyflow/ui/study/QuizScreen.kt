package id.studyflow.ui.study

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.studyflow.data.remote.AnswerResultDto
import id.studyflow.data.remote.QuestionDto
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

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
                        Text(
                            "Skor: ${a.score}/${a.total}",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
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
                    if (result.correct) {
                        "Benar"
                    } else if (result.selectedOptionId == null) {
                        "Tidak dijawab"
                    } else {
                        "Salah"
                    },
                    color = if (result.correct) colors.primary else colors.error,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(result.explanation, style = MaterialTheme.typography.bodyMedium)
                result.sources.forEach { source ->
                    Text(
                        "Sumber: ${source.title ?: "catatan"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onReport, enabled = !reported) {
                    Text(if (reported) "Soal sudah dilaporkan" else "Tandai soal bermasalah")
                }
            }
        }
    }
}

// ---------- Riwayat kuis (dipakai layar Progres) ----------
