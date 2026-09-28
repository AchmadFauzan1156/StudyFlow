package id.studyflow.ui.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
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
import id.studyflow.data.repository.NoteRepository
import id.studyflow.domain.Limits
import id.studyflow.ui.components.ConfirmDeleteDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

data class NoteEditorUiState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val title: String = "",
    val content: String = "",
    val saving: Boolean = false,
    val error: String? = null,
)

class NoteEditorViewModel(
    private val courseId: String,
    private val noteId: String?,
    private val repository: NoteRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(NoteEditorUiState(isNew = noteId == null, loading = noteId != null))
    val state: StateFlow<NoteEditorUiState> = _state.asStateFlow()

    init {
        if (noteId != null) {
            viewModelScope.launch {
                val note = repository.get(noteId)
                _state.update {
                    if (note == null) it.copy(loading = false, error = "Catatan tidak ditemukan")
                    else it.copy(loading = false, title = note.title, content = note.content)
                }
            }
        }
    }

    fun onTitleChange(value: String) {
        if (value.length <= Limits.NOTE_TITLE_MAX) _state.update { it.copy(title = value, error = null) }
    }

    fun onContentChange(value: String) {
        if (value.length <= Limits.NOTE_CONTENT_MAX) _state.update { it.copy(content = value, error = null) }
    }

    fun save(onDone: () -> Unit) {
        val current = _state.value
        if (current.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            runCatching {
                if (noteId == null) repository.create(courseId, current.title, current.content)
                else repository.update(noteId, current.title, current.content)
            }.onSuccess { onDone() }
                .onFailure { e -> _state.update { it.copy(saving = false, error = e.message ?: "Gagal menyimpan") } }
        }
    }

    fun delete(onDone: () -> Unit) {
        val id = noteId ?: return
        viewModelScope.launch {
            repository.delete(id)
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(courseId: String, noteId: String?, onDone: () -> Unit) {
    val viewModel = koinViewModel<NoteEditorViewModel>(key = "note-$courseId-$noteId") { parametersOf(courseId, noteId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "Catatan baru" else "Ubah catatan") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali tanpa menyimpan") }
                },
                actions = {
                    if (!state.isNew) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Hapus catatan") }
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
                Modifier.widthIn(max = 840.dp).fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = state.title,
                    onValueChange = viewModel::onTitleChange,
                    label = { Text("Judul") },
                    singleLine = true,
                    enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.content,
                    onValueChange = viewModel::onContentChange,
                    label = { Text("Isi catatan") },
                    enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDeleteDialog(
            title = "Hapus catatan?",
            message = "Catatan ini akan dihapus dari mata kuliah.",
            onConfirm = { confirmDelete = false; viewModel.delete(onDone) },
            onDismiss = { confirmDelete = false },
        )
    }
}
