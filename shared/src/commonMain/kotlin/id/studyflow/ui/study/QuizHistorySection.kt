package id.studyflow.ui.study

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.studyflow.data.auth.AuthState
import id.studyflow.data.auth.SessionManager
import id.studyflow.domain.formatDateTime
import id.studyflow.ui.components.NeedsAccountPanel
import kotlinx.datetime.TimeZone
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun QuizHistorySection(zone: TimeZone, onOpenAttempt: (quizId: String, attemptId: String) -> Unit) {
    val session = koinInject<SessionManager>()
    val auth by session.state.collectAsStateWithLifecycle()
    if (auth !is AuthState.SignedIn) {
        NeedsAccountPanel(
            "Riwayat kuis",
            "Buat kuis dari catatan dan lihat perkembangan skormu.",
            canSignIn = auth is AuthState.SignedOut,
        )
        return
    }
    val viewModel = koinViewModel<QuizHistoryViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.load() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Riwayat kuis", style = MaterialTheme.typography.titleSmall)
        if (state.offline) OfflineNote()
        state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (state.loaded && state.attempts.isEmpty() && state.error == null) {
            Text("Belum ada kuis yang dikerjakan.", style = MaterialTheme.typography.bodyMedium)
        }
        if (state.attempts.isNotEmpty()) {
            val totalScore = state.attempts.sumOf { it.score }
            val totalQuestions = state.attempts.sumOf { it.total }.coerceAtLeast(1)
            Text(
                "Rata-rata benar ${totalScore * 100 / totalQuestions}% dari ${state.attempts.size} percobaan",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        state.attempts.forEach { a ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onOpenAttempt(a.quizId, a.id) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.quizTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        a.submittedAt?.let { Text(formatDateTime(it, zone), style = MaterialTheme.typography.bodySmall) }
                    }
                    Text("${a.score}/${a.total}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
