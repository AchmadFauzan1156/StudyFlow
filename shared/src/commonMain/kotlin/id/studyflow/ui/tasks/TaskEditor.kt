package id.studyflow.ui.tasks

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
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
import id.studyflow.data.AppClock
import id.studyflow.data.repository.TaskInput
import id.studyflow.data.repository.TaskRepository
import id.studyflow.domain.Limits
import id.studyflow.domain.TaskStatus
import id.studyflow.domain.formatDate
import id.studyflow.domain.formatTime
import id.studyflow.domain.toEpochMillis
import id.studyflow.domain.toLocalDateTime
import id.studyflow.ui.components.ConfirmDeleteDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.time.ExperimentalTime

data class TaskEditorUiState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val title: String = "",
    val description: String = "",
    val date: LocalDate = LocalDate(2026, 1, 1),
    val hour: Int = 23,
    val minute: Int = 59,
    val timeZoneId: String = "Asia/Jakarta",
    val status: TaskStatus = TaskStatus.TODO,
    val saving: Boolean = false,
    val error: String? = null,
)

class TaskEditorViewModel(
    private val courseId: String,
    private val taskId: String?,
    private val repository: TaskRepository,
    clock: AppClock,
) : ViewModel() {
    private val _state: MutableStateFlow<TaskEditorUiState>
    val state: StateFlow<TaskEditorUiState>

    init {
        // Default tugas baru: besok pukul 23.59 di zona pengguna saat ini.
        val zone = clock.currentZone()
        val tomorrow = toLocalDateTime(clock.nowMillis(), zone).date.plus(1, DateTimeUnit.DAY)
        _state = MutableStateFlow(
            TaskEditorUiState(loading = taskId != null, isNew = taskId == null, date = tomorrow, timeZoneId = zone.id),
        )
        state = _state.asStateFlow()
        if (taskId != null) load(taskId)
    }

    private fun load(id: String) {
        viewModelScope.launch {
            val task = repository.get(id)
            _state.update {
                if (task == null) {
                    it.copy(loading = false, error = "Tugas tidak ditemukan")
                } else {
                    // Tampilkan waktu pada zona saat tugas dibuat agar maknanya tidak bergeser.
                    val dt = toLocalDateTime(task.dueAt, TimeZone.of(task.timeZoneId))
                    it.copy(
                        loading = false,
                        title = task.title,
                        description = task.description,
                        date = dt.date,
                        hour = dt.hour,
                        minute = dt.minute,
                        timeZoneId = task.timeZoneId,
                        status = task.status,
                    )
                }
            }
        }
    }

    fun onTitleChange(value: String) {
        if (value.length <= Limits.TASK_TITLE_MAX) _state.update { it.copy(title = value, error = null) }
    }

    fun onDescriptionChange(value: String) {
        if (value.length <= Limits.TASK_DESCRIPTION_MAX) _state.update { it.copy(description = value, error = null) }
    }

    fun onDateChange(date: LocalDate) = _state.update { it.copy(date = date) }
    fun onTimeChange(hour: Int, minute: Int) = _state.update { it.copy(hour = hour, minute = minute) }
    fun onStatusChange(status: TaskStatus) = _state.update { it.copy(status = status) }

    fun save(onDone: () -> Unit) {
        val s = _state.value
        if (s.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            runCatching {
                val zone = TimeZone.of(s.timeZoneId)
                val dueAt = toEpochMillis(LocalDateTime(s.date, LocalTime(s.hour, s.minute)), zone)
                val input = TaskInput(s.title, s.description, dueAt, s.timeZoneId, s.status)
                if (taskId == null) repository.create(courseId, input) else repository.update(taskId, input)
            }.onSuccess { onDone() }
                .onFailure { e -> _state.update { it.copy(saving = false, error = e.message ?: "Gagal menyimpan") } }
        }
    }

    fun delete(onDone: () -> Unit) {
        val id = taskId ?: return
        viewModelScope.launch {
            repository.delete(id)
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalTime::class)
@Composable
fun TaskEditorScreen(courseId: String, taskId: String?, onDone: () -> Unit) {
    val viewModel = koinViewModel<TaskEditorViewModel>(key = "task-$courseId-$taskId") { parametersOf(courseId, taskId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "Tugas baru" else "Ubah tugas") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali tanpa menyimpan") }
                },
                actions = {
                    if (!state.isNew) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Hapus tugas") }
                    }
                    TextButton(
                        onClick = { viewModel.save(onDone) },
                        enabled = !state.loading && !state.saving && state.title.isNotBlank(),
                    ) { Text("Simpan") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 840.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = state.title,
                    onValueChange = viewModel::onTitleChange,
                    label = { Text("Judul tugas") },
                    singleLine = true,
                    enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.description,
                    onValueChange = viewModel::onDescriptionChange,
                    label = { Text("Deskripsi (opsional)") },
                    minLines = 3,
                    enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Deadline", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { showDatePicker = true }, enabled = !state.loading) {
                        Icon(Icons.Filled.DateRange, contentDescription = null)
                        Text(
                            formatDate(LocalDateTime(state.date, LocalTime(0, 0))),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    OutlinedButton(onClick = { showTimePicker = true }, enabled = !state.loading) {
                        Text(formatTime(state.hour, state.minute))
                    }
                }
                Text(
                    "Zona waktu: ${state.timeZoneId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Status", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.status == TaskStatus.TODO,
                        onClick = { viewModel.onStatusChange(TaskStatus.TODO) },
                        label = { Text("Belum selesai") },
                    )
                    FilterChip(
                        selected = state.status == TaskStatus.DONE,
                        onClick = { viewModel.onStatusChange(TaskStatus.DONE) },
                        label = { Text("Selesai") },
                    )
                }
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { viewModel.onDateChange(toLocalDateTime(it, TimeZone.UTC).date) }
                    showDatePicker = false
                }) { Text("Pilih") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Batal") } },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showTimePicker) {
        val timeState = rememberTimePickerState(initialHour = state.hour, initialMinute = state.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Pilih jam") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onTimeChange(timeState.hour, timeState.minute)
                    showTimePicker = false
                }) { Text("Pilih") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Batal") } },
        )
    }

    if (confirmDelete) {
        ConfirmDeleteDialog(
            title = "Hapus tugas?",
            message = "Tugas ini akan dihapus dari mata kuliah.",
            onConfirm = { confirmDelete = false; viewModel.delete(onDone) },
            onDismiss = { confirmDelete = false },
        )
    }
}
