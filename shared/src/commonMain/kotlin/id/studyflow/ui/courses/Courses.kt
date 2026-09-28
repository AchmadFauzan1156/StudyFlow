package id.studyflow.ui.courses

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import id.studyflow.data.repository.CourseRepository
import id.studyflow.domain.Course
import id.studyflow.domain.Limits
import id.studyflow.ui.components.ConfirmDeleteDialog
import id.studyflow.ui.components.EmptyState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

class CoursesViewModel(private val repository: CourseRepository) : ViewModel() {
    val courses: StateFlow<List<Course>?> = repository.observeCourses()
        .map<List<Course>, List<Course>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Membuat atau memperbarui mata kuliah; [onSuccess] dipanggil jika tersimpan. */
    fun save(existingId: String?, name: String, semester: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            runCatching {
                if (existingId == null) repository.create(name, semester) else repository.update(existingId, name, semester)
            }.onSuccess {
                _error.value = null
                onSuccess()
            }.onFailure { _error.value = it.message ?: "Gagal menyimpan" }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }

    fun clearError() {
        _error.value = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoursesScreen(onOpenCourse: (String) -> Unit) {
    val viewModel = koinViewModel<CoursesViewModel>()
    val courses by viewModel.courses.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Course?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Course?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Mata Kuliah") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Mata kuliah") },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            val list = courses
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyState(
                    title = "Belum ada mata kuliah",
                    message = "Tambahkan mata kuliah pertama, misalnya Kriptografi.",
                )
                else -> LazyColumn(
                    modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(list, key = { it.id }) { course ->
                        CourseCard(
                            course = course,
                            onClick = { onOpenCourse(course.id) },
                            onEdit = { editing = course },
                            onDelete = { deleting = course },
                        )
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        CourseFormDialog(
            initial = editing,
            error = error,
            onSave = { name, semester ->
                viewModel.save(editing?.id, name, semester) {
                    creating = false
                    editing = null
                }
            },
            onDismiss = {
                creating = false
                editing = null
                viewModel.clearError()
            },
        )
    }
    deleting?.let { course ->
        ConfirmDeleteDialog(
            title = "Hapus ${course.name}?",
            message = "Catatan dan tugas di mata kuliah ini juga akan dihapus.",
            onConfirm = {
                viewModel.delete(course.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun CourseCard(course: Course, onClick: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(course.name, style = MaterialTheme.typography.titleMedium)
                val meta = listOfNotNull(
                    course.semester,
                    "${course.noteCount} catatan",
                    "${course.openTaskCount} tugas terbuka",
                ).joinToString(" · ")
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Opsi ${course.name}")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Ubah") }, onClick = { menuOpen = false; onEdit() })
                    DropdownMenuItem(text = { Text("Hapus") }, onClick = { menuOpen = false; onDelete() })
                }
            }
        }
    }
}

@Composable
fun CourseFormDialog(
    initial: Course?,
    error: String?,
    onSave: (name: String, semester: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var semester by remember { mutableStateOf(initial?.semester.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Mata kuliah baru" else "Ubah mata kuliah") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= Limits.COURSE_NAME_MAX) name = it },
                    label = { Text("Nama") },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = semester,
                    onValueChange = { if (it.length <= Limits.SEMESTER_MAX) semester = it },
                    label = { Text("Semester (opsional)") },
                    placeholder = { Text("Ganjil 2026/2027") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, semester) }, enabled = name.isNotBlank()) { Text("Simpan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}
