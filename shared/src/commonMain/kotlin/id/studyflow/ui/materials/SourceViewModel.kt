package id.studyflow.ui.materials

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.studyflow.data.ai.AiException
import id.studyflow.data.ai.AiRepository
import id.studyflow.data.remote.ChunkDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SourceUiState(val chunk: ChunkDto? = null, val offline: Boolean = false, val busy: Boolean = false, val message: String? = null)

class SourceViewModel(private val chunkId: String, private val ai: AiRepository) : ViewModel() {
    private val _state = MutableStateFlow(SourceUiState())
    val state: StateFlow<SourceUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = try {
                val loaded = ai.chunk(chunkId)
                SourceUiState(loaded.value, loaded.fromCache)
            } catch (e: AiException) {
                SourceUiState(message = e.message)
            }
        }
    }

    fun openPdf(open: (ByteArray, String) -> Boolean) {
        val chunk = _state.value.chunk ?: return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val opened = open(ai.download(chunk.documentId), chunk.documentTitle)
                _state.update {
                    it.copy(
                        busy = false,
                        message = if (opened) {
                            "Buka halaman ${chunk.pageStart} di penampil PDF."
                        } else {
                            "Tidak ada aplikasi untuk membuka PDF."
                        },
                    )
                }
            } catch (e: AiException) {
                _state.update { it.copy(busy = false, message = e.message) }
            }
        }
    }
}
