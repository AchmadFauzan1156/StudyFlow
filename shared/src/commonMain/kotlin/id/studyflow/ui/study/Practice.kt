package id.studyflow.ui.study

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import id.studyflow.data.ai.AiException
import id.studyflow.data.ai.AiRepository
import id.studyflow.data.auth.AuthState
import id.studyflow.data.auth.SessionManager
import id.studyflow.data.remote.FlashcardDto
import id.studyflow.data.remote.ProgressDto
import id.studyflow.ui.components.EmptyState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

// ---------- Progres per topik ----------

data class TopicsUiState(val progress: ProgressDto? = null, val offline: Boolean = false, val error: String? = null)

class TopicsViewModel(private val ai: AiRepository) : ViewModel() {
    private val _state = MutableStateFlow(TopicsUiState())
    val state: StateFlow<TopicsUiState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.value = try {
                val loaded = ai.topics()
                TopicsUiState(loaded.value, loaded.fromCache)
            } catch (e: AiException) {
                TopicsUiState(error = e.message)
            }
        }
    }
}

@Composable
fun TopicsSection(onOpenNote: (String) -> Unit, onOpenFlashcards: () -> Unit) {
    val session = koinInject<SessionManager>()
    val auth by session.state.collectAsStateWithLifecycle()
    if (auth !is AuthState.SignedIn) return
    val viewModel = koinViewModel<TopicsViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Penguasaan per topik", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onOpenFlashcards) { Text("Latihan kartu") }
        }
        if (state.offline) OfflineNote()
        state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        val progress = state.progress ?: return@Column
        if (progress.topics.isEmpty()) {
            Text("Kerjakan kuis untuk melihat penguasaan per topik.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        Text(
            "Disarankan latihan ulang jika sudah ≥ ${progress.minAnswers} jawaban dan benar < " +
                "${(progress.accuracyThreshold * 100).toInt()}%. Soal yang dilaporkan tidak dihitung.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        progress.topics.forEach { topic ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onOpenNote(topic.noteId) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(topic.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (topic.needsPractice) {
                            Label("Perlu latihan", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                    LinearProgressIndicator(progress = { topic.accuracy.toFloat() }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "${topic.correct}/${topic.answered} benar" + (topic.noteTitle?.let { " · catatan: $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---------- Latihan kartu ----------

data class FlashcardsUiState(
    val loading: Boolean = true,
    val cards: List<FlashcardDto> = emptyList(),
    val index: Int = 0,
    val revealed: Boolean = false,
    val reviewed: Int = 0,
    val remaining: Int = 0,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val current: FlashcardDto? get() = cards.getOrNull(index)
}

class FlashcardsViewModel(private val ai: AiRepository) : ViewModel() {
    private val _state = MutableStateFlow(FlashcardsUiState())
    val state: StateFlow<FlashcardsUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = try {
                val due = ai.dueFlashcards()
                FlashcardsUiState(loading = false, cards = due.cards, remaining = due.dueTotal)
            } catch (e: AiException) {
                FlashcardsUiState(loading = false, error = e.message)
            }
        }
    }

    fun reveal() = _state.update { it.copy(revealed = true) }

    fun answer(remembered: Boolean) {
        val card = _state.value.current ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                ai.review(card.questionId, remembered)
                _state.update {
                    it.copy(busy = false, index = it.index + 1, revealed = false, reviewed = it.reviewed + 1,
                        remaining = (it.remaining - 1).coerceAtLeast(0))
                }
            } catch (e: AiException) {
                _state.update { it.copy(busy = false, error = e.message) }
            }
        }
    }
}

@Composable
fun FlashcardsScreen(onBack: () -> Unit) {
    val viewModel = koinViewModel<FlashcardsViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    StudyScaffold("Latihan kartu", onBack) { modifier ->
        Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.loading) {
                CircularProgressIndicator()
                return@Column
            }
            val card = state.current
            if (card == null) {
                EmptyState(
                    if (state.reviewed > 0) "Selesai untuk sekarang" else "Tidak ada kartu jatuh tempo",
                    if (state.remaining > 0) "Masih ada ${state.remaining} kartu; muat lagi untuk melanjutkan."
                    else "Kartu dibuat dari soal kuis yang pernah dijawab salah, lalu dijadwalkan ulang 1–16 hari.",
                    action = if (state.remaining > 0) ({ Button(onClick = viewModel::load) { Text("Muat lagi") } }) else null,
                )
                return@Column
            }
            Text("Sisa ${state.remaining} kartu · kotak ${card.box}/5 · ${card.topicLabel}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(card.question, style = MaterialTheme.typography.titleMedium)
                    if (state.revealed) {
                        Text(card.answer, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                        Text(card.explanation, style = MaterialTheme.typography.bodyMedium)
                        card.noteTitle?.let {
                            Text("Sumber: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (!state.revealed) {
                Button(onClick = viewModel::reveal, modifier = Modifier.fillMaxWidth()) { Text("Lihat jawaban") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.answer(false) }, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                        Text("Belum ingat")
                    }
                    Button(onClick = { viewModel.answer(true) }, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                        Text("Sudah ingat")
                    }
                }
            }
        }
    }
}
