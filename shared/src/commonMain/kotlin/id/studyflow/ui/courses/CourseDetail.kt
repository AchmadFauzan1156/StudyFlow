package id.studyflow.ui.courses

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import id.studyflow.data.AppClock
import id.studyflow.data.repository.CourseRepository
import id.studyflow.data.repository.NoteRepository
import id.studyflow.data.repository.TaskRepository
import id.studyflow.domain.Course
import id.studyflow.domain.Note
import id.studyflow.domain.StudyTask
import id.studyflow.domain.TaskStatus
import id.studyflow.domain.shortDueLabel
import id.studyflow.ui.LocalAppActions
import id.studyflow.ui.components.ConfirmDeleteDialog
import id.studyflow.ui.components.CourseStripe
import id.studyflow.ui.components.EmptyState
import id.studyflow.ui.components.TaskRow
import id.studyflow.ui.components.heading
import id.studyflow.ui.materials.MaterialsTab
import id.studyflow.ui.study.StudyTab
import id.studyflow.ui.theme.ContentWidth
import id.studyflow.ui.theme.Spacing
import id.studyflow.ui.theme.tabularNumbers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

data class CourseDetailUiState(
    val loading: Boolean = true,
    val course: Course? = null,
    val notes: List<Note> = emptyList(),
    val tasks: List<StudyTask> = emptyList(),
    val nowMillis: Long = 0,
    val zone: TimeZone = TimeZone.UTC,
)

class CourseDetailViewModel(
    courseId: String,
    private val courses: CourseRepository,
    notes: NoteRepository,
    private val tasks: TaskRepository,
    private val clock: AppClock,
) : ViewModel() {
    val state: StateFlow<CourseDetailUiState> = combine(
        courses.observeCourse(courseId),
        notes.observeByCourse(courseId),
        tasks.observeByCourse(courseId),
    ) { course, noteList, taskList ->
        CourseDetailUiState(
            loading = false,
            course = course,
            notes = noteList,
            tasks = taskList,
            nowMillis = clock.nowMillis(),
            zone = clock.currentZone(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CourseDetailUiState())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun toggleDone(task: StudyTask) {
        viewModelScope.launch {
            tasks.setStatus(task.id, if (task.status == TaskStatus.DONE) TaskStatus.TODO else TaskStatus.DONE)
        }
    }

    fun updateCourse(id: String, name: String, semester: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            runCatching { courses.update(id, name, semester) }
                .onSuccess {
                    _error.value = null
                    onSuccess()
                }
                .onFailure { _error.value = it.message ?: "Gagal menyimpan" }
        }
    }

    fun deleteCourse(id: String, onDeleted: () -> Unit) {
        viewModelScope.launch {
            courses.delete(id)
            onDeleted()
        }
    }

    fun clearError() {
        _error.value = null
    }
}

private val tabs = listOf("Catatan", "Materi", "Tugas", "Belajar")
private const val TAB_NOTES = 0
private const val TAB_TASKS = 2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailScreen(
    courseId: String,
    initialTab: Int,
    onBack: () -> Unit,
    onOpenNote: (String?) -> Unit,
    onOpenTask: (String?) -> Unit,
    onOpenStudy: (String) -> Unit,
    onOpenDocument: (String) -> Unit,
    onOpenAsk: (courseName: String) -> Unit,
) {
    val actions = LocalAppActions.current
    val viewModel = koinViewModel<CourseDetailViewModel>(key = "course-$courseId") { parametersOf(courseId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableStateOf(initialTab.coerceIn(tabs.indices)) }
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val course = state.course

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(course?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.heading()) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali") }
                },
                actions = {
                    if (course != null) {
                        IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, contentDescription = "Ubah mata kuliah") }
                        // Hapus ada di menu agar tidak bersebelahan langsung dengan aksi lain.
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Opsi lain")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Hapus mata kuliah", color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        menuOpen = false
                                        deleting = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            when (selectedTab) {
                TAB_NOTES -> ExtendedFloatingActionButton(
                    onClick = { onOpenNote(null) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Catatan") },
                )
                TAB_TASKS -> ExtendedFloatingActionButton(
                    onClick = { onOpenTask(null) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Tugas") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!state.loading && course == null) {
                EmptyState(title = "Mata kuliah tidak ditemukan", message = "Mungkin sudah dihapus.")
                return@Column
            }
            // Tab berada di kolom yang sama dengan isinya, agar tidak membentang selebar jendela di desktop.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                PrimaryTabRow(selectedTabIndex = selectedTab, modifier = Modifier.widthIn(max = ContentWidth.list).fillMaxWidth()) {
                    tabs.forEachIndexed { index, title ->
                        Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(title) })
                    }
                }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                val contentModifier = Modifier.widthIn(max = ContentWidth.list).fillMaxWidth()
                when (selectedTab) {
                    TAB_NOTES -> NotesTab(state.notes, state.nowMillis, state.zone, onOpenNote, contentModifier)
                    1 -> MaterialsTab(courseId, onOpenDocument, contentModifier)
                    TAB_TASKS -> TasksTab(state, onOpenTask, viewModel::toggleDone, contentModifier)
                    else -> StudyTab(state.notes, onOpenStudy, { onOpenAsk(course?.name.orEmpty()) }, contentModifier)
                }
            }
        }
    }

    if (editing && course != null) {
        CourseFormDialog(
            initial = course,
            error = error,
            onSave = { name, semester ->
                viewModel.updateCourse(course.id, name, semester) {
                    editing = false
                    actions.showMessage("Mata kuliah disimpan")
                }
            },
            onDismiss = {
                editing = false
                viewModel.clearError()
            },
        )
    }
    if (deleting && course != null) {
        ConfirmDeleteDialog(
            title = "Hapus ${course.name}?",
            message = "Catatan dan tugas di mata kuliah ini juga akan dihapus.",
            onConfirm = {
                deleting = false
                viewModel.deleteCourse(course.id) {
                    actions.showMessage("Mata kuliah dihapus")
                    onBack()
                }
            },
            onDismiss = { deleting = false },
        )
    }
}

