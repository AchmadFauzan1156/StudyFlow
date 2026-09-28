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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import id.studyflow.ui.study.QuizHistorySection
import id.studyflow.ui.study.TopicsSection
import org.koin.compose.koinInject
import id.studyflow.ui.components.StatTile
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.koin.compose.viewmodel.koinViewModel

data class ProgressUiState(
    val courseCount: Int = 0,
    val noteCount: Int = 0,
    val taskTotal: Int = 0,
    val taskDone: Int = 0,
    val overdue: Int = 0,
)

class ProgressViewModel(
    courses: CourseRepository,
    notes: NoteRepository,
    tasks: TaskRepository,
    clock: AppClock,
) : ViewModel() {
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

    Scaffold(topBar = { TopAppBar(title = { Text("Progres") }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 840.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("Mata kuliah", state.courseCount.toString(), Modifier.weight(1f))
                    StatTile("Catatan", state.noteCount.toString(), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("Tugas selesai", "${state.taskDone}/${state.taskTotal}", Modifier.weight(1f))
                    StatTile("Terlambat", state.overdue.toString(), Modifier.weight(1f))
                }
                if (state.taskTotal > 0) {
                    Text("Penyelesaian tugas", style = MaterialTheme.typography.titleSmall)
                    LinearProgressIndicator(
                        progress = { state.taskDone.toFloat() / state.taskTotal },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TopicsSection(onOpenNote, onOpenFlashcards)
                QuizHistorySection(zone = koinInject<AppClock>().currentZone(), onOpenAttempt = onOpenAttempt)
            }
        }
    }
}
