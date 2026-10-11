package id.studyflow.ui.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.studyflow.data.IdGenerator
import id.studyflow.data.ai.AiException
import id.studyflow.data.ai.AiRepository
import id.studyflow.data.ai.NoteReadiness
import id.studyflow.data.remote.AiInfoDto
import id.studyflow.data.remote.AttemptDto
import id.studyflow.data.remote.AttemptSummaryDto
import id.studyflow.data.remote.JobDto
import id.studyflow.data.remote.NoteResultDto
import id.studyflow.data.remote.QuizDto
import id.studyflow.data.remote.SummaryDto
import id.studyflow.data.repository.NoteRepository
import id.studyflow.data.sync.SyncManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.min

/** Pesan untuk kode kegagalan job dari server. */
fun jobErrorMessage(code: String?): String = when (code) {
    "source_changed" -> "Catatan berubah saat diproses. Kuota tidak dihitung; coba lagi."
    "source_deleted" -> "Catatan sudah dihapus."
    "invalid_output" -> "AI menghasilkan jawaban yang tidak valid. Kuota tidak dihitung; coba lagi."
    "provider_unavailable" -> "Layanan AI gratis sedang sibuk. Kuota tidak dihitung; coba lagi nanti."
    "provider_rejected" -> "Layanan AI menolak permintaan. Hubungi pengelola server."
    else -> "Pemrosesan gagal. Coba lagi."
}

data class StudyNoteUiState(
    val loading: Boolean = true,
    val noteTitle: String = "",
    val readiness: NoteReadiness? = null,
    val info: AiInfoDto? = null,
    val offline: Boolean = false,
    val results: List<NoteResultDto> = emptyList(),
    val jobs: List<JobDto> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val consentFor: PendingRequest? = null,
)

sealed interface PendingRequest {
    data object Summary : PendingRequest
    data class Quiz(val count: Int) : PendingRequest
}

class StudyNoteViewModel(
    private val noteId: String,
    private val ai: AiRepository,
    private val notes: NoteRepository,
    private val syncManager: SyncManager,
    private val ids: IdGenerator,
) : ViewModel() {
    private val _state = MutableStateFlow(StudyNoteUiState())
    val state: StateFlow<StudyNoteUiState> = _state.asStateFlow()

    // Idempotency key dipertahankan sampai server memberi jawaban pasti, agar retry tidak menggandakan job.
    private var pendingKey: Pair<PendingRequest, String>? = null
    private var polling: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val note = notes.get(noteId)
            val readiness = ai.noteReadiness(noteId)
            _state.update { it.copy(noteTitle = note?.title.orEmpty(), readiness = readiness) }
            try {
                val info = ai.info()
                val results = if (readiness is NoteReadiness.NotFound) null else ai.noteResults(noteId)
                _state.update {
                    it.copy(
                        loading = false,
                        info = info.value,
                        offline = info.fromCache || results?.fromCache == true,
                        results = results?.value?.results.orEmpty(),
                        jobs = results?.value?.pendingJobs.orEmpty().filter { job -> !job.isTerminal || job.status == "failed" },
                        message = null,
                    )
                }
                startPollingIfNeeded()
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

    fun request(kind: PendingRequest) {
        val info = _state.value.info ?: return
        viewModelScope.launch {
            val label = info.providerLabel.orEmpty()
            if (!ai.hasConsent(label)) {
                _state.update { it.copy(consentFor = kind) }
            } else {
                send(kind)
            }
        }
    }

    fun confirmConsent() {
        val kind = _state.value.consentFor ?: return
        val label = _state.value.info?.providerLabel.orEmpty()
        _state.update { it.copy(consentFor = null) }
        viewModelScope.launch {
            ai.giveConsent(label)
            send(kind)
        }
    }

    fun dismissConsent() = _state.update { it.copy(consentFor = null) }

    private suspend fun send(kind: PendingRequest) {
        val ready = _state.value.readiness as? NoteReadiness.Ready ?: return
        val key = pendingKey?.takeIf { it.first == kind }?.second ?: ids.newId().also { pendingKey = kind to it }
        _state.update { it.copy(busy = true, message = null) }
        try {
            val job = when (kind) {
                PendingRequest.Summary -> ai.requestSummary(noteId, ready.version, key)
                is PendingRequest.Quiz -> ai.requestQuiz(noteId, ready.version, kind.count, key)
            }
            pendingKey = null
            _state.update { s -> s.copy(busy = false, jobs = listOf(job) + s.jobs.filter { it.id != job.id }) }
            startPollingIfNeeded()
        } catch (e: AiException) {
            if (e.code != "offline") pendingKey = null
            if (e.code == "source_version_mismatch") syncManager.requestSync()
            _state.update { it.copy(busy = false, message = e.message) }
        }
    }

    private fun startPollingIfNeeded() {
        if (polling?.isActive == true || _state.value.jobs.none { !it.isTerminal }) return
        polling = viewModelScope.launch {
            var interval = 2_000L
            while (true) {
                delay(interval)
                val active = _state.value.jobs.filter { !it.isTerminal }
                if (active.isEmpty()) break
                val updated = active.mapNotNull { runCatching { ai.job(it.id) }.getOrNull() }
                _state.update { s -> s.copy(jobs = s.jobs.map { old -> updated.firstOrNull { it.id == old.id } ?: old }) }
                if (updated.any { it.status == "succeeded" }) {
                    refreshResultsOnly()
                }
                interval = min(interval + 1_000L, 10_000L)
            }
        }
    }

    private suspend fun refreshResultsOnly() {
        runCatching { ai.noteResults(noteId) }.getOrNull()?.let { loaded ->
            _state.update { s ->
                s.copy(results = loaded.value.results, jobs = s.jobs.filter { it.status != "succeeded" })
            }
        }
        runCatching { ai.info() }.getOrNull()?.let { info -> _state.update { it.copy(info = info.value) } }
    }

    fun dismissJob(id: String) = _state.update { s -> s.copy(jobs = s.jobs.filter { it.id != id }) }
}

// ---------- Rangkuman ----------

data class SummaryUiState(val summary: SummaryDto? = null, val offline: Boolean = false, val error: String? = null)

class SummaryViewModel(private val id: String, private val ai: AiRepository) : ViewModel() {
    private val _state = MutableStateFlow(SummaryUiState())
    val state: StateFlow<SummaryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = try {
                val loaded = ai.summary(id)
                SummaryUiState(loaded.value, loaded.fromCache)
            } catch (e: AiException) {
                SummaryUiState(error = e.message)
            }
        }
    }
}

