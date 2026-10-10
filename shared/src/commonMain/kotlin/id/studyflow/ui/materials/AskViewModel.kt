package id.studyflow.ui.materials

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.studyflow.data.IdGenerator
import id.studyflow.data.ai.AiException
import id.studyflow.data.ai.AiRepository
import id.studyflow.data.remote.AiInfoDto
import id.studyflow.data.remote.MessageDto
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