/** Baris pertama isi catatan yang tidak kosong, untuk pratinjau satu baris di daftar. */
internal fun notePreview(content: String): String? = content.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }

@Composable
private fun NotesTab(notes: List<Note>, nowMillis: Long, zone: TimeZone, onOpenNote: (String?) -> Unit, modifier: Modifier) {
    if (notes.isEmpty()) {
        EmptyState("Belum ada catatan", "Tulis catatan kuliah pertama untuk mata kuliah ini.", modifier)
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = Spacing.md, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        items(notes, key = { it.id }) { note -> NoteRow(note, nowMillis, zone, onClick = { onOpenNote(note.id) }) }
    }
}

/** Baris catatan: garis warna mata kuliah, judul, baris pertama isi, dan waktu diubah. */
@Composable
private fun NoteRow(note: Note, nowMillis: Long, zone: TimeZone, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .heightIn(min = 64.dp)
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        CourseStripe(note.courseId)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(note.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            notePreview(note.content)?.let { preview ->
                Text(
                    preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "Diubah ${shortDueLabel(note.updatedAt, nowMillis, zone)}",
                style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TasksTab(state: CourseDetailUiState, onOpenTask: (String?) -> Unit, onToggleDone: (StudyTask) -> Unit, modifier: Modifier) {
    if (state.tasks.isEmpty()) {
        EmptyState("Belum ada tugas", "Tambahkan tugas beserta deadline-nya.", modifier)
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = Spacing.md, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        items(state.tasks, key = { it.id }) { task ->
            TaskRow(
                task = task,
                nowMillis = state.nowMillis,
                zone = state.zone,
                showCourse = false,
                onToggleDone = { onToggleDone(task) },
                onClick = { onOpenTask(task.id) },
            )
        }
    }
}