// ---------- Kuis & hasil ----------

data class QuizUiState(
    val quiz: QuizDto? = null,
    val offline: Boolean = false,
    val answers: Map<String, String> = emptyMap(),
    val attempt: AttemptDto? = null,
    val submitting: Boolean = false,
    val reported: Set<String> = emptySet(),
    val error: String? = null,
)

class QuizViewModel(
    private val quizId: String,
    private val attemptId: String?,
    private val ai: AiRepository,
    private val ids: IdGenerator,
) : ViewModel() {
    private val _state = MutableStateFlow(QuizUiState())
    val state: StateFlow<QuizUiState> = _state.asStateFlow()
    private var submitKey: String? = null

    init {
        viewModelScope.launch {
            try {
                val quiz = ai.quiz(quizId)
                val attempt = attemptId?.let { ai.attempt(it) }
                _state.update {
                    it.copy(
                        quiz = quiz.value,
                        offline = quiz.fromCache || attempt?.fromCache == true,
                        attempt = attempt?.value,
                        answers = attempt?.value?.results
                            ?.mapNotNull { r -> r.selectedOptionId?.let { r.questionId to it } }?.toMap().orEmpty(),
                    )
                }
            } catch (e: AiException) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun select(questionId: String, optionId: String) {
        if (_state.value.attempt != null) return
        _state.update { it.copy(answers = it.answers + (questionId to optionId)) }
    }

    fun submit() {
        val s = _state.value
        val quiz = s.quiz ?: return
        if (s.submitting || s.attempt != null) return
        val key = submitKey ?: ids.newId().also { submitKey = it }
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            try {
                val answers = quiz.questions.associate { q -> q.id to s.answers[q.id] }
                val attempt = ai.submitAttempt(quiz.id, answers, key)
                submitKey = null
                _state.update { it.copy(submitting = false, attempt = attempt) }
            } catch (e: AiException) {
                _state.update { it.copy(submitting = false, error = e.message) }
            }
        }
    }

    fun retake() {
        submitKey = null
        _state.update { it.copy(attempt = null, answers = emptyMap(), error = null) }
    }

    fun report(questionId: String) {
        viewModelScope.launch {
            try {
                ai.reportQuestion(questionId, "Ditandai bermasalah dari aplikasi")
                _state.update { it.copy(reported = it.reported + questionId) }
            } catch (e: AiException) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }
}

// ---------- Riwayat (Progres) ----------

data class QuizHistoryState(
    val attempts: List<AttemptSummaryDto> = emptyList(),
    val offline: Boolean = false,
    val error: String? = null,
    val loaded: Boolean = false,
)

class QuizHistoryViewModel(private val ai: AiRepository) : ViewModel() {
    private val _state = MutableStateFlow(QuizHistoryState())
    val state: StateFlow<QuizHistoryState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.value = try {
                val loaded = ai.attempts()
                QuizHistoryState(loaded.value, loaded.fromCache, loaded = true)
            } catch (e: AiException) {
                QuizHistoryState(error = e.message, loaded = true)
            }
        }
    }
}
