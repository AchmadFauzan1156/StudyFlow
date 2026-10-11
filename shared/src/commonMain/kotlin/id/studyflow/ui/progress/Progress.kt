package id.studyflow.ui.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import id.studyflow.data.AppClock
import id.studyflow.data.repository.CourseRepository
import id.studyflow.data.repository.NoteRepository
import id.studyflow.data.repository.TaskRepository
import id.studyflow.domain.DeadlineStatus
import id.studyflow.domain.TaskStatus
import id.studyflow.domain.deadlineStatus
import id.studyflow.ui.components.CountRow
import id.studyflow.ui.components.GroupLabel
import id.studyflow.ui.components.heading
import id.studyflow.ui.study.QuizHistorySection
import id.studyflow.ui.study.TopicsSection
import id.studyflow.ui.theme.ContentWidth
import id.studyflow.ui.theme.Spacing
import id.studyflow.ui.theme.tabularNumbers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

data class ProgressUiState(
    val courseCount: Int = 0,
    val noteCount: Int = 0,
    val taskTotal: Int = 0,
    val taskDone: Int = 0,
    val overdue: Int = 0,
)

class ProgressViewModel(courses: CourseRepository, notes: NoteRepository, tasks: TaskRepository, clock: AppClock) : ViewModel() {
    val state: StateFlow<ProgressUiState> = combine(
        courses.observeCount(),
        notes.observeCount(),
        tasks.observeAll(),
    ) { courseCount, noteCount, allTasks ->
        val now = clock.nowMillis()
        val zone = clock.currentZone()
        ProgressUiState(
            courseCount = courseCount,
            noteCount = noteCount,
            taskTotal = allTasks.size,
            taskDone = allTasks.count { it.status == TaskStatus.DONE },
            overdue = allTasks.count { deadlineStatus(it.dueAt, it.status, now, zone) == DeadlineStatus.OVERDUE },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProgressUiState())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(
    onOpenAttempt: (quizId: String, attemptId: String) -> Unit,
    onOpenNote: (String) -> Unit,
    onOpenFlashcards: () -> Unit,
) {
    val viewModel = koinViewModel<ProgressViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("Progres", Modifier.heading()) }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = ContentWidth.list).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                TaskCompletion(state)
                Column {
                    GroupLabel("Isi")
                    CountRow("Mata kuliah", state.courseCount)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    CountRow("Catatan", state.noteCount)
                }
                TopicsSection(onOpenNote, onOpenFlashcards)
                QuizHistorySection(zone = koinInject<AppClock>().currentZone(), onOpenAttempt = onOpenAttempt)
            }
        }
    }
}

/** Persentase tugas selesai dengan konteksnya ("1 dari 5"), dan jumlah terlambat bila ada. */
@Composable
private fun TaskCompletion(state: ProgressUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        GroupLabel("Tugas")
        if (state.taskTotal == 0) {
            Text(
                "Belum ada tugas. Tambahkan deadline dari tab Tugas di mata kuliah.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }
        val percent = state.taskDone * 100 / state.taskTotal
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text("$percent%", style = MaterialTheme.typography.headlineMedium.tabularNumbers())
            Text(
                "${state.taskDone} dari ${state.taskTotal} tugas selesai",
                style = MaterialTheme.typography.bodyMedium.tabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.xs),
            )
        }
        LinearProgressIndicator(progress = { state.taskDone.toFloat() / state.taskTotal }, modifier = Modifier.fillMaxWidth())
        if (state.overdue > 0) {
            Text(
                "${state.overdue} tugas terlambat",
                style = MaterialTheme.typography.bodyMedium.tabularNumbers(),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
