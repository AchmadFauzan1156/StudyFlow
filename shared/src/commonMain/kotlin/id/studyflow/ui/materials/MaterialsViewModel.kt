package id.studyflow.ui.materials

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.studyflow.data.IdGenerator
import id.studyflow.data.ai.AiException
import id.studyflow.data.ai.AiRepository
import id.studyflow.data.remote.AiInfoDto
import id.studyflow.data.remote.DocumentDto
import id.studyflow.data.sync.SyncManager
import id.studyflow.platform.PickedFile
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MaterialsUiState(
    val loading: Boolean = true,
    val courseSynced: Boolean = true,
    val documents: List<DocumentDto> = emptyList(),
    val offline: Boolean = false,
    val info: AiInfoDto? = null,
    val uploading: Boolean = false,
    val message: String? = null,
    val consentFor: PickedFile? = null,
    /** Unggahan terakhir gagal karena jaringan; bisa dicoba lagi dengan idempotency key yang sama. */
    val retryAvailable: Boolean = false,
)

class MaterialsViewModel(
    private val courseId: String,
    private val ai: AiRepository,
    private val syncManager: SyncManager,
    private val ids: IdGenerator,
) : ViewModel() {
    private val _state = MutableStateFlow(MaterialsUiState())
    val state: StateFlow<MaterialsUiState> = _state.asStateFlow()
    private var polling: Job? = null

    // Key per berkas terpilih, dipertahankan untuk retry agar unggahan tidak berganda.
    private var pendingUpload: Pair<PickedFile, String>? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val synced = ai.courseSynced(courseId)
            _state.update { it.copy(courseSynced = synced) }
            if (!synced) {
                _state.update { it.copy(loading = false) }
                return@launch
            }
            try {
                val docs = ai.documents(courseId)
                val info = runCatching { ai.info().value }.getOrNull()
                _state.update { it.copy(loading = false, documents = docs.value, offline = docs.fromCache, info = info) }
                startPolling()
            } catch (e: AiException) {
                _state.update { it.copy(loading = false, offline = e.code == "offline", message = e.message) }
            }
        }
    }

    fun syncNow() {
        syncManager.requestSync()
        viewModelScope.launch {
            delay(2_000)
            refresh()
        }
    }

    fun onPicked(file: PickedFile) {
        viewModelScope.launch {
            val label = _state.value.info?.providerLabel.orEmpty()
            if (ai.hasConsent("materials:$label")) upload(file) else _state.update { it.copy(consentFor = file) }
        }
    }

    fun confirmConsent() {
        val file = _state.value.consentFor ?: return
        val label = _state.value.info?.providerLabel.orEmpty()
        _state.update { it.copy(consentFor = null) }
        viewModelScope.launch {
            ai.giveConsent("materials:$label")
            upload(file)
        }
    }

    fun dismissConsent() = _state.update { it.copy(consentFor = null) }

    fun retryUpload() {
        pendingUpload?.first?.let { file -> viewModelScope.launch { upload(file) } }
    }

    private suspend fun upload(file: PickedFile) {
        val key = pendingUpload?.takeIf { it.first === file }?.second ?: ids.newId().also { pendingUpload = file to it }
        _state.update { it.copy(uploading = true, message = null) }
        try {
            val title = file.name.removeSuffix(".pdf").removeSuffix(".PDF")
            val doc = ai.upload(courseId, title, file.name, file.bytes, key)
            pendingUpload = null
            _state.update { s ->
                s.copy(uploading = false, retryAvailable = false, documents = listOf(doc) + s.documents.filter { it.id != doc.id })
            }
            startPolling()
        } catch (e: AiException) {
            if (e.code != "offline") pendingUpload = null
            _state.update { it.copy(uploading = false, message = e.message, retryAvailable = pendingUpload != null) }
        }
    }

    fun delete(documentId: String) {
        viewModelScope.launch {
            try {
                ai.deleteDocument(documentId)
                _state.update { s -> s.copy(documents = s.documents.filter { it.id != documentId }) }
            } catch (e: AiException) {
                _state.update { it.copy(message = e.message) }
            }
        }
    }

    private fun startPolling() {
        if (polling?.isActive == true) return
        polling = pollWhile({ _state.value.documents.any { it.isProcessing } }) {
            runCatching { ai.documents(courseId) }.getOrNull()?.let { docs ->
                _state.update { it.copy(documents = docs.value, offline = docs.fromCache) }
            }
        }
    }
}

// ---------- Detail dokumen ----------
