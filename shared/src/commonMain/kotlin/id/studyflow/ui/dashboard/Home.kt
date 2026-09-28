package id.studyflow.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import id.studyflow.data.AppClock
import id.studyflow.data.repository.CourseRepository
import id.studyflow.data.repository.TaskRepository
import id.studyflow.domain.DeadlineStatus
import id.studyflow.domain.StudyTask
import id.studyflow.domain.TaskStatus
import id.studyflow.domain.deadlineStatus
import id.studyflow.ui.components.EmptyState
import id.studyflow.ui.components.InfoCard
import id.studyflow.ui.components.TaskRow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import org.koin.compose.viewmodel.koinViewModel

data class HomeUiState(
    val loading: Boolean = true,
    val courseCount: Int = 0,
    val upcoming: List<StudyTask> = emptyList(),
    val overdueCount: Int = 0,
    val nowMillis: Long = 0,
    val zone: TimeZone = TimeZone.UTC,
)

/** Jumlah deadline yang ditampilkan di beranda. */
private const val UPCOMING_LIMIT = 8

class HomeViewModel(
    courses: CourseRepository,
    private val tasks: TaskRepository,
    private val clock: AppClock,
) : ViewModel() {
    val state: StateFlow<HomeUiState> =
        combine(courses.observeCount(), tasks.observeAll()) { courseCount, allTasks ->
            val now = clock.nowMillis()
            val zone = clock.currentZone()
            val open = allTasks.filter { it.status != TaskStatus.DONE }
            HomeUiState(
                loading = false,
                courseCount = courseCount,
                upcoming = open.take(UPCOMING_LIMIT),
                overdueCount = open.count { deadlineStatus(it.dueAt, it.status, now, zone) == DeadlineStatus.OVERDUE },
                nowMillis = now,
                zone = zone,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun toggleDone(task: StudyTask) {
        viewModelScope.launch {
            tasks.setStatus(task.id, if (task.status == TaskStatus.DONE) TaskStatus.TODO else TaskStatus.DONE)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenCourses: () -> Unit, onOpenTask: (StudyTask) -> Unit) {
    val viewModel = koinViewModel<HomeViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("Beranda") }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    InfoCard(
                        title = "Mode lokal",
                        message = "Data tersimpan di perangkat ini dan tetap bisa dipakai tanpa internet. " +
                            "Akun, sinkronisasi, dan fitur AI hadir di tahap berikutnya.",
                    )
                }
                if (!state.loading && state.courseCount == 0) {
                    item {
                        EmptyState(
                            title = "Belum ada mata kuliah",
                            message = "Mulai dengan menambahkan mata kuliah, lalu isi catatan dan deadline tugas.",
                            action = { Button(onClick = onOpenCourses) { Text("Tambah mata kuliah") } },
                        )
                    }
                } else if (!state.loading) {
                    item {
                        val summary = buildString {
                            append("${state.courseCount} mata kuliah")
                            if (state.overdueCount > 0) append(" · ${state.overdueCount} tugas terlambat")
                        }
                        Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item { Text("Deadline terdekat", style = MaterialTheme.typography.titleMedium) }
                    if (state.upcoming.isEmpty()) {
                        item {
                            EmptyState(
                                title = "Tidak ada tugas terbuka",
                                message = "Tambahkan deadline dari tab Tugas pada mata kuliah.",
                            )
                        }
                    }
                    items(state.upcoming, key = { it.id }) { task ->
                        TaskRow(
                            task = task,
                            nowMillis = state.nowMillis,
                            zone = state.zone,
                            showCourse = true,
                            onToggleDone = { viewModel.toggleDone(task) },
                            onClick = { onOpenTask(task) },
                        )
                    }
                }
            }
        }
    }
}
