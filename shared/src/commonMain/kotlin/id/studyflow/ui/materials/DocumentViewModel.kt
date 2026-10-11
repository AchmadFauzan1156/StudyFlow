package id.studyflow.ui.materials

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.studyflow.data.ai.AiException
import id.studyflow.data.ai.AiRepository
import id.studyflow.data.remote.DocumentDto
import id.studyflow.data.remote.PageDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DocumentUiState(
    val document: DocumentDto? = null,
    val pages: List<PageDto> = emptyList(),
    val hasMorePages: Boolean = false,
    val offline: Boolean = false,
    val busy: Boolean = false,
    val deleted: Boolean = false,
    val message: String? = null,
)

class DocumentViewModel(private val documentId: String, private val ai: AiRepository) : ViewModel() {
    private val _state = MutableStateFlow(DocumentUiState())
    val state: StateFlow<DocumentUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val doc = ai.document(documentId)
                _state.update { it.copy(document = doc.value, offline = doc.fromCache) }
                if (doc.value.status == "ready") loadMorePages()
            } catch (e: AiException) {
                _state.update { it.copy(message = e.message) }
            }
        }
    }

    fun loadMorePages() {
        val doc = _state.value.document ?: return
        val start = _state.value.pages.size + 1
        if (start > doc.pageCount) return
        viewModelScope.launch {
            try {
                val loaded = ai.pages(documentId, start, PAGE_BATCH)
                _state.update { s ->
                    val pages = s.pages + loaded.value
                    s.copy(pages = pages, hasMorePages = pages.size < doc.pageCount, offline = s.offline || loaded.fromCache)
                }
            } catch (e: AiException) {
                _state.update { it.copy(message = e.message) }
            }
        }
    }

    /** Mengunduh PDF lalu menyerahkannya ke [open] (penampil bawaan platform). */
    fun open(open: (ByteArray, String) -> Boolean) {
        val doc = _state.value.document ?: return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val bytes = ai.download(documentId)
                val opened = open(bytes, doc.title)
                _state.update { it.copy(busy = false, message = if (opened) null else "Tidak ada aplikasi untuk membuka PDF.") }
            } catch (e: AiException) {
                _state.update { it.copy(busy = false, message = e.message) }
            }
        }
    }

    fun delete() {
        viewModelScope.launch {
            try {
                ai.deleteDocument(documentId)
                _state.update { it.copy(deleted = true) }
            } catch (e: AiException) {
                _state.update { it.copy(message = e.message) }
            }
        }
    }

    private companion object {
        const val PAGE_BATCH = 5
    }
}

// ---------- Tanya materi ----------
