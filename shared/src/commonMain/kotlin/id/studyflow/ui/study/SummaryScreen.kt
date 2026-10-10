package id.studyflow.ui.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.studyflow.domain.formatDateTime
import id.studyflow.ui.components.InfoCard
import kotlinx.datetime.TimeZone
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun SummaryScreen(summaryId: String, zone: TimeZone, onBack: () -> Unit) {
    val viewModel = koinViewModel<SummaryViewModel>(key = "summary-$summaryId") { parametersOf(summaryId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    StudyScaffold("Rangkuman", onBack) { modifier ->
        Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val summary = state.summary ?: return@Column
            if (state.offline) OfflineNote()
            if (summary.isDemo) DemoLabel()
            if (summary.source.outdated) {
                InfoCard("Dari versi catatan lama", "Catatan sudah diubah setelah rangkuman ini dibuat.")
            }
            Text(summary.content, style = MaterialTheme.typography.bodyLarge)
            Text(
                "Sumber: ${summary.source.title ?: "catatan"} (versi ${summary.source.version})" +
                    (summary.createdAt?.let { " · ${formatDateTime(it, zone)}" } ?: "") +
                    " · model ${summary.modelId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!summary.isDemo) {
                Text(
                    "Rangkuman dibuat AI dan bisa keliru. Periksa kembali dengan catatan aslimu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------- Kuis ----------
