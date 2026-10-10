package id.studyflow.ui.materials

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.studyflow.data.IdGenerator
import id.studyflow.data.ai.AiException
import id.studyflow.data.ai.AiRepository
import id.studyflow.data.remote.AiInfoDto
import id.studyflow.data.remote.ChunkDto
import id.studyflow.data.remote.DocumentDto
import id.studyflow.data.remote.MessageDto
import id.studyflow.data.remote.PageDto
import id.studyflow.data.sync.SyncManager
import id.studyflow.platform.PickedFile
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.min

/** Pesan untuk kode status/kesalahan dokumen dari server. */
fun documentErrorMessage(code: String?): String = when (code) {
    "pdf_no_text" -> "PDF tidak memiliki teks yang bisa dibaca (kemungkinan hasil scan). OCR belum didukung."
    "pdf_encrypted" -> "PDF terkunci password."
    "pdf_invalid" -> "PDF rusak atau tidak dapat dibaca."
    "extract_timeout" -> "Ekstraksi teks terlalu lama. Coba PDF yang lebih kecil."
    "provider_unavailable" -> "Layanan embedding sedang sibuk. Kuota tidak dihitung; unggah ulang nanti."
    "invalid_output", "provider_rejected" -> "Pemrosesan gagal di layanan AI. Unggah ulang nanti."
    else -> "Pemrosesan gagal."
}

/** Polling berjeda (2–10 detik) selama [active] mengembalikan true; berhenti saat ViewModel ditutup. */
private fun ViewModel.pollWhile(active: () -> Boolean, refresh: suspend () -> Unit): Job = viewModelScope.launch {
    var interval = 2_000L
    while (active()) {
        delay(interval)
        refresh()
        interval = min(interval + 1_000L, 10_000L)
    }
}

// ---------- Daftar materi mata kuliah ----------

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

data class AskUiState(
    val loading: Boolean = true,
    val threadId: String? = null,
    val messages: List<MessageDto> = emptyList(),
    val input: String = "",
    val sending: Boolean = false,
    val offline: Boolean = false,
    val info: AiInfoDto? = null,
    val consentNeeded: Boolean = false,
    val courseSynced: Boolean = true,
    val message: String? = null,
) {
    /** Pertanyaan yang jawabannya masih diproses di server. */
    val waiting: Boolean
        get() = messages.any { m -> m.role == "user" && (m.jobStatus == "queued" || m.jobStatus == "running") }
}

class AskViewModel(private val courseId: String, private val ai: AiRepository, private val ids: IdGenerator) : ViewModel() {
    private val _state = MutableStateFlow(AskUiState())
    val state: StateFlow<AskUiState> = _state.asStateFlow()
    private var polling: Job? = null
    private var pendingKey: Pair<String, String>? = null

    init {
        viewModelScope.launch {
            if (!ai.courseSynced(courseId)) {
                _state.update { it.copy(loading = false, courseSynced = false) }
                return@launch
            }
            try {
                val thread = ai.courseThread(courseId)
                val info = runCatching { ai.info().value }.getOrNull()
                _state.update { it.copy(threadId = thread.value.id, info = info, offline = thread.fromCache) }
                reload()
            } catch (e: AiException) {
                _state.update { it.copy(loading = false, message = e.message, offline = e.code == "offline") }
            }
        }
    }

    fun onInput(value: String) {
        if (value.length <= MAX_QUESTION) _state.update { it.copy(input = value) }
    }

    fun send() {
        val question = _state.value.input.trim()
        if (question.length < 3 || _state.value.sending) return
        viewModelScope.launch {
            val label = _state.value.info?.providerLabel.orEmpty()
            if (!ai.hasConsent(label)) _state.update { it.copy(consentNeeded = true) } else submit(question)
        }
    }

    fun confirmConsent() {
        val label = _state.value.info?.providerLabel.orEmpty()
        _state.update { it.copy(consentNeeded = false) }
        viewModelScope.launch {
            ai.giveConsent(label)
            submit(_state.value.input.trim())
        }
    }

    fun dismissConsent() = _state.update { it.copy(consentNeeded = false) }

    private suspend fun submit(question: String) {
        val threadId = _state.value.threadId ?: return
        val key = pendingKey?.takeIf { it.first == question }?.second ?: ids.newId().also { pendingKey = question to it }
        _state.update { it.copy(sending = true, message = null) }
        try {
            val message = ai.ask(threadId, question, key)
            pendingKey = null
            _state.update { s -> s.copy(sending = false, input = "", messages = s.messages + message) }
            startPolling()
        } catch (e: AiException) {
            if (e.code != "offline") pendingKey = null
            _state.update { it.copy(sending = false, message = e.message) }
        }
    }

    private suspend fun reload() {
        val threadId = _state.value.threadId ?: return
        try {
            val loaded = ai.messages(threadId)
            _state.update { it.copy(loading = false, messages = loaded.value, offline = loaded.fromCache) }
            startPolling()
        } catch (e: AiException) {
            _state.update { it.copy(loading = false, message = e.message) }
        }
    }

    private fun startPolling() {
        if (polling?.isActive == true) return
        polling = pollWhile({ _state.value.waiting }) { reload() }
    }

    private companion object {
        const val MAX_QUESTION = 1_000
    }
}

// ---------- Cuplikan sumber ----------

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
